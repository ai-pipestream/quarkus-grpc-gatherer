package ai.pipestream.grpc.gatherer.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
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

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;

/**
 * A {@link CodeGenProvider} that gathers {@code .proto} files from various sources before
 * downstream gRPC code generation takes place.
 * <p>
 * Supported sources include:
 * <ul>
 * <li>Local filesystem directories</li>
 * <li>Project runtime dependencies (JARs)</li>
 * <li>External Git repositories</li>
 * <li>Google Well-Known Types (from {@code protobuf-java})</li>
 * </ul>
 * <p>
 * This provider is designed to run very early (id: {@code a-grpc-gather}) so that
 * its outputs are available for other code generators like {@code grpc-zero}.
 */
public class GrpcGatherCodeGen implements CodeGenProvider {

    /**
     * Creates a new {@code GrpcGatherCodeGen} instance.
     */
    public GrpcGatherCodeGen() {
    }

    private static final Logger LOG = Logger.getLogger(GrpcGatherCodeGen.class);

    private static final String ENABLED = "quarkus.grpc-gather.enabled";
    private static final String CLEAN_TARGET = "quarkus.grpc-gather.clean-target";
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
    private static final String INCLUDE_GOOGLE_WKT = "quarkus.grpc-gather.include-google-wkt";

    private static final String GATHERED_PROTOS_DIR = "gathered-protos";
    private static final String PROTO_SOURCES_DIR = "proto-sources";
    private static final String MANIFEST_FILE = ".gathered-protos-manifest.txt";

    @Override
    public String providerId() {
        return "a-grpc-gather";
    }

    @Override
    public String[] inputExtensions() {
        return new String[] { "proto" };
    }

    @Override
    public String inputDirectory() {
        return "proto";
    }

    @Override
    public void init(ApplicationModel model, Map<String, String> properties) {
        // We use init to inject configuration for downstream providers (like grpc-zero).
        // Since provider IDs are sorted, 'a-grpc-gather' runs before 'grpc'.
        try {
            Path classesDir = model.getAppArtifact().getResolvedPaths().getSinglePath();
            Path buildDir = null;
            Path current = classesDir;
            while (current != null) {
                String name = current.getFileName().toString();
                if ("build".equals(name) || "target".equals(name)) {
                    buildDir = current;
                    break;
                }
                current = current.getParent();
            }
            if (buildDir == null) {
                buildDir = classesDir.getParent().getParent().getParent();
            }
            String mergeDirAbs = buildDir.resolve(PROTO_SOURCES_DIR).toAbsolutePath().toString();

            // Inject into the properties map used by some providers in their init()
            properties.put("quarkus.grpc.codegen.proto-directory", mergeDirAbs);

            // Also set as system properties for providers that read from Config/System during trigger()
            System.setProperty("quarkus.grpc.codegen.proto-directory", mergeDirAbs);
            System.setProperty("quarkus.generate-code.grpc.proto-directory", mergeDirAbs);
        } catch (Exception e) {
            LOG.debug("Failed to determine build directory during init", e);
        }
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        LOG.debugf("gRPC Proto Gatherer trigger called for context: %s", context.inputDir());
        Config config = context.config();
        if (!isEnabled(config)) {
            return false;
        }

        Path buildDir = getBuildDir(context);
        // Staging area for each algorithm (separated by source type)
        Path stagedRoot = buildDir.resolve(GATHERED_PROTOS_DIR);
        // Final merged directory that gRPC codegen will use
        Path mergeDir = buildDir.resolve(PROTO_SOURCES_DIR);
        LOG.debugf("gRPC Proto Gatherer starting. stagedRoot: %s, mergeDir: %s", stagedRoot, mergeDir);

        try {
            boolean clean = config.getOptionalValue(CLEAN_TARGET, Boolean.class).orElse(true);
            if (clean) {
                cleanPreviousGathered(mergeDir);
            }

            // Ensure staging and merge directories exist
            deleteTree(stagedRoot);
            Files.createDirectories(stagedRoot);
            Files.createDirectories(mergeDir);

            int gathered = 0;
            Map<String, String> seenHashes = new HashMap<>();

        // Gather from all configured sources into algorithm-specific folders
        gathered += gatherFilesystem(config, stagedRoot, new HashMap<>());
        gathered += gatherFromJars(context, config, stagedRoot.resolve("jar"), new HashMap<>());
        gathered += gatherFromGit(config, stagedRoot.resolve("git"), new HashMap<>());
        gathered += gatherGoogleWkt(context, config, stagedRoot.resolve("google"), new HashMap<>());

            // Merge each gathered source into the final mergeDir
            Map<String, String> mergedHashes = new HashMap<>();
            List<String> manifestPaths = new ArrayList<>();

            if (Files.isDirectory(stagedRoot)) {
                try (Stream<Path> subdirs = Files.list(stagedRoot)) {
                    for (Path subdir : subdirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                        String sourceName = subdir.getFileName().toString();
                        mergeSource(subdir, subdir, mergeDir, mergedHashes, manifestPaths, sourceName);
                    }
                }
            }

            // Also merge first-class protos from src/main/proto (last, so they can overwrite if needed or just participate in hash check)
            Path firstClassDir = CodeGenProvider.resolve(context.inputDir());
            if (firstClassDir != null && Files.isDirectory(firstClassDir)) {
                mergeSource(firstClassDir, firstClassDir, mergeDir, mergedHashes, null, "first-class");
            }

            // Write manifest for future clean runs
            Files.write(mergeDir.resolve(MANIFEST_FILE), manifestPaths);

            LOG.infof("gRPC gatherer staged %d proto file(s), merged %d into %s",
                    gathered, mergedHashes.size(), mergeDir);

            return gathered > 0;
        } catch (IOException e) {
            throw new CodeGenException("Failed while gathering proto files", e);
        }
    }

