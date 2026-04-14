package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;

public final class GitCloneCache {

    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{7,40}$");
    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private GitCloneCache() {
    }

    public static Path ensureCheckout(Path gradleUserHome, String repoUrl, String ref, CredentialsProvider credentials,
            boolean offline) throws IOException {
        Path cacheDir = cacheDir(gradleUserHome, repoUrl);
        Path lockFile = lockFile(cacheDir);
        Files.createDirectories(lockFile.getParent());
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(lockFile.toAbsolutePath().normalize(), lockPath -> new ReentrantLock());
        jvmLock.lock();

        try {
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                if (offline) {
                    if (Files.isDirectory(cacheDir)) {
                        return cacheDir;
                    }
                    throw new IOException("--offline but no cached checkout for " + repoUrl + "@" + ref);
                }

                if (!Files.exists(cacheDir)) {
                    clone(repoUrl, ref, credentials, cacheDir);
                    return cacheDir;
                }
                fetchAndReset(repoUrl, ref, credentials, cacheDir);
                return cacheDir;
            }
        } finally {
            jvmLock.unlock();
        }
    }

    public static String resolveRemoteSha(Path gradleUserHome, String repoUrl, String ref, CredentialsProvider credentials,
            boolean offline) throws IOException {
        if (COMMIT_SHA.matcher(ref).matches()) {
            return ref.toLowerCase(Locale.ROOT);
        }

        Path cacheDir = cacheDir(gradleUserHome, repoUrl);
        if (offline) {
            if (!Files.isDirectory(cacheDir)) {
                throw new IOException("--offline but no cached checkout for " + repoUrl + "@" + ref);
            }
            try (Git git = Git.open(cacheDir.toFile())) {
                ObjectId fetchHead = git.getRepository().resolve("FETCH_HEAD");
                if (fetchHead != null) {
                    return fetchHead.getName();
                }
                ObjectId head = git.getRepository().resolve("HEAD");
                if (head != null) {
                    return head.getName();
                }
                throw new IOException("Could not resolve " + ref + " on " + repoUrl);
            } catch (Exception e) {
                throw new IOException("Failed resolving cached SHA for " + repoUrl + "@" + ref, e);
            }
        }

        try {
            LsRemoteCommand command = Git.lsRemoteRepository()
                    .setRemote(repoUrl)
                    .setHeads(true)
                    .setTags(true);
            if (credentials != null) {
                command.setCredentialsProvider(credentials);
            }
            Iterable<Ref> refs = command.call();
            String headRef = "refs/heads/" + ref;
            String tagRef = "refs/tags/" + ref;
            String peeledTagRef = tagRef + "^{}";

            String tagSha = null;
            for (Ref remoteRef : refs) {
                String name = remoteRef.getName();
                if (headRef.equals(name)) {
                    return remoteRef.getObjectId().getName();
                }
                if (peeledTagRef.equals(name)) {
                    return remoteRef.getObjectId().getName();
                }
                if (tagRef.equals(name)) {
                    tagSha = remoteRef.getObjectId().getName();
                }
            }
            if (tagSha != null) {
                return tagSha;
            }
            throw new IOException("Could not resolve " + ref + " on " + repoUrl);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed resolving remote SHA for " + repoUrl + "@" + ref, e);
        }
    }

    private static void clone(String repoUrl, String ref, CredentialsProvider credentials, Path cacheDir) throws IOException {
        try {
            CloneCommand clone = Git.cloneRepository()
                    .setURI(repoUrl)
                    .setDirectory(cacheDir.toFile());
            if (credentials != null) {
                clone.setCredentialsProvider(credentials);
            }
            try (Git git = clone.call()) {
                git.checkout().setName(ref).call();
            }
        } catch (Exception e) {
            throw new IOException("Failed cloning/checking out git repo: " + repoUrl + "@" + ref, e);
        }
    }

    private static void fetchAndReset(String repoUrl, String ref, CredentialsProvider credentials, Path cacheDir)
            throws IOException {
        try (Git git = Git.open(cacheDir.toFile())) {
            FetchCommand fetch = git.fetch().setRemote("origin");
            if (COMMIT_SHA.matcher(ref).matches()) {
                fetch.setRefSpecs(new RefSpec(ref));
            } else {
                fetch.setRefSpecs(
                        new RefSpec("+refs/heads/*:refs/remotes/origin/*"),
                        new RefSpec("+refs/tags/*:refs/tags/*"));
            }
            if (credentials != null) {
                fetch.setCredentialsProvider(credentials);
            }
            fetch.call();
            String resetRef = "FETCH_HEAD";
            if (!COMMIT_SHA.matcher(ref).matches()) {
                ObjectId branchHead = git.getRepository().resolve("refs/remotes/origin/" + ref);
                if (branchHead != null) {
                    resetRef = branchHead.getName();
                } else {
                    ObjectId tagHead = git.getRepository().resolve("refs/tags/" + ref);
                    if (tagHead != null) {
                        resetRef = tagHead.getName();
                    }
                }
            }
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef(resetRef)
                    .call();
        } catch (Exception e) {
            throw new IOException("Failed fetching/resetting git repo: " + repoUrl + "@" + ref, e);
        }
    }

    private static Path cacheDir(Path gradleUserHome, String repoUrl) throws IOException {
        String normalized = repoUrl == null ? "" : repoUrl.trim();
        String hash = sha1(normalized);
        return gradleUserHome.resolve("caches").resolve("grpc-gatherer").resolve(hash);
    }

    private static Path lockFile(Path cacheDir) {
        return cacheDir.getParent().resolve(cacheDir.getFileName().toString() + ".lock");
    }

    private static String sha1(String value) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 algorithm unavailable", e);
        }
    }
}
