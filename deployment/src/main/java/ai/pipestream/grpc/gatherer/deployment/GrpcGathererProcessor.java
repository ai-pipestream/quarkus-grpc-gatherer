package ai.pipestream.grpc.gatherer.deployment;

import java.nio.file.Path;

import org.jboss.logging.Logger;

import ai.pipestream.grpc.gatherer.runtime.GrpcGatherBuildTimeConfig;
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
     * Emits a build-time log line pointing at the staging directory populated
     * by {@link GrpcGatherCodeGen}, so users running Quarkus with debug logging
     * enabled can verify the gatherer ran and find its output.
     *
     * <p>There is no automatic wiring into grpc-zero here: the consumer is
     * expected to declare {@code build/gathered-protos/java} as a Java
     * source directory so Quarkus's CodeGenerator picks up
     * {@code build/gathered-protos/proto} via the source-parent mechanism.
     *
     * @param config the gatherer build-time configuration
     * @param outputTarget the output target build item
     * @param systemProperties unused; kept in the signature in case a future
     *        version needs to contribute system properties at build time
     */
    @BuildStep
    void configureGrpc(GrpcGatherBuildTimeConfig config,
                       OutputTargetBuildItem outputTarget,
                       BuildProducer<SystemPropertyBuildItem> systemProperties) {
        if (config.enabled()) {
            Path buildDir = outputTarget.getOutputDirectory();
            Path protoDir = buildDir.resolve("gathered-protos").resolve("proto").toAbsolutePath();
            LOG.debugf("gRPC gatherer staging directory: %s "
                    + "(add sourceSets.main.java.srcDirs += file(\"$buildDir/gathered-protos/java\") "
                    + "so quarkus-grpc-zero picks it up)", protoDir);
        }
    }
}
