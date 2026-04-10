package ai.pipestream.grpc.gatherer.deployment;

import java.nio.file.Path;

import org.jboss.logging.Logger;

import ai.pipestream.grpc.gatherer.runtime.GrpcGatherBuildTimeConfig;
import ai.pipestream.grpc.gatherer.runtime.GrpcZeroDescriptorSetGatherConfig;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.SystemPropertyBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;

/**
 * Build-time processor for the gRPC Proto Gatherer extension.
 * <p>
 * This processor handles the configuration of gRPC code generation by pointing it to the
 * gathered proto files and providing defaults for descriptor set generation.
 */
class GrpcGathererProcessor {

    private static final Logger LOG = Logger.getLogger(GrpcGathererProcessor.class);
    private static final String FEATURE = "grpc-gatherer";

    /**
     * Registers the "grpc-gatherer" feature in Quarkus.
     *
     * @return the feature build item
     */
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * Configures the gRPC extension to use the gathered proto files.
     * <p>
     * If the gatherer is enabled, this step sets the {@code quarkus.grpc.codegen.proto-directory}
     * system property to the directory where the protos were merged. It also configures
     * defaults for {@code grpc-zero} descriptor set generation if enabled.
     *
     * @param config the gatherer build-time configuration
     * @param grpcZeroConfig the configuration for grpc-zero descriptor set gathering
     * @param outputTarget the output target build item
     * @param systemProperties the producer for system properties
     */
    @BuildStep
    void configureGrpc(GrpcGatherBuildTimeConfig config,
                       GrpcZeroDescriptorSetGatherConfig grpcZeroConfig,
                       OutputTargetBuildItem outputTarget,
                       BuildProducer<SystemPropertyBuildItem> systemProperties) {
        if (config.enabled()) {
            // Automatically point gRPC codegen to the gathered proto files.
            Path buildDir = outputTarget.getOutputDirectory();
            String protoDir = buildDir.resolve("proto-sources").toAbsolutePath().toString();
            LOG.debugf("Configuring gRPC proto directory to: %s", protoDir);
            
            // NOTE: grpc-zero uses 'quarkus.grpc.codegen.proto-directory'
            systemProperties.produce(new SystemPropertyBuildItem("quarkus.grpc.codegen.proto-directory", protoDir));
            // Standard Quarkus gRPC also uses this:
            systemProperties.produce(new SystemPropertyBuildItem("quarkus.generate-code.grpc.proto-directory", protoDir));

            if (grpcZeroConfig.generate()) {
                // Manually trigger gRPC descriptor generation by setting the core property
                systemProperties.produce(new SystemPropertyBuildItem("quarkus.generate-code.grpc.descriptor-set.generate", "true"));

                // Provide defaults for descriptor set generation if grpc-zero is used.
                if (grpcZeroConfig.outputDir().isEmpty()) {
                    String descriptorDir = buildDir.resolve("grpc-descriptors").toAbsolutePath().toString();
                    LOG.debugf("Configuring gRPC descriptor-set output directory to: %s", descriptorDir);
                    systemProperties.produce(new SystemPropertyBuildItem("quarkus.generate-code.grpc.descriptor-set.output-dir", descriptorDir));
                }
                if (grpcZeroConfig.name().isEmpty()) {
                    LOG.debug("Configuring gRPC descriptor-set name to: services.dsc");
                    systemProperties.produce(new SystemPropertyBuildItem("quarkus.generate-code.grpc.descriptor-set.name", "services.dsc"));
                }
            }
        }
    }
}
