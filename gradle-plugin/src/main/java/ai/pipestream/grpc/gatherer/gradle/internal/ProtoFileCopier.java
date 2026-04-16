package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ProtoFileCopier {

    private ProtoFileCopier() {
    }

    static int copyProtoTree(Path root, Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        int copied = 0;
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .toList();
            for (Path proto : protos) {
                Path rel = root.relativize(proto);
                Path target = targetDir.resolve(stripProtoPrefix(rel.toString())).normalize();
                Files.createDirectories(target.getParent());
                Files.copy(proto, target, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            }
        }
        return copied;
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    static String stripProtoPrefix(String path) {
        String normalized = path.replace('\\', '/');
        if (normalized.startsWith("proto/")) {
            return normalized.substring("proto/".length());
        }
        return normalized;
    }
}
