package ai.pipestream.grpc.gatherer.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class GathererWithGrpcZeroTest {

    @Test
    void gathererSupportsFirstClassFilesystemJarAndBufSourcesWithDescriptor() throws Exception {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        // If run from root, user.dir is root. If from integration-tests, it's integration-tests.
        Path integrationTestsDir = Files.isDirectory(projectDir.resolve("integration-tests"))
                ? projectDir.resolve("integration-tests") : projectDir;

        Path gatheredProtoDir = integrationTestsDir.resolve("build").resolve("proto-sources");
        Path stagedProtoDir = integrationTestsDir.resolve("build").resolve("gathered-protos");

        // First-class proto (src/main/proto): verify well-known types Any, Struct, Timestamp are generated.
        Class<?> firstClassRequest = Class.forName("firstclass.v1.FirstClassRequest");
        Method timestampGetter = firstClassRequest.getMethod("getOccurredAt");
        assertEquals("com.google.protobuf.Timestamp", timestampGetter.getReturnType().getName());
        assertNotNull(Class.forName("com.google.protobuf.Struct"));
        assertNotNull(Class.forName("com.google.protobuf.Any"));
        assertNotNull(Class.forName("firstclass.v1.FirstClassServiceGrpc"));

        // Filesystem gather source.
        assertNotNull(Class.forName("filesystem.v1.FileSystemServiceGrpc"));
        assertTrue(
                Files.exists(gatheredProtoDir.resolve("filesystem/v1/filesystem.proto")),
                "Expected filesystem source proto in gathered output");
        assertTrue(
                Files.exists(stagedProtoDir.resolve("filesystem").resolve("filesystem/v1/filesystem.proto")),
                "Expected origin staging for filesystem");

        // JAR gather source.
        assertNotNull(Class.forName("io.grpc.channelz.v1.ChannelzGrpc"));
        assertTrue(
                Files.exists(gatheredProtoDir.resolve("grpc/channelz/v1/channelz.proto")),
                "Expected jar-sourced proto in gathered output");
        assertTrue(
                Files.exists(stagedProtoDir.resolve("jar").resolve("grpc/channelz/v1/channelz.proto")),
                "Expected origin staging for jar");

        // Git gather source (OpenSearch protobufs tag 1.3.0).
        assertNotNull(Class.forName("org.opensearch.protobufs.services.SearchServiceGrpc"));
        assertTrue(
                Files.exists(gatheredProtoDir.resolve("services/search_service.proto")),
                "Expected git-sourced proto in gathered output");
        assertTrue(
                Files.exists(stagedProtoDir.resolve("git").resolve("services/search_service.proto")),
                "Expected origin staging for git");

        // Buf gather source.
        assertNotNull(Class.forName("com.google.type.Date"));
        assertTrue(
                Files.exists(gatheredProtoDir.resolve("google/type/date.proto")),
                "Expected buf-sourced proto in gathered output");
        assertTrue(
                Files.exists(stagedProtoDir.resolve("buf").resolve("google/type/date.proto")),
                "Expected origin staging for buf");

        // Google WKT source.
        assertTrue(
                Files.exists(gatheredProtoDir.resolve("google/protobuf/any.proto")),
                "Expected google WKT in gathered output");
        assertTrue(
                Files.exists(stagedProtoDir.resolve("google").resolve("google/protobuf/any.proto")),
                "Expected origin staging for google WKT");
    }
}
