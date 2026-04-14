package ai.pipestream.grpc.gatherer.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class DescriptorSetClasspathIT {

    @Test
    void descriptorSetIsLoadableViaRuntimeClassloader() throws Exception {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/grpc/services.dsc")) {
            assertNotNull(in, "META-INF/grpc/services.dsc must be on the runtime classpath");
            FileDescriptorSet set = FileDescriptorSet.parseFrom(in);

            Set<String> files = set.getFileList().stream()
                    .map(FileDescriptorProto::getName)
                    .collect(Collectors.toSet());
            Set<String> services = set.getFileList().stream()
                    .flatMap(f -> f.getServiceList().stream().map(s -> f.getPackage() + "." + s.getName()))
                    .collect(Collectors.toSet());

            assertTrue(files.stream().anyMatch(f -> f.endsWith("grpc/health/v1/health.proto")),
                    "descriptor set must include grpc/health/v1/health.proto; found: " + files);
            assertTrue(services.contains("grpc.health.v1.Health"),
                    "descriptor set must include grpc.health.v1.Health service; found: " + services);
        }
    }
}
