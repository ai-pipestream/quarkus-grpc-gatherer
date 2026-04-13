package ai.pipestream.grpc.gatherer.spi;

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

import org.jboss.logging.Logger;

/**
 * Shared helpers used by all {@link ProtoGatherer} implementations to copy
 * {@code .proto} files into their staging directory with per-gatherer
 * dedup, exclude filtering, and filename-based skip rules.
 *
 * <p>The filename skip rules exclude paths that look like known negative
 * test fixtures ({@code invalids/}, {@code /dir/}, {@code invalid.proto}).
 * These match the original in-lined behavior and are preserved so existing
 * tests keep passing.
 */
public final class ProtoFileCopier {

    private static final Logger LOG = Logger.getLogger(ProtoFileCopier.class);

    private ProtoFileCopier() {
    }

    /**
     * Copy every {@code .proto} file under {@code sourceDir} into
     * {@code targetDir}, preserving relative paths under {@code root}.
     *
     * @param root the base path to relativize against
     * @param sourceDir the directory to scan for proto files
     * @param targetDir the directory to copy proto files into
     * @param ctx the gather context
     * @param pathPrefix an optional prefix to prepend to paths when checking for exclusion
     * @return the number of files copied
     * @throws IOException if an I/O error occurs
     */
    public static int copyProtoTree(Path root, Path sourceDir, Path targetDir,
            GatherContext ctx, String pathPrefix) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        final int[] copied = { 0 };
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .filter(ProtoFileCopier::notANegativeFixture)
                    .toList();
            for (Path proto : protos) {
                Path rel = root.relativize(proto);
                copied[0] += copySingleProto(proto, rel, targetDir, ctx, pathPrefix);
            }
        }
        return copied[0];
    }

    /**
     * Copy a single {@code .proto} file into the target staging directory,
     * honoring dedup and exclude filters.
     *
     * @param source the source proto file path
     * @param relative the relative path of the proto file
     * @param targetDir the directory to copy the proto file into
     * @param ctx the gather context
     * @param pathPrefix an optional prefix to prepend to paths when checking for exclusion
     * @return {@code 1} if the file was copied or already present,
     *         {@code 0} if it was excluded or deduped away
     * @throws IOException if an I/O error occurs
     */
    public static int copySingleProto(Path source, Path relative, Path targetDir,
            GatherContext ctx, String pathPrefix) throws IOException {
        String rel = relative.toString().replace('\\', '/');
        if (rel.startsWith("proto/")) {
            rel = rel.substring("proto/".length());
        }

        String checkPath = (pathPrefix != null ? pathPrefix : "") + rel;
        if (ctx.isExcluded(checkPath)) {
            LOG.debugf("Excluding proto file: %s", checkPath);
            return 0;
        }

        Path target = targetDir.resolve(rel).normalize();

        String contentHash = sha256(source);
        if (ctx.seenHashes().containsKey(rel)) {
            return 0;
        }
        ctx.seenHashes().put(rel, contentHash);

        if (Files.exists(target) && sha256(target).equals(contentHash)) {
            return 1;
        }

        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return 1;
    }

    /**
     * Computes the SHA-256 hash of the given file.
     *
     * @param path the path to the file
     * @return the hex string representing the hash
     * @throws IOException if an I/O error occurs
     */
    public static String sha256(Path path) throws IOException {
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

    private static boolean notANegativeFixture(Path p) {
        String s = p.toString().replace('\\', '/');
        return !s.contains("/invalids/") && !s.contains("/dir/") && !s.contains("invalid.proto");
    }

    /**
     * Splits a comma-separated string into a list of trimmed, non-empty strings.
     *
     * @param raw the raw CSV string
     * @return a list of trimmed strings
     */
    public static List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
