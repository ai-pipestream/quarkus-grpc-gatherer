package ai.pipestream.grpc.gatherer.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatherProtosTaskTest {

    @TempDir
    Path testProjectDir;

    @Test
    void gatherProtosRunsAndIsUpToDateOnSecondRun() throws IOException {
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

        Files.writeString(testProjectDir.resolve("build.gradle"), """
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
                }
                """);

        BuildResult first = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments("gatherProtos")
                .build();

        assertEquals(TaskOutcome.SUCCESS, first.task(":gatherProtos").getOutcome());
        assertTrue(Files.isDirectory(testProjectDir.resolve("build/custom-gathered/proto")));

        BuildResult second = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withPluginClasspath()
                .withArguments("gatherProtos")
                .build();

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gatherProtos").getOutcome());
    }
}
