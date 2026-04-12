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
 * the underlying {@link CodeGenContext}, the {@link Config} for reading
 * configuration keys, the staging root under which each gatherer owns a
 * subdirectory, and a shared seen-hashes map used to dedupe files staged
 * by the same gatherer.
 *
 * <p>Implementations should read their configuration from
 * {@link #config()} using keys in the {@code quarkus.grpc-gather.<id>.*}
 * namespace and write files into {@link #stagingDirFor(String)} preserving
 * the relative paths that downstream code generation needs for imports.
 */
public final class GatherContext {

    private final CodeGenContext codeGenContext;
    private final Config config;
    private final Path stagingRoot;
    private final Map<String, String> seenHashes;
    private final PathFilter excludeFilter;

    public GatherContext(CodeGenContext codeGenContext, Config config, Path stagingRoot,
            Map<String, String> seenHashes, PathFilter excludeFilter) {
        this.codeGenContext = codeGenContext;
        this.config = config;
        this.stagingRoot = stagingRoot;
        this.seenHashes = seenHashes;
        this.excludeFilter = excludeFilter;
    }

    public CodeGenContext codeGenContext() {
        return codeGenContext;
    }

    public Config config() {
        return config;
    }

    public ApplicationModel applicationModel() {
        return codeGenContext.applicationModel();
    }

    /**
     * @return the shared seen-hashes map used by {@code copySingleProto} to
     *         dedupe files within a single gatherer's staging area
     */
    public Map<String, String> seenHashes() {
        return seenHashes;
    }

    /**
     * @return the optional exclude filter (may be {@code null}) applied to
     *         relative paths before copying
     */
    public PathFilter excludeFilter() {
        return excludeFilter;
    }

    /**
     * Resolve the staging directory for the given gatherer id, creating it
     * if it does not already exist. Each gatherer owns its own subdirectory
     * under the staging root to prevent cross-contamination during merge.
     *
     * @param gathererId the gatherer's {@link ProtoGatherer#id()}
     * @return the staging directory, guaranteed to exist
     * @throws IOException if the directory cannot be created
     */
    public Path stagingDirFor(String gathererId) throws IOException {
        Path dir = stagingRoot.resolve(gathererId);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * @return the root staging directory (useful for gatherers that want to
     *         own more than one subdirectory, e.g. filesystem-scan)
     */
    public Path stagingRoot() {
        return stagingRoot;
    }

    public boolean isExcluded(String relPath) {
        return excludeFilter != null && !excludeFilter.isVisible(relPath);
    }
}
