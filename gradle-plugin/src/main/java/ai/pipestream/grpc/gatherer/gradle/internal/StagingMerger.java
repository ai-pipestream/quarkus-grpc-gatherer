package ai.pipestream.grpc.gatherer.gradle.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.gradle.api.logging.Logger;

public final class StagingMerger {

    private static final String MANIFEST_FILE = ".gathered-protos-manifest.txt";

    private StagingMerger() {
    }

    public static MergeResult merge(Path stagedRoot, Path targetDir, Logger logger) throws IOException {
        Map<String, String> mergedHashes = new HashMap<>();
        Map<String, String> mergedSources = new HashMap<>();
        Map<String, List<String>> allConflicts = new HashMap<>();
        List<String> manifestPaths = new ArrayList<>();

        if (Files.isDirectory(stagedRoot)) {
            try (Stream<Path> subdirs = Files.list(stagedRoot)) {
                // Sort subdir iteration so "first source wins" is deterministic
                // across platforms. Files.list returns filesystem-dependent order
                // (alphabetical on Windows NTFS, hash-indexed on Linux ext4 with
                // dir_index, insertion order on some filesystems) which meant
                // conflict-resolution used to be flaky: on ext4 the merge could
                // pick the B source over A purely because readdir happened to
                // return filesystem-1 before filesystem-0 in hash order. Sorting
                // by file name makes the filesystem-<index> naming scheme
                // FilesystemStager writes (and similar conventions used by the
                // other stagers) resolve in index order, which means the first
                // dir the user configured wins the conflict — the only intuitive
                // outcome.
                List<Path> orderedSubdirs = subdirs
                        .filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path subdir : orderedSubdirs) {
                    String sourceName = subdir.getFileName().toString();
                    if ("google".equals(sourceName)) {
                        continue;
                    }
                    mergeSource(subdir, targetDir, mergedHashes, mergedSources, allConflicts, manifestPaths, sourceName);
                }
            }
        }

        reportConflicts(allConflicts, logger);
        Files.write(targetDir.resolve(MANIFEST_FILE), manifestPaths);
        return new MergeResult(mergedHashes.size(), allConflicts.size());
    }

    private static void mergeSource(Path sourceDir, Path targetDir, Map<String, String> mergedHashes,
            Map<String, String> mergedSources, Map<String, List<String>> allConflicts,
            List<String> manifestPaths, String sourceName) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(sourceDir)) {
            List<Path> protos = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".proto"))
                    .toList();
            for (Path proto : protos) {
                String relStr = sourceDir.relativize(proto).toString().replace('\\', '/');
                Path target = targetDir.resolve(relStr).normalize();
                String contentHash = ProtoFileCopier.sha256(proto);

                if (mergedHashes.containsKey(relStr)) {
                    if (!mergedHashes.get(relStr).equals(contentHash)) {
                        allConflicts.computeIfAbsent(relStr, k -> {
                            List<String> list = new ArrayList<>();
                            list.add(mergedSources.get(relStr));
                            return list;
                        }).add(sourceName);
                    }
                    continue;
                }
                mergedHashes.put(relStr, contentHash);
                mergedSources.put(relStr, sourceName);
                manifestPaths.add(relStr);

                if (Files.exists(target) && ProtoFileCopier.sha256(target).equals(contentHash)) {
                    continue;
                }
                Files.createDirectories(target.getParent());
                Files.copy(proto, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void reportConflicts(Map<String, List<String>> allConflicts, Logger logger) {
        if (allConflicts.isEmpty()) {
            return;
        }
        int count = allConflicts.size();
        logger.warn("Detected {} conflicting proto file(s) during merging. For each path, the version from the first source encountered was kept.",
                count);
        int reported = 0;
        for (Map.Entry<String, List<String>> entry : allConflicts.entrySet()) {
            if (reported >= 10) {
                logger.warn(" - ... and {} more conflict(s)", count - reported);
                break;
            }
            List<String> sources = entry.getValue();
            String sourcesStr = sources.size() > 5
                    ? sources.subList(0, 5).toString().replace("]", "") + ", ... and " + (sources.size() - 5) + " more]"
                    : sources.toString();
            logger.warn(" - {}: provided by {} (using version from {})", entry.getKey(), sourcesStr, sources.getFirst());
            reported++;
        }
    }

    public record MergeResult(int mergedCount, int conflictCount) {
    }
}
