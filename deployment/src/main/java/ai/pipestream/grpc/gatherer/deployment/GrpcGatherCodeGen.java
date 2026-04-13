package ai.pipestream.grpc.gatherer.deployment;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkus.paths.PathFilter;

/**
 * Gathers {@code .proto} files from multiple sources before downstream gRPC
 * code generation runs. Source-specific logic lives in
 * {@link ai.pipestream.grpc.gatherer.spi.ProtoGatherer} implementations
 * discovered via {@link java.util.ServiceLoader}; the built-in set covers
 * filesystem directories, filesystem scan roots, JAR dependencies, Google
 * Well-Known Types, Git repositories, and buf-style multi-module workspaces.
 *
 * <h2>Output flow</h2>
 *
 * <p>Each gather run does two things, both under the project build directory
 * so nothing ever touches the source tree:
 *
 * <ol>
 *   <li><b>Stage</b>: every {@link ai.pipestream.grpc.gatherer.spi.ProtoGatherer}
 *       writes its files into its own subdirectory under
 *       {@code build/gathered-protos-staging/<source>/}.
 *   <li><b>Merge</b>: the staging subdirectories are combined into the
 *       canonical output {@code build/gathered-protos/proto/}, with
 *       content-hash conflict detection. The gatherer also creates an empty
 *       {@code build/gathered-protos/java/} so the consumer's
 *       {@code sourceSets.main.java.srcDirs} addition (see below) does not
 *       resolve to a missing directory.
 * </ol>
 *
 * <h2>Hooking the staged protos into quarkus-grpc-zero</h2>
 *
 * <p>Consumers wire the staged output into Quarkus code generation by
 * adding one line to their Gradle build script:
 *
 * <pre>
 *   sourceSets.main.java.srcDirs += file("$buildDir/gathered-protos/java")
 * </pre>
 *
 * <p>The Quarkus Gradle plugin computes the "source parents" passed to
 * every {@link CodeGenProvider} as {@code Path::getParent} of every Java
 * {@code srcDir} of the main source set (see
 * {@code QuarkusPlugin.getSourcesParents} in Quarkus core). Adding the
 * fake {@code build/gathered-protos/java} srcDir makes
 * {@code build/gathered-protos} a source parent, and grpc-zero's default
 * {@code inputDirectory()="proto"} resolves it to
 * {@code build/gathered-protos/proto} - the exact directory this gatherer
 * just populated. No {@code src/main/proto} mirror, no system properties,
 * no classpath hacks.
 *
 * <p>Earlier versions of this extension copied the staged protos into
 * {@code src/main/proto} as a workaround because we mistakenly believed
 * grpc-zero had no way to consume a custom input directory. That workaround
 * is gone: the mirror logic, the manifest file, and the {@code clean-target}
 * config key have all been removed.
 */
public class GrpcGatherCodeGen implements CodeGenProvider {

    private static final Logger LOG = Logger.getLogger(GrpcGatherCodeGen.class);

    private static final String ENABLED = "quarkus.grpc-gather.enabled";
    private static final String EXCLUDES = "quarkus.grpc-gather.excludes";

    /**
     * Staging root (per-source gatherer subdirectories land here before merge).
     * Relative to the build directory. Ephemeral - cleaned every run.
     */
    private static final String STAGING_DIR = "gathered-protos-staging";

    /**
     * Build-directory merge target. The canonical output where the gatherer
     * combines every source into one tree with conflict detection. Consumers
     * expose this to Quarkus's CodeGenerator by adding
     * {@code build/gathered-protos/java} as a Java source directory.
     */
    private static final String BUILD_TARGET_ROOT = "gathered-protos";
    private static final String BUILD_TARGET_PROTO_SUBDIR = "proto";
    private static final String BUILD_TARGET_JAVA_SUBDIR = "java";

    /**
     * Manifest written into the merge target directory listing every file the
     * gatherer merged. Useful for debugging and for consumers that want to
     * reason about what was produced in a given run.
     */
    private static final String MANIFEST_FILE = ".gathered-protos-manifest.txt";

    /**
     * Creates a new instance of {@link GrpcGatherCodeGen}.
     */
    public GrpcGatherCodeGen() {
    }

    @Override
    public @NonNull String providerId() {
        return "a-grpc-gather";
    }

