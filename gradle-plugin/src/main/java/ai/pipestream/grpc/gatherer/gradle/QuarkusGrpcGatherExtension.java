package ai.pipestream.grpc.gatherer.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Nested;

public abstract class QuarkusGrpcGatherExtension {

    public abstract DirectoryProperty getOutputDir();

    @Nested
    public abstract BufWorkspaceSpec getBufWorkspace();

    public void bufWorkspace(Action<? super BufWorkspaceSpec> action) {
        action.execute(getBufWorkspace());
    }

    @Nested
    public abstract FilesystemSpec getFilesystem();

    public void filesystem(Action<? super FilesystemSpec> action) {
        action.execute(getFilesystem());
    }

    @Nested
    public abstract JarDependenciesSpec getJarDependencies();

    public void jarDependencies(Action<? super JarDependenciesSpec> action) {
        action.execute(getJarDependencies());
    }

    @Nested
    public abstract GitSpec getGit();

    public void git(Action<? super GitSpec> action) {
        action.execute(getGit());
    }

    @Nested
    public abstract GoogleWktSpec getGoogleWkt();

    public void googleWkt(Action<? super GoogleWktSpec> action) {
        action.execute(getGoogleWkt());
    }
}
