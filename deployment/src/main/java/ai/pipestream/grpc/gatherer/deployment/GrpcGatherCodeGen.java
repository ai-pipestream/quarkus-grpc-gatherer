package ai.pipestream.grpc.gatherer.deployment;

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
 * <p>After every gatherer has staged its files, {@link #trigger(CodeGenContext)}
 * merges each staging subdirectory into a single proto source directory under
 * the build output, detects conflicts via content hash, and records the merge
 * set in a manifest for the next clean run.
 *
 * <p>The provider id {@code a-grpc-gather} sorts alphabetically before
 * {@code grpc}, so this provider runs before {@code quarkus-grpc-zero} and its
 * merged output is visible to the generator.
 */
public class GrpcGatherCodeGen implements CodeGenProvider {

    private static final Logger LOG = Logger.getLogger(GrpcGatherCodeGen.class);

    private static final String ENABLED = "quarkus.grpc-gather.enabled";
    private static final String CLEAN_TARGET = "quarkus.grpc-gather.clean-target";
    private static final String EXCLUDES = "quarkus.grpc-gather.excludes";

    /** Key that grpc-zero reads in its {@code init()} to locate the input proto directory. */
    private static final String GRPC_ZERO_INPUT_DIR_KEY = "quarkus.grpc.codegen.proto-directory";

    private static final String GATHERED_PROTOS_DIR = "gathered-protos";
    private static final String PROTO_SOURCES_DIR = "proto-sources";
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
     * Inject the merged proto source directory as {@link #GRPC_ZERO_INPUT_DIR_KEY}
     * into the properties map so {@code quarkus-grpc-zero}'s {@code init()} picks
     * it up and generates from our output. This is the documented
     * {@link CodeGenProvider#init(ApplicationModel, Map)} contract - no system
     * property pollution, no skip-flag hijack.
     */
    @Override
    public void init(ApplicationModel model, Map<String, String> properties) {
        // Inject the merge directory into the shared properties map so
        // quarkus-grpc-zero (which reads this key in its own init()) compiles
        // from our gathered output. Requires this provider to load before
        // grpc-zero; classpath order (set the gatherer dep before grpc-zero
        // in your build file) controls this.
        //
        // Best-effort: if we can't resolve the merge directory at this phase
        // (e.g. multi-output artifact), let the user set the key manually in
        // application.properties. Never crash init() - that would break code
        // generation for every provider.
        try {
            Path mergeDir = resolveMergeDir(model);
            Files.createDirectories(mergeDir);
            properties.put(GRPC_ZERO_INPUT_DIR_KEY, mergeDir.toAbsolutePath().toString());
            LOG.debugf("gRPC gatherer: injected %s=%s", GRPC_ZERO_INPUT_DIR_KEY, mergeDir);
        } catch (Exception e) {
            LOG.debugf("Failed to initialize gatherer merge directory: %s", e.toString());
        }
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        LOG.debugf("gRPC Proto Gatherer trigger called for context: %s", context.inputDir());
        Config config = context.config();
        if (!isEnabled(config)) {
            return false;
        }

        Path buildDir = resolveBuildDir(context);
        Path stagedRoot = buildDir.resolve(GATHERED_PROTOS_DIR);
        Path mergeDir = buildDir.resolve(PROTO_SOURCES_DIR);
        LOG.debugf("gRPC Proto Gatherer starting. stagedRoot: %s, mergeDir: %s", stagedRoot, mergeDir);

        try {
            if (config.getOptionalValue(CLEAN_TARGET, Boolean.class).orElse(true)) {
                cleanPreviousGathered(mergeDir);
            }

            deleteTree(stagedRoot);
            Files.createDirectories(stagedRoot);
            Files.createDirectories(mergeDir);

            PathFilter excludeFilter = buildExcludeFilter(config);
            Map<String, String> seenHashes = new HashMap<>();
            GatherContext gatherContext = new GatherContext(context, config, stagedRoot, seenHashes, excludeFilter);

            int gathered = runGatherers(gatherContext);
            int merged = mergeStaged(stagedRoot, mergeDir, context);

            LOG.infof("gRPC gatherer staged %d proto file(s), merged %d into %s", gathered, merged, mergeDir);
            return gathered > 0;
        } catch (IOException e) {
            throw new CodeGenException("Failed while gathering proto files", e);
        }
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

    private int mergeStaged(Path stagedRoot, Path mergeDir, CodeGenContext context) throws IOException {
        Map<String, String> mergedHashes = new HashMap<>();
        Map<String, String> mergedSources = new HashMap<>();
        Map<String, List<String>> allConflicts = new HashMap<>();
        List<String> manifestPaths = new ArrayList<>();

        if (Files.isDirectory(stagedRoot)) {
            try (Stream<Path> subdirs = Files.list(stagedRoot)) {
                for (Path subdir : subdirs.filter(Files::isDirectory).toList()) {
                    String sourceName = subdir.getFileName().toString();
                    // Google WKTs stay in their staging subdir to avoid split-package
                    // collisions with protobuf-java at compile time.
                    if ("google".equals(sourceName)) {
                        LOG.debug("Skipping merge of Google WKTs into final merge directory");
                        continue;
                    }
                    mergeSource(subdir, mergeDir, mergedHashes, mergedSources, allConflicts, manifestPaths, sourceName);
                }
            }
        }

        Path firstClassDir = CodeGenProvider.resolve(context.inputDir());
        if (firstClassDir != null && Files.isDirectory(firstClassDir)) {
            mergeSource(firstClassDir, mergeDir, mergedHashes, mergedSources, allConflicts, null, "first-class");
        }

        reportConflicts(allConflicts);

        Files.write(mergeDir.resolve(MANIFEST_FILE), manifestPaths);
        return mergedHashes.size();
    }

    private void mergeSource(Path sourceDir, Path mergeDir, Map<String, String> mergedHashes,
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
                Path target = mergeDir.resolve(relStr).normalize();
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
                if (manifestPaths != null) {
                    manifestPaths.add(relStr);
                }

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

    /** Resolve the merge directory from just an {@link ApplicationModel} (used in {@link #init}). */
    private static Path resolveMergeDir(ApplicationModel model) {
        return fallbackBuildDirFromModel(model).resolve(PROTO_SOURCES_DIR);
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

    public @NonNull Path getMergeDir(CodeGenContext context) {
        return resolveBuildDir(context).resolve(PROTO_SOURCES_DIR);
    }

    private void cleanPreviousGathered(Path mergeDir) throws IOException {
        Path manifest = mergeDir.resolve(MANIFEST_FILE);
        if (!Files.exists(manifest)) {
            return;
        }
        List<String> paths = Files.readAllLines(manifest);
        for (String p : paths) {
            Files.deleteIfExists(mergeDir.resolve(p));
        }
        Files.deleteIfExists(manifest);
        LOG.infof("Cleaned %d previously gathered proto(s)", paths.size());
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
