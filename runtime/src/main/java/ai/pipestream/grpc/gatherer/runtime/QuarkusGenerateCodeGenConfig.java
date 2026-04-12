package ai.pipestream.grpc.gatherer.runtime;

import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Standard configuration keys for gRPC code generation that we "claim" to avoid
 * "unrecognized configuration key" warnings during the build.
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "quarkus.generate-code.grpc")
public interface QuarkusGenerateCodeGenConfig {
    /**
     * The directory where proto files are located.
     * 
     * @return the proto directory
     */
    Optional<String> protoDirectory();
}
