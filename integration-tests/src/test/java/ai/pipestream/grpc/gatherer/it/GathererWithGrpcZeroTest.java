package ai.pipestream.grpc.gatherer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * End-to-end test that proves four things about the
 * quarkus-grpc-gatherer &rarr; quarkus-grpc-zero pipeline:
 *
 * <ol>
 *   <li>The gatherer stages files under {@code build/gathered-protos/proto/}
 *       (NOT under {@code src/main/proto/} - there is no source-tree mirror).
 *   <li>Quarkus's {@code CodeGenerator} picks those up via the source-parent
 *       mechanism because {@code build/gathered-protos/java} is declared as
 *       an additional Java source directory.
 *   <li>grpc-zero compiles the gathered protos to Java classes, so the
 *       usual generated types (e.g. {@code HealthCheckRequest},
 *       {@code SearchRequest}) are on the classpath at test time.
 *   <li>grpc-zero also emits a {@code FileDescriptorSet} when
 *       {@code quarkus.generate-code.grpc.descriptor-set.generate=true} is
 *       set, and the Gradle build routes it onto the classpath at
 *       {@code META-INF/grpc/services.dsc} where runtime consumers
 *       (e.g. pipestream's {@code GoogleDescriptorLoader}) expect it.
 * </ol>
 */
@QuarkusTest
class GathererWithGrpcZeroTest {

    private static Path moduleDir() {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        return Files.isDirectory(projectDir.resolve("integration-tests"))
                ? projectDir.resolve("integration-tests") : projectDir;
    }

    private static Path gatheredProtosRoot() {
        return moduleDir().resolve("build").resolve("gathered-protos").resolve("proto");
    }

    @Test
    void gitGatheredProtoIsCompiledByGrpcZero() throws Exception {
        // Gatherer wrote directly to build/gathered-protos/proto, NOT to
        // src/main/proto. The mirror hack is gone.
        Path gatheredProto = gatheredProtosRoot().resolve("schemas").resolve("common.proto");
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
        Path gatheredHealth = gatheredProtosRoot()
                .resolve("grpc").resolve("health").resolve("v1").resolve("health.proto");
        assertTrue(Files.exists(gatheredHealth),
                "Expected gathered health.proto at " + gatheredHealth);

        assertNotNull(Class.forName("io.grpc.health.v1.HealthCheckRequest"),
                "Expected HealthCheckRequest from health.proto to be generated");
        assertNotNull(Class.forName("io.grpc.health.v1.HealthCheckResponse"),
                "Expected HealthCheckResponse from health.proto to be generated");
    }

    @Test
    void doesNotMirrorIntoSrcMainProto() {
        // The old implementation copied every gathered proto into
        // integration-tests/src/main/proto/, which leaked build output into
        // the source tree. It is now a hard error for that directory (or the
        // old manifest) to exist as a byproduct of a gather run.
        Path srcMainProto = moduleDir().resolve("src").resolve("main").resolve("proto");
        assertFalse(Files.exists(srcMainProto),
                "src/main/proto must not exist - the gatherer no longer mirrors into the source tree. "
                        + "Found: " + srcMainProto);
    }

    @Test
    void descriptorSetIsOnClasspathUnderMetaInfGrpc() throws Exception {
        // GoogleDescriptorLoader looks up descriptors at META-INF/grpc/services.dsc
        // on the classpath. Enabling quarkus.generate-code.grpc.descriptor-set.generate
        // + naming it services.dsc in application.properties + routing it via
        // processResources.from() in build.gradle must put it there.
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/grpc/services.dsc")) {
            assertNotNull(in, "Expected META-INF/grpc/services.dsc on the classpath. "
                    + "Either grpc-zero did not emit a descriptor set, or the Gradle build "
                    + "did not route it into the resources output.");

            FileDescriptorSet set = DescriptorProtos.FileDescriptorSet.parseFrom(in);
            assertTrue(set.getFileCount() > 0,
                    "descriptor set on classpath must contain at least one FileDescriptorProto");

            // Collect all proto filenames and every service name the set declares.
            Set<String> files = set.getFileList().stream()
                    .map(FileDescriptorProto::getName)
                    .collect(Collectors.toSet());
            Set<String> services = set.getFileList().stream()
                    .flatMap(f -> f.getServiceList().stream().map(s -> f.getPackage() + "." + s.getName()))
                    .collect(Collectors.toSet());

            // The git-gathered schemas/common.proto from opensearch-protobufs
            // must be in the set. Its filename is whatever grpc-zero resolved
            // it to on disk; both the raw name and the gathered staging-tree
            // name are acceptable because grpc-zero strips the proto root.
            assertTrue(files.stream().anyMatch(f -> f.endsWith("schemas/common.proto") || f.equals("common.proto")),
                    "descriptor set must include schemas/common.proto; found files: " + files);

            // The jar-gathered health.proto must be in the set, and its
            // Health service must be registered.
            assertTrue(files.stream().anyMatch(f -> f.endsWith("grpc/health/v1/health.proto")),
                    "descriptor set must include grpc/health/v1/health.proto; found files: " + files);
            assertTrue(services.contains("grpc.health.v1.Health"),
                    "descriptor set must include the grpc.health.v1.Health service; found services: " + services);

            // Sanity check: non-empty file list and every file has a proto name.
            assertEquals(0,
                    set.getFileList().stream().filter(f -> f.getName().isBlank()).count(),
                    "descriptor set must not contain any FileDescriptorProto with a blank name");
        }
    }
}
