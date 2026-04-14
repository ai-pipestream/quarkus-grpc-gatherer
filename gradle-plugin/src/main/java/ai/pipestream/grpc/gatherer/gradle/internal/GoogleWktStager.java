package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.pipestream.grpc.gatherer.gradle.GoogleWktSpec;

public final class GoogleWktStager {

    private GoogleWktStager() {
    }

    public static int stage(GoogleWktSpec spec, Path targetDir) throws IOException {
        if (!spec.getInclude().getOrElse(false)) {
            return 0;
        }

        Files.createDirectories(targetDir);
        for (File file : spec.getProtobufJavaJar().getFiles()) {
            if (isProtobufJavaJar(file.toPath())) {
                return JarDependencyStager.extractProtoEntries(file.toPath(), targetDir, "google/protobuf/");
            }
        }
        return 0;
    }

    private static boolean isProtobufJavaJar(Path jarPath) {
        String fileName = jarPath.getFileName().toString();
        return fileName.startsWith("protobuf-java-") && fileName.endsWith(".jar");
    }
}
