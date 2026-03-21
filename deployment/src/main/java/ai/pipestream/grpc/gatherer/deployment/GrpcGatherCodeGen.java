package ai.pipestream.grpc.gatherer.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;

public class GrpcGatherCodeGen implements CodeGenProvider {

    private static final Logger LOG = Logger.getLogger(GrpcGatherCodeGen.class);

    private static final String ENABLED = "quarkus.grpc-gather.enabled";
    private static final String CLEAN = "quarkus.grpc-gather.clean-target";
    private static final String FILESYSTEM_DIRS = "quarkus.grpc-gather.filesystem-dirs";
    private static final String JAR_DEPS = "quarkus.grpc-gather.jar-dependencies";
    private static final String JAR_SCAN_ALL = "quarkus.grpc-gather.jar-scan-all";
    private static final String GIT_REPO = "quarkus.grpc-gather.git-repo";
    private static final String GIT_REF = "quarkus.grpc-gather.git-ref";
    private static final String GIT_SUBDIR = "quarkus.grpc-gather.git-subdir";
    private static final String GIT_PATHS = "quarkus.grpc-gather.git-paths";
    private static final String GIT_USERNAME = "quarkus.grpc-gather.git-username";
    private static final String GIT_PASSWORD = "quarkus.grpc-gather.git-password";
    private static final String GIT_TOKEN = "quarkus.grpc-gather.git-token";
    private static final String BUF_MODULE = "quarkus.grpc-gather.buf-module";
    private static final String BUF_PATHS = "quarkus.grpc-gather.buf-paths";
    private static final String GRPC_PROTO_DIR = "quarkus.grpc.codegen.proto-directory";

    @Override
    public String providerId() {
        // Run before the grpc-zero provider ("grpc") so gathered protos are available in time.
        return "a-grpc-gather";
    }

    @Override
    public String[] inputExtensions() {
        return new String[] { "proto" };
    }

    @Override
    public String inputDirectory() {
        // Run against the standard proto source directory so gathered files are available for grpc codegen.
        return "proto";
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        if (context.test()) {
            return false;
        }
        Config config = context.config();
        if (!isEnabled(config)) {
            return false;
        }

        Path targetProtoDir = resolveGrpcProtoDirectory(context, config);
        if (targetProtoDir == null) {
            throw new CodeGenException("Unable to resolve Quarkus proto input directory.");
        }

        try {
            Files.createDirectories(targetProtoDir);
            if (config.getOptionalValue(CLEAN, Boolean.class).orElse(true)) {
                cleanProtoDir(targetProtoDir);
            }

            Map<String, String> seenHashes = new HashMap<>();
            int copied = 0;

            copied += gatherFilesystem(config, targetProtoDir, seenHashes);
            copied += gatherFromJars(context, config, targetProtoDir, seenHashes);
            copied += gatherFromGit(config, targetProtoDir, seenHashes);
            copied += gatherFromBuf(config, targetProtoDir, seenHashes);

            LOG.infof("gRPC gatherer copied %d proto file(s) into %s", copied, targetProtoDir);
            return copied > 0;
        } catch (IOException e) {
            throw new CodeGenException("Failed while gathering proto files", e);
        }
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        if (!isEnabled(config)) {
            return false;
        }
        return hasAnySource(config);
    }

