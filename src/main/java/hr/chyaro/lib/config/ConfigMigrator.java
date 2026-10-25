package hr.chyaro.lib.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.logging.Level;

/**
 * ConfigMigrator automatically merges new configuration entries from packaged default
 * JAR resources into an existing plugin config file on disk without changing or overwriting
 * existing user-defined values (such as IP addresses, server names, custom pricing, etc.).
 *
 * It uses the new default configuration structure as the template baseline so new options
 * are placed in their proper sections rather than being appended at the end of the file.
 */
public class ConfigMigrator {

    private ConfigMigrator() {
    }

    /**
     * Migrates missing default configuration keys from the default config resource stream
     * into the target plugin configuration file on disk while preserving template structure.
     *
     * @param plugin The JavaPlugin instance.
     * @param configFile The target configuration file on disk.
     * @param defaultStream The input stream of the default configuration file from JAR resources.
     * @return true if new configuration keys were added and saved; false otherwise.
     */
    public static boolean migrateConfig(JavaPlugin plugin, File configFile, InputStream defaultStream) {
        if (plugin == null || configFile == null || defaultStream == null) {
            return false;
        }

        if (!configFile.exists()) {
            plugin.saveResource(configFile.getName(), false);
            return true;
        }

        try {
            FileConfiguration existingConfig = YamlConfiguration.loadConfiguration(configFile);
            FileConfiguration newConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream, StandardCharsets.UTF_8));

            Set<String> defaultKeys = newConfig.getKeys(true);
            int addedCount = 0;

            for (String key : defaultKeys) {
                if (!existingConfig.contains(key, true)) {
                    if (!newConfig.isConfigurationSection(key)) {
                        addedCount++;
                    }
                }
            }

            if (addedCount > 0) {
                // Copy all existing user values into the new configuration template
                Set<String> existingKeys = existingConfig.getKeys(true);
                for (String key : existingKeys) {
                    if (!existingConfig.isConfigurationSection(key)) {
                        newConfig.set(key, existingConfig.get(key));
                    }
                }

                newConfig.save(configFile);
                plugin.getLogger().info("ConfigMigrator: Automatically migrated " + addedCount + " new configuration setting(s) into " + configFile.getName() + " while preserving user customizations and template structure.");
                return true;
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "ConfigMigrator: Failed to migrate configuration file " + configFile.getName(), e);
        }

        return false;
    }

    /**
     * Migrates missing default configuration keys for a specific resource file in the plugin data folder.
     *
     * @param plugin The JavaPlugin instance.
     * @param resourcePath Path to the resource file (e.g. "config.yml").
     * @return true if new keys were added and saved; false otherwise.
     */
    public static boolean migrateConfig(JavaPlugin plugin, String resourcePath) {
        if (plugin == null || resourcePath == null || resourcePath.isEmpty()) {
            return false;
        }
        File configFile = new File(plugin.getDataFolder(), resourcePath);
        InputStream defaultStream = plugin.getResource(resourcePath);
        if (defaultStream == null) {
            return false;
        }
        return migrateConfig(plugin, configFile, defaultStream);
    }

    /**
     * Convenience method to migrate the standard 'config.yml' for a plugin.
     *
     * @param plugin The JavaPlugin instance.
     * @return true if new keys were added and saved; false otherwise.
     */
    public static boolean migrateConfig(JavaPlugin plugin) {
        return migrateConfig(plugin, "config.yml");
    }
}
