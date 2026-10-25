package hr.chyaro.lib.addon;

import hr.chyaro.lib.api.AddonDescriptor;
import hr.chyaro.lib.api.DatabaseService;
import hr.chyaro.lib.api.GenSproutAddon;
import hr.chyaro.lib.api.ManagedHostRegistration;
import hr.chyaro.lib.api.ManagedWorldService;
import hr.chyaro.lib.reload.JarCache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public final class AddonManager implements AutoCloseable {
    private final String chyarolibVersion;
    private final DatabaseService databases;
    private final ManagedWorldService worlds;
    private final JarCache jarCache;
    private final ClassLoader apiClassLoader;
    private final Logger logger;
    private final Map<String, LinkedHashMap<String, LoadedAddon>> loaded = new HashMap<>();

    public AddonManager(String chyarolibVersion, DatabaseService databases, ManagedWorldService worlds,
                        JarCache jarCache, ClassLoader apiClassLoader, Logger logger) {
        this.chyarolibVersion = chyarolibVersion;
        this.databases = databases;
        this.worlds = worlds;
        this.jarCache = jarCache;
        this.apiClassLoader = apiClassLoader;
        this.logger = logger;
    }

    public synchronized void loadAll(ManagedHostRegistration host) throws Exception {
        Files.createDirectories(host.addonsDirectory());
        Map<String, Candidate> candidates = discover(host);
        List<Candidate> ordered = sort(candidates);
        LinkedHashMap<String, LoadedAddon> hostAddons = loaded.computeIfAbsent(host.id(), ignored -> new LinkedHashMap<>());
        for (Candidate candidate : ordered) {
            if (hostAddons.containsKey(candidate.descriptor.id())) continue;
            boolean missingDependency = candidate.descriptor.dependencies().stream().anyMatch(dep -> !hostAddons.containsKey(dep));
            if (missingDependency) {
                logger.warning("[chyarolib] Skipping addon " + candidate.descriptor.id() + " because a dependency did not load");
                continue;
            }
            try {
                LoadedAddon addon = load(host, candidate);
                hostAddons.put(candidate.descriptor.id(), addon);
                jarCache.snapshot(cacheId(host.id(), candidate.descriptor.id()), candidate.path);
                logger.info("[chyarolib] Enabled addon " + candidate.descriptor.name() + " v" + candidate.descriptor.version());
            } catch (Exception exception) {
                logger.severe("[chyarolib] Could not enable addon " + candidate.descriptor.id() + ": " + exception.getMessage());
            }
        }
    }

    public synchronized void unloadAll(String hostId) {
        LinkedHashMap<String, LoadedAddon> hostAddons = loaded.remove(hostId);
        if (hostAddons == null) return;
        List<LoadedAddon> reverse = new ArrayList<>(hostAddons.values());
        java.util.Collections.reverse(reverse);
        reverse.forEach(this::disable);
    }

    public synchronized void reload(ManagedHostRegistration host, String addonId) throws Exception {
        if (addonId == null || addonId.isBlank()) {
            unloadAll(host.id());
            Map<String, Candidate> candidates = discover(host);
            LinkedHashMap<String, LoadedAddon> hostAddons = loaded.computeIfAbsent(host.id(), ignored -> new LinkedHashMap<>());
            for (Candidate candidate : sort(candidates)) {
                LoadedAddon addon = load(host, candidate);
                hostAddons.put(candidate.descriptor.id(), addon);
                jarCache.snapshot(cacheId(host.id(), candidate.descriptor.id()), candidate.path);
            }
            return;
        }
        String id = addonId.toLowerCase();
        LinkedHashMap<String, LoadedAddon> hostAddons = loaded.computeIfAbsent(host.id(), ignored -> new LinkedHashMap<>());
        LoadedAddon current = hostAddons.remove(id);
        Path path = current == null ? findPath(host, id) : current.path;
        if (current != null) disable(current);
        AddonDescriptor descriptor = AddonDescriptorReader.read(path);
        LoadedAddon replacement = load(host, new Candidate(path, descriptor));
        hostAddons.put(id, replacement);
        jarCache.snapshot(cacheId(host.id(), id), path);
    }

    public synchronized Path preflight(ManagedHostRegistration host, String addonId) throws Exception {
        String id = addonId.toLowerCase();
        Path path = addonPath(host.id(), id);
        if (path == null) path = findPath(host, id);
        AddonDescriptor descriptor = AddonDescriptorReader.read(path);
        validateCompatibility(host, descriptor);
        for (String dependency : descriptor.dependencies()) {
            if (!addonIds(host.id()).contains(dependency)) {
                throw new IllegalArgumentException("Required addon is not enabled: " + dependency);
            }
        }
        return path;
    }

    public synchronized List<String> addonIds(String hostId) {
        LinkedHashMap<String, LoadedAddon> hostAddons = loaded.get(hostId);
        return hostAddons == null ? List.of() : List.copyOf(hostAddons.keySet());
    }

    public Path addonPath(String hostId, String addonId) {
        LinkedHashMap<String, LoadedAddon> hostAddons = loaded.get(hostId);
        LoadedAddon addon = hostAddons == null ? null : hostAddons.get(addonId);
        return addon == null ? null : addon.path;
    }

    private LoadedAddon load(ManagedHostRegistration host, Candidate candidate) throws Exception {
        validateCompatibility(host, candidate.descriptor);
        AddonClassLoader loader = new AddonClassLoader(candidate.path.toUri().toURL(), apiClassLoader);
        AddonContextImpl context = null;
        GenSproutAddon instance = null;
        try {
            Class<?> rawClass = Class.forName(candidate.descriptor.mainClass(), true, loader);
            if (!GenSproutAddon.class.isAssignableFrom(rawClass)) {
                throw new IllegalArgumentException("Main class does not implement GenSproutAddon");
            }
            instance = (GenSproutAddon) rawClass.getDeclaredConstructor().newInstance();
            Path dataPath = host.addonsDirectory().resolve(candidate.descriptor.id());
            context = new AddonContextImpl(candidate.descriptor, host.plugin(), host.bridge(), dataPath, databases, worlds);
            instance.onLoad(context);
            instance.onEnable();
            return new LoadedAddon(candidate.path, candidate.descriptor, loader, context, instance);
        } catch (Exception exception) {
            try { if (instance != null) instance.onDisable(); }
            catch (Throwable ignored) { }
            if (context != null) context.close();
            try { loader.close(); } catch (Exception ignored) { }
            throw exception;
        }
    }

    private void disable(LoadedAddon addon) {
        try { addon.instance.onDisable(); }
        catch (Exception exception) { logger.warning("[chyarolib] Addon disable failed for " + addon.descriptor.id() + ": " + exception.getMessage()); }
        addon.context.close();
        try { addon.loader.close(); }
        catch (Exception exception) { logger.warning("[chyarolib] Could not close addon JAR " + addon.descriptor.id() + ": " + exception.getMessage()); }
    }

    private Map<String, Candidate> discover(ManagedHostRegistration host) throws Exception {
        Map<String, Candidate> candidates = new HashMap<>();
        try (var stream = Files.list(host.addonsDirectory())) {
            for (Path path : stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar")).toList()) {
                AddonDescriptor descriptor = AddonDescriptorReader.read(path);
                if (!descriptor.host().equals(host.id())) continue;
                Candidate previous = candidates.putIfAbsent(descriptor.id(), new Candidate(path, descriptor));
                if (previous != null) throw new IllegalArgumentException("Duplicate addon id " + descriptor.id());
            }
        }
        return candidates;
    }

    private List<Candidate> sort(Map<String, Candidate> candidates) {
        Map<String, List<String>> dependencies = new HashMap<>();
        candidates.forEach((id, candidate) -> dependencies.put(id, candidate.descriptor.dependencies()));
        return AddonDependencyGraph.sort(dependencies).stream().map(candidates::get).toList();
    }

    private void validateCompatibility(ManagedHostRegistration host, AddonDescriptor descriptor) {
        if (descriptor.apiVersion() != 1) throw new IllegalArgumentException("Unsupported addon API " + descriptor.apiVersion());
        if (!descriptor.host().equals(host.id())) throw new IllegalArgumentException("Addon targets host " + descriptor.host());
        if (!SemanticVersion.satisfies(host.bridge().version(), descriptor.hostVersion())) {
            throw new IllegalArgumentException("Host version " + host.bridge().version() + " does not satisfy " + descriptor.hostVersion());
        }
        if (!SemanticVersion.satisfies(chyarolibVersion, descriptor.chyarolibVersion())) {
            throw new IllegalArgumentException("chyarolib version " + chyarolibVersion + " does not satisfy " + descriptor.chyarolibVersion());
        }
    }

    private Path findPath(ManagedHostRegistration host, String addonId) throws Exception {
        Candidate candidate = discover(host).get(addonId);
        if (candidate == null) throw new IllegalArgumentException("Unknown addon " + addonId);
        return candidate.path;
    }

    private static String cacheId(String hostId, String addonId) { return hostId + "-addon-" + addonId; }

    @Override
    public synchronized void close() {
        for (String hostId : List.copyOf(loaded.keySet())) unloadAll(hostId);
    }

    private record Candidate(Path path, AddonDescriptor descriptor) { }
    private record LoadedAddon(Path path, AddonDescriptor descriptor, AddonClassLoader loader,
                               AddonContextImpl context, GenSproutAddon instance) { }
}
