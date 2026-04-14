package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import ai.pipestream.grpc.gatherer.gradle.JarDependenciesSpec;

public final class JarDependencyStager {

    private JarDependencyStager() {
    }

    public static int stage(JarDependenciesSpec spec, Path targetDir) throws IOException {
        boolean scanAll = spec.getScanAll().getOrElse(false);
        List<String> dependencies = spec.getDependencies().getOrElse(List.of());
        if (!scanAll && dependencies.isEmpty()) {
            return 0;
        }

        Files.createDirectories(targetDir);
        Set<String> requested = new HashSet<>(dependencies);

        int copied = 0;
        for (File jar : spec.getResolvedJars().getFiles()) {
            if (!jar.getName().endsWith(".jar")) {
                continue;
            }
            if (!scanAll && !matchesRequested(jar.toPath(), requested)) {
                continue;
            }
            copied += extractProtoEntries(jar.toPath(), targetDir, null);
        }
        return copied;
    }

    static int extractProtoEntries(Path jarPath, Path targetDir, String requiredPrefix) throws IOException {
        int copied = 0;
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".proto")) {
                    continue;
                }
                String normalized = name.replace('\\', '/');
                if (requiredPrefix != null && !normalized.startsWith(requiredPrefix)) {
                    continue;
                }
                Path asPath = Path.of(normalized);
                if (!ProtoFileCopier.notANegativeFixture(asPath)) {
                    continue;
                }
                String relative = ProtoFileCopier.stripProtoPrefix(normalized);
                Path staged = targetDir.resolve(relative).normalize();
                Files.createDirectories(staged.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            }
        }
        return copied;
    }

    private static boolean matchesRequested(Path jarPath, Set<String> requested) {
        String path = jarPath.toAbsolutePath().normalize().toString().replace('\\', '/');
        for (String ga : requested) {
            int idx = ga.indexOf(':');
            if (idx <= 0 || idx == ga.length() - 1) {
                continue;
            }
            String group = ga.substring(0, idx);
            String artifact = ga.substring(idx + 1);
            String groupPath = "/" + group.replace('.', '/') + "/" + artifact + "/";
            if (path.contains(groupPath) || jarPath.getFileName().toString().startsWith(artifact + "-")) {
                return true;
            }
        }
        return false;
    }
}
