package ai.pipestream.grpc.gatherer.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BufWorkspaceStagerTest {

    private static void writeProto(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "syntax = \"proto3\";\n" + body);
    }

    @Test
    void flattensBufWorkspaceModulesIntoSingleRoot(@TempDir Path temp) throws Exception {
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

        int copied = BufWorkspaceStager.stageWorkspace(clone, List.of("common", "pipeline-module"),
                "proto", staging);

        assertEquals(2, copied);
        assertTrue(Files.exists(staging.resolve("ai/pipestream/data/v1/pipeline_core_types.proto")));
        assertTrue(Files.exists(staging.resolve("ai/pipestream/data/module/v1/pipe_step_processor_service.proto")));
        assertFalse(Files.exists(staging.resolve("common")));
        assertFalse(Files.exists(staging.resolve("proto")));
    }

    @Test
    void fallsBackToModuleRootWhenNoProtoSubdir(@TempDir Path temp) throws Exception {
        Path clone = temp.resolve("clone");
        writeProto(
                clone.resolve("flat-module/ai/pipestream/flat/v1/flat.proto"),
                "package ai.pipestream.flat.v1;\n");

        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);

        int copied = BufWorkspaceStager.stageWorkspace(clone, List.of("flat-module"),
                "proto", staging);

        assertEquals(1, copied);
        assertTrue(Files.exists(staging.resolve("ai/pipestream/flat/v1/flat.proto")));
    }

    @Test
    void rejectsMissingModuleDirectory(@TempDir Path temp) throws Exception {
        Path clone = temp.resolve("clone");
        Files.createDirectories(clone);
        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);

        IOException ex = assertThrows(IOException.class, () -> BufWorkspaceStager.stageWorkspace(
                clone, List.of("does-not-exist"), "proto", staging));
        assertTrue(ex.getMessage().contains("does-not-exist"));
    }

    @Test
    void rejectsModulePathEscape(@TempDir Path temp) throws Exception {
        Path clone = temp.resolve("clone");
        Files.createDirectories(clone);
        Path staging = temp.resolve("staging");
        Files.createDirectories(staging);

        String escape = temp.resolve("outside").toAbsolutePath().toString();
        assertThrows(IOException.class, () -> BufWorkspaceStager.stageWorkspace(
                clone, List.of(escape), "proto", staging));
    }
}
