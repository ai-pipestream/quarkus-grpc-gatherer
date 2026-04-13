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
 * Clones a multi-module git repository laid out as a buf workspace and
 * stages {@code .proto} files from each named module into a single
 * flattened proto root. This does NOT invoke the {@code buf} CLI — it
 * applies the same conventions in pure Java, so the resulting staging
 * tree is what {@code buf build} / {@code protoc} would expect.
 *
 * <p>A typical buf workspace looks like:
 * <pre>
 *   repo-root/
 *     common/
 *       buf.yaml
 *       proto/
 *         ai/pipestream/data/v1/pipeline_core_types.proto
 *     pipeline-module/
 *       buf.yaml
 *       proto/
 *         ai/pipestream/data/module/v1/pipe_step_processor_service.proto
 * </pre>
 *
 * <p>With configuration
 * {@code quarkus.grpc-gather.buf-workspace-modules=common,pipeline-module},
 * this gatherer produces the flattened tree
 * <pre>
 *   &lt;staging&gt;/
 *     ai/pipestream/data/v1/pipeline_core_types.proto
 *     ai/pipestream/data/module/v1/pipe_step_processor_service.proto
 * </pre>
 * so cross-module imports like
 * {@code import "ai/pipestream/data/v1/pipeline_core_types.proto"} resolve.
 *
 * <p>Each module's proto source root is discovered by appending
 * {@code buf-workspace-proto-subdir} (default {@code "proto"}) to the
 * module directory. If that subdirectory does not exist the module
 * directory itself is treated as the proto root, so modules that put
 * their {@code .proto} files directly under the module dir still work.
 *
 * <p>Authentication: set {@code buf-workspace-token} for token auth, or
 * {@code buf-workspace-username}/{@code buf-workspace-password} for
 * basic auth.
 */
public final class BufWorkspaceGatherer implements ProtoGatherer {

    /**
     * Creates a new instance of {@link BufWorkspaceGatherer}.
     */
    public BufWorkspaceGatherer() {
    }

    static final String BUF_REPO = "quarkus.grpc-gather.buf-workspace-repo";
    static final String BUF_REF = "quarkus.grpc-gather.buf-workspace-ref";
    static final String BUF_MODULES = "quarkus.grpc-gather.buf-workspace-modules";
    static final String BUF_PROTO_SUBDIR = "quarkus.grpc-gather.buf-workspace-proto-subdir";
    static final String BUF_TOKEN = "quarkus.grpc-gather.buf-workspace-token";
    static final String BUF_USERNAME = "quarkus.grpc-gather.buf-workspace-username";
    static final String BUF_PASSWORD = "quarkus.grpc-gather.buf-workspace-password";

    @Override
    public String id() {
        return "buf-workspace";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(BUF_REPO, String.class).filter(s -> !s.isBlank()).isPresent()
                && context.config().getOptionalValue(BUF_MODULES, String.class).filter(s -> !s.isBlank()).isPresent();
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        Config config = context.config();
        String repo = config.getOptionalValue(BUF_REPO, String.class).orElse("").trim();
        List<String> modules = ProtoFileCopier.splitCsv(
                config.getOptionalValue(BUF_MODULES, String.class).orElse(""));
        if (repo.isEmpty() || modules.isEmpty()) {
            return 0;
        }

        String ref = config.getOptionalValue(BUF_REF, String.class).orElse("main");
        String protoSubdir = config.getOptionalValue(BUF_PROTO_SUBDIR, String.class).orElse("proto");

        Path targetDir = context.stagingDirFor(id());
        Path temp = Files.createTempDirectory("grpc-gather-buf-workspace");
        try {
            cloneRepo(repo, ref, temp, config);
            return stageWorkspace(temp, modules, protoSubdir, targetDir, context);
        } finally {
            deleteTree(temp);
        }
    }

    /**
     * Walks each named module under {@code cloneRoot} and stages its proto
     * files into {@code targetDir}, flattened onto a single root.
     *
     * <p>Exposed so unit tests can bypass the git clone step and drive the
     * staging logic directly against a synthesized workspace on disk.
     *
     * @param cloneRoot directory containing the cloned workspace
     * @param modules list of top-level module directory names
     * @param protoSubdir per-module proto subdirectory name (e.g. {@code "proto"})
     * @param targetDir directory to stage files into
     * @param context the gather context
     * @return the number of files copied
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a module path is invalid
     */
    public static int stageWorkspace(Path cloneRoot, List<String> modules, String protoSubdir,
            Path targetDir, GatherContext context) throws IOException, CodeGenException {
        int copied = 0;
        for (String module : modules) {
            Path moduleDir = cloneRoot.resolve(module).normalize();
            if (!moduleDir.startsWith(cloneRoot)) {
                throw new CodeGenException("buf-workspace module path escapes repo root: " + module);
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new CodeGenException("buf-workspace module directory does not exist in repo: " + module);
            }

            // Each module's proto source root is <module>/<protoSubdir>. If
            // that subdir doesn't exist, fall back to the module directory
            // itself. Relativizing against protoRoot (instead of cloneRoot)
            // is what flattens the modules into a shared tree.
            Path protoRoot = moduleDir.resolve(protoSubdir).normalize();
            if (!Files.isDirectory(protoRoot)) {
                protoRoot = moduleDir;
            }

            copied += ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir, context,
                    "buf-workspace/" + module + "/");
        }
        return copied;
    }

    private void cloneRepo(String repo, String ref, Path temp, Config config) throws CodeGenException {
        try {
            CloneCommand clone = Git.cloneRepository().setURI(repo).setDirectory(temp.toFile());
            CredentialsProvider credentials = resolveCredentials(config);
            if (credentials != null) {
                clone.setCredentialsProvider(credentials);
            }
            try (Git git = clone.call()) {
                git.checkout().setName(ref).call();
            }
        } catch (Exception e) {
            throw new CodeGenException("Failed cloning/checking out buf-workspace git repo: " + repo + "@" + ref, e);
        }
    }

    private CredentialsProvider resolveCredentials(Config config) {
        String token = config.getOptionalValue(BUF_TOKEN, String.class).orElse("").trim();
        if (!token.isEmpty()) {
            return new UsernamePasswordCredentialsProvider("x-access-token", token);
        }
        String username = config.getOptionalValue(BUF_USERNAME, String.class).orElse("").trim();
        if (username.isEmpty()) {
            return null;
        }
        String password = config.getOptionalValue(BUF_PASSWORD, String.class).orElse("");
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
