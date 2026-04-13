package ai.pipestream.grpc.gatherer.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.pipestream.grpc.gatherer.sources.BufWorkspaceGatherer;
import ai.pipestream.grpc.gatherer.spi.GatherContext;

import io.quarkus.bootstrap.prebuild.CodeGenException;

/**
 * Unit tests for {@link BufWorkspaceGatherer#stageWorkspace}. These bypass
 * the git clone step and drive the staging logic directly against a
 * synthesized buf workspace layout on disk.
 */
class BufWorkspaceGathererTest {

    private static GatherContext newCtx(Path stagingRoot) {
        return new GatherContext(null, null, stagingRoot, null, new HashMap<>(), null);
    }

    private static void writeProto(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "syntax = \"proto3\";\n" + body);
    }

    @Test
    void flattensBufWorkspaceModulesIntoSingleRoot(@TempDir Path temp) throws Exception {
        // Arrange: build a buf-workspace-shaped repo on disk with two modules
        // that cross-import using the flattened import path convention.
        Path clone = temp.resolve("clone");
        writeProto(
                clone.resolve("common/proto/ai/pipestream/data/v1/pipeline_core_types.proto"),
                "package ai.pipestream.data.v1;\n");
        writeProto(
                clone.resolve("pipeline-module/proto/ai/pipestream/data/module/v1/pipe_step_processor_service.proto"),
                "package ai.pipestream.data.module.v1;\n"
                        + "import \"ai/pipestream/data/v1/pipeline_core_types.proto\";\n");

        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);
        GatherContext ctx = newCtx(staging);

        // Act
        int copied = BufWorkspaceGatherer.stageWorkspace(clone, List.of("common", "pipeline-module"),
                "proto", staging, ctx);

        // Assert: both protos land under the shared flattened root,
        // matching the import paths from the cross-module import.
        assertEquals(2, copied);
        assertTrue(Files.exists(staging.resolve("ai/pipestream/data/v1/pipeline_core_types.proto")),
                "common module's proto should be flattened onto the shared root");
        assertTrue(Files.exists(staging.resolve("ai/pipestream/data/module/v1/pipe_step_processor_service.proto")),
                "pipeline-module's proto should be flattened onto the shared root");
        // Buf-workspace prefix and per-module `proto/` dir must NOT appear
        // anywhere in the staged tree.
        assertFalse(Files.exists(staging.resolve("common")),
                "module prefix must not leak into staging tree");
        assertFalse(Files.exists(staging.resolve("proto")),
                "per-module proto/ subdir must not leak into staging tree");
    }

    @Test
    void fallsBackToModuleRootWhenNoProtoSubdir(@TempDir Path temp) throws Exception {
        // Arrange: a module without a proto/ subdir places its .proto files
        // directly under the module directory.
        Path clone = temp.resolve("clone");
        writeProto(
                clone.resolve("flat-module/ai/pipestream/flat/v1/flat.proto"),
                "package ai.pipestream.flat.v1;\n");

        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);
        GatherContext ctx = newCtx(staging);

        // Act
        int copied = BufWorkspaceGatherer.stageWorkspace(clone, List.of("flat-module"),
                "proto", staging, ctx);

        // Assert: gatherer falls back to treating the module dir itself as
        // the proto root, so the flat layout still resolves.
        assertEquals(1, copied);
        assertTrue(Files.exists(staging.resolve("ai/pipestream/flat/v1/flat.proto")));
    }

    @Test
    void rejectsMissingModuleDirectory(@TempDir Path temp) throws Exception {
        Path clone = temp.resolve("clone");
        Files.createDirectories(clone);
        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);
        GatherContext ctx = newCtx(staging);

        CodeGenException ex = assertThrows(CodeGenException.class, () -> BufWorkspaceGatherer.stageWorkspace(
                clone, List.of("does-not-exist"), "proto", staging, ctx));
        assertTrue(ex.getMessage().contains("does-not-exist"));
    }

    @Test
    void rejectsModulePathEscape(@TempDir Path temp) throws Exception {
        Path clone = temp.resolve("clone");
        Files.createDirectories(clone);
        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);
        GatherContext ctx = newCtx(staging);

        // An absolute path as a module name must be rejected rather than
        // silently escaping the clone root.
        String escape = temp.resolve("outside").toAbsolutePath().toString();
        assertThrows(CodeGenException.class, () -> BufWorkspaceGatherer.stageWorkspace(
                clone, List.of(escape), "proto", staging, ctx));
    }
}
