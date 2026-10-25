package hr.chyaro.lib.api;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable, implementation-free boundary between GenSprout and managed addons. */
public interface GenSproutBridge {
    String version();
    Localization localization();
    Economy economy();
    Progression progression();
    Generators generators();
    Harvests harvests();
    Teams teams();
    Guides guides();
    default Coops coops() {
        Teams t = teams();
        return new Coops() {
            @Override public boolean inSameTeam(UUID first, UUID second) { return t.inSameTeam(first, second); }
            @Override public Collection<UUID> members(UUID playerId) { return t.members(playerId); }
            @Override public Registration registerTeamProvider(TeamProvider provider) { return t.registerTeamProvider(provider); }
            @Override public boolean inSameCoop(UUID first, UUID second) { return t.inSameTeam(first, second); }
            @Override public Registration registerCoopProvider(CoopProvider provider) { return t.registerTeamProvider(provider); }
        };
    }

    interface Localization {
        Component component(String key, CommandSender viewer, Map<String, String> values);
        Component render(String miniMessage, CommandSender viewer, Map<String, String> values);
        String locale(CommandSender viewer);
    }

    interface Economy {
        boolean available();
        double balance(OfflinePlayer player);
        boolean withdraw(OfflinePlayer player, double amount);
        void deposit(OfflinePlayer player, double amount);
        String format(double amount);
    }

    interface Progression {
        int level(UUID playerId);
        int prestige(UUID playerId);
        int essence(UUID playerId);
    }

    interface Generators {
        int countIn(Location min, Location max);
        Registration registerPlacementPolicy(GeneratorPlacementPolicy policy);
        Registration registerProductionModifier(GeneratorProductionModifier modifier);
    }

    interface Harvests {
        Registration register(HarvestListener listener);
    }

    @FunctionalInterface
    interface HarvestListener {
        void onHarvest(HarvestContext context);
    }

    record HarvestContext(Player player, Material crop, Location location, int harvestedBlocks) { }

    @FunctionalInterface
    interface GeneratorPlacementPolicy {
        PlacementDecision check(Player player, Location location, int currentPersonalCount);
    }

    record PlacementDecision(boolean allowed, Component denialMessage) {
        public static PlacementDecision allow() { return new PlacementDecision(true, Component.empty()); }
        public static PlacementDecision deny(Component message) { return new PlacementDecision(false, message); }
    }

    @FunctionalInterface
    interface GeneratorProductionModifier {
        double multiplier(UUID owner, Location location);
    }

    interface Teams {
        boolean inSameTeam(UUID first, UUID second);
        Collection<UUID> members(UUID playerId);
        Registration registerTeamProvider(TeamProvider provider);
        default Registration registerTeamLifecycleListener(TeamLifecycleListener listener) { return () -> {}; }
    }

    interface TeamLifecycleListener {
        default void onTeamCreated(Player leader, UUID teamId, String teamName) {}
        default void onMemberJoined(UUID teamId, Player player) {}
        default void onMemberLeft(UUID teamId, UUID player) {}
        default void onMemberKicked(UUID teamId, UUID actor, UUID target) {}
        default void onRoleChanged(UUID teamId, UUID target, String role) {}
        default void onTeamTransferred(UUID teamId, UUID oldLeader, UUID newLeader) {}
        default void onTeamDisbanded(UUID teamId, UUID actor) {}
    }

    interface Coops extends Teams {
        boolean inSameCoop(UUID first, UUID second);
        Registration registerCoopProvider(CoopProvider provider);
    }

    @FunctionalInterface
    interface TeamProvider {
        boolean inSameTeam(UUID first, UUID second);
        default Collection<UUID> members(UUID playerId) { return List.of(playerId); }
    }

    @FunctionalInterface
    interface CoopProvider extends TeamProvider {
        boolean inSameCoop(UUID first, UUID second);
        @Override default boolean inSameTeam(UUID first, UUID second) { return inSameCoop(first, second); }
    }

    interface Guides {
        Registration registerCategory(GuideCategory category);
        Collection<GuideCategory> categories();
        default java.util.Optional<GuideCategory> category(String id) {
            return categories().stream().filter(c -> c.id().equalsIgnoreCase(id)).findFirst();
        }
    }

    record GuideCategory(
            String id,
            Component title,
            org.bukkit.inventory.ItemStack icon,
            Component summary,
            List<Component> lines,
            String actionButtonLabel,
            GuideAction action
    ) {
        public GuideCategory(String id, Component title, org.bukkit.inventory.ItemStack icon, Component summary, List<Component> lines) {
            this(id, title, icon, summary, lines, null, null);
        }
    }

    @FunctionalInterface
    interface GuideAction {
        void execute(Player player);
    }
}
