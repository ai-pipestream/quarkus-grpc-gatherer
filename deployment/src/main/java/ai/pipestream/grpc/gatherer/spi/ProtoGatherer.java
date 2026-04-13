package ai.pipestream.grpc.gatherer.spi;

import java.io.IOException;

import io.quarkus.bootstrap.prebuild.CodeGenException;

/**
 * SPI for plugging in new sources of {@code .proto} files. Implementations are
 * discovered via {@link java.util.ServiceLoader} and run during the gather phase
 * of {@code quarkus-grpc-gatherer}.
 *
 * <p>Each implementation writes {@code .proto} files into its own subdirectory
 * under the staging root, preserving the relative paths that should be visible
 * to downstream code generation. The top-level merge phase (owned by the main
 * gatherer) is responsible for combining staged sources into a single input
 * directory for {@code quarkus-grpc-zero}.
 *
 * <p>Built-in implementations cover filesystem directories, filesystem scan
 * roots, JAR dependencies, Google Well-Known Types, and external Git
 * repositories. Additional implementations (e.g., Apicurio Registry, Confluent
 * Schema Registry, Buf Schema Registry) ship as separate extension jars.
 */
public interface ProtoGatherer {

    /**
     * Stable identifier used for logging and for naming this gatherer's
     * subdirectory under the staging root. Lowercase, hyphen-separated.
     *
     * <p>Examples: {@code "filesystem"}, {@code "git"}, {@code "jar"},
     * {@code "google-wkt"}, {@code "apicurio"}.
     *
     * @return the unique identifier for this gatherer
     */
    String id();

    /**
     * Decide whether this gatherer has any work to do based on the user's
     * configuration. Called before {@link #gather(GatherContext)} to allow
     * the main loop to skip disabled gatherers without creating their
     * staging subdirectory.
     *
     * @param context the gather context
     * @return {@code true} if this gatherer is configured to run
     */
    boolean isConfigured(GatherContext context);

    /**
     * Run this gatherer. Implementations walk their configured sources and
     * write {@code .proto} files into the staging directory returned by
     * {@link GatherContext#stagingDirFor(String)}.
     *
     * @param context the gather context
     * @return the number of proto files staged by this invocation
     * @throws IOException if an I/O error occurs
     * @throws CodeGenException if configuration or gather logic fails
     */
    int gather(GatherContext context) throws IOException, CodeGenException;
}