    @Override
    public @NonNull String[] inputExtensions() {
        return new String[] { "proto" };
    }

    @Override
    public @NonNull String inputDirectory() {
        return "proto";
    }

    /**
     * The actual gather work runs here, NOT in {@link #trigger}. Reason:
     * Quarkus's {@code CodeGenerator} runs every provider's {@code init()}
     * before any provider's {@code trigger()}, and ServiceLoader ordering
     * between grpc-zero and this gatherer is not under our control. If we
     * staged in {@code trigger()}, grpc-zero might walk an empty directory
     * before we had a chance to populate it.
     *
     * <p>By doing the work in {@code init()}, the staged output at
     * {@code build/gathered-protos/proto/} is guaranteed to exist before any
     * downstream {@code trigger()} runs, regardless of provider order.
     *
     * <p>Trade-off: at init time we don't have a {@link CodeGenContext}, so
     * {@link GatherContext} is constructed from the {@link ApplicationModel}
     * directly and the work directory is approximated as
     * {@code <buildDir>/grpc-gather-tmp}.
     */
    @Override
    public void init(ApplicationModel model, Map<String, String> properties) {
        try {
            Config config = ConfigProvider.getConfig();
            if (!isEnabled(config)) {
                return;
            }
            File moduleDirFile = model.getApplicationModule().getModuleDir();
            if (moduleDirFile == null) {
                LOG.debug("ApplicationModel has no module dir; skipping gatherer init");
                return;
            }
            Path buildDir = fallbackBuildDirFromModel(model);
            Path stagedRoot = buildDir.resolve(STAGING_DIR);
            Path buildTargetRoot = buildDir.resolve(BUILD_TARGET_ROOT);
            Path buildTargetDir = buildTargetRoot.resolve(BUILD_TARGET_PROTO_SUBDIR);
            Path buildTargetJavaDir = buildTargetRoot.resolve(BUILD_TARGET_JAVA_SUBDIR);
            Path workDir = buildDir.resolve("grpc-gather-tmp");

            deleteTree(stagedRoot);
            deleteTree(buildTargetDir);
            Files.createDirectories(stagedRoot);
            Files.createDirectories(buildTargetDir);
            // Empty java dir so consumers can safely declare
            //   sourceSets.main.java.srcDirs += file("$buildDir/gathered-protos/java")
            // without Gradle warning about a missing directory.
            Files.createDirectories(buildTargetJavaDir);
            Files.createDirectories(workDir);

            PathFilter excludeFilter = buildExcludeFilter(config);
            Map<String, String> seenHashes = new HashMap<>();
            GatherContext gatherContext = new GatherContext(model, config, stagedRoot, workDir, seenHashes, excludeFilter);

            int gathered = runGatherers(gatherContext);
            int merged = mergeStaged(stagedRoot, buildTargetDir);

            LOG.infof("gRPC gatherer staged %d proto file(s), merged %d into %s",
                    gathered, merged, buildTargetDir);
        } catch (Exception e) {
            LOG.errorf(e, "gRPC gatherer init() failed: %s", e.toString());
        }
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        // No-op. The actual gather work runs in init() so it completes
        // before any other CodeGenProvider's trigger() runs. We still
        // implement trigger() because Quarkus expects it.
        return false;
    }

    private int runGatherers(GatherContext gatherContext) throws IOException, CodeGenException {
        int gathered = 0;
        for (ProtoGatherer gatherer : ServiceLoader.load(ProtoGatherer.class, getClass().getClassLoader())) {
            if (!gatherer.isConfigured(gatherContext)) {
                LOG.debugf("Skipping unconfigured gatherer: %s", gatherer.id());
                continue;
            }
            LOG.debugf("Running gatherer: %s", gatherer.id());
            int count = gatherer.gather(gatherContext);
            LOG.debugf("Gatherer %s staged %d file(s)", gatherer.id(), count);
            gathered += count;
        }
        return gathered;
    }

