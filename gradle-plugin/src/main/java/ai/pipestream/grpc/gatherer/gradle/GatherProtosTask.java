package ai.pipestream.grpc.gatherer.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Task input model is still being implemented")
public abstract class GatherProtosTask extends DefaultTask {

    @Nested
    public abstract BufWorkspaceSpec getBufWorkspace();

    @Nested
    public abstract FilesystemSpec getFilesystem();

    @Nested
    public abstract JarDependenciesSpec getJarDependencies();

    @Nested
    public abstract GitSpec getGit();

    @Nested
    public abstract GoogleWktSpec getGoogleWkt();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void gather() throws IOException {
        Path output = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(output);
        getLogger().lifecycle("gatherProtos: output = {}", output);
    }
}
