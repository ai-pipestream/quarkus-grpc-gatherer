package ai.pipestream.grpc.gatherer.deployment;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.bootstrap.prebuild.CodeGenException;

class GrpcGatherCodeGenConflictTest {

    @Test
    void failsFastOnConflictingProtoContent() throws Exception {
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
            Files.writeString(first, "syntax = \"proto3\";\nmessage A {}\n");
            Files.writeString(second, "syntax = \"proto3\";\nmessage B {}\n");

            Map<String, String> seen = new HashMap<>();
            Path target = temp.resolve("out");

            method.invoke(codeGen, first, Path.of("demo/v1/conflict.proto"), target, seen);

            assertThrows(CodeGenException.class, () -> {
                try {
                    method.invoke(codeGen, second, Path.of("demo/v1/conflict.proto"), target, seen);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof CodeGenException cge) {
                        throw cge;
                    }
                    throw new RuntimeException(e.getCause());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
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
