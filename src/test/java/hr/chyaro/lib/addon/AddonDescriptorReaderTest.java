package hr.chyaro.lib.addon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddonDescriptorReaderTest {
    @TempDir Path temporary;

    @Test
    void readsValidManagedDescriptor() throws Exception {
        Path jar = addonJar("skyblock", false);
        assertEquals("skyblock", AddonDescriptorReader.read(jar).id());
    }

    @Test
    void requiresLowercaseIds() throws Exception {
        Path jar = addonJar("SkyBlock", false);
        assertThrows(IllegalArgumentException.class, () -> AddonDescriptorReader.read(jar));
    }

    @Test
    void rejectsBukkitPluginMetadata() throws Exception {
        Path jar = addonJar("skyblock", true);
        assertThrows(IllegalArgumentException.class, () -> AddonDescriptorReader.read(jar));
    }

    private Path addonJar(String id, boolean bukkitMetadata) throws Exception {
        Path jar = temporary.resolve(id + (bukkitMetadata ? "-bukkit" : "") + ".jar");
        String yaml = "id: " + id + "\nname: Test Addon\nversion: 1.0.0-beta1\n"
                + "main: example.Addon\nhost: gensprout\nhost-version: '>=1.3.0-beta1 <1.4.0'\n"
                + "chyarolib-version: '>=1.0.0 <2.0.0'\napi-version: 1\n";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "gensprout-addon.yml", yaml.getBytes(StandardCharsets.UTF_8));
            put(output, "example/Addon.class", new byte[]{0});
            if (bukkitMetadata) put(output, "plugin.yml", "name: Bad\n".getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static void put(JarOutputStream output, String name, byte[] bytes) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }
}
