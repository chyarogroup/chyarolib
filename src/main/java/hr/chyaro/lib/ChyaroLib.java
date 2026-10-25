package hr.chyaro.lib;

import hr.chyaro.lib.command.GenSproutCheckCommand;
import hr.chyaro.lib.command.ChyaroLibCommand;
import hr.chyaro.lib.command.RestartCommand;
import hr.chyaro.lib.command.UpdateJarCommand;
import hr.chyaro.lib.config.ConfigMigrator;
import hr.chyaro.lib.purpur.PurpurDownloader;
import hr.chyaro.lib.version.GenSproutVersionChecker;
import hr.chyaro.lib.addon.AddonManager;
import hr.chyaro.lib.api.DatabaseService;
import hr.chyaro.lib.api.ManagedHostRegistry;
import hr.chyaro.lib.api.ManagedWorldService;
import hr.chyaro.lib.database.SqliteDatabaseService;
import hr.chyaro.lib.reload.JarCache;
import hr.chyaro.lib.reload.ManagedHostRegistryImpl;
import hr.chyaro.lib.world.ManagedWorldServiceImpl;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class ChyaroLib extends JavaPlugin implements Listener {

    private PurpurDownloader purpurDownloader;
    private GenSproutVersionChecker genSproutVersionChecker;
    private SqliteDatabaseService databaseService;
    private ManagedWorldServiceImpl worldService;
    private ManagedHostRegistryImpl managedHostRegistry;

    @Override
    public void onEnable() {
        // Save default config.yml if not existing and migrate new keys
        saveDefaultConfig();
        ConfigMigrator.migrateConfig(this);

        try {
            this.databaseService = new SqliteDatabaseService(getLogger(),
                    getConfig().getInt("database.busy-timeout-millis", 10000));
            net.kyori.adventure.text.Component freezeMessage = net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .deserialize(getConfig().getString("managed-reload.freeze-message",
                            "<gold>[chyarolib]</gold> <yellow>This managed world is briefly paused.</yellow>"));
            this.worldService = new ManagedWorldServiceImpl(this, freezeMessage);
            JarCache jarCache = new JarCache(getDataFolder().toPath().resolve("reload-cache"),
                    getConfig().getInt("managed-reload.cache-versions", 3));
            AddonManager addonManager = new AddonManager(getDescription().getVersion(), databaseService,
                    worldService, jarCache, getClassLoader(), getLogger());
            this.managedHostRegistry = new ManagedHostRegistryImpl(addonManager, worldService, jarCache, getLogger());

            Bukkit.getServicesManager().register(DatabaseService.class, databaseService, this, ServicePriority.Normal);
            Bukkit.getServicesManager().register(ManagedWorldService.class, worldService, this, ServicePriority.Normal);
            Bukkit.getServicesManager().register(ManagedHostRegistry.class, managedHostRegistry, this, ServicePriority.Normal);
        } catch (Exception exception) {
            getLogger().severe("[chyarolib] Could not initialize managed runtime: " + exception.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Register listener for dynamic plugin loading events
        Bukkit.getPluginManager().registerEvents(this, this);

        // Migrate configuration for existing loaded plugins (e.g. GenSprout)
        migratePluginConfigs();

        // Initialize services
        this.purpurDownloader = new PurpurDownloader(this);
        this.genSproutVersionChecker = new GenSproutVersionChecker(this);

        // Register commands using Paper's modern Lifecycle Command API (Brigadier)
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            unregisterExistingRestartCommand();
            event.registrar().register(
                "chyarolib",
                "Manage chyarolib hosts and addons",
                List.of(),
                new ChyaroLibCommand(managedHostRegistry)
            );
            event.registrar().register(
                "restart",
                "Announce and restart the Minecraft server",
                List.of(),
                new RestartCommand(this)
            );
            event.registrar().register(
                "updatejar",
                "Download latest Purpur server jar for current version",
                List.of(),
                new UpdateJarCommand(this)
            );
            event.registrar().register(
                "updatecheck",
                "Check Modrinth API for latest GenSprout updates",
                List.of(),
                new GenSproutCheckCommand(this)
            );
        });

        // Auto-download Purpur on startup if enabled in config
        if (getConfig().getBoolean("download.auto-download-on-startup", false)) {
            getLogger().info("auto-download-on-startup is enabled. Checking for latest Purpur JAR build...");
            purpurDownloader.downloadLatestPurpur(null, null);
        }

        // Check GenSprout version on startup if enabled
        if (getConfig().getBoolean("version-check.enabled", true) && getConfig().getBoolean("version-check.check-on-startup", true)) {
            genSproutVersionChecker.checkVersion(null, true);
        }

        getLogger().info("chyarolib v" + getDescription().getVersion() + " (Paper Plugin) enabled successfully!");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        Plugin plugin = event.getPlugin();
        if (plugin instanceof JavaPlugin javaPlugin && !plugin.getName().equalsIgnoreCase(getName())) {
            ConfigMigrator.migrateConfig(javaPlugin);
        }
    }

    private void migratePluginConfigs() {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled() && plugin instanceof JavaPlugin javaPlugin && !plugin.getName().equalsIgnoreCase(getName())) {
                ConfigMigrator.migrateConfig(javaPlugin);
            }
        }
    }

    @Override
    public void onDisable() {
        if (managedHostRegistry != null) managedHostRegistry.close();
        if (databaseService != null) databaseService.close();
        getLogger().info("chyarolib disabled.");
    }

    private void unregisterExistingRestartCommand() {
        try {
            org.bukkit.command.CommandMap commandMap = Bukkit.getCommandMap();
            Class<?> clazz = commandMap.getClass();
            Field knownCommandsField = null;
            while (clazz != null && clazz != Object.class) {
                try {
                    knownCommandsField = clazz.getDeclaredField("knownCommands");
                    break;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            if (knownCommandsField != null) {
                knownCommandsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, org.bukkit.command.Command> knownCommands = (Map<String, org.bukkit.command.Command>) knownCommandsField.get(commandMap);
                knownCommands.remove("restart");
                knownCommands.remove("chyarolib:restart");
                knownCommands.remove("minecraft:restart");
                knownCommands.remove("spigot:restart");
            }
        } catch (Exception e) {
            getLogger().warning("Could not unregister existing restart command: " + e.getMessage());
        }
    }

    public PurpurDownloader getPurpurDownloader() {
        return purpurDownloader;
    }

    public GenSproutVersionChecker getGenSproutVersionChecker() {
        return genSproutVersionChecker;
    }

    public ManagedHostRegistry getManagedHostRegistry() { return managedHostRegistry; }
    public DatabaseService getDatabaseService() { return databaseService; }
}
