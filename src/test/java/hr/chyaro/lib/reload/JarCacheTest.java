package hr.chyaro.lib.reload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarCacheTest {
    @TempDir Path temporary;

    @Test
    void retainsThreeMostRecentAndRestoresLastKnownGood() throws Exception {
        JarCache cache = new JarCache(temporary.resolve("cache"), 3);
        Path deployed = temporary.resolve("host.jar");
        Path good = writeJar(deployed, "good");
        Path snapshot = cache.snapshot("gensprout", good);
        Files.setLastModifiedTime(snapshot, FileTime.fromMillis(10));

        for (int version = 2; version <= 4; version++) {
            writeJar(deployed, "version-" + version);
            Path saved = cache.snapshot("gensprout", deployed);
            Files.setLastModifiedTime(saved, FileTime.fromMillis(version * 10L));
        }
        try (var files = Files.list(temporary.resolve("cache/gensprout"))) {
            assertEquals(3, files.filter(Files::isRegularFile).count());
        }

        String expected = JarCache.sha256(deployed);
        writeJar(deployed, "rejected");
        cache.restore("gensprout", deployed);
        assertEquals(expected, JarCache.sha256(deployed));
        try (var quarantined = Files.list(temporary.resolve("cache/quarantine"))) {
            assertTrue(quarantined.anyMatch(Files::isRegularFile));
        }
    }

    private static Path writeJar(Path path, String marker) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return path;
    }
}
