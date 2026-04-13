package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Standard gRPC configuration keys that we "claim" to avoid "unrecognized configuration key" warnings.
 * This is particularly useful when using {@code grpc-zero} as a replacement for the standard
 * Quarkus gRPC extension, but we still want to support or at least acknowledge these standard keys.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.grpc.codegen")
public interface QuarkusGrpcCodeGenConfig {
    /**
     * If true, standard gRPC code generation is skipped.
     * 
     * @return {@code true} if skipped
     */
    @WithDefault("false")
    boolean skip();

    /**
     * The directory where proto files are located for the standard gRPC generator.
     * 
     * @return the proto directory
     */
    Optional<String> protoDirectory();
}
