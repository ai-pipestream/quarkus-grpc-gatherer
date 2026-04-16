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

    /**
     * Stages {@code .proto} files from a cached git checkout into a
     * target directory. Three modes, selected in priority order by which
     * properties are set on the {@link GitSpec}:
     *
     * <ol>
     *   <li><b>Multi-module mode</b> — when {@code modules} is non-empty,
     *       each listed module directory is walked for its own
     *       {@code <module>/<subdir>/**.proto} tree. The files from every
     *       module are <strong>flattened</strong> onto a single target
     *       root, so cross-module imports resolve. This is the pattern
     *       monorepos with shared proto packages use (e.g.
     *       {@code common/proto/ai/foo/v1/common.proto} and
     *       {@code service/proto/ai/foo/v1/service.proto} both landing
     *       under {@code ai/foo/v1/} in the output).</li>
     *   <li><b>Explicit paths mode</b> — when {@code modules} is empty
     *       and {@code paths} is set, each listed path is copied verbatim
     *       from {@code <subdir>/<path>}, preserving directory structure
     *       relative to {@code subdir}.</li>
     *   <li><b>Single-subdir mode</b> — when neither is set, every
     *       {@code .proto} under {@code <subdir>} (default
     *       {@code "proto"}) is copied, preserving directory structure
     *       relative to {@code subdir}.</li>
     * </ol>
     */
    public static int stage(GitSpec spec, Path cacheCheckoutRoot, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);

        String subdir = spec.getSubdir().getOrElse("proto");
        List<String> modules = spec.getModules().getOrElse(List.of());
        if (!modules.isEmpty()) {
            return stageFlattenedModules(cacheCheckoutRoot, modules, subdir, targetDir);
        }

        Path protoRoot = cacheCheckoutRoot.resolve(subdir).normalize();
        if (!Files.isDirectory(protoRoot)) {
            throw new IOException("Configured git proto subdir does not exist: " + protoRoot);
        }

        List<String> paths = spec.getPaths().getOrElse(List.of());
        if (!paths.isEmpty()) {
            return stageConfiguredPaths(protoRoot, paths, targetDir);
        }

        return ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir);
    }

    /**
     * Walks each named module under {@code cloneRoot}, treats
     * {@code <module>/<subdir>} as that module's proto source root, and
     * copies the trees flattened onto a single {@code targetDir}. The
     * relativization uses the per-module proto root (not the repo root),
     * which is what flattens the modules into a shared tree.
     *
     * <p>If a module's {@code <subdir>} does not exist, the module
     * directory itself is treated as the proto root — so modules that
     * put their {@code .proto} files directly under the module dir still
     * work without reconfiguration.
     */
    private static int stageFlattenedModules(Path cloneRoot, List<String> modules, String subdir, Path targetDir)
            throws IOException {
        int copied = 0;
        for (String module : modules) {
            Path moduleDir = cloneRoot.resolve(module).normalize();
            if (!moduleDir.startsWith(cloneRoot)) {
                throw new IOException("Git module path escapes repo root: " + module);
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new IOException("Git module directory does not exist in repo: " + module);
            }

            Path protoRoot = moduleDir.resolve(subdir).normalize();
            if (!Files.isDirectory(protoRoot)) {
                protoRoot = moduleDir;
            }

            copied += ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir);
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
