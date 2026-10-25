package hr.chyaro.lib.reload;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class JarCache {
    private final Path root;
    private final int retainedVersions;

    public JarCache(Path root, int retainedVersions) throws Exception {
        this.root = root.toAbsolutePath().normalize();
        this.retainedVersions = Math.max(1, retainedVersions);
        Files.createDirectories(root);
    }

    public synchronized Path snapshot(String componentId, Path source) throws Exception {
        validateJar(source);
        Path directory = root.resolve(componentId.toLowerCase());
        Files.createDirectories(directory);
        String hash = sha256(source);
        Path target = directory.resolve(hash + ".jar");
        if (!Files.exists(target)) Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.from(Instant.now()));
        prune(directory);
        return target;
    }

    public synchronized Path lastKnownGood(String componentId) throws Exception {
        Path directory = root.resolve(componentId.toLowerCase());
        if (!Files.isDirectory(directory)) return null;
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .max(Comparator.comparingLong(this::modified)).orElse(null);
        }
    }

    public synchronized Path restore(String componentId, Path deployedJar) throws Exception {
        Path backup = lastKnownGood(componentId);
        if (backup == null) throw new IllegalStateException("No last-known-good JAR for " + componentId);
        Path quarantine = root.resolve("quarantine");
        Files.createDirectories(quarantine);
        if (Files.exists(deployedJar)) {
            Path rejected = quarantine.resolve(componentId.toLowerCase() + "-" + System.currentTimeMillis() + ".jar");
            Files.move(deployedJar, rejected, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.copy(backup, deployedJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    public static void validateJar(Path jar) throws Exception {
        if (jar == null || !Files.isRegularFile(jar) || Files.size(jar) < 32) {
            throw new IllegalArgumentException("Missing or empty JAR: " + jar);
        }
        try (java.util.jar.JarFile ignored = new java.util.jar.JarFile(jar.toFile())) { }
    }

    public static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void prune(Path directory) throws Exception {
        List<Path> entries;
        try (var stream = Files.list(directory)) {
            entries = stream.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparingLong(this::modified).reversed()).toList();
        }
        for (int i = retainedVersions; i < entries.size(); i++) Files.deleteIfExists(entries.get(i));
    }

    private long modified(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (Exception ignored) { return 0L; }
    }
}
