package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Build-time configuration for gRPC Descriptor Set generation (typically handled by grpc-zero).
 * <p>
 * This extension provides sensible defaults for these properties when proto gathering is enabled,
 * simplifying the configuration needed in {@code application.properties}.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.grpc-gather.descriptor-set")
public interface GrpcZeroDescriptorSetGatherConfig {

    /**
     * If true, a gRPC descriptor set (containing FileDescriptorProtos) will be generated.
     *
     * @return {@code true} if generation is enabled
     */
    @WithDefault("false")
    boolean generate();

    /**
     * The directory where the generated descriptor set should be written.
     * <p>
     * Defaults to {@code build/grpc-descriptors} (Gradle) or {@code target/grpc-descriptors} (Maven)
     * if gathering is enabled and this is unset.
     *
     * @return the output directory
     */
    Optional<String> outputDir();

    /**
     * The name of the generated descriptor set file.
     * <p>
     * Defaults to {@code services.dsc} if gathering is enabled and this is unset.
     *
     * @return the descriptor set file name
     */
    Optional<String> name();
}
