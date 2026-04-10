package ai.pipestream.grpc.gatherer.deployment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

import io.quarkus.bootstrap.model.ApplicationModel;
import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenContext;
import io.quarkus.deployment.CodeGenProvider;
import io.quarkiverse.grpc.codegen.GrpcZeroCodeGen;
import org.jspecify.annotations.NonNull;

/**
 * A proxy {@link CodeGenProvider} for {@link GrpcZeroCodeGen} that ensures it runs
 * correctly after the proto gatherer.
 * <p>
 * This proxy is needed because we often need to force gRPC zero generation
 * from the gathered protos in {@code build/proto-sources}, while skipping
 * the standard {@code grpc} provider which might fail due to missing native plugins.
 */
public class GrpcZeroProxyCodeGen implements CodeGenProvider {

    private static final Logger LOG = Logger.getLogger(GrpcZeroProxyCodeGen.class);

    private final GrpcZeroCodeGen delegate = new GrpcZeroCodeGen();

    /**
     * Creates a new {@code GrpcZeroProxyCodeGen} instance.
     */
    public GrpcZeroProxyCodeGen() {
        LOG.debug("GrpcZeroProxyCodeGen initialized");
    }

    @Override
    public @NonNull String providerId() {
        // Use 'grpc' to overwrite the standard provider
        return "grpc";
    }

    @Override
    public @NonNull String[] inputExtensions() {
        return new String[] { "proto" };
    }

    @Override
    public @NonNull String inputDirectory() {
        // This is ignored because we override in init/trigger, but we must return something
        return "proto";
    }

    @Override
    public void init(ApplicationModel model, Map<String, String> properties) {
        LOG.debug("GrpcZeroProxyCodeGen init called");
        delegate.init(model, properties);
    }

    @Override
    public boolean trigger(CodeGenContext context) throws CodeGenException {
        LOG.debug("GrpcZeroProxyCodeGen trigger called");
        
        // Ensure standard gRPC codegen is skipped to avoid conflicts
        System.setProperty("grpc.codegen.skip", "true");
        System.setProperty("quarkus.grpc.codegen.skip", "true");
        
        // Determine the actual merged proto directory
        Path mergeDir = new GrpcGatherCodeGen().getMergeDir(context);
        if (!Files.isDirectory(mergeDir)) {
            LOG.warnf("Merge directory %s does not exist, skipping GrpcZeroProxyCodeGen", mergeDir);
            return false;
        }

        // Create a new context that points to our merged protos as the input directory
        CodeGenContext proxyContext = new CodeGenContext(
                context.applicationModel(),
                context.outDir(),
                context.workDir(),
                mergeDir, // This is the key change
                context.shouldRedirectIO(),
                context.config(),
                context.test()
        );
        
        // Delegate the actual generation to gRPC zero
        return delegate.trigger(proxyContext);
    }

    @Override
    public boolean shouldRun(Path sourceDir, Config config) {
        // Always try to run if gRPC gathering is enabled, 
        // as the gathered protos are the ones we care about.
        return config.getOptionalValue("quarkus.grpc-gather.enabled", Boolean.class).orElse(false);
    }
}
