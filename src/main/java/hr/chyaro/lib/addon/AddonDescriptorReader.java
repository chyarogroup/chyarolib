package hr.chyaro.lib.addon;

import hr.chyaro.lib.api.AddonDescriptor;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class AddonDescriptorReader {
    private AddonDescriptorReader() { }

    static AddonDescriptor read(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("gensprout-addon.yml");
            if (entry == null) throw new IllegalArgumentException("Missing gensprout-addon.yml");
            if (jar.getJarEntry("plugin.yml") != null || jar.getJarEntry("paper-plugin.yml") != null) {
                throw new IllegalArgumentException("Managed addons must not declare themselves as Bukkit plugins");
            }
            if (jar.stream().map(JarEntry::getName).anyMatch(name -> name.startsWith("com/github/gensprout/")
                    || name.startsWith("hr/chyaro/lib/"))) {
                throw new IllegalArgumentException("Managed addon contains forbidden host or chyarolib implementation classes");
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8));
            String rawId = required(yaml, "id");
            String rawHost = required(yaml, "host");
            requireLowercaseId(rawId, "addon id");
            requireLowercaseId(rawHost, "host id");
            for (String dependency : yaml.getStringList("depends")) requireLowercaseId(dependency, "dependency id");
            AddonDescriptor descriptor = new AddonDescriptor(
                    rawId,
                    required(yaml, "name"),
                    required(yaml, "version"),
                    required(yaml, "main"),
                    rawHost,
                    required(yaml, "host-version"),
                    required(yaml, "chyarolib-version"),
                    yaml.getInt("api-version", 1),
                    yaml.getStringList("depends").stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()
            );
            SemanticVersion.parse(descriptor.version());
            String classEntry = descriptor.mainClass().replace('.', '/') + ".class";
            if (jar.getJarEntry(classEntry) == null) throw new IllegalArgumentException("Main class is missing: " + descriptor.mainClass());
            return descriptor;
        }
    }

    private static void requireLowercaseId(String value, String field) {
        if (!value.equals(value.toLowerCase(Locale.ROOT)) || !value.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid lowercase " + field + ": " + value);
        }
    }

    private static String required(YamlConfiguration yaml, String key) {
        String value = yaml.getString(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing descriptor key: " + key);
        return value.trim();
    }
}
