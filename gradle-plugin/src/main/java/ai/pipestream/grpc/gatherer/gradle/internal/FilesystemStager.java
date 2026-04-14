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

    public static int stage(FilesystemSpec spec, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        int copied = 0;
        for (File dir : spec.getDirs().getFiles()) {
            Path source = dir.toPath().toAbsolutePath().normalize();
            copied += ProtoFileCopier.copyProtoTree(source, source, targetDir);
        }

        if (spec.getScanRoot().isPresent()) {
            String scanRoot = spec.getScanRoot().get().trim();
            if (!scanRoot.isEmpty()) {
                copied += stageScanned(Path.of(scanRoot).toAbsolutePath().normalize(), targetDir);
            }
        }
        return copied;
    }

    private static int stageScanned(Path root, Path targetDir) throws IOException {
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
                copied += ProtoFileCopier.copyProtoTree(protoDir, protoDir, targetDir);
            }
        }
        return copied;
    }

    private static boolean isScanCandidate(Path p) {
        String s = p.toString().replace('\\', '/');
        if (s.contains("/invalids") || s.contains("/dir/") || s.endsWith("/dir")
                || s.contains("/grpc-gatherer-orig-tests")
                || s.contains("/build/") || s.contains("/target/")
                || s.contains("/src/test/")) {
            return false;
        }
        return s.endsWith("/src/main/proto") || s.endsWith("/src/main/resources");
    }
}
