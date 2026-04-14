package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import ai.pipestream.grpc.gatherer.gradle.GitSpec;

public final class GitRepoStager {

    private GitRepoStager() {
    }

    public static int stage(GitSpec spec, Path cacheCheckoutRoot, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        List<String> modules = spec.getModules().getOrElse(List.of());
        if (!modules.isEmpty()) {
            return stageModules(cacheCheckoutRoot, modules, targetDir);
        }

        Path protoRoot = cacheCheckoutRoot.resolve(spec.getSubdir().getOrElse("proto")).normalize();
        if (!Files.isDirectory(protoRoot)) {
            throw new IOException("Configured git proto subdir does not exist: " + protoRoot);
        }

        List<String> paths = spec.getPaths().getOrElse(List.of());
        if (!paths.isEmpty()) {
            return stageConfiguredPaths(protoRoot, paths, targetDir);
        }

        return ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir);
    }

    private static int stageModules(Path cloneRoot, List<String> modules, Path targetDir) throws IOException {
        int copied = 0;
        for (String module : modules) {
            Path moduleDir = cloneRoot.resolve(module).normalize();
            if (!moduleDir.startsWith(cloneRoot)) {
                throw new IOException("Git module path escapes repo root: " + module);
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new IOException("Git module directory does not exist in repo: " + module);
            }
            copied += ProtoFileCopier.copyProtoTree(cloneRoot, moduleDir, targetDir);
        }
        return copied;
    }

    private static int stageConfiguredPaths(Path protoRoot, List<String> configuredPaths, Path targetDir) throws IOException {
        int copied = 0;
        for (String configuredPath : configuredPaths) {
            Path resolved = protoRoot.resolve(configuredPath).normalize();
            if (!resolved.startsWith(protoRoot)) {
                throw new IOException("Configured git path escapes git-subdir: " + configuredPath);
            }
            if (Files.isDirectory(resolved)) {
                copied += ProtoFileCopier.copyProtoTree(protoRoot, resolved, targetDir);
                continue;
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IOException("Configured git path does not exist: " + resolved);
            }
            if (!resolved.getFileName().toString().endsWith(".proto")) {
                throw new IOException("Configured git path is not a .proto file: " + resolved);
            }
            Path relative = protoRoot.relativize(resolved);
            Path target = targetDir.resolve(ProtoFileCopier.stripProtoPrefix(relative.toString())).normalize();
            Path normalizedTargetDir = targetDir.toAbsolutePath().normalize();
            Path normalizedTarget = target.toAbsolutePath().normalize();
            if (!normalizedTarget.startsWith(normalizedTargetDir)) {
                throw new IOException("Configured git path escapes output root: " + configuredPath);
            }
            Files.createDirectories(normalizedTarget.getParent());
            Files.copy(resolved, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }
}
