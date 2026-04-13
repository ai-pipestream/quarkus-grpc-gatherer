package ai.pipestream.grpc.gatherer.sources;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.microprofile.config.Config;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.prebuild.CodeGenException;

/**
 * Clones an external Git repository and stages {@code .proto} files from it.
 *
 * <p>Supports three layouts, in priority order:
 *
 * <ol>
 *   <li><b>Multi-module workspace</b>: set
 *       {@code quarkus.grpc-gather.git-modules=common,admin} to clone once
 *       and harvest every {@code .proto} under each named top-level
 *       subdirectory. Each module's subdirectory structure is preserved in
 *       the staging output so {@code import "common/foo.proto"} statements
 *       resolve correctly.
 *   <li><b>Explicit paths</b>: set {@code quarkus.grpc-gather.git-paths} to
 *       a comma-separated list of file-or-directory paths relative to
 *       {@code git-subdir}. Each path is copied verbatim.
 *   <li><b>Single subdirectory</b>: with neither of the above, every
 *       {@code .proto} under {@code git-subdir} (default {@code "proto"}) is
 *       staged.
 * </ol>
 *
 * <p>Authentication: set either {@code git-token} (uses
 * {@code x-access-token} auth) or {@code git-username}/{@code git-password}.
 */
public final class GitRepoGatherer implements ProtoGatherer {

    /**
     * Creates a new instance of {@link GitRepoGatherer}.
     */
    public GitRepoGatherer() {
    }

    static final String GIT_REPO = "quarkus.grpc-gather.git-repo";
    static final String GIT_REF = "quarkus.grpc-gather.git-ref";
    static final String GIT_SUBDIR = "quarkus.grpc-gather.git-subdir";
    static final String GIT_PATHS = "quarkus.grpc-gather.git-paths";
    static final String GIT_MODULES = "quarkus.grpc-gather.git-modules";
    static final String GIT_USERNAME = "quarkus.grpc-gather.git-username";
    static final String GIT_PASSWORD = "quarkus.grpc-gather.git-password";
    static final String GIT_TOKEN = "quarkus.grpc-gather.git-token";

    @Override
    public String id() {
        return "git";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(GIT_REPO, String.class).filter(s -> !s.isBlank()).isPresent();
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        Config config = context.config();
        String repo = config.getOptionalValue(GIT_REPO, String.class).orElse("").trim();
        if (repo.isEmpty()) {
            return 0;
        }

        Path targetDir = context.stagingDirFor(id());
        String ref = config.getOptionalValue(GIT_REF, String.class).orElse("main");
        List<String> modules = ProtoFileCopier.splitCsv(config.getOptionalValue(GIT_MODULES, String.class).orElse(""));
        List<String> gitPaths = ProtoFileCopier.splitCsv(config.getOptionalValue(GIT_PATHS, String.class).orElse(""));

        Path temp = Files.createTempDirectory("grpc-gather-git");
        try {
            cloneRepo(repo, ref, temp, config);

            if (!modules.isEmpty()) {
                return gatherModules(temp, modules, targetDir, context);
            }

            String subdir = config.getOptionalValue(GIT_SUBDIR, String.class).orElse("proto");
            Path protoRoot = temp.resolve(subdir).normalize();
            if (!Files.isDirectory(protoRoot)) {
                throw new CodeGenException("Configured git proto subdir does not exist: " + protoRoot);
            }

            if (gitPaths.isEmpty()) {
                return ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir, context, "git/");
            }

            int copied = 0;
            for (String configuredPath : gitPaths) {
                Path resolved = protoRoot.resolve(configuredPath).normalize();
                if (!resolved.startsWith(protoRoot)) {
                    throw new CodeGenException("Configured git path escapes git-subdir: " + configuredPath);
                }
                if (Files.isDirectory(resolved)) {
                    copied += ProtoFileCopier.copyProtoTree(protoRoot, resolved, targetDir, context, "git/");
                    continue;
                }
                if (!Files.isRegularFile(resolved)) {
                    throw new CodeGenException("Configured git path does not exist: " + resolved);
                }
                if (!resolved.getFileName().toString().endsWith(".proto")) {
                    throw new CodeGenException("Configured git path is not a .proto file: " + resolved);
                }
                copied += ProtoFileCopier.copySingleProto(resolved, protoRoot.relativize(resolved), targetDir, context, "git/");
            }
            return copied;
        } finally {
            deleteTree(temp);
        }
    }

    private int gatherModules(Path cloneRoot, List<String> modules, Path targetDir, GatherContext context)
            throws IOException, CodeGenException {
        int copied = 0;
        for (String module : modules) {
            Path moduleDir = cloneRoot.resolve(module).normalize();
            if (!moduleDir.startsWith(cloneRoot)) {
                throw new CodeGenException("Git module path escapes repo root: " + module);
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new CodeGenException("Git module directory does not exist in repo: " + module);
            }
            copied += ProtoFileCopier.copyProtoTree(cloneRoot, moduleDir, targetDir, context, "git/" + module + "/");
        }
        return copied;
    }

    private void cloneRepo(String repo, String ref, Path temp, Config config) throws CodeGenException {
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
    }

    private CredentialsProvider resolveGitCredentials(Config config) {
        String token = config.getOptionalValue(GIT_TOKEN, String.class).orElse("").trim();
        if (!token.isEmpty()) {
            return new UsernamePasswordCredentialsProvider("x-access-token", token);
        }

        String username = config.getOptionalValue(GIT_USERNAME, String.class).orElse("").trim();
        if (username.isEmpty()) {
            return null;
        }
        String password = config.getOptionalValue(GIT_PASSWORD, String.class).orElse("");
        return new UsernamePasswordCredentialsProvider(username, password);
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