    /**
     * Returns the build directory (e.g., {@code build/} for Gradle or {@code target/} for Maven).
     *
     * @param context the code generation context
     * @return the build directory path
     */
    private Path getBuildDir(CodeGenContext context) {
        // Prefer context.workDir() which in Gradle points to build/ and in Maven to target/
        if (context.workDir() != null) {
            return context.workDir();
        }
        try {
            Path classesDir = context.applicationModel().getAppArtifact().getResolvedPaths().getSinglePath();
            // Try to find 'build' or 'target' by going up
            Path current = classesDir;
            while (current != null) {
                String name = current.getFileName().toString();
                if ("build".equals(name) || "target".equals(name)) {
                    return current;
                }
                current = current.getParent();
            }
            // build/classes/java/main -> build
            return classesDir.getParent().getParent().getParent();
        } catch (Exception e) {
            // Fallback to workDir if we can't determine the build directory
            return context.workDir();
        }
    }

    /**
     * Returns the merged proto sources directory.
     *
     * @param context the code generation context
     * @return the directory where all gathered and merged protos are materialized
     */
    public Path getMergeDir(CodeGenContext context) {
        // This tells where we materialized the merged protos.
        return getBuildDir(context).resolve(PROTO_SOURCES_DIR);
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        if (!isEnabled(config)) {
            return false;
        }
        return hasAnySource(config);
    }

    private boolean isEnabled(Config config) {
        return config.getOptionalValue(ENABLED, Boolean.class).orElse(false);
    }

