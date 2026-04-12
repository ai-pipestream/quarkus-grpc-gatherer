package ai.pipestream.grpc.gatherer.spi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.microprofile.config.Config;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.paths.PathFilter;

/**
 * Context passed to each {@link ProtoGatherer} during a gather run. Carries
 * the {@link Config} for reading configuration keys, the {@link ApplicationModel}
 * for dependency lookups, the staging root under which each gatherer owns a
 * subdirectory, an optional working directory for temporary scratch space,
 * and a shared seen-hashes map used to dedupe files staged by the same
 * gatherer.
 *
 * <p>The {@link CodeGenContext} is optional. The gatherer runs at two phases:
 * once during {@code init()} (where {@code CodeGenContext} is unavailable
 * because Quarkus has not yet entered the trigger phase) and once during
 * {@code trigger()}. {@link ProtoGatherer} implementations should prefer the
 * direct accessors ({@link #applicationModel()}, {@link #workDir()}) over
 * reaching through {@link #codeGenContext()}, which may return {@code null}.
 */
public final class GatherContext {

    private final CodeGenContext codeGenContext;
    private final ApplicationModel applicationModel;
    private final Config config;
    private final Path stagingRoot;
    private final Path workDir;
    private final Map<String, String> seenHashes;
    private final PathFilter excludeFilter;

    /**
     * Trigger-phase constructor: derives application model and work dir from
     * the Quarkus {@link CodeGenContext}.
     */
    public GatherContext(CodeGenContext codeGenContext, Config config, Path stagingRoot,
            Map<String, String> seenHashes, PathFilter excludeFilter) {
        this.codeGenContext = codeGenContext;
        this.applicationModel = codeGenContext.applicationModel();
        this.config = config;
        this.stagingRoot = stagingRoot;
        this.workDir = codeGenContext.workDir();
        this.seenHashes = seenHashes;
        this.excludeFilter = excludeFilter;
    }

    /**
     * Init-phase constructor: takes the application model and work dir
     * directly because no {@link CodeGenContext} exists yet.
     */
    public GatherContext(ApplicationModel applicationModel, Config config, Path stagingRoot,
            Path workDir, Map<String, String> seenHashes, PathFilter excludeFilter) {
        this.codeGenContext = null;
        this.applicationModel = applicationModel;
        this.config = config;
        this.stagingRoot = stagingRoot;
        this.workDir = workDir;
        this.seenHashes = seenHashes;
        this.excludeFilter = excludeFilter;
    }

    /** May be {@code null} during init-phase invocations. */
    public CodeGenContext codeGenContext() {
        return codeGenContext;
    }

    public Config config() {
        return config;
    }

    public ApplicationModel applicationModel() {
        return applicationModel;
    }

    /**
     * @return a working directory for scratch operations (jar extraction,
     *         git clones, etc.). Always non-null.
     */
    public Path workDir() {
        return workDir;
    }

    public Map<String, String> seenHashes() {
        return seenHashes;
    }

    public PathFilter excludeFilter() {
        return excludeFilter;
    }

    /**
     * Resolve the staging directory for the given gatherer id, creating it
     * if it does not already exist.
     */
    public Path stagingDirFor(String gathererId) throws IOException {
        Path dir = stagingRoot.resolve(gathererId);
        Files.createDirectories(dir);
        return dir;
    }

    public Path stagingRoot() {
        return stagingRoot;
    }

    public boolean isExcluded(String relPath) {
        return excludeFilter != null && !excludeFilter.isVisible(relPath);
    }
}
