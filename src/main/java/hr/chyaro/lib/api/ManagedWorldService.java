package hr.chyaro.lib.api;

import org.bukkit.World;

public interface ManagedWorldService {
    World getOrCreateVoidWorld(String worldName);
    Registration claim(String hostId, String addonId, World world);
    boolean isFrozen(World world);
}