    private boolean hasAnySource(Config config) {
        return config.getOptionalValue(FILESYSTEM_DIRS, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(JAR_DEPS, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false)
                || config.getOptionalValue(GIT_REPO, String.class).filter(s -> !s.isBlank()).isPresent()
                || config.getOptionalValue(INCLUDE_GOOGLE_WKT, Boolean.class).orElse(false);
    }

    private void cleanPreviousGathered(Path mergeDir) throws IOException {
        Path manifest = mergeDir.resolve(MANIFEST_FILE);
        if (Files.exists(manifest)) {
            List<String> paths = Files.readAllLines(manifest);
            for (String p : paths) {
                Files.deleteIfExists(mergeDir.resolve(p));
            }
            Files.deleteIfExists(manifest);
            LOG.infof("Cleaned %d previously gathered proto(s)", paths.size());
        }
    }

    // --- Gather methods (each writes to its own staging dir) ---

    /**
     * Gathers {@code .proto} files from configured filesystem directories.
     *
     * @param config the configuration
     * @param stagedRoot the root directory for algorithm-specific staging
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return the number of gathered files
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a configuration or gathering error occurs
     */
    private int gatherFilesystem(Config config, Path stagedRoot, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        String dirs = config.getOptionalValue(FILESYSTEM_DIRS, String.class).orElse("").trim();
        if (dirs.isEmpty()) {
            return 0;
        }

        int copied = 0;
        for (String entry : splitCsv(dirs)) {
            String buildRef = "filesystem";
            String dirPath = entry;

            int eqIdx = entry.indexOf('=');
            if (eqIdx > 0) {
                String candidate = entry.substring(0, eqIdx).trim();
                String pathPart = entry.substring(eqIdx + 1).trim();
                if (!pathPart.isEmpty()) {
                    buildRef = candidate;
                    dirPath = pathPart;
                }
            }

            Path source = Path.of(dirPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(source)) {
                throw new CodeGenException("Configured filesystem proto directory does not exist: " + source);
            }

            Path targetDir = stagedRoot.resolve(buildRef);
            Files.createDirectories(targetDir);
            copied += copyProtoTree(source, source, targetDir, seenHashes);
        }
        return copied;
    }

    /**
     * Gathers {@code .proto} files from project dependencies (JARs).
     *
     * @param context the code generation context
     * @param config the configuration
     * @param targetDir the target staging directory for JAR sources
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return the number of gathered files
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a configuration or gathering error occurs
     */
    private int gatherFromJars(CodeGenContext context, Config config, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        boolean scanAll = config.getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false);
        Set<String> requested = new HashSet<>(splitCsv(config.getOptionalValue(JAR_DEPS, String.class).orElse("")));
        if (!scanAll && requested.isEmpty()) {
            return 0;
        }

        Files.createDirectories(targetDir);
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
                        Files.copy(is, staged, StandardCopyOption.REPLACE_EXISTING);
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

    /**
     * Gathers {@code .proto} files from an external Git repository.
     *
     * @param config the configuration
     * @param targetDir the target staging directory for Git sources
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return the number of gathered files
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a configuration or gathering error occurs
     */
    private int gatherFromGit(Config config, Path targetDir, Map<String, String> seenHashes) throws IOException, CodeGenException {
        String repo = config.getOptionalValue(GIT_REPO, String.class).orElse("").trim();
        if (repo.isEmpty()) {
            return 0;
        }

        Files.createDirectories(targetDir);
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
                    copied += copyProtoTree(protoRoot, resolved, targetDir, seenHashes);
                    continue;
                }
                if (!Files.isRegularFile(resolved)) {
                    throw new CodeGenException("Configured git path does not exist: " + resolved);
                }
                if (!resolved.getFileName().toString().endsWith(".proto")) {
                    throw new CodeGenException("Configured git path is not a .proto file: " + resolved);
                }
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
            return new UsernamePasswordCredentialsProvider("x-access-token", token);
        }

