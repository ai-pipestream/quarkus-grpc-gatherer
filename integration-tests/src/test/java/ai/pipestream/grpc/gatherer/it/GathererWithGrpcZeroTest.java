package ai.pipestream.grpc.gatherer.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * End-to-end test that proves the gatherer hands off to grpc-zero by writing
 * gathered protos directly into {@code src/main/proto/}, where grpc-zero
 * reads them as part of its default input directory.
 *
 * <p>The gatherer fetches a single well-known proto file from the
 * {@code opensearch-project/opensearch-protobufs} repository at tag 1.3.0
 * ({@code protos/schemas/common.proto}), materializes it under
 * {@code src/main/proto/schemas/common.proto}, and grpc-zero compiles it
 * into Java classes under {@code org.opensearch.protobufs}.
 */
@QuarkusTest
class GathererWithGrpcZeroTest {

    private static Path srcMainProto() {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        Path integrationTestsDir = Files.isDirectory(projectDir.resolve("integration-tests"))
                ? projectDir.resolve("integration-tests") : projectDir;
        return integrationTestsDir.resolve("src").resolve("main").resolve("proto");
    }

    @Test
    void gitGatheredProtoIsCompiledByGrpcZero() throws Exception {
        Path gatheredProto = srcMainProto().resolve("schemas").resolve("common.proto");
        assertTrue(Files.exists(gatheredProto),
                "Expected gathered proto at " + gatheredProto);

        assertNotNull(Class.forName("org.opensearch.protobufs.SearchRequest"),
                "Expected SearchRequest from common.proto to be generated");
        assertNotNull(Class.forName("org.opensearch.protobufs.SearchResponse"),
                "Expected SearchResponse from common.proto to be generated");
    }

    @Test
    void jarGatheredProtoIsCompiledByGrpcZero() throws Exception {
        // grpc-services ships several .proto files; the gatherer pulls all of
        // them out and grpc-zero recompiles each. health.proto is the smallest
        // self-contained one and it lands at grpc/health/v1/health.proto with
        // java_package=io.grpc.health.v1.
        Path gatheredHealth = srcMainProto()
                .resolve("grpc").resolve("health").resolve("v1").resolve("health.proto");
        assertTrue(Files.exists(gatheredHealth),
                "Expected gathered health.proto at " + gatheredHealth);

        assertNotNull(Class.forName("io.grpc.health.v1.HealthCheckRequest"),
                "Expected HealthCheckRequest from health.proto to be generated");
        assertNotNull(Class.forName("io.grpc.health.v1.HealthCheckResponse"),
                "Expected HealthCheckResponse from health.proto to be generated");
    }
}
