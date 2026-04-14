package ai.pipestream.grpc.gatherer.deployment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.logging.Logger;

import ai.pipestream.grpc.gatherer.runtime.GrpcGatherBuildTimeConfig;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.OutputTargetBuildItem;

/**
 * Build-time processor for the quarkus-grpc-gatherer extension. Registers the
 * feature and routes the grpc-zero-generated {@code FileDescriptorSet} onto
 * the application classpath at {@code META-INF/grpc/services.dsc}.
 */
public class GrpcGathererProcessor {

    private static final Logger LOG = Logger.getLogger(GrpcGathererProcessor.class);
    private static final String FEATURE = "grpc-gatherer";

    /**
     * Path, relative to the Quarkus output directory, that grpc-zero's
     * default codegen emits generated Java sources into. See
     * {@code io.quarkiverse.grpc.codegen.GrpcZeroCodeGen} - the output goes
     * to {@code context.outDir()} which Quarkus's Gradle plugin points at
     * {@code <buildDir>/classes/java/quarkus-generated-sources/<providerId>}.
     */
    private static final Path GRPC_ZERO_OUTPUT_RELATIVE = Path.of(
            "classes", "java", "quarkus-generated-sources", "grpc");

    /**
     * Default filename grpc-zero writes the descriptor set as when
     * {@code quarkus.generate-code.grpc.descriptor-set.name} is set by
     * the Gradle gather task wiring before Quarkus code generation runs.
     */
    private static final String DESCRIPTOR_FILENAME = "services.dsc";

    /**
     * Classpath path runtime consumers (pipestream's
     * {@code GoogleDescriptorLoader}, Apicurio protobuf schema upload, etc.)
     * read the descriptor set from.
     */
    private static final String CLASSPATH_LOCATION = "META-INF/grpc/services.dsc";

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
     * Reads the {@code FileDescriptorSet} that grpc-zero wrote during code
     * generation and routes its bytes onto the runtime classpath as
     * {@code META-INF/grpc/services.dsc} via {@link GeneratedResourceBuildItem}.
     *
     * <p>Quarkus consumes {@link GeneratedResourceBuildItem} in two places:
     *
     * <ul>
     *   <li>Packaged jar: {@code AbstractJarBuilder} writes the bytes into
     *       the application jar, so a production runtime classpath finds
     *       the file.</li>
     *   <li>{@code @QuarkusTest} runtime: {@code StartupActionImpl} pulls
     *       every {@code GeneratedResourceBuildItem} into a
     *       {@code Map<String, byte[]>}, passes it to
     *       {@code CuratedApplication.createRuntimeClassLoader} which wraps
     *       it in a {@code MemoryClassPathElement}, and attaches it to the
     *       runtime {@code QuarkusClassLoader}. Calling
     *       {@code getContextClassLoader().getResourceAsStream(...)} in a
     *       {@code @QuarkusTest} then finds the file without the consumer
     *       having to copy it anywhere.</li>
     * </ul>
     *
     * <p>The descriptor is also registered as a native-image resource so
     * GraalVM builds keep the file accessible.
     *
     * @param config the gatherer build-time configuration
     * @param outputTarget provides the Quarkus output directory
     * @param generatedResources producer for runtime classpath resources
     * @param nativeResources producer for native-image resources
     */
    @BuildStep
    void routeGrpcDescriptorSetToResources(GrpcGatherBuildTimeConfig config,
            OutputTargetBuildItem outputTarget,
            BuildProducer<GeneratedResourceBuildItem> generatedResources,
            BuildProducer<NativeImageResourceBuildItem> nativeResources) {
        if (!config.enabled()) {
            return;
        }
        Path buildDir = outputTarget.getOutputDirectory();
        Path descriptor = buildDir.resolve(GRPC_ZERO_OUTPUT_RELATIVE).resolve(DESCRIPTOR_FILENAME);
        if (!Files.isRegularFile(descriptor)) {
            // grpc-zero did not run, or did not produce a descriptor set.
            // That can happen when there are no proto files to compile, when
            // the consumer explicitly disabled descriptor-set generation via
            // quarkus.generate-code.grpc.descriptor-set.generate=false, or
            // when dependency declaration order put grpc-zero ahead of this
            // gatherer and grpc-zero's init() ran before the Map mutation.
            // Log loudly but do not fail the build: some consumers legitimately
            // do not care about the descriptor set.
            LOG.warnf("grpc-zero descriptor set not found at %s; "
                    + "META-INF/grpc/services.dsc will NOT be on the classpath. "
                    + "If runtime proto reflection consumers (Apicurio, GoogleDescriptorLoader, etc.) "
                    + "need this file, verify that quarkus-grpc-gatherer is declared BEFORE "
                    + "quarkus-grpc-zero in your dependencies block and that "
                    + "quarkus.generate-code.grpc.descriptor-set.generate has not been "
                    + "explicitly set to false.", descriptor);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(descriptor);
            generatedResources.produce(new GeneratedResourceBuildItem(CLASSPATH_LOCATION, bytes));
            nativeResources.produce(new NativeImageResourceBuildItem(CLASSPATH_LOCATION));
            LOG.infof("Routed %d-byte grpc-zero descriptor set from %s to classpath at %s",
                    bytes.length, descriptor, CLASSPATH_LOCATION);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to read grpc-zero descriptor set at %s: %s", descriptor, e.toString());
        }
    }
}
