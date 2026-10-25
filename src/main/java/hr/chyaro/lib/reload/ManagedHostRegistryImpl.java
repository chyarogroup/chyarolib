package hr.chyaro.lib.reload;

import hr.chyaro.lib.addon.AddonManager;
import hr.chyaro.lib.api.ManagedHostRegistration;
import hr.chyaro.lib.api.ManagedHostRegistry;
import hr.chyaro.lib.api.Registration;
import hr.chyaro.lib.world.ManagedWorldServiceImpl;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public final class ManagedHostRegistryImpl implements ManagedHostRegistry, AutoCloseable {
    private final Map<String, ManagedHostRegistration> hosts = new ConcurrentHashMap<>();
    private final AddonManager addons;
    private final ManagedWorldServiceImpl worlds;
    private final JarCache cache;
    private final Logger logger;
    private final AtomicBoolean reloading = new AtomicBoolean();
    private volatile PaperPluginReloader paperReloader;

    public ManagedHostRegistryImpl(AddonManager addons, ManagedWorldServiceImpl worlds, JarCache cache, Logger logger) {
        this.addons = addons;
        this.worlds = worlds;
        this.cache = cache;
        this.logger = logger;
    }

    @Override
    public Registration register(ManagedHostRegistration registration) {
        String id = normalize(registration.id());
        if (id.equals("chyarolib")) throw new IllegalArgumentException("chyarolib cannot register itself as a reload target");
        ManagedHostRegistration existing = hosts.get(id);
        if (existing != null && existing.plugin() != registration.plugin() && existing.plugin().isEnabled()) {
            throw new IllegalStateException("Managed host is already registered: " + id);
        }
        hosts.put(id, registration);
        try {
            if (!reloading.get()) cache.snapshot(id, registration.deployedJar());
            addons.loadAll(registration);
        } catch (Exception exception) {
            logger.severe("[chyarolib] Managed host registration completed with addon errors for " + id + ": " + exception.getMessage());
        }
        logger.info("[chyarolib] Registered managed host " + id + " v" + registration.bridge().version());
        return () -> {
            if (hosts.remove(id, registration)) addons.unloadAll(id);
        };
    }

    @Override public Optional<ManagedHostRegistration> find(String id) { return Optional.ofNullable(hosts.get(normalize(id))); }
    @Override public Collection<String> hostIds() { return List.copyOf(hosts.keySet()); }
    @Override public Collection<String> addonIds(String hostId) { return addons.addonIds(normalize(hostId)); }

    @Override
    public ReloadResult reloadHost(String hostId) {
        String id = normalize(hostId);
        if (id.equals("chyarolib")) return new ReloadResult(false, false, "chyarolib cannot reload itself");
        ManagedHostRegistration current = hosts.get(id);
        if (current == null) return new ReloadResult(false, false, "Unknown managed host: " + id);
        if (!reloading.compareAndSet(false, true)) return new ReloadResult(false, false, "Another managed reload is already running");

        List<ManagedHostRegistration> forward = List.of(current);
        Set<String> disrupted = new LinkedHashSet<>();
        Map<String, Plugin> replacements = new LinkedHashMap<>();
        boolean frozen = false;
        boolean keepFrozen = false;
        try {
            PaperPluginReloader reloader = reloader();
            forward = managedCascade(current);
            for (ManagedHostRegistration host : forward) reloader.preflight(host.plugin(), host.deployedJar());
            String dependent = unmanagedDependent(forward);
            if (dependent != null) return new ReloadResult(false, false, "Unmanaged plugin depends on target: " + dependent);

            for (ManagedHostRegistration host : forward) worlds.freezeHost(host.id(), true);
            frozen = true;
            List<ManagedHostRegistration> reverse = reversed(forward);
            for (ManagedHostRegistration host : reverse) {
                host.closeDialogs();
                host.prepareReload();
                addons.unloadAll(host.id());
            }
            for (ManagedHostRegistration host : reverse) {
                disrupted.add(host.id());
                reloader.unload(host.plugin());
            }
            for (ManagedHostRegistration host : forward) {
                Plugin replacement = reloader.load(host.deployedJar());
                replacements.put(host.id(), replacement);
                requireRegisteredReplacement(host.id(), replacement);
            }
            for (ManagedHostRegistration host : forward) cache.snapshot(host.id(), host.deployedJar());
            ManagedHostRegistration registered = hosts.get(id);
            return new ReloadResult(true, false, "Reloaded " + forward.size() + " managed host(s); "
                    + id + " is v" + registered.bridge().version());
        } catch (Exception failure) {
            logger.severe("[chyarolib] Reload failed for " + id + ": " + failure.getMessage());
            if (!frozen) return new ReloadResult(false, false, "Reload preflight failed for " + id + ": " + failure.getMessage());
            try {
                List<ManagedHostRegistration> reverse = reversed(forward);
                for (ManagedHostRegistration host : reverse) {
                    Plugin replacement = replacements.get(host.id());
                    if (replacement != null) reloader().removeFailed(replacement);
                }
                for (ManagedHostRegistration host : reverse) {
                    if (!disrupted.contains(host.id())) continue;
                    reloader().removeFailed(host.plugin());
                    ManagedHostRegistration active = hosts.get(host.id());
                    if (active != null && active.plugin() != host.plugin()) reloader().removeFailed(active.plugin());
                }
                for (ManagedHostRegistration host : forward) {
                    if (disrupted.contains(host.id())) cache.restore(host.id(), host.deployedJar());
                }
                for (ManagedHostRegistration host : forward) {
                    if (!disrupted.contains(host.id())) continue;
                    Plugin restored = reloader().loadAfterFailure(host.deployedJar());
                    requireRegisteredReplacement(host.id(), restored);
                }
                for (ManagedHostRegistration host : forward) {
                    ManagedHostRegistration active = hosts.get(host.id());
                    if (active != null) addons.loadAll(active);
                }
                ManagedHostRegistration registered = hosts.get(id);
                return new ReloadResult(false, true, "Reload failed; restored last-known-good " + id
                        + " v" + registered.bridge().version());
            } catch (Exception rollbackFailure) {
                logger.severe("[chyarolib] Rollback failed for " + id + ": " + rollbackFailure.getMessage());
                keepFrozen = true;
                return new ReloadResult(false, false, "Reload and rollback failed for " + id + "; restart required");
            }
        } finally {
            if (frozen && !keepFrozen) for (ManagedHostRegistration host : forward) worlds.freezeHost(host.id(), false);
            reloading.set(false);
        }
    }

    @Override
    public ReloadResult reloadAddons(String hostId, String addonId) {
        String id = normalize(hostId);
        ManagedHostRegistration host = hosts.get(id);
        if (host == null) return new ReloadResult(false, false, "Unknown managed host: " + id);
        if (!reloading.compareAndSet(false, true)) return new ReloadResult(false, false, "Another managed reload is already running");
        String normalizedAddon = addonId == null || addonId.isBlank() ? null : normalize(addonId);
        boolean frozen = false;
        boolean keepFrozen = false;
        Map<String, Path> deployedPaths = new LinkedHashMap<>();
        try {
            if (normalizedAddon == null) {
                for (String loadedId : addons.addonIds(id)) {
                    deployedPaths.put(loadedId, addons.preflight(host, loadedId));
                }
                worlds.freezeHost(id, true);
            } else {
                Path deployed = addons.preflight(host, normalizedAddon);
                deployedPaths.put(normalizedAddon, deployed);
                worlds.freezeAddon(id, normalizedAddon, true);
            }
            frozen = true;
            addons.reload(host, normalizedAddon);
            return new ReloadResult(true, false, normalizedAddon == null
                    ? "Reloaded all addons for " + id
                    : "Reloaded addon " + normalizedAddon + " for " + id);
        } catch (Exception failure) {
            logger.severe("[chyarolib] Addon reload failed for " + id + ": " + failure.getMessage());
            if (!frozen) return new ReloadResult(false, false, "Addon preflight failed for " + id + ": " + failure.getMessage());
            if (normalizedAddon == null) {
                try {
                    for (Map.Entry<String, Path> entry : deployedPaths.entrySet()) {
                        cache.restore(cacheId(id, entry.getKey()), entry.getValue());
                    }
                    addons.reload(host, null);
                    return new ReloadResult(false, true, "Addon reload failed; restored all addons for " + id);
                } catch (Exception rollbackFailure) {
                    logger.severe("[chyarolib] Addon rollback failed for " + id + ": " + rollbackFailure.getMessage());
                    keepFrozen = true;
                    return new ReloadResult(false, false, "Addon reload and rollback failed for " + id + "; restart required");
                }
            }
            try {
                Path deployed = deployedPaths.get(normalizedAddon);
                cache.restore(cacheId(id, normalizedAddon), deployed);
                addons.reload(host, normalizedAddon);
                return new ReloadResult(false, true, "Addon reload failed; restored " + normalizedAddon);
            } catch (Exception rollbackFailure) {
                logger.severe("[chyarolib] Addon rollback failed for " + normalizedAddon + ": " + rollbackFailure.getMessage());
                keepFrozen = true;
                return new ReloadResult(false, false, "Addon reload and rollback failed for " + normalizedAddon + "; restart required");
            }
        } finally {
            if (frozen && !keepFrozen) {
                if (normalizedAddon == null) worlds.freezeHost(id, false); else worlds.freezeAddon(id, normalizedAddon, false);
            }
            reloading.set(false);
        }
    }

    private PaperPluginReloader reloader() throws Exception {
        PaperPluginReloader current = paperReloader;
        if (current != null) return current;
        synchronized (this) {
            if (paperReloader == null) paperReloader = new PaperPluginReloader();
            return paperReloader;
        }
    }

    private List<ManagedHostRegistration> managedCascade(ManagedHostRegistration target) {
        Map<String, ManagedHostRegistration> affected = new LinkedHashMap<>();
        affected.put(normalize(target.id()), target);
        boolean changed;
        do {
            changed = false;
            Set<String> pluginNames = affected.values().stream().map(host -> host.plugin().getName().toLowerCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            for (ManagedHostRegistration candidate : List.copyOf(hosts.values())) {
                String candidateId = normalize(candidate.id());
                if (affected.containsKey(candidateId)) continue;
                if (candidate.plugin().getPluginMeta().getPluginDependencies().stream()
                        .map(name -> name.toLowerCase(Locale.ROOT)).anyMatch(pluginNames::contains)) {
                    affected.put(candidateId, candidate);
                    changed = true;
                }
            }
        } while (changed);

        List<ManagedHostRegistration> ordered = new java.util.ArrayList<>();
        Set<String> pending = new LinkedHashSet<>(affected.keySet());
        while (!pending.isEmpty()) {
            boolean progressed = false;
            for (String candidateId : List.copyOf(pending)) {
                ManagedHostRegistration candidate = affected.get(candidateId);
                Set<String> internalDependencies = candidate.plugin().getPluginMeta().getPluginDependencies().stream()
                        .map(name -> findAffectedByPluginName(affected, name)).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                Set<String> orderedIds = ordered.stream().map(host -> normalize(host.id()))
                        .collect(java.util.stream.Collectors.toSet());
                if (!orderedIds.containsAll(internalDependencies)) continue;
                ordered.add(candidate);
                pending.remove(candidateId);
                progressed = true;
            }
            if (!progressed) throw new IllegalStateException("Managed host dependency cycle involving " + pending);
        }
        return List.copyOf(ordered);
    }

    private static String findAffectedByPluginName(Map<String, ManagedHostRegistration> affected, String pluginName) {
        return affected.entrySet().stream().filter(entry -> entry.getValue().plugin().getName().equalsIgnoreCase(pluginName))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    private String unmanagedDependent(List<ManagedHostRegistration> affected) {
        Set<Plugin> managedPlugins = affected.stream().map(ManagedHostRegistration::plugin)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> targetNames = managedPlugins.stream().map(plugin -> plugin.getName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (managedPlugins.contains(plugin) || !plugin.isEnabled()) continue;
            boolean depends = plugin.getPluginMeta().getPluginDependencies().stream()
                    .map(name -> name.toLowerCase(Locale.ROOT)).anyMatch(targetNames::contains);
            if (depends) return plugin.getName();
        }
        return null;
    }

    private void requireRegisteredReplacement(String id, Plugin replacement) {
        ManagedHostRegistration registered = hosts.get(id);
        if (registered == null || registered.plugin() != replacement) {
            reloaderRemoveQuietly(replacement);
            throw new IllegalStateException("Replacement did not register its chyarolib bridge: " + id);
        }
    }

    private void reloaderRemoveQuietly(Plugin plugin) {
        try { reloader().removeFailed(plugin); } catch (Exception ignored) { }
    }

    private static List<ManagedHostRegistration> reversed(List<ManagedHostRegistration> input) {
        List<ManagedHostRegistration> result = new java.util.ArrayList<>(input);
        java.util.Collections.reverse(result);
        return result;
    }

    private static String cacheId(String hostId, String addonId) { return hostId + "-addon-" + addonId; }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z0-9_-]+")) throw new IllegalArgumentException("Invalid managed id: " + value);
        return result;
    }

    @Override
    public void close() {
        addons.close();
        hosts.clear();
    }
}