    private Path resolveGrpcProtoDirectory(CodeGenContext context, Config config) {
        String configured = config.getOptionalValue(GRPC_PROTO_DIR, String.class).orElse("").trim();
        if (!configured.isEmpty()) {
            String expanded = configured.replace("${user.dir}", System.getProperty("user.dir"));
            return Path.of(expanded).toAbsolutePath().normalize();
        }
        Path gatherInputDir = CodeGenProvider.resolve(context.inputDir());
        if (gatherInputDir != null) {
            Path normalized = gatherInputDir.toAbsolutePath().normalize();
            if ("proto".equals(String.valueOf(normalized.getFileName()))) {
                Path parent = normalized.getParent();
                if (parent != null && "proto".equals(String.valueOf(parent.getFileName()))) {
                    return parent;
                }
                return normalized;
            }
            Path parent = normalized.getParent();
            if (parent != null) {
                return parent.resolve("proto").toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private boolean isEnabled(Config config) {
        return config.getOptionalValue(ENABLED, Boolean.class).orElse(false);
    }

    private boolean hasAnySource(Config config) {
        return config.getOptionalValue(FILESYSTEM_DIRS, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(JAR_DEPS, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false)
                || config.getOptionalValue(GIT_REPO, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(BUF_MODULE, String.class).filter(s -> !s.isBlank()).isPresent();
    }

    private int gatherFilesystem(Config config, Path targetDir, Map<String, String> seenHashes) throws IOException, CodeGenException {
        String dirs = config.getOptionalValue(FILESYSTEM_DIRS, String.class).orElse("").trim();
        if (dirs.isEmpty()) {
            return 0;
        }

        int copied = 0;
        for (String dir : splitCsv(dirs)) {
            Path source = Path.of(dir).toAbsolutePath().normalize();
            if (!Files.isDirectory(source)) {
                throw new CodeGenException("Configured filesystem proto directory does not exist: " + source);
            }
            copied += copyProtoTree(source, source, targetDir, seenHashes);
        }
        return copied;
    }

    private int gatherFromJars(CodeGenContext context, Config config, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        boolean scanAll = config.getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false);
        Set<String> requested = new HashSet<>(splitCsv(config.getOptionalValue(JAR_DEPS, String.class).orElse("")));
        if (!scanAll && requested.isEmpty()) {
            return 0;
        }

        int copied = 0;
        Path jarTemp = context.workDir().resolve("grpc-gather-jar-protos");
        Files.createDirectories(jarTemp);

        for (ResolvedDependency dep : context.applicationModel().getRuntimeDependencies()) {
            String gav = dep.getGroupId() + ":" + dep.getArtifactId();
            if (!scanAll && !requested.contains(gav)) {
                continue;
            }
            copied += extractProtoFromDependency(dep, jarTemp, targetDir, seenHashes);
        }
        return copied;
    }

    private int extractProtoFromDependency(ResolvedDependency dep, Path tempDir, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        final int[] copied = { 0 };
        String uniqueName = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
        Path unzipDir = tempDir.resolve(HashUtil.sha1(uniqueName));
        Files.createDirectories(unzipDir);

        try {
            dep.getContentTree(new PathFilter(List.of("**/*.proto"), List.of())).walk(pathVisit -> {
                Path path = pathVisit.getPath();
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".proto")) {
                    return;
                }
                try {
                    Path rel;
                    Path root = pathVisit.getRoot();
                    if (Files.isDirectory(root)) {
                        rel = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
                    } else {
                        rel = path.getRoot().relativize(path);
                    }
                    Path staged = unzipDir.resolve(rel.toString());
                    Files.createDirectories(staged.getParent());
                    try (InputStream is = Files.newInputStream(path)) {
                        Files.copy(is, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied[0] += copySingleProto(staged, rel, targetDir, seenHashes);
                } catch (IOException | CodeGenException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof CodeGenException cge) {
                throw cge;
            }
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
        return copied[0];
    }

    private int gatherFromGit(Config config, Path targetDir, Map<String, String> seenHashes) throws IOException, CodeGenException {
        String repo = config.getOptionalValue(GIT_REPO, String.class).orElse("").trim();
        if (repo.isEmpty()) {
            return 0;
        }
        String ref = config.getOptionalValue(GIT_REF, String.class).orElse("main");
        String subdir = config.getOptionalValue(GIT_SUBDIR, String.class).orElse("proto");
        List<String> gitPaths = splitCsv(config.getOptionalValue(GIT_PATHS, String.class).orElse(""));

        Path temp = Files.createTempDirectory("grpc-gather-git");
        try {
            try {
                CloneCommand clone = Git.cloneRepository().setURI(repo).setDirectory(temp.toFile());
                CredentialsProvider credentials = resolveGitCredentials(config);
                if (credentials != null) {
                    clone.setCredentialsProvider(credentials);
                }
                try (Git git = clone.call()) {
                    git.checkout().setName(ref).call();
                }
            } catch (Exception e) {
                throw new CodeGenException("Failed cloning/checking out git repo: " + repo + "@" + ref, e);
            }
            Path protoRoot = temp.resolve(subdir).normalize();
            if (!Files.isDirectory(protoRoot)) {
                throw new CodeGenException("Configured git proto subdir does not exist: " + protoRoot);
            }
            if (gitPaths.isEmpty()) {
                return copyProtoTree(protoRoot, protoRoot, targetDir, seenHashes);
            }
            int copied = 0;
            for (String configuredPath : gitPaths) {
                Path resolved = protoRoot.resolve(configuredPath).normalize();
                if (!resolved.startsWith(protoRoot)) {
                    throw new CodeGenException("Configured git path escapes git-subdir: " + configuredPath);
                }
                if (Files.isDirectory(resolved)) {
                    LOG.infof("gRPC gatherer git path (dir): %s", protoRoot.relativize(resolved));
                    copied += copyProtoTree(protoRoot, resolved, targetDir, seenHashes);
                    continue;
                }
                if (!Files.isRegularFile(resolved)) {
                    throw new CodeGenException("Configured git path does not exist: " + resolved);
                }
                if (!resolved.getFileName().toString().endsWith(".proto")) {
                    throw new CodeGenException("Configured git path is not a .proto file: " + resolved);
                }
                LOG.infof("gRPC gatherer git path (file): %s", protoRoot.relativize(resolved));
                copied += copySingleProto(resolved, protoRoot.relativize(resolved), targetDir, seenHashes);
            }
            return copied;
        } finally {
            deleteTree(temp);
        }
    }

    private CredentialsProvider resolveGitCredentials(Config config) {
        String token = config.getOptionalValue(GIT_TOKEN, String.class).orElse("").trim();
        if (!token.isEmpty()) {
            // GitHub accepts x-access-token as username for PAT/OAuth token auth.
            return new UsernamePasswordCredentialsProvider("x-access-token", token);
        }

        String username = config.getOptionalValue(GIT_USERNAME, String.class).orElse("").trim();
        if (username.isEmpty()) {
            return null;
        }
        String password = config.getOptionalValue(GIT_PASSWORD, String.class).orElse("");
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    private int gatherFromBuf(Config config, Path targetDir, Map<String, String> seenHashes) throws IOException, CodeGenException {
        String module = config.getOptionalValue(BUF_MODULE, String.class).orElse("").trim();
        if (module.isEmpty()) {
            return 0;
        }

        Path temp = Files.createTempDirectory("grpc-gather-buf");
        try {
            List<String> command = new ArrayList<>();
            command.add("buf");
            command.add("export");
            command.add(module);
            for (String p : splitCsv(config.getOptionalValue(BUF_PATHS, String.class).orElse(""))) {
                command.add("--path");
                command.add(p);
            }
            command.add("--output");
            command.add(temp.toAbsolutePath().toString());

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            int exit = process.waitFor();
            if (exit != 0) {
                throw new CodeGenException("buf export failed (" + exit + "): " + output);
            }
            return copyProtoTree(temp, temp, targetDir, seenHashes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CodeGenException("Interrupted while running buf export", e);
        } finally {
            deleteTree(temp);
        }
    }

    private int copyProtoTree(Path root, Path sourceDir, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        final int[] copied = { 0 };
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .collect(Collectors.toList());
            for (Path proto : protos) {
                Path rel = root.relativize(proto);
                copied[0] += copySingleProto(proto, rel, targetDir, seenHashes);
            }
        }
        return copied[0];
    }

    private int copySingleProto(Path source, Path relative, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        String rel = relative.toString().replace('\\', '/');
        if (rel.startsWith("proto/")) {
            rel = rel.substring("proto/".length());
        }
        Path target = targetDir.resolve(rel).normalize();
        Files.createDirectories(target.getParent());

        String contentHash = sha256(source);
        if (seenHashes.containsKey(rel)) {
            if (!seenHashes.get(rel).equals(contentHash)) {
                throw new CodeGenException("Conflicting proto content for path '" + rel + "'.");
            }
            return 0;
        }
        seenHashes.put(rel, contentHash);
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return 1;
    }

    private static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void cleanProtoDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
