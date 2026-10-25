package hr.chyaro.lib.world;

import hr.chyaro.lib.api.ManagedWorldService;
import hr.chyaro.lib.api.Registration;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ManagedWorldServiceImpl implements ManagedWorldService, Listener {
    private static final ChunkGenerator VOID_GENERATOR = new ChunkGenerator() { };

    private final Plugin plugin;
    private final Component freezeMessage;
    private final Map<UUID, Set<Claim>> claims = new ConcurrentHashMap<>();
    private final Set<String> frozenOwners = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<UUID>> frozenWorlds = new ConcurrentHashMap<>();

    public ManagedWorldServiceImpl(Plugin plugin, Component freezeMessage) {
        this.plugin = plugin;
        this.freezeMessage = freezeMessage;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public World getOrCreateVoidWorld(String worldName) {
        World existing = Bukkit.getWorld(worldName);
        if (existing != null) return existing;
        World created = new WorldCreator(worldName)
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .generator(VOID_GENERATOR)
                .createWorld();
        if (created == null) throw new IllegalStateException("Could not create managed void world " + worldName);
        created.setSpawnLocation(0, 100, 0);
        return created;
    }

    @Override
    public Registration claim(String hostId, String addonId, World world) {
        Claim claim = new Claim(normalize(hostId), normalize(addonId));
        claims.computeIfAbsent(world.getUID(), ignored -> ConcurrentHashMap.newKeySet()).add(claim);
        return () -> claims.computeIfPresent(world.getUID(), (ignored, current) -> {
            current.remove(claim);
            return current.isEmpty() ? null : current;
        });
    }

    public void freezeHost(String hostId, boolean frozen) {
        setFrozen(normalize(hostId), frozen);
    }

    public void freezeAddon(String hostId, String addonId, boolean frozen) {
        setFrozen(normalize(hostId) + ":" + normalize(addonId), frozen);
    }

    private void setFrozen(String owner, boolean frozen) {
        if (frozen) {
            frozenOwners.add(owner);
            Set<UUID> snapshot = ConcurrentHashMap.newKeySet();
            claims.forEach((worldId, worldClaims) -> {
                if (worldClaims.stream().anyMatch(claim -> claim.hostId.equals(owner) || claim.owner().equals(owner))) {
                    snapshot.add(worldId);
                }
            });
            frozenWorlds.put(owner, snapshot);
        } else {
            frozenOwners.remove(owner);
            frozenWorlds.remove(owner);
        }
    }

    @Override
    public boolean isFrozen(World world) {
        if (frozenWorlds.values().stream().anyMatch(worlds -> worlds.contains(world.getUID()))) return true;
        Set<Claim> worldClaims = claims.get(world.getUID());
        if (worldClaims == null) return false;
        for (Claim claim : worldClaims) {
            if (frozenOwners.contains(claim.hostId) || frozenOwners.contains(claim.owner())) return true;
        }
        return false;
    }

    private boolean deny(World world) {
        return world != null && isFrozen(world);
    }

    private void notify(org.bukkit.entity.Player player) {
        player.sendActionBar(freezeMessage);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { if (deny(event.getBlock().getWorld())) { event.setCancelled(true); notify(event.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) { if (deny(event.getBlock().getWorld())) { event.setCancelled(true); notify(event.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) { if (deny(event.getPlayer().getWorld())) { event.setCancelled(true); notify(event.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) { if (deny(event.getPlayer().getWorld())) { event.setCancelled(true); notify(event.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) { if (deny(event.getPlayer().getWorld())) { event.setCancelled(true); if (event.getPlayer() instanceof org.bukkit.entity.Player p) notify(p); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) { if (deny(event.getWhoClicked().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) { if (deny(event.getWhoClicked().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) { if (deny(event.getFrom().getWorld()) || deny(event.getTo().getWorld())) { event.setCancelled(true); notify(event.getPlayer()); } }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (deny(event.getFrom().getWorld()) && event.hasChangedPosition()) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if ((!frozenOwners.isEmpty() || deny(event.getPlayer().getWorld()))
                && !event.getMessage().toLowerCase().startsWith("/chyarolib")) {
            event.setCancelled(true); notify(event.getPlayer());
        }
    }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPiston(BlockPistonExtendEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPiston(BlockPistonRetractEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) { if (deny(event.getBlock().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) { if (deny(event.getLocation().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) { if (deny(event.getEntity().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) { if (deny(event.getEntity().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) { if (deny(event.getEntity().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) { if (deny(event.getEntity().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) { if (deny(event.getVehicle().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) { if (deny(event.getVehicle().getWorld())) event.setCancelled(true); }
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) { if (deny(event.getVehicle().getWorld())) event.setCancelled(true); }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    private record Claim(String hostId, String addonId) {
        String owner() { return hostId + ":" + addonId; }
    }
}
