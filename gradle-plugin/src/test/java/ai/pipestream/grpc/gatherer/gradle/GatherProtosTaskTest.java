package ai.pipestream.grpc.gatherer.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatherProtosTaskTest {

    @TempDir
    Path testProjectDir;

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

    private BuildResult runGatherProtos() {
        return GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments("gatherProtos", "--console=plain")
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
}
