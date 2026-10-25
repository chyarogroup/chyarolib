package hr.chyaro.lib.command;

import hr.chyaro.lib.ChyaroLib;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class RestartCommand implements BasicCommand {

    private final ChyaroLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final AtomicBoolean restarting = new AtomicBoolean(false);

    public RestartCommand(ChyaroLib plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!stack.getSender().hasPermission(permission())) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>You do not have permission to execute this command.</red>"));
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("now")) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>Executing immediate server restart...</red>"));
            performRestart();
            return;
        }

        if (!restarting.compareAndSet(false, true)) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>Server restart is already in progress.</red>"));
            return;
        }

        stack.getSender().sendMessage(miniMessage.deserialize("<green>Initiating 30-second server restart sequence...</green>"));
        startRestartSequence();
    }

    private void startRestartSequence() {
        new BukkitRunnable() {
            int secondsRemaining = 30;

            @Override
            public void run() {
                if (secondsRemaining > 10) {
                    String unit = (secondsRemaining == 1) ? "second" : "seconds";
                    Component actionbarMessage = miniMessage.deserialize(
                        "<green>Server restarting in " + secondsRemaining + " " + unit + " for update!</green>"
                    );
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.sendActionBar(actionbarMessage);
                    }
                    if (secondsRemaining == 30 || secondsRemaining == 20 || secondsRemaining == 15) {
                        plugin.getLogger().info("Server restarting in " + secondsRemaining + " seconds for update!");
                    }
                } else if (secondsRemaining >= 1) {
                    String color = (secondsRemaining <= 3) ? "red" : ((secondsRemaining <= 5) ? "gold" : "yellow");
                    Component titleComp = miniMessage.deserialize("<" + color + ">" + secondsRemaining + "</" + color + ">");
                    Component subtitleComp = miniMessage.deserialize("<aqua>Server updating now</aqua>");
                    Title.Times times = Title.Times.times(Ticks.duration(0), Ticks.duration(25), Ticks.duration(5));
                    Title title = Title.title(titleComp, subtitleComp, times);

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.showTitle(title);
                    }
                    plugin.getLogger().info("Restart countdown: " + secondsRemaining + "...");
                } else {
                    cancel();
                    Component titleComp = miniMessage.deserialize("<red>RESTARTING</red>");
                    Component subtitleComp = miniMessage.deserialize("<aqua>Server update in progress</aqua>");
                    Title.Times times = Title.Times.times(Ticks.duration(0), Ticks.duration(40), Ticks.duration(10));
                    Title title = Title.title(titleComp, subtitleComp, times);

                    for (Player player : Bukkit.getOnlinePlayers()) {
                        player.showTitle(title);
                    }
                    plugin.getLogger().info("Executing server restart now...");
                    performRestart();
                }

                secondsRemaining--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    @SuppressWarnings("deprecation")
    private void performRestart() {
        try {
            Bukkit.spigot().restart();
        } catch (Throwable t) {
            plugin.getLogger().warning("Bukkit.spigot().restart() failed, falling back to Bukkit.shutdown(): " + t.getMessage());
            Bukkit.shutdown();
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length == 1 && "now".startsWith(args[0].toLowerCase())) {
            return List.of("now");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "chyarolib.admin";
    }
}
