package ai.pipestream.grpc.gatherer.gradle;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.eclipse.jgit.transport.CredentialsProvider;

import ai.pipestream.grpc.gatherer.gradle.internal.FilesystemStager;
import ai.pipestream.grpc.gatherer.gradle.internal.GitCloneCache;
import ai.pipestream.grpc.gatherer.gradle.internal.GitCredentials;
import ai.pipestream.grpc.gatherer.gradle.internal.GitRepoStager;
import ai.pipestream.grpc.gatherer.gradle.internal.GoogleWktStager;
import ai.pipestream.grpc.gatherer.gradle.internal.JarDependencyStager;
import ai.pipestream.grpc.gatherer.gradle.internal.StagingMerger;

@DisableCachingByDefault(because = "Task input model is still being implemented")
public abstract class GatherProtosTask extends DefaultTask {

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

    @Internal
    public abstract DirectoryProperty getGradleUserHome();

    @Internal
    public abstract Property<Boolean> getOffline();

    @TaskAction
    public void gather() throws IOException {
        Path outputDir = getOutputDir().get().getAsFile().toPath();
        Path stagingRoot = getTemporaryDir().toPath().resolve("staging");
        Path gradleUserHome = getGradleUserHome().get().getAsFile().toPath();
        boolean offline = getOffline().getOrElse(false);

        deleteTree(stagingRoot);
        deleteTree(outputDir);
        Files.createDirectories(stagingRoot);
        Files.createDirectories(outputDir);

        int filesystemCount = FilesystemStager.stage(getFilesystem(), stagingRoot);
        int jarCount = JarDependencyStager.stage(getJarDependencies(), stagingRoot.resolve("jar-dependencies"));
        int googleCount = GoogleWktStager.stage(getGoogleWkt(), stagingRoot.resolve("google"));

        int gitCount = 0;
        String gitRepo = getGit().getRepo().getOrNull();
        if (gitRepo != null && !gitRepo.isBlank()) {
            String gitRef = getGit().getRef().getOrElse("main");
            Path gitCheckout = GitCloneCache.ensureCheckout(gradleUserHome, gitRepo, gitRef,
                    credentialsFrom(getGit()), offline);
            gitCount = GitRepoStager.stage(getGit(), gitCheckout, stagingRoot.resolve("git"));
        }

        StagingMerger.MergeResult mergeResult = StagingMerger.merge(stagingRoot, outputDir, getLogger());

        getLogger().lifecycle(
                "gatherProtos: filesystem={}, jarDependencies={}, googleWktStaged={}, git={}, merged={}, conflicts={}, output={}",
                filesystemCount, jarCount, googleCount, gitCount,
                mergeResult.mergedCount(), mergeResult.conflictCount(), outputDir);
    }

    private static CredentialsProvider credentialsFrom(GitSpec spec) {
        return GitCredentials.from(spec.getToken().getOrNull(), spec.getUsername().getOrNull(), spec.getPassword().getOrNull());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
