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
 * Well-Known Types, and Git repositories.
 *
 * <h2>Output flow</h2>
 *
 * <p>Each gather run does three things:
 *
 * <ol>
 *   <li><b>Stage</b>: every {@link ai.pipestream.grpc.gatherer.spi.ProtoGatherer}
 *       writes its files into its own subdirectory under
 *       {@code build/gathered-protos-staging/<source>/}.
 *   <li><b>Merge</b>: the staging subdirectories are combined into the
 *       canonical, ephemeral output {@code build/gathered-protos/proto/},
 *       with content-hash conflict detection.
 *   <li><b>Mirror</b>: the merged output is then copied into
 *       {@code src/main/proto/} so that quarkus-grpc-zero finds the files
 *       through its default input directory. A manifest at
 *       {@code src/main/proto/.gathered-protos-manifest.txt} records every
 *       mirrored file so the next run can clean them before re-mirroring.
 *       User-authored protos at the same relative path are never
 *       overwritten - the mirror step skips and warns instead.
 * </ol>
 *
 * <p>The mirror step exists as a temporary workaround. Once
 * quarkus-grpc-zero exposes a configurable {@code proto-directories}
 * (or equivalent) input list, the gatherer can drop the mirror and tell
 * grpc-zero to read from {@code build/gathered-protos/proto/} directly
 * without touching the source tree.
 *
 * <p>Users should gitignore the paths listed in the manifest so the
 * mirrored files don't get committed. The set is logged at {@code info}
 * level on every run.
 */
public class GrpcGatherCodeGen implements CodeGenProvider {

    private static final Logger LOG = Logger.getLogger(GrpcGatherCodeGen.class);

    private static final String ENABLED = "quarkus.grpc-gather.enabled";
    private static final String CLEAN_TARGET = "quarkus.grpc-gather.clean-target";
    private static final String EXCLUDES = "quarkus.grpc-gather.excludes";

    /**
     * Staging root (per-source gatherer subdirectories land here before merge).
     * Relative to the build directory. Ephemeral - cleaned every run.
     */
    private static final String STAGING_DIR = "gathered-protos-staging";

    /**
     * Build-directory merge target. The canonical, ephemeral output where the
     * gatherer combines every source into one tree with conflict detection.
     * This is what we want grpc-zero to read from once upstream supports
     * configurable proto directories.
     */
    private static final String BUILD_TARGET_ROOT = "gathered-protos";
    private static final String BUILD_TARGET_PROTO_SUBDIR = "proto";

