package ai.pipestream.grpc.gatherer.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;

class ProtoFileCopierTest {

    private static GatherContext newCtx(Path stagingRoot) {
        // Use the init-phase constructor so we can pass null for the fields
        // ProtoFileCopier doesn't touch (applicationModel, workDir, config).
        return new GatherContext(null, null, stagingRoot, null, new HashMap<>(), null);
    }

    @Test
    void testSplitCsv() {
        assertEquals(List.of("a", "b", "c"), ProtoFileCopier.splitCsv("a, b, c"));
        assertEquals(List.of("a", "b", "c"), ProtoFileCopier.splitCsv("a,,b, ,c"));
        assertEquals(List.of(), ProtoFileCopier.splitCsv(null));
        assertEquals(List.of(), ProtoFileCopier.splitCsv("  "));
    }

    @Test
    void testSha256(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("test.txt");
        Files.writeString(file, "hello world");
        String hash1 = ProtoFileCopier.sha256(file);

        Files.writeString(file, "hello world!");
        String hash2 = ProtoFileCopier.sha256(file);

        assertFalse(hash1.equals(hash2));
        assertEquals(64, hash1.length());
    }

    @Test
    void testCopySingleProto(@TempDir Path temp) throws Exception {
        Path sourceDir = temp.resolve("source");
        Path targetDir = temp.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Path proto = sourceDir.resolve("test.proto");
        Files.writeString(proto, "syntax = \"proto3\";");

        GatherContext ctx = newCtx(targetDir);
        int result = ProtoFileCopier.copySingleProto(proto, Path.of("test.proto"), targetDir, ctx, null);

        assertEquals(1, result);
        assertTrue(Files.exists(targetDir.resolve("test.proto")));
        assertEquals(1, ctx.seenHashes().size());
        assertTrue(ctx.seenHashes().containsKey("test.proto"));

        // Copy again, should return 0 (already seen)
        result = ProtoFileCopier.copySingleProto(proto, Path.of("test.proto"), targetDir, ctx, null);
        assertEquals(0, result);
    }

    @Test
    void testCopySingleProtoWithPrefix(@TempDir Path temp) throws Exception {
        Path sourceDir = temp.resolve("source");
        Path targetDir = temp.resolve("target");
        Files.createDirectories(sourceDir);
        Files.createDirectories(targetDir);

        Path proto = sourceDir.resolve("proto/test.proto");
        Files.createDirectories(proto.getParent());
        Files.writeString(proto, "syntax = \"proto3\";");

        GatherContext ctx = newCtx(targetDir);
        int result = ProtoFileCopier.copySingleProto(proto, Path.of("proto/test.proto"), targetDir, ctx, null);

        assertEquals(1, result);
        // The proto/ prefix in the relative path should be stripped in the target
        assertTrue(Files.exists(targetDir.resolve("test.proto")));
        assertFalse(Files.exists(targetDir.resolve("proto/test.proto")));
    }

    @Test
    void testDedupSkipsSameNameDifferentContent(@TempDir Path temp) throws Exception {
        // When a caller tries to stage two files at the same relative path,
        // the first one wins and the second is skipped (no exception).
        Path targetDir = temp.resolve("target");
        Files.createDirectories(targetDir);

        Path first = temp.resolve("a.proto");
        Path second = temp.resolve("b.proto");
        String firstContent = "syntax = \"proto3\";\nmessage A {}\n";
        String secondContent = "syntax = \"proto3\";\nmessage B {}\n";
        Files.writeString(first, firstContent);
        Files.writeString(second, secondContent);

        GatherContext ctx = newCtx(targetDir);
        int r1 = ProtoFileCopier.copySingleProto(first, Path.of("demo/v1/conflict.proto"), targetDir, ctx, null);
        int r2 = ProtoFileCopier.copySingleProto(second, Path.of("demo/v1/conflict.proto"), targetDir, ctx, null);

        assertEquals(1, r1);
        assertEquals(0, r2);
        assertEquals(firstContent, Files.readString(targetDir.resolve("demo/v1/conflict.proto")));
    }
}