    /**
     * Merge each staging subdirectory into the target directory, deduping
     * by content hash and recording conflicts.
     */
    private int mergeStaged(Path stagedRoot, Path targetDir) throws IOException {
        Map<String, String> mergedHashes = new HashMap<>();
        Map<String, String> mergedSources = new HashMap<>();
        Map<String, List<String>> allConflicts = new HashMap<>();
        List<String> manifestPaths = new ArrayList<>();

        if (Files.isDirectory(stagedRoot)) {
            try (Stream<Path> subdirs = Files.list(stagedRoot)) {
                for (Path subdir : subdirs.filter(Files::isDirectory).toList()) {
                    String sourceName = subdir.getFileName().toString();
                    // Google WKTs stay in the staging area and are not merged.
                    // protobuf-java already carries them at compile time, and
                    // split-package collisions with protobuf-java's own jar
                    // cause trouble if we materialize them alongside user types.
                    if ("google".equals(sourceName)) {
                        LOG.debug("Skipping merge of Google WKTs into final target directory");
                        continue;
                    }
                    mergeSource(subdir, targetDir, mergedHashes, mergedSources, allConflicts, manifestPaths, sourceName);
                }
            }
        }

        reportConflicts(allConflicts);

        Files.write(targetDir.resolve(MANIFEST_FILE), manifestPaths);
        return mergedHashes.size();
    }

    private void mergeSource(Path sourceDir, Path targetDir, Map<String, String> mergedHashes,
            Map<String, String> mergedSources, Map<String, List<String>> allConflicts,
            List<String> manifestPaths, String sourceName) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return !s.contains("/invalids/") && !s.contains("/dir/") && !s.contains("invalid.proto");
                    })
                    .toList();
            for (Path proto : protos) {
                String relStr = sourceDir.relativize(proto).toString().replace('\\', '/');
                Path target = targetDir.resolve(relStr).normalize();
                String contentHash = ProtoFileCopier.sha256(proto);

                if (mergedHashes.containsKey(relStr)) {
                    if (!mergedHashes.get(relStr).equals(contentHash)) {
                        allConflicts.computeIfAbsent(relStr, k -> {
                            List<String> list = new ArrayList<>();
                            list.add(mergedSources.get(relStr));
                            return list;
                        }).add(sourceName);
                    }
                    continue;
                }
                mergedHashes.put(relStr, contentHash);
                mergedSources.put(relStr, sourceName);
                manifestPaths.add(relStr);

                if (Files.exists(target) && ProtoFileCopier.sha256(target).equals(contentHash)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(proto, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void reportConflicts(Map<String, List<String>> allConflicts) {
        if (allConflicts.isEmpty()) {
            return;
        }
        int count = allConflicts.size();
        LOG.warnf("Detected %d conflicting proto file(s) during merging. "
                + "For each path, the version from the first source encountered was kept.", count);
        int reported = 0;
        for (Map.Entry<String, List<String>> entry : allConflicts.entrySet()) {
            if (reported >= 10) {
                LOG.warnf(" - ... and %d more conflict(s)", count - reported);
                break;
            }
            List<String> sources = entry.getValue();
            String sourcesStr = sources.size() > 5
                    ? sources.subList(0, 5).toString().replace("]", "") + ", ... and " + (sources.size() - 5) + " more]"
                    : sources.toString();
            LOG.warnf(" - %s: provided by %s (using version from %s)",
                    entry.getKey(), sourcesStr, sources.get(0));
            reported++;
        }
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        return isEnabled(config);
    }

    private boolean isEnabled(Config config) {
        return config.getOptionalValue(ENABLED, Boolean.class).orElse(false);
    }

    private static PathFilter buildExcludeFilter(Config config) {
        List<String> excludes = ProtoFileCopier.splitCsv(config.getOptionalValue(EXCLUDES, String.class).orElse(""));
        return excludes.isEmpty() ? null : new PathFilter(List.of(), excludes);
    }

    private static Path fallbackBuildDirFromModel(ApplicationModel model) {
        // Multi-output artifacts (e.g. Gradle's classes + resources) expose
        // multiple resolved paths. Any of them walks up to the same build/
        // or target/ ancestor, so take the first one.
        Path classesDir = model.getAppArtifact().getResolvedPaths().iterator().next();
        Path current = classesDir;
        while (current != null) {
            String name = current.getFileName().toString();
            if ("build".equals(name) || "target".equals(name)) {
                return current;
            }
            current = current.getParent();
        }
        // build/classes/java/main -> walk up three levels
        return classesDir.getParent().getParent().getParent();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public @NonNull FileVisitResult visitFile(@NonNull Path file, @NonNull BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public @NonNull FileVisitResult postVisitDirectory(@NonNull Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
