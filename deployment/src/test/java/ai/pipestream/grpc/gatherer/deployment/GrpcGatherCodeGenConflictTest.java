package ai.pipestream.grpc.gatherer.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class GrpcGatherCodeGenConflictTest {

    @Test
    void warnsOnConflictingProtoContent() throws Exception {
        GrpcGatherCodeGen codeGen = new GrpcGatherCodeGen();
        Method method = GrpcGatherCodeGen.class.getDeclaredMethod(
                "copySingleProto",
                Path.class,
                Path.class,
                Path.class,
                Map.class);
        method.setAccessible(true);

        Path temp = Files.createTempDirectory("grpc-gather-conflict");
        try {
            Path first = temp.resolve("a.proto");
            Path second = temp.resolve("b.proto");
            String firstContent = "syntax = \"proto3\";\nmessage A {}\n";
            String secondContent = "syntax = \"proto3\";\nmessage B {}\n";
            Files.writeString(first, firstContent);
            Files.writeString(second, secondContent);

            Map<String, String> seen = new HashMap<>();
            Path targetDir = temp.resolve("out");
            Files.createDirectories(targetDir);

            // First one wins
            method.invoke(codeGen, first, Path.of("demo/v1/conflict.proto"), targetDir, seen);
            assertEquals(firstContent, Files.readString(targetDir.resolve("demo/v1/conflict.proto")));

            // Second one should not throw but skip
            assertDoesNotThrow(() -> {
                method.invoke(codeGen, second, Path.of("demo/v1/conflict.proto"), targetDir, seen);
            });
            
            // Verify content is still the first one's
            assertEquals(firstContent, Files.readString(targetDir.resolve("demo/v1/conflict.proto")));
        } finally {
            Files.walk(temp)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
