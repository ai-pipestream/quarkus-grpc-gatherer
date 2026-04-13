package ai.pipestream.grpc.gatherer.sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.maven.dependency.ResolvedDependency;

/**
 * Copies Google's Well-Known Types ({@code google/protobuf/*.proto}) from
 * the {@code com.google.protobuf:protobuf-java} dependency into its own
 * staging subdirectory. The top-level merge phase deliberately does not
 * merge this subdirectory into the final proto source directory to avoid
 * split-package collisions with {@code protobuf-java} at compile time.
 *
 * <p>Reads {@code quarkus.grpc-gather.include-google-wkt}.
 */
public final class GoogleWktGatherer implements ProtoGatherer {

    /**
     * Creates a new instance of {@link GoogleWktGatherer}.
     */
    public GoogleWktGatherer() {
    }

    static final String INCLUDE_GOOGLE_WKT = "quarkus.grpc-gather.include-google-wkt";

    @Override
    public String id() {
        return "google";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(INCLUDE_GOOGLE_WKT, Boolean.class).orElse(false);
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        if (!isConfigured(context)) {
            return 0;
        }
        Path targetDir = context.stagingDirFor(id());
        for (ResolvedDependency dep : context.applicationModel().getRuntimeDependencies()) {
            if (dep.getGroupId().equals("com.google.protobuf") && dep.getArtifactId().equals("protobuf-java")) {
                Path temp = context.workDir().resolve("grpc-gather-wkt");
                Files.createDirectories(temp);
                return JarDependencyGatherer.extractProtoFromDependency(dep, temp, targetDir, context, "google/");
            }
        }
        return 0;
    }
}
