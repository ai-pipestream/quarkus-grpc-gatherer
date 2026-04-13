package ai.pipestream.grpc.gatherer.sources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.prebuild.CodeGenException;

/**
 * Gathers {@code .proto} files from configured filesystem directories.
 *
 * <p>Reads {@code quarkus.grpc-gather.filesystem-dirs}, a comma-separated
 * list of absolute or relative directory paths. Each entry may optionally
 * be prefixed with a build-ref alias using the form
 * {@code ref=/absolute/path}; the alias is used as the staging subdirectory
 * name under {@code gathered-protos/}. Without an alias, files land under
 * {@code gathered-protos/fs-manual/}.
 */
public final class FilesystemGatherer implements ProtoGatherer {

    /**
     * Creates a new instance of {@link FilesystemGatherer}.
     */
    public FilesystemGatherer() {
    }

    static final String FILESYSTEM_DIRS = "quarkus.grpc-gather.filesystem-dirs";

    @Override
    public String id() {
        return "filesystem";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(FILESYSTEM_DIRS, String.class)
                .filter(s -> !s.isBlank())
                .isPresent();
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        String dirs = context.config().getOptionalValue(FILESYSTEM_DIRS, String.class).orElse("").trim();
        if (dirs.isEmpty()) {
            return 0;
        }

        int copied = 0;
        for (String entry : ProtoFileCopier.splitCsv(dirs)) {
            String buildRef = "fs-manual";
            String dirPath = entry;

            int eqIdx = entry.indexOf('=');
            if (eqIdx > 0) {
                String candidate = entry.substring(0, eqIdx).trim();
                String pathPart = entry.substring(eqIdx + 1).trim();
                if (!pathPart.isEmpty()) {
                    buildRef = candidate;
                    dirPath = pathPart;
                }
            }

            Path source = Path.of(dirPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(source)) {
                throw new CodeGenException("Configured filesystem proto directory does not exist: " + source);
            }

            Path targetDir = context.stagingRoot().resolve(buildRef);
            Files.createDirectories(targetDir);
            copied += ProtoFileCopier.copyProtoTree(source, source, targetDir, context, buildRef + "/");
        }
        return copied;
    }
}
