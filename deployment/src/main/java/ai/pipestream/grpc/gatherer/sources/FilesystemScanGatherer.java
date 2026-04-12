package ai.pipestream.grpc.gatherer.sources;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.jboss.logging.Logger;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.deployment.CodeGenProvider;

/**
 * Walks a root directory and harvests every {@code src/main/proto} or
 * {@code src/main/resources} subdirectory beneath it, skipping common
 * build, test, and negative-fixture directories. Useful for monorepo
 * layouts where many sibling projects each own a proto source tree.
 *
 * <p>Reads {@code quarkus.grpc-gather.filesystem-scan-root}.
 */
public final class FilesystemScanGatherer implements ProtoGatherer {

    private static final Logger LOG = Logger.getLogger(FilesystemScanGatherer.class);

    static final String FILESYSTEM_SCAN_ROOT = "quarkus.grpc-gather.filesystem-scan-root";

    @Override
    public String id() {
        return "filesystem-scan";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(FILESYSTEM_SCAN_ROOT, String.class)
                .filter(s -> !s.isBlank())
                .isPresent();
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        String scanRoot = context.config().getOptionalValue(FILESYSTEM_SCAN_ROOT, String.class).orElse("").trim();
        if (scanRoot.isEmpty()) {
            return 0;
        }

        Path root = Path.of(scanRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            LOG.warnf("Configured filesystem scan root does not exist: %s", root);
            return 0;
        }

        Path currentProjProtoDir = CodeGenProvider.resolve(context.codeGenContext().inputDir());

        int copied = 0;
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> protoDirs = paths
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        if (s.contains("/invalids") || s.contains("/dir/") || s.endsWith("/dir")
                                || s.contains("/grpc-gatherer-orig-tests")
                                || s.contains("/build/") || s.contains("/target/")
                                || s.contains("/src/test/")) {
                            return false;
                        }
                        if (currentProjProtoDir != null
                                && p.toAbsolutePath().normalize().equals(currentProjProtoDir.toAbsolutePath().normalize())) {
                            return false;
                        }
                        boolean match = s.endsWith("/src/main/proto") || s.endsWith("/src/main/resources");
                        if (match) {
                            LOG.debugf("Scanned and matched proto directory root: %s", p);
                        }
                        return match;
                    })
                    .toList();
            for (Path protoDir : protoDirs) {
                String relPath = root.relativize(protoDir).toString().replace(File.separator, "-");
                if (relPath.isEmpty()) {
                    relPath = "root";
                }
                Path targetDir = context.stagingRoot().resolve("scan-" + relPath);
                Files.createDirectories(targetDir);
                copied += ProtoFileCopier.copyProtoTree(protoDir, protoDir, targetDir, context, "scan-" + relPath + "/");
            }
        }
        return copied;
    }
}
