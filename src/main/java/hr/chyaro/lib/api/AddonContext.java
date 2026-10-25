package hr.chyaro.lib.api;

import io.papermc.paper.dialog.Dialog;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public interface AddonContext {
    AddonDescriptor descriptor();
    Plugin hostPlugin();
    GenSproutBridge bridge();
    Path dataDirectory();
    Logger logger();
    DatabaseService databases();
    ManagedWorldService worlds();

    Registration registerListener(Listener listener);
    Registration registerCommand(String name, String description, List<String> aliases,
                                 CommandExecutor executor, TabCompleter completer);
    <T> Registration registerService(Class<T> service, T provider, ServicePriority priority);
    Registration runTaskLater(Runnable task, long delayTicks);
    Registration runTaskTimer(Runnable task, long delayTicks, long periodTicks);
    Registration runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks);
    CompletableFuture<Void> runSync(Runnable task);
    void showDialog(Player player, Dialog dialog);
    void closeDialogs();
}
