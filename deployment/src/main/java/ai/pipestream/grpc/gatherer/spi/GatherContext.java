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
     *
     * @param codeGenContext the Quarkus code generation context
     * @param config the configuration
     * @param stagingRoot the root directory where proto files are staged
     * @param seenHashes a map of file paths to their content hashes to prevent duplicates
     * @param excludeFilter a filter to exclude specific paths
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
     *
     * @param applicationModel the application model
     * @param config the configuration
     * @param stagingRoot the root directory where proto files are staged
     * @param workDir a working directory for scratch operations
     * @param seenHashes a map of file paths to their content hashes to prevent duplicates
     * @param excludeFilter a filter to exclude specific paths
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

    /**
     * Returns the {@link CodeGenContext}.
     *
     * @return the {@link CodeGenContext}, may be {@code null} during init-phase invocations.
     */
    public CodeGenContext codeGenContext() {
        return codeGenContext;
    }

    /**
     * Returns the configuration.
     *
     * @return the configuration
     */
    public Config config() {
        return config;
    }

    /**
     * Returns the application model.
     *
     * @return the application model
     */
    public ApplicationModel applicationModel() {
        return applicationModel;
    }

    /**
     * Gets the working directory for scratch operations (jar extraction,
     * git clones, etc.).
     *
     * @return a working directory for scratch operations. Always non-null.
     */
    public Path workDir() {
        return workDir;
    }

    /**
     * Returns a map of file paths to their content hashes.
     *
     * @return a map of file paths to their content hashes
     */
    public Map<String, String> seenHashes() {
        return seenHashes;
    }

    /**
     * Returns the filter used to exclude specific paths.
     *
     * @return the filter used to exclude specific paths
     */
    public PathFilter excludeFilter() {
        return excludeFilter;
    }

    /**
     * Resolve the staging directory for the given gatherer id, creating it
     * if it does not already exist.
     *
     * @param gathererId the unique identifier of the gatherer
     * @return the resolved staging directory for the gatherer
     * @throws IOException if an I/O error occurs while creating the directory
     */
    public Path stagingDirFor(String gathererId) throws IOException {
        Path dir = stagingRoot.resolve(gathererId);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * Returns the root directory where all gatherers stage their files.
     *
     * @return the root directory where all gatherers stage their files
     */
    public Path stagingRoot() {
        return stagingRoot;
    }

    /**
     * Checks if the given relative path is excluded by the current filter.
     *
     * @param relPath the relative path to check
     * @return {@code true} if the path is excluded
     */
    public boolean isExcluded(String relPath) {
        return excludeFilter != null && !excludeFilter.isVisible(relPath);
    }
}
