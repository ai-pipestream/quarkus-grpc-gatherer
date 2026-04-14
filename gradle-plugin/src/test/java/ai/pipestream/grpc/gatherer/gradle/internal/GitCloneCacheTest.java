package ai.pipestream.grpc.gatherer.gradle.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCloneCacheTest {

    @Test
    void offlineWithNoCacheThrows(@TempDir Path gradleUserHome) {
        IOException ex = assertThrows(IOException.class, () ->
                GitCloneCache.ensureCheckout(gradleUserHome, "https://example.com/repo.git", "main", null, true));
        assertTrue(ex.getMessage().contains("--offline but no cached checkout"));
    }

    @Test
    void resolveRemoteShaShortCircuitsPinnedSha(@TempDir Path gradleUserHome) throws Exception {
        String resolved = GitCloneCache.resolveRemoteSha(
                gradleUserHome,
                "https://example.com/repo.git",
                "ABCDEF1234",
                null,
                false);
        assertEquals("abcdef1234", resolved);
    }
}
