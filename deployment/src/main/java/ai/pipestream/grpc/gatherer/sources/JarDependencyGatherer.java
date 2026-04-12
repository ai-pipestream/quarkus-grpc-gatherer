package ai.pipestream.grpc.gatherer.sources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.pipestream.grpc.gatherer.spi.GatherContext;
import ai.pipestream.grpc.gatherer.spi.ProtoFileCopier;
import ai.pipestream.grpc.gatherer.spi.ProtoGatherer;

import io.quarkus.bootstrap.prebuild.CodeGenException;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathFilter;
import io.quarkus.runtime.util.HashUtil;

/**
 * Extracts {@code .proto} files from project runtime dependencies (JARs).
 *
 * <p>Reads {@code quarkus.grpc-gather.jar-dependencies} (comma-separated
 * {@code groupId:artifactId} list) or {@code quarkus.grpc-gather.jar-scan-all}
 * to scan every runtime dependency.
 */
public final class JarDependencyGatherer implements ProtoGatherer {

    static final String JAR_DEPS = "quarkus.grpc-gather.jar-dependencies";
    static final String JAR_SCAN_ALL = "quarkus.grpc-gather.jar-scan-all";

    @Override
    public String id() {
        return "jar";
    }

    @Override
    public boolean isConfigured(GatherContext context) {
        return context.config().getOptionalValue(JAR_DEPS, String.class).filter(s -> !s.isBlank()).isPresent()
                || context.config().getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false);
    }

    @Override
    public int gather(GatherContext context) throws IOException, CodeGenException {
        boolean scanAll = context.config().getOptionalValue(JAR_SCAN_ALL, Boolean.class).orElse(false);
        Set<String> requested = new HashSet<>(
                ProtoFileCopier.splitCsv(context.config().getOptionalValue(JAR_DEPS, String.class).orElse("")));
        if (!scanAll && requested.isEmpty()) {
            return 0;
        }

        Path targetDir = context.stagingDirFor(id());
        Path jarTemp = context.codeGenContext().workDir().resolve("grpc-gather-jar-protos");
        Files.createDirectories(jarTemp);

        int copied = 0;
        for (ResolvedDependency dep : context.applicationModel().getRuntimeDependencies()) {
            String gav = dep.getGroupId() + ":" + dep.getArtifactId();
            if (!scanAll && !requested.contains(gav)) {
                continue;
            }
            copied += extractProtoFromDependency(dep, jarTemp, targetDir, context, "jar:" + gav + "/");
        }
        return copied;
    }

    static int extractProtoFromDependency(ResolvedDependency dep, Path tempDir, Path targetDir,
            GatherContext ctx, String pathPrefix) throws IOException, CodeGenException {
        final int[] copied = { 0 };
        String uniqueName = dep.getGroupId() + ":" + dep.getArtifactId() + ":" + dep.getVersion();
        Path unzipDir = tempDir.resolve(HashUtil.sha1(uniqueName));
        Files.createDirectories(unzipDir);

        try {
            dep.getContentTree(new PathFilter(List.of("**/*.proto"), List.of())).walk(pathVisit -> {
                Path path = pathVisit.getPath();
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".proto")) {
                    return;
                }
                try {
                    Path rel;
                    Path root = pathVisit.getRoot();
                    if (Files.isDirectory(root)) {
                        rel = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize());
                    } else {
                        rel = path.getRoot().relativize(path);
                    }
                    Path staged = unzipDir.resolve(rel.toString());
                    Files.createDirectories(staged.getParent());
                    try (InputStream is = Files.newInputStream(path)) {
                        Files.copy(is, staged, StandardCopyOption.REPLACE_EXISTING);
                    }
                    copied[0] += ProtoFileCopier.copySingleProto(staged, rel, targetDir, ctx, pathPrefix);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof CodeGenException cge) {
                throw cge;
            }
            if (e.getCause() instanceof IOException ioe) {
                throw ioe;
            }
            throw e;
        }
        return copied[0];
    }
}