        String username = config.getOptionalValue(GIT_USERNAME, String.class).orElse("").trim();
        if (username.isEmpty()) {
            return null;
        }
        String password = config.getOptionalValue(GIT_PASSWORD, String.class).orElse("");
        return new UsernamePasswordCredentialsProvider(username, password);
    }

    /**
     * Gathers Google's Well-Known Types (WKTs) from the {@code protobuf-java} dependency.
     *
     * @param context the code generation context
     * @param config the configuration
     * @param targetDir the target staging directory for Google WKTs
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return the number of gathered files
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a configuration or gathering error occurs
     */
    private int gatherGoogleWkt(CodeGenContext context, Config config, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        if (!config.getOptionalValue(INCLUDE_GOOGLE_WKT, Boolean.class).orElse(false)) {
            return 0;
        }
        Files.createDirectories(targetDir);
        int copied = 0;
        // Try to find com.google.protobuf:protobuf-java in dependencies
        for (ResolvedDependency dep : context.applicationModel().getRuntimeDependencies()) {
            if (dep.getGroupId().equals("com.google.protobuf") && dep.getArtifactId().equals("protobuf-java")) {
                copied += extractProtoFromDependency(dep, context.workDir().resolve("grpc-gather-wkt"), targetDir, seenHashes);
                break;
            }
        }
        return copied;
    }

    // --- Merge: combines all staged sources + first-class protos into the merge dir ---

    /**
     * Merges a staged source directory into the final merge directory.
     *
     * @param root the root of the staged source (for relative path resolution)
     * @param sourceDir the specific subdirectory to merge
     * @param mergeDir the final merge directory
     * @param mergedHashes a map of relative paths to their content hashes to detect conflicts
     * @param manifestPaths a list to track all merged paths for the manifest
     * @param sourceName the name of the source being merged (for error reporting)
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a content conflict is detected
     */
    private void mergeSource(Path root, Path sourceDir, Path mergeDir, Map<String, String> mergedHashes,
            List<String> manifestPaths, String sourceName)
            throws IOException, CodeGenException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .collect(Collectors.toList());
            for (Path proto : protos) {
                Path rel = root.relativize(proto);
                String relStr = rel.toString().replace('\\', '/');
                Path target = mergeDir.resolve(relStr).normalize();

                String contentHash = sha256(proto);
                if (mergedHashes.containsKey(relStr)) {
                    if (!mergedHashes.get(relStr).equals(contentHash)) {
                        throw new CodeGenException(
                                "Conflicting proto content for '" + relStr + "' from source '" + sourceName + "'.");
                    }
                    continue;
                }
                mergedHashes.put(relStr, contentHash);
                if (manifestPaths != null) {
                    manifestPaths.add(relStr);
                }

                // Only copy if different
                if (Files.exists(target) && sha256(target).equals(contentHash)) {
                    continue;
                }

                Files.createDirectories(target.getParent());
                Files.copy(proto, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // --- File copy utilities ---

    /**
     * Copies a tree of {@code .proto} files from a source directory to a target directory.
     *
     * @param root the root source directory
     * @param sourceDir the specific directory to copy from
     * @param targetDir the target directory
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return the number of files copied
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a content conflict is detected
     */
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

    /**
     * Copies a single {@code .proto} file to the target directory.
     *
     * @param source the source file path
     * @param relative the relative path of the file
     * @param targetDir the target directory
     * @param seenHashes a map to track and de-duplicate gathered files
     * @return {@code 1} if the file was copied, {@code 0} if it was already seen and skipped
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if a content conflict is detected
     */
    int copySingleProto(Path source, Path relative, Path targetDir, Map<String, String> seenHashes)
            throws IOException, CodeGenException {
        String rel = relative.toString().replace('\\', '/');
        // Handle cases where relative path might start with 'proto/' if it was extracted from a JAR with that prefix
        if (rel.startsWith("proto/")) {
            rel = rel.substring("proto/".length());
        }
        Path target = targetDir.resolve(rel).normalize();

        String contentHash = sha256(source);
        if (seenHashes.containsKey(rel)) {
            if (!seenHashes.get(rel).equals(contentHash)) {
                throw new CodeGenException("Conflicting proto content for path '" + rel + "'.");
            }
            return 0;
        }
        seenHashes.put(rel, contentHash);

        if (Files.exists(target) && sha256(target).equals(contentHash)) {
            return 1;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return 1;
    }

    static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    static String sha256(Path path) throws IOException {
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
