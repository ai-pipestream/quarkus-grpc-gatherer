package ai.pipestream.grpc.gatherer.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.pipestream.grpc.gatherer.gradle.GitSpec;

class GitRepoStagerTest {

    @Test
    void stageModulesMode(@TempDir Path temp) throws Exception {
        Path cloneRoot = temp.resolve("clone");
        writeProto(cloneRoot.resolve("common/proto/common/v1/common.proto"));
        writeProto(cloneRoot.resolve("service/proto/service/v1/service.proto"));

        GitSpec spec = newSpec();
        spec.getModules().set(List.of("common", "service"));

        Path target = temp.resolve("out");
        int copied = GitRepoStager.stage(spec, cloneRoot, target);

        assertEquals(2, copied);
        assertTrue(Files.exists(target.resolve("common/proto/common/v1/common.proto")));
        assertTrue(Files.exists(target.resolve("service/proto/service/v1/service.proto")));
    }

    @Test
    void stagePathsMode(@TempDir Path temp) throws Exception {
        Path cloneRoot = temp.resolve("clone");
        writeProto(cloneRoot.resolve("proto/foo.proto"));
        writeProto(cloneRoot.resolve("proto/subdir/bar.proto"));

        GitSpec spec = newSpec();
        spec.getSubdir().set("proto");
        spec.getPaths().set(List.of("foo.proto", "subdir"));

        Path target = temp.resolve("out");
        int copied = GitRepoStager.stage(spec, cloneRoot, target);

        assertEquals(2, copied);
        assertTrue(Files.exists(target.resolve("foo.proto")));
        assertTrue(Files.exists(target.resolve("subdir/bar.proto")));
    }

    @Test
    void stageSingleSubdirMode(@TempDir Path temp) throws Exception {
        Path cloneRoot = temp.resolve("clone");
        writeProto(cloneRoot.resolve("proto/root.proto"));
        writeProto(cloneRoot.resolve("proto/deep/nested.proto"));

        GitSpec spec = newSpec();
        spec.getSubdir().set("proto");
        spec.getModules().set(List.of());
        spec.getPaths().set(List.of());

        Path target = temp.resolve("out");
        int copied = GitRepoStager.stage(spec, cloneRoot, target);

        assertEquals(2, copied);
        assertTrue(Files.exists(target.resolve("root.proto")));
        assertTrue(Files.exists(target.resolve("deep/nested.proto")));
    }

    @Test
    void rejectsPathEscape(@TempDir Path temp) throws Exception {
        Path cloneRoot = temp.resolve("clone");
        writeProto(cloneRoot.resolve("proto/foo.proto"));

        GitSpec spec = newSpec();
        spec.getSubdir().set("proto");
        spec.getPaths().set(List.of("../outside.proto"));

        IOException ex = assertThrows(IOException.class,
                () -> GitRepoStager.stage(spec, cloneRoot, temp.resolve("out")));
        assertTrue(ex.getMessage().contains("escapes git-subdir"));
    }

    @Test
    void rejectsMissingSubdir(@TempDir Path temp) throws Exception {
        Path cloneRoot = temp.resolve("clone");
        Files.createDirectories(cloneRoot);

        GitSpec spec = newSpec();
        spec.getSubdir().set("nonexistent");

        IOException ex = assertThrows(IOException.class,
                () -> GitRepoStager.stage(spec, cloneRoot, temp.resolve("out")));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    private static GitSpec newSpec() {
        Project project = ProjectBuilder.builder().build();
        GitSpec spec = project.getObjects().newInstance(GitSpec.class);
        spec.getPaths().set(List.of());
        spec.getModules().set(List.of());
        return spec;
    }

    private static void writeProto(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "syntax = \"proto3\";\nmessage Test {}\n");
    }
}