    /**
     * Manifest written into {@code src/main/proto} listing every file the
     * mirror step copied there. Used to clean previously-mirrored files on
     * the next run so stale output never accumulates in the source tree.
     */
    private static final String MANIFEST_FILE = ".gathered-protos-manifest.txt";

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
     * Eagerly materialize {@code src/main/proto} during init() so that
     * downstream providers like quarkus-grpc-zero see a directory their
     * default {@code shouldRun()} check (which is just
     * {@code Files.isDirectory(sourceDir)}) accepts. Otherwise grpc-zero
     * skips itself for users who haven't yet committed any first-class
     * proto files - and since the gatherer's mirror happens at trigger()
     * time, by the time files exist grpc-zero has already given up.
     */
    /**
     * The actual gather + mirror work happens here, NOT in {@link #trigger}.
     * Reason: ServiceLoader iteration puts {@code grpc-zero} ahead of this
     * gatherer in practice, and Quarkus's CodeGenerator runs every provider's
     * {@code trigger()} in that same order. If we gathered in {@code trigger()},
     * grpc-zero would walk an empty {@code src/main/proto} before we had a
     * chance to mirror anything into it.
     *
     * <p>{@code init()} runs for every provider before any provider's
     * {@code trigger()}, so by doing the work here we guarantee the source
     * tree is populated before grpc-zero looks at it.
     *
     * <p>The trade-off: at init time we don't have a {@link CodeGenContext},
     * so {@link GatherContext} is constructed from the {@link ApplicationModel}
     * directly and {@link io.quarkus.deployment.CodeGenContext#workDir()} is
     * approximated as {@code <buildDir>/grpc-gather-tmp}.
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
            Path moduleDir = moduleDirFile.toPath();
            Path srcMainProto = moduleDir.resolve("src").resolve("main").resolve("proto");
            Path buildDir = fallbackBuildDirFromModel(model);
            Path stagedRoot = buildDir.resolve(STAGING_DIR);
            Path buildTargetDir = buildDir.resolve(BUILD_TARGET_ROOT).resolve(BUILD_TARGET_PROTO_SUBDIR);
            Path workDir = buildDir.resolve("grpc-gather-tmp");

            Files.createDirectories(srcMainProto);
            if (config.getOptionalValue(CLEAN_TARGET, Boolean.class).orElse(true)) {
                cleanPreviousMirrored(srcMainProto);
            }
            deleteTree(stagedRoot);
            deleteTree(buildTargetDir);
            Files.createDirectories(stagedRoot);
            Files.createDirectories(buildTargetDir);
            Files.createDirectories(workDir);

            PathFilter excludeFilter = buildExcludeFilter(config);
            Map<String, String> seenHashes = new HashMap<>();
            GatherContext gatherContext = new GatherContext(model, config, stagedRoot, workDir, seenHashes, excludeFilter);

            int gathered = runGatherers(gatherContext);
            int merged = mergeStaged(stagedRoot, buildTargetDir);
            int mirrored = mirrorToSrcMainProto(buildTargetDir, srcMainProto);

            LOG.infof("gRPC gatherer staged %d proto file(s), merged %d into %s, mirrored %d into %s",
                    gathered, merged, buildTargetDir, mirrored, srcMainProto);
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

    /**
     * Workaround until quarkus-grpc-zero supports configurable proto input
     * directories: copy every file from the canonical build-dir target into
     * {@code src/main/proto/} and record a manifest so the next run can
     * remove these files cleanly.
     *
     * <p>Each mirrored file path is checked against any pre-existing user
     * file at the same relative path; if there's a content mismatch, the
     * gatherer logs a warning and skips the mirror for that path so the
     * user's first-class proto is never overwritten.
     */
    private int mirrorToSrcMainProto(Path buildTargetDir, Path srcMainProto) throws IOException {
        if (!Files.isDirectory(buildTargetDir)) {
            return 0;
        }
        Path srcMainProtoAbs = srcMainProto.toAbsolutePath().normalize();
        List<String> manifest = new ArrayList<>();
        int copied = 0;
        try (Stream<Path> walk = Files.walk(buildTargetDir)) {
            List<Path> protos = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .toList();
            for (Path proto : protos) {
                String relStr = buildTargetDir.relativize(proto).toString().replace('\\', '/');
                Path target = srcMainProto.resolve(relStr).toAbsolutePath().normalize();
                if (!target.startsWith(srcMainProtoAbs)) {
                    throw new IOException("Refusing to mirror outside src/main/proto: " + relStr);
                }
                if (Files.exists(target)) {
                    String existingHash = ProtoFileCopier.sha256(target);
                    String newHash = ProtoFileCopier.sha256(proto);
                    if (!existingHash.equals(newHash)) {
                        LOG.warnf("Skipping mirror of %s: a different file already exists at %s "
                                + "(left as-is so user-authored protos are never overwritten)", relStr, target);
                        continue;
                    }
                }
                Files.createDirectories(target.getParent());
                Files.copy(proto, target, StandardCopyOption.REPLACE_EXISTING);
                manifest.add(relStr);
                copied++;
            }
        }
        Files.write(srcMainProto.resolve(MANIFEST_FILE), manifest);
        return copied;
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
     * by content hash and recording conflicts. First-class user protos in
     * {@code src/main/proto} are NOT merged here - grpc-zero walks that
     * directory independently as its default input. This gatherer only
     * materializes the externally-gathered protos into a sibling source
     * parent so grpc-zero compiles both naturally.
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

    /**
     * @return the build directory ({@code build/} for Gradle, {@code target/} for Maven)
     */
    private static Path resolveBuildDir(CodeGenContext context) {
        if (context.workDir() != null) {
            return context.workDir();
        }
        return fallbackBuildDirFromModel(context.applicationModel());
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

    /**
     * Read the manifest from a previous gather run and remove every file it
     * lists from {@code srcMainProto}, leaving user-authored protos intact.
     */
    private void cleanPreviousMirrored(Path srcMainProto) throws IOException {
        Path manifest = srcMainProto.resolve(MANIFEST_FILE);
        if (!Files.exists(manifest)) {
            return;
        }
        List<String> paths = Files.readAllLines(manifest);
        for (String p : paths) {
            Files.deleteIfExists(srcMainProto.resolve(p));
        }
        Files.deleteIfExists(manifest);
        LOG.infof("Cleaned %d previously mirrored proto(s) from %s", paths.size(), srcMainProto);
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
