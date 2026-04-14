package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import ai.pipestream.grpc.gatherer.gradle.FilesystemSpec;

public final class FilesystemStager {

    private FilesystemStager() {
    }

    public static int stage(FilesystemSpec spec, Path stagingRoot) throws IOException {
        Files.createDirectories(stagingRoot);
        int copied = 0;
        int index = 0;
        for (File dir : spec.getDirs().getFiles()) {
            Path source = dir.toPath().toAbsolutePath().normalize();
            Path targetDir = stagingRoot.resolve("filesystem-" + index++);
            Files.createDirectories(targetDir);
            copied += ProtoFileCopier.copyProtoTree(source, source, targetDir);
        }

        if (spec.getScanRoot().isPresent()) {
            String scanRoot = spec.getScanRoot().get().trim();
            if (!scanRoot.isEmpty()) {
                copied += stageScanned(Path.of(scanRoot).toAbsolutePath().normalize(), stagingRoot);
            }
        }
        return copied;
    }

    private static int stageScanned(Path root, Path stagingRoot) throws IOException {
        if (!Files.isDirectory(root)) {
            return 0;
        }

        int copied = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> protoDirs = paths
                    .filter(Files::isDirectory)
                    .filter(FilesystemStager::isScanCandidate)
                    .toList();
            for (Path protoDir : protoDirs) {
                String sanitizedDirName = root.relativize(protoDir).toString().replace('\\', '-').replace('/', '-');
                if (sanitizedDirName.isEmpty()) {
                    sanitizedDirName = "root";
                }
                Path targetDir = stagingRoot.resolve("scan-" + sanitizedDirName);
                Files.createDirectories(targetDir);
                copied += ProtoFileCopier.copyProtoTree(protoDir, protoDir, targetDir);
            }
        }
        return copied;
    }

    private static boolean isScanCandidate(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.endsWith("/src/main/proto") || s.endsWith("/src/main/resources");
    }
}
