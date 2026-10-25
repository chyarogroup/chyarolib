package hr.chyaro.lib.command;

import hr.chyaro.lib.ChyaroLib;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public class UpdateJarCommand implements BasicCommand {

    private final ChyaroLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public UpdateJarCommand(ChyaroLib plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!stack.getSender().hasPermission(permission())) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>You do not have permission to execute this command.</red>"));
            return;
        }

        String targetVersion = (args.length > 0) ? args[0] : null;
        plugin.getPurpurDownloader().downloadLatestPurpur(stack.getSender(), targetVersion);
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length == 1) {
            String currentVersion = plugin.getPurpurDownloader().getCurrentMinecraftVersion();
            if (currentVersion != null && currentVersion.toLowerCase().startsWith(args[0].toLowerCase())) {
                return List.of(currentVersion);
            }
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "chyarolib.admin";
    }
}
