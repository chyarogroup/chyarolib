package hr.chyaro.lib.command;

import hr.chyaro.lib.api.ManagedHostRegistry;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class ChyaroLibCommand implements BasicCommand {
    private final ManagedHostRegistry registry;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ChyaroLibCommand(ManagedHostRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void execute(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (!stack.getSender().hasPermission(permission())) {
            stack.getSender().sendMessage(miniMessage.deserialize("<red>You do not have permission to use chyarolib reloads.</red>"));
            return;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("reload")) {
            stack.getSender().sendMessage(miniMessage.deserialize(
                    "<gold>[chyarolib]</gold> <yellow>Usage: /chyarolib reload <host|hostaddon> [addon-id]</yellow>"));
            return;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        ManagedHostRegistry.ReloadResult result;
        if (registry.find(target).isPresent()) {
            result = registry.reloadHost(target);
        } else if (target.endsWith("addon") && target.length() > "addon".length()) {
            String hostId = target.substring(0, target.length() - "addon".length());
            String addonId = args.length >= 3 ? args[2] : null;
            result = registry.reloadAddons(hostId, addonId);
        } else {
            result = new ManagedHostRegistry.ReloadResult(false, false, "Unknown managed target: " + target);
        }

        String color = result.success() ? "green" : result.rolledBack() ? "gold" : "red";
        stack.getSender().sendMessage(miniMessage.deserialize(
                "<gold>[chyarolib]</gold> <" + color + ">" + escape(result.message()) + "</" + color + ">"));
    }

    @Override
    public @NotNull Collection<String> suggest(@NotNull CommandSourceStack stack, @NotNull String[] args) {
        if (args.length <= 1) return partial(args.length == 0 ? "" : args[0], List.of("reload"));
        if (!args[0].equalsIgnoreCase("reload")) return List.of();
        if (args.length == 2) {
            List<String> targets = new ArrayList<>();
            for (String host : registry.hostIds()) {
                targets.add(host);
                targets.add(host + "addon");
            }
            return partial(args[1], targets);
        }
        if (args.length == 3 && args[1].toLowerCase(Locale.ROOT).endsWith("addon")) {
            String target = args[1].toLowerCase(Locale.ROOT);
            String host = target.substring(0, target.length() - "addon".length());
            return partial(args[2], registry.addonIds(host));
        }
        return List.of();
    }

    @Override public String permission() { return "chyarolib.reload"; }

    private static List<String> partial(String input, Collection<String> choices) {
        String lower = input.toLowerCase(Locale.ROOT);
        return choices.stream().filter(choice -> choice.toLowerCase(Locale.ROOT).startsWith(lower)).sorted().toList();
    }

    private static String escape(String value) {
        return value.replace("<", "\\<");
    }
}
