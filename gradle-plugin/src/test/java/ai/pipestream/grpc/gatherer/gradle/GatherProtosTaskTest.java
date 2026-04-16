package ai.pipestream.grpc.gatherer.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.eclipse.jgit.api.Git;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatherProtosTaskTest {

    @TempDir
    Path testProjectDir;

    @TempDir
    Path tempGitDir;

    private final List<Git> openedGits = new ArrayList<>();

    @AfterEach
    void closeGits() {
        for (Git git : openedGits) {
            git.close();
        }
        openedGits.clear();
    }

    @Test
    void filesystemDirsDedupAndIncrementalBehavior() throws IOException {
        writeSettings();

        Path firstDir = testProjectDir.resolve("test-protos-a");
        Path secondDir = testProjectDir.resolve("test-protos-b");
        Files.createDirectories(firstDir);
        Files.createDirectories(secondDir.resolve("bar"));

        Files.writeString(firstDir.resolve("foo.proto"), "syntax = \"proto3\"; message Foo {}\n");
        Files.writeString(secondDir.resolve("foo.proto"), "syntax = \"proto3\"; message Foo {}\n");
        Files.writeString(secondDir.resolve("bar/baz.proto"), "syntax = \"proto3\"; message Baz {}\n");

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('custom-gathered/proto')
                    filesystem {
                        dirs.from(file('test-protos-a'), file('test-protos-b'))
                    }
                }
                """);

        BuildResult first = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());
        assertTrue(Files.isDirectory(testProjectDir.resolve("build/custom-gathered/proto")));
        assertTrue(Files.exists(testProjectDir.resolve("build/custom-gathered/proto/foo.proto")));
        assertTrue(Files.exists(testProjectDir.resolve("build/custom-gathered/proto/bar/baz.proto")));

        BuildResult second = runGatherProtos();
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gatherProtos").getOutcome());

        Files.writeString(firstDir.resolve("foo.proto"), "syntax = \"proto3\"; message FooChanged {}\n");

        BuildResult third = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, third.task(":gatherProtos").getOutcome());
    }

    @Test
    void filesystemDirsDifferentContentWarnsAboutConflict() throws IOException {
        writeSettings();

        String firstContent = "syntax = \"proto3\"; message FooFirst {}\n";
        String secondContent = "syntax = \"proto3\"; message FooSecond {}\n";

        Path firstDir = testProjectDir.resolve("conflict-a");
        Path secondDir = testProjectDir.resolve("conflict-b");
        Files.createDirectories(firstDir);
        Files.createDirectories(secondDir);
        Files.writeString(firstDir.resolve("foo.proto"), firstContent);
        Files.writeString(secondDir.resolve("foo.proto"), secondContent);

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('conflict-gathered/proto')
                    filesystem {
                        dirs.from(file('conflict-a'), file('conflict-b'))
                    }
                }
                """);

        BuildResult first = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());
        assertTrue(first.getOutput().contains("conflicting proto file(s)"));

        Path mergedProto = testProjectDir.resolve("build/conflict-gathered/proto/foo.proto");
        assertEquals(firstContent, Files.readString(mergedProto));

        BuildResult second = runGatherProtos();
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gatherProtos").getOutcome());
    }

    @Test
    void scanRootFindsNestedSrcMainProtoAndResources() throws IOException {
        writeSettings();

        Path scanRoot = testProjectDir.resolve("workspace");
        Path moduleA = scanRoot.resolve("module-a/src/main/proto");
        Path moduleB = scanRoot.resolve("module-b/src/main/resources");

        Files.createDirectories(moduleA);
        Files.createDirectories(moduleB.resolve("nested"));

        Files.writeString(moduleA.resolve("scan-a.proto"), "syntax = \"proto3\"; message ScanA {}\n");
        Files.writeString(moduleB.resolve("nested/scan-b.proto"), "syntax = \"proto3\"; message ScanB {}\n");

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('scan-gathered/proto')
                    filesystem {
                        scanRoot = '%s'
                    }
                }
                """.formatted(scanRoot.toString().replace("\\", "/")));

        BuildResult result = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, result.task(":gatherProtos").getOutcome());
        assertTrue(Files.exists(testProjectDir.resolve("build/scan-gathered/proto/scan-a.proto")));
        assertTrue(Files.exists(testProjectDir.resolve("build/scan-gathered/proto/nested/scan-b.proto")));
    }

    @Test
    void jarDependenciesExtractProtoFiles() throws IOException {
        writeSettings();

        Path mavenRepo = testProjectDir.resolve("test-m2");
        Path artifactDir = mavenRepo.resolve("com/example/demo-protos/1.0.0");
        Files.createDirectories(artifactDir);

        Path jarPath = artifactDir.resolve("demo-protos-1.0.0.jar");
        createJarWithEntries(jarPath, Map.of(
                "proto/example/hello.proto", "syntax = \"proto3\"; message Hello {}\n",
                "example/nested/world.proto", "syntax = \"proto3\"; message World {}\n"));

        Files.writeString(artifactDir.resolve("demo-protos-1.0.0.pom"), """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>demo-protos</artifactId>
                  <version>1.0.0</version>
                </project>
                """);

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                    maven { url = uri('test-m2') }
                }

                dependencies {
                    implementation 'com.example:demo-protos:1.0.0'
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('jar-gathered/proto')
                    jarDependencies {
                        dependencies = ['com.example:demo-protos']
                        scanAll = false
                    }
                }
                """);

        BuildResult result = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, result.task(":gatherProtos").getOutcome());
        assertTrue(Files.exists(testProjectDir.resolve("build/jar-gathered/proto/example/hello.proto")));
        assertTrue(Files.exists(testProjectDir.resolve("build/jar-gathered/proto/example/nested/world.proto")));
    }

    @Test
    void googleWktStagesButDoesNotMergeIntoOutput() throws IOException {
        writeSettings();

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                dependencies {
                    implementation 'com.google.protobuf:protobuf-java:4.29.3'
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('wkt-gathered/proto')
                    googleWkt {
                        include = true
                    }
                }
                """);

        BuildResult result = runGatherProtos();
        assertEquals(TaskOutcome.SUCCESS, result.task(":gatherProtos").getOutcome());

        Path outputAny = testProjectDir.resolve("build/wkt-gathered/proto/google/protobuf/any.proto");
        Path stagedAny = testProjectDir.resolve("build/tmp/gatherProtos/staging/google/google/protobuf/any.proto");

        assertFalse(Files.exists(outputAny));
        assertTrue(Files.exists(stagedAny));
    }

    @Test
    void gitModulesCloneOnceThenUpToDateOnRerun() throws Exception {
        writeSettings();
        Path gradleUserHome = testProjectDir.resolve("gradle-home");
        GitFixture fixture = createProtoFixtureRepo();

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('buf-gathered/proto')
                    git {
                        repo = '%s'
                        ref = 'main'
                        modules = ['common', 'pipeline-module']
                        subdir = 'proto'
                    }
                }
                """.formatted(fixture.repoUri()));

        BuildResult first = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());

        Path cacheRoot = gradleUserHome.resolve("caches/grpc-gatherer");
        assertTrue(Files.isDirectory(cacheRoot));
        assertTrue(Files.list(cacheRoot).findAny().isPresent());

        BuildResult second = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gatherProtos").getOutcome());
    }

    @Test
    void gitModulesRerunWhenUpstreamMoves() throws Exception {
        writeSettings();
        Path gradleUserHome = testProjectDir.resolve("gradle-home");
        GitFixture fixture = createProtoFixtureRepo();

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('buf-moved/proto')
                    git {
                        repo = '%s'
                        ref = 'main'
                        modules = ['common']
                        subdir = 'proto'
                    }
                }
                """.formatted(fixture.repoUri()));

        BuildResult first = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());

        fixture.commitToMain("common/proto/example/common/v1/extra.proto",
                "syntax = \"proto3\"; package example.common.v1; message Extra {}\n");

        BuildResult second = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.SUCCESS, second.task(":gatherProtos").getOutcome());
    }

    @Test
    void pinnedRefStaysUpToDateAcrossMainBranchChanges() throws Exception {
        writeSettings();
        Path gradleUserHome = testProjectDir.resolve("gradle-home");
        GitFixture fixture = createProtoFixtureRepo();
        fixture.createTag("v1.0.0");

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('buf-tagged/proto')
                    git {
                        repo = '%s'
                        ref = 'v1.0.0'
                        modules = ['common']
                        subdir = 'proto'
                    }
                }
                """.formatted(fixture.repoUri()));

        BuildResult first = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());

        fixture.commitToMain("common/proto/example/common/v1/new_main_only.proto",
                "syntax = \"proto3\"; package example.common.v1; message MainOnly {}\n");

        BuildResult second = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gatherProtos").getOutcome());
    }

    @Test
    void offlineBuildUsesCachedShaAndCheckout() throws Exception {
        writeSettings();
        Path gradleUserHome = testProjectDir.resolve("gradle-home");
        GitFixture fixture = createProtoFixtureRepo();

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3'
                    id 'ai.pipestream.quarkus-grpc-gatherer'
                }

                repositories {
                    mavenLocal()
                    mavenCentral()
                }

                quarkusGrpcGather {
                    outputDir = layout.buildDirectory.dir('buf-offline/proto')
                    git {
                        repo = '%s'
                        ref = 'main'
                        modules = ['common']
                        subdir = 'proto'
                    }
                }
                """.formatted(fixture.repoUri()));

        BuildResult first = runGatherProtos(gradleUserHome);
        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());

        Path movedRepo = fixture.repoPath().resolveSibling("moved-remote");
        Files.move(fixture.repoPath(), movedRepo, StandardCopyOption.ATOMIC_MOVE);

        BuildResult offline = runGatherProtos(gradleUserHome, "--offline");
        assertEquals(TaskOutcome.UP_TO_DATE, offline.task(":gatherProtos").getOutcome());
    }

    @Test
    void parallelProjectsCanShareCloneCacheWithoutLockErrors() throws Exception {
        Path gradleUserHome = testProjectDir.resolve("gradle-home");
        GitFixture fixture = createProtoFixtureRepo();

        Files.writeString(testProjectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                rootProject.name = 'parallel-gather-test'
                include('a', 'b')
                """);

        writeBuildFile("""
                plugins {
                    id 'io.quarkus' version '3.34.3' apply false
                    id 'ai.pipestream.quarkus-grpc-gatherer' apply false
                }

                subprojects {
                    apply plugin: 'io.quarkus'
                    apply plugin: 'ai.pipestream.quarkus-grpc-gatherer'

                    repositories {
                        mavenLocal()
                        mavenCentral()
                    }

                    quarkusGrpcGather {
                        outputDir = layout.buildDirectory.dir('gathered/proto')
                        git {
                            repo = '%s'
                            ref = 'main'
                            modules = ['common']
                            subdir = 'proto'
                        }
                    }
                }
                """.formatted(fixture.repoUri()));

        Files.createDirectories(testProjectDir.resolve("a"));
        Files.createDirectories(testProjectDir.resolve("b"));

        BuildResult result = runBuild(gradleUserHome, ":a:gatherProtos", ":b:gatherProtos", "--parallel", "--max-workers=2");
        assertEquals(TaskOutcome.SUCCESS, result.task(":a:gatherProtos").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":b:gatherProtos").getOutcome());
        assertFalse(result.getOutput().contains("OverlappingFileLockException"));
    }

    private BuildResult runGatherProtos() {
        return runGatherProtos(testProjectDir.resolve("gradle-home"));
    }

    private BuildResult runGatherProtos(Path gradleUserHome, String... extraArgs) {
        String[] args = new String[2 + extraArgs.length];
        args[0] = "gatherProtos";
        args[1] = "--console=plain";
        System.arraycopy(extraArgs, 0, args, 2, extraArgs.length);
        return runBuild(gradleUserHome, args);
    }

    private BuildResult runBuild(Path gradleUserHome, String... args) {
        String[] withGradleHome = new String[args.length + 2];
        withGradleHome[0] = "-g";
        withGradleHome[1] = gradleUserHome.toString();
        System.arraycopy(args, 0, withGradleHome, 2, args.length);
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments(Arrays.asList(withGradleHome))
                .build();
    }

    private void writeSettings() throws IOException {
        Files.writeString(testProjectDir.resolve("settings.gradle"), """
                pluginManagement {
                    repositories {
                        mavenLocal()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                rootProject.name = 'gather-protos-task-test'
                """);
    }

    private void writeBuildFile(String contents) throws IOException {
        Files.writeString(testProjectDir.resolve("build.gradle"), contents);
    }

    private static void createJarWithEntries(Path jarPath, Map<String, String> entries) throws IOException {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes());
                jar.closeEntry();
            }
        }
    }

    private GitFixture createProtoFixtureRepo() throws Exception {
        Path repoPath = tempGitDir.resolve("proto-remote");
        Files.createDirectories(repoPath);
        Git git = Git.init()
                .setInitialBranch("main")
                .setDirectory(repoPath.toFile())
                .call();
        openedGits.add(git);
        writeRepoFile(repoPath.resolve("common/proto/example/common/v1/common.proto"),
                "syntax = \"proto3\";\npackage example.common.v1;\nmessage Common {}\n");
        writeRepoFile(repoPath.resolve("pipeline-module/proto/example/pipeline/v1/pipeline.proto"),
                "syntax = \"proto3\";\npackage example.pipeline.v1;\nimport \"example/common/v1/common.proto\";\nmessage Pipeline {}\n");
        git.add().addFilepattern(".").call();
        git.commit().setMessage("initial").call();
        return new GitFixture(repoPath, git);
    }

    private static void writeRepoFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private record GitFixture(Path repoPath, Git git) {
        String repoUri() {
            return repoPath.toUri().toString();
        }

        void commitToMain(String relativePath, String content) throws Exception {
            writeRepoFile(repoPath.resolve(relativePath), content);
            git.add().addFilepattern(".").call();
            git.commit().setMessage("update-" + relativePath).call();
        }

        void createTag(String tagName) throws Exception {
            git.tag().setName(tagName).setAnnotated(false).call();
        }
    }
}
