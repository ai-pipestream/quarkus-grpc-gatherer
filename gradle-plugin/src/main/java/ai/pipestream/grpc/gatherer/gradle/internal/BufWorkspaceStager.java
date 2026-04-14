package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class BufWorkspaceStager {

    private BufWorkspaceStager() {
    }

    public static int stageWorkspace(Path cloneRoot, List<String> modules, String protoSubdir, Path targetDir)
            throws IOException {
        int copied = 0;
        for (String module : modules) {
            Path moduleDir = cloneRoot.resolve(module).normalize();
            if (!moduleDir.startsWith(cloneRoot)) {
                throw new IOException("buf-workspace module path escapes repo root: " + module);
            }
            if (!Files.isDirectory(moduleDir)) {
                throw new IOException("buf-workspace module directory does not exist in repo: " + module);
            }

            Path protoRoot = moduleDir.resolve(protoSubdir).normalize();
            if (!Files.isDirectory(protoRoot)) {
                protoRoot = moduleDir;
            }

            copied += ProtoFileCopier.copyProtoTree(protoRoot, protoRoot, targetDir);
        }
        return copied;
    }
}
