package hr.chyaro.lib.addon;

import hr.chyaro.lib.api.AddonContext;
import hr.chyaro.lib.api.AddonDescriptor;
import hr.chyaro.lib.api.DatabaseService;
import hr.chyaro.lib.api.GenSproutBridge;
import hr.chyaro.lib.api.ManagedWorldService;
import hr.chyaro.lib.api.Registration;
import hr.chyaro.lib.command.CommandRegistryAccess;
import io.papermc.paper.dialog.Dialog;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

final class AddonContextImpl implements AddonContext, AutoCloseable {
    private final AddonDescriptor descriptor;
    private final Plugin hostPlugin;
    private final GenSproutBridge bridge;
    private final Path dataDirectory;
    private final DatabaseService databaseService;
    private final ManagedWorldService worldService;
    private final List<Registration> registrations = new ArrayList<>();
    private final Set<UUID> dialogViewers = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    AddonContextImpl(AddonDescriptor descriptor, Plugin hostPlugin, GenSproutBridge bridge, Path dataDirectory,
                     DatabaseService databaseService, ManagedWorldService worldService) throws Exception {
        this.descriptor = descriptor;
        this.hostPlugin = hostPlugin;
        this.bridge = bridge;
        this.dataDirectory = dataDirectory;
        this.databaseService = databaseService;
        this.worldService = worldService;
        Files.createDirectories(dataDirectory);
    }

    @Override public AddonDescriptor descriptor() { return descriptor; }
    @Override public Plugin hostPlugin() { return hostPlugin; }
    @Override public GenSproutBridge bridge() { return bridge; }
    @Override public Path dataDirectory() { return dataDirectory; }
    @Override public Logger logger() { return hostPlugin.getLogger(); }
    @Override public DatabaseService databases() { return databaseService; }
    @Override public ManagedWorldService worlds() { return worldService; }

    @Override
    public Registration registerListener(Listener listener) {
        ensureOpen();
        Bukkit.getPluginManager().registerEvents(listener, hostPlugin);
        return track(() -> HandlerList.unregisterAll(listener));
    }

    @Override
    public Registration registerCommand(String name, String description, List<String> aliases,
                                        CommandExecutor executor, TabCompleter completer) {
        ensureOpen();
        CommandMap commandMap = Bukkit.getCommandMap();
        Map<String, Command> known = CommandRegistryAccess.mutableKnownCommands();
        List<String> keys = new ArrayList<>();
        keys.add(name.toLowerCase());
        for (String alias : aliases) keys.add(alias.toLowerCase());
        keys.add(descriptor.id() + ":" + name.toLowerCase());
        Map<String, Command> previous = new LinkedHashMap<>();
        for (String key : keys) if (known.containsKey(key)) previous.put(key, known.get(key));

        AddonCommand command = new AddonCommand(hostPlugin, name, description, aliases, executor, completer);
        commandMap.register(descriptor.id(), command);
        syncCommands();
        return track(() -> {
            known.entrySet().stream().filter(entry -> entry.getValue() == command)
                    .map(Map.Entry::getKey).toList().forEach(known::remove);
            command.unregister(commandMap);
            previous.forEach(known::putIfAbsent);
            syncCommands();
        });
    }

    @Override
    public Registration runTaskLater(Runnable task, long delayTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskLater(hostPlugin, task, delayTicks);
        return track(handle::cancel);
    }

    @Override
    public Registration runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskTimer(hostPlugin, task, delayTicks, periodTicks);
        return track(handle::cancel);
    }

    @Override
    public <T> Registration registerService(Class<T> service, T provider, ServicePriority priority) {
        ensureOpen();
        Bukkit.getServicesManager().register(service, provider, hostPlugin, priority);
        return track(() -> Bukkit.getServicesManager().unregister(service, provider));
    }

    @Override
    public Registration runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        BukkitTask handle = Bukkit.getScheduler().runTaskTimerAsynchronously(hostPlugin, task, delayTicks, periodTicks);
        return track(handle::cancel);
    }

    @Override
    public CompletableFuture<Void> runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(hostPlugin, () -> {
            try { task.run(); future.complete(null); }
            catch (Throwable throwable) { future.completeExceptionally(throwable); }
        });
        return future;
    }

    @Override
    public void showDialog(Player player, Dialog dialog) {
        ensureOpen();
        dialogViewers.add(player.getUniqueId());
        player.showDialog(dialog);
    }

    @Override
    public void closeDialogs() {
        for (UUID uuid : Set.copyOf(dialogViewers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.closeDialog();
        }
        dialogViewers.clear();
    }

    Registration track(Registration registration) {
        synchronized (registrations) {
            ensureOpen();
            registrations.add(registration);
        }
        return registration;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        closeDialogs();
        synchronized (registrations) {
            for (int i = registrations.size() - 1; i >= 0; i--) {
                try { registrations.get(i).close(); }
                catch (Throwable throwable) { logger().warning("[chyarolib] Addon cleanup failed: " + throwable.getMessage()); }
            }
            registrations.clear();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Addon context is closed: " + descriptor.id());
    }

    private static void syncCommands() {
        try { Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer()); }
        catch (ReflectiveOperationException ignored) { }
    }

    private static final class AddonCommand extends Command implements PluginIdentifiableCommand {
        private final Plugin plugin;
        private final CommandExecutor executor;
        private final TabCompleter completer;

        private AddonCommand(Plugin plugin, String name, String description, List<String> aliases,
                             CommandExecutor executor, TabCompleter completer) {
            super(name, description, "/" + name, aliases);
            this.plugin = plugin;
            this.executor = executor;
            this.completer = completer;
        }

        @Override public Plugin getPlugin() { return plugin; }

        @Override
        public boolean execute(CommandSender sender, String commandLabel, String[] args) {
            return executor.onCommand(sender, this, commandLabel, args);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            List<String> result = completer == null ? null : completer.onTabComplete(sender, this, alias, args);
            return result == null ? List.of() : result;
        }
    }
}
