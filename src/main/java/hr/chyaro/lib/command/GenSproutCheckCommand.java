package hr.chyaro.lib.command;

import hr.chyaro.lib.ChyaroLib;
import hr.chyaro.lib.util.TargetResolver;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;

public class GenSproutCheckCommand implements BasicCommand {

    private final ChyaroLib plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public GenSproutCheckCommand(ChyaroLib plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!stack.getSender().hasPermission(permission())) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>You do not have permission to execute this command.</red>"));
            return;
        }

        List<Player> targets = TargetResolver.resolveTargets(stack, args, 0);
        if (!targets.isEmpty()) {
            for (Player player : targets) {
                plugin.getGenSproutVersionChecker().checkVersion(player, false);
            }
        } else {
            plugin.getGenSproutVersionChecker().checkVersion(stack.getSender(), false);
        }
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length <= 1) {
            String current = (args.length == 1) ? args[0] : "";
            return TargetResolver.suggestPlayersAndSelectors(current);
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "chyarolib.admin";
    }
}
