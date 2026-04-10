package ai.pipestream.grpc.gatherer.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcGatherCodeGenTest {

    @Test
    void testSplitCsv() {
        assertEquals(List.of("a", "b", "c"), GrpcGatherCodeGen.splitCsv("a, b, c"));
        assertEquals(List.of("a", "b", "c"), GrpcGatherCodeGen.splitCsv("a,,b, ,c"));
        assertEquals(List.of(), GrpcGatherCodeGen.splitCsv(null));
        assertEquals(List.of(), GrpcGatherCodeGen.splitCsv("  "));
    }

    @Test
    void testSha256(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("test.txt");
        Files.writeString(file, "hello world");
        String hash1 = GrpcGatherCodeGen.sha256(file);
        
        Files.writeString(file, "hello world!");
        String hash2 = GrpcGatherCodeGen.sha256(file);
        
        assertFalse(hash1.equals(hash2));
        assertEquals(64, hash1.length());
    }

    @Test
    void testCopySingleProto(@TempDir Path temp) throws Exception {
        GrpcGatherCodeGen codeGen = new GrpcGatherCodeGen();
        Path sourceDir = temp.resolve("source");
        Path targetDir = temp.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Path proto = sourceDir.resolve("test.proto");
        Files.writeString(proto, "syntax = \"proto3\";");

        Map<String, String> seen = new HashMap<>();
        int result = codeGen.copySingleProto(proto, Path.of("test.proto"), targetDir, seen);

        assertEquals(1, result);
        assertTrue(Files.exists(targetDir.resolve("test.proto")));
        assertEquals(1, seen.size());
        assertTrue(seen.containsKey("test.proto"));

        // Copy again, should return 0 (already seen and identical)
        result = codeGen.copySingleProto(proto, Path.of("test.proto"), targetDir, seen);
        assertEquals(0, result);
    }
    
    @Test
    void testCopySingleProtoWithPrefix(@TempDir Path temp) throws Exception {
        GrpcGatherCodeGen codeGen = new GrpcGatherCodeGen();
        Path sourceDir = temp.resolve("source");
        Path targetDir = temp.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Path proto = sourceDir.resolve("proto/test.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, "syntax = \"proto3\";");

        Map<String, String> seen = new HashMap<>();
        // Note: relative path starts with proto/
        int result = codeGen.copySingleProto(proto, Path.of("proto/test.proto"), targetDir, seen);

        assertEquals(1, result);
        // Should be stripped of proto/ prefix in target
        assertTrue(Files.exists(targetDir.resolve("test.proto")));
        assertFalse(Files.exists(targetDir.resolve("proto/test.proto")));
    }
}
