package hr.chyaro.lib.util;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ProxiedCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TargetResolver {

    private TargetResolver() {
    }

    public static List<Player> resolveTargets(CommandSourceStack stack, String[] args, int targetArgIndex) {
        CommandSender sender = stack != null ? stack.getSender() : null;
        List<Player> targets = new ArrayList<>();

        // Priority 1: Target argument specified in args (e.g. @p, @a, username)
        if (targetArgIndex >= 0 && args != null && args.length > targetArgIndex) {
            String arg = args[targetArgIndex];
            if (arg != null && !arg.trim().isEmpty()) {
                List<Player> argTargets = resolveTargetPlayers(sender, arg);
                if (!argTargets.isEmpty()) {
                    return argTargets;
                }
            }
        }

        // Priority 2: Check stack.getExecutor() in Paper Brigadier API (/execute as <target>)
        if (stack != null) {
            try {
                if (stack.getExecutor() instanceof Player executor) {
                    targets.add(executor);
                    return targets;
                }
            } catch (Throwable ignored) {
            }
        }

        // Priority 3: Check ProxiedCommandSender / reflection
        Player proxiedOrEntityTarget = getProxiedOrEntityTarget(sender);
        if (proxiedOrEntityTarget != null) {
            targets.add(proxiedOrEntityTarget);
            return targets;
        }

        // Priority 4: Direct sender if sender is a Player
        if (sender instanceof Player p) {
            targets.add(p);
            return targets;
        }

        // Priority 5: Sender name lookup
        if (sender != null && sender.getName() != null 
                && !sender.getName().equalsIgnoreCase("CONSOLE") 
                && !sender.getName().equalsIgnoreCase("Server") 
                && !sender.getName().startsWith("@")) {
            Player pName = Bukkit.getPlayer(sender.getName());
            if (pName != null && pName.isOnline()) {
                targets.add(pName);
                return targets;
            }
        }

        return targets;
    }

    public static List<Player> resolveTargetPlayers(CommandSender sender, String input) {
        List<Player> players = new ArrayList<>();
        if (input == null || input.trim().isEmpty()) return players;

        if (input.startsWith("@")) {
            try {
                List<Entity> selected = Bukkit.selectEntities(sender, input);
                for (Entity entity : selected) {
                    if (entity instanceof Player p) {
                        players.add(p);
                    }
                }
                if (!players.isEmpty()) {
                    return players;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }

        Player direct = Bukkit.getPlayer(input);
        if (direct != null && direct.isOnline()) {
            players.add(direct);
            return players;
        }

        try {
            List<Entity> selected = Bukkit.selectEntities(sender, input);
            for (Entity entity : selected) {
                if (entity instanceof Player p) {
                    players.add(p);
                }
            }
        } catch (IllegalArgumentException ignored) {
        }

        return players;
    }

    public static Player getProxiedOrEntityTarget(CommandSender sender) {
        if (sender == null) return null;

        if (sender instanceof ProxiedCommandSender pcs) {
            if (pcs.getCallee() instanceof Player callee) return callee;
            if (pcs.getCallee() != null && pcs.getCallee().getName() != null) {
                Player p = Bukkit.getPlayer(pcs.getCallee().getName());
                if (p != null) return p;
            }
        }

        try {
            java.lang.reflect.Method getEntityMethod = sender.getClass().getMethod("getEntity");
            Object entityObj = getEntityMethod.invoke(sender);
            if (entityObj instanceof Player p) return p;
            if (entityObj instanceof Entity entity && entity instanceof Player p) return p;
            if (entityObj != null) {
                java.lang.reflect.Method getNameMethod = entityObj.getClass().getMethod("getName");
                Object nameObj = getNameMethod.invoke(entityObj);
                if (nameObj instanceof String name) {
                    Player p = Bukkit.getPlayer(name);
                    if (p != null) return p;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            java.lang.reflect.Method getCalleeMethod = sender.getClass().getMethod("getCallee");
            Object calleeObj = getCalleeMethod.invoke(sender);
            if (calleeObj instanceof Player p) return p;
            if (calleeObj != null) {
                java.lang.reflect.Method getNameMethod = calleeObj.getClass().getMethod("getName");
                Object nameObj = getNameMethod.invoke(calleeObj);
                if (nameObj instanceof String name) {
                    Player p = Bukkit.getPlayer(name);
                    if (p != null) return p;
                }
            }
        } catch (Throwable ignored) {
        }

        if (sender instanceof Player p) return p;

        return null;
    }

    public static List<String> suggestPlayersAndSelectors(String currentInput) {
        List<String> suggestions = new ArrayList<>();
        List<String> defaults = List.of("@p", "@a", "@r", "@s");
        String lower = currentInput.toLowerCase();

        for (String sel : defaults) {
            if (sel.startsWith(lower)) {
                suggestions.add(sel);
            }
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(lower)) {
                suggestions.add(player.getName());
            }
        }

        return suggestions;
    }
}
