package hr.chyaro.lib.reload;

import hr.chyaro.lib.command.CommandRegistryAccess;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.InputStreamReader;
import java.io.Closeable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Capability-checked adapter for the modern Paper/Purpur plugin registry. */
public final class PaperPluginReloader {
    private static final Set<String> SUPPORTED_MINECRAFT = Set.of(
            "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
            "26.1", "26.1.1", "26.1.2", "26.2"
    );

    private final Object instanceManager;
    private final Field pluginsField;
    private final Field lookupNamesField;
    private final Field dependencyTreeField;
    private final Method registryLoadMethod;
    private final Object paperPluginFileType;
    private final Method paperFileTypeRegisterMethod;
    private final Class<?> entrypointHandlerClass;
    private final Object pluginEntrypoint;
    private final Object bootstrapEntrypoint;
    private final Class<?> configuredClassLoaderClass;
    private final Object classLoaderStorage;
    private final Method unregisterClassLoaderMethod;

    public PaperPluginReloader() throws Exception {
        String minecraft = Bukkit.getMinecraftVersion();
        if (!SUPPORTED_MINECRAFT.contains(minecraft)) {
            throw new UnsupportedOperationException("Full JAR reload is not verified for Minecraft " + minecraft);
        }
        Object paperManager = resolvePaperManager();
        Field instanceField = findField(paperManager.getClass(), "instanceManager");
        this.instanceManager = get(instanceField, paperManager);
        this.pluginsField = findField(instanceManager.getClass(), "plugins");
        this.lookupNamesField = findField(instanceManager.getClass(), "lookupNames");
        this.dependencyTreeField = findField(instanceManager.getClass(), "dependencyTree");
        this.registryLoadMethod = instanceManager.getClass().getDeclaredMethod("loadPlugin", Plugin.class);
        this.registryLoadMethod.setAccessible(true);

        Class<?> fileTypeClass = Class.forName("io.papermc.paper.plugin.provider.type.PluginFileType");
        Field paperField = fileTypeClass.getField("PAPER");
        this.paperPluginFileType = paperField.get(null);
        this.entrypointHandlerClass = Class.forName("io.papermc.paper.plugin.entrypoint.EntrypointHandler");
        this.paperFileTypeRegisterMethod = fileTypeClass.getMethod(
                "register", entrypointHandlerClass, JarFile.class, Path.class);
        this.paperFileTypeRegisterMethod.setAccessible(true);
        Class<?> entrypointClass = Class.forName("io.papermc.paper.plugin.entrypoint.Entrypoint");
        this.pluginEntrypoint = entrypointClass.getField("PLUGIN").get(null);
        this.bootstrapEntrypoint = entrypointClass.getField("BOOTSTRAPPER").get(null);

        this.configuredClassLoaderClass = Class.forName(
                "io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader");
        Class<?> storageClass = Class.forName(
                "io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage");
        this.classLoaderStorage = storageClass.getMethod("instance").invoke(null);
        this.unregisterClassLoaderMethod = storageClass.getMethod(
                "unregisterClassloader", configuredClassLoaderClass);
        if (!(get(pluginsField, instanceManager) instanceof List)
                || !(get(lookupNamesField, instanceManager) instanceof Map)) {
            throw new UnsupportedOperationException("Paper plugin registry layout is not supported");
        }
    }

    public void preflight(Plugin current, Path deployedJar) throws Exception {
        JarCache.validateJar(deployedJar);
        String declaredName = readPluginName(deployedJar);
        if (!current.getName().equalsIgnoreCase(declaredName)) {
            throw new IllegalArgumentException("Replacement JAR declares plugin '" + declaredName
                    + "' but target is '" + current.getName() + "'");
        }
        YamlConfiguration descriptor = readPluginDescriptor(deployedJar);
        if (descriptor.contains("bootstrapper")) {
            throw new UnsupportedOperationException("Runtime reload does not support Paper bootstrap entrypoints");
        }
        var serverDependencies = descriptor.getConfigurationSection("dependencies.server");
        if (serverDependencies != null) {
            for (String dependency : serverDependencies.getKeys(false)) {
                boolean required = serverDependencies.getBoolean(dependency + ".required", true);
                Plugin loaded = Bukkit.getPluginManager().getPlugin(dependency);
                if (required && (loaded == null || !loaded.isEnabled())) {
                    throw new IllegalStateException("Required replacement dependency is not enabled: " + dependency);
                }
            }
        }
        Object tree = get(dependencyTreeField, instanceManager);
        findCompatibleMethod(tree.getClass(), "remove", current.getPluginMeta().getClass().getInterfaces());
        findCompatibleMethod(tree.getClass(), "add", current.getPluginMeta().getClass().getInterfaces());
        CommandRegistryAccess.mutableKnownCommands();
    }

    public Plugin reload(Plugin oldPlugin, Path deployedJar) throws Exception {
        unload(oldPlugin);
        return load(deployedJar);
    }

    public void unload(Plugin plugin) throws Exception {
        Bukkit.getPluginManager().disablePlugin(plugin);
        removeCommands(plugin);
        removeFromRegistry(plugin);
    }

    public Plugin load(Path deployedJar) throws Exception {
        Plugin replacement = runtimeLoadPaperPlugin(deployedJar);
        if (replacement == null) throw new IllegalStateException("Paper did not load a plugin from " + deployedJar);
        Bukkit.getPluginManager().enablePlugin(replacement);
        if (!replacement.isEnabled()) {
            removeFailed(replacement);
            throw new IllegalStateException("Replacement plugin failed during enable: " + replacement.getName());
        }
        syncCommands();
        return replacement;
    }

    public Plugin loadAfterFailure(Path deployedJar) throws Exception {
        return load(deployedJar);
    }

    public void removeFailed(Plugin plugin) {
        try { Bukkit.getPluginManager().disablePlugin(plugin); } catch (Throwable ignored) { }
        try { removeCommands(plugin); } catch (Throwable ignored) { }
        try { removeFromRegistry(plugin); } catch (Throwable ignored) { }
    }

    @SuppressWarnings("unchecked")
    private void removeFromRegistry(Plugin plugin) throws Exception {
        List<Plugin> plugins = (List<Plugin>) get(pluginsField, instanceManager);
        Map<String, Plugin> names = (Map<String, Plugin>) get(lookupNamesField, instanceManager);
        plugins.remove(plugin);
        names.entrySet().stream().filter(entry -> entry.getValue() == plugin)
                .map(Map.Entry::getKey).toList().forEach(names::remove);

        Exception failure = null;
        try {
            Object tree = get(dependencyTreeField, instanceManager);
            Method remove = findCompatibleMethod(tree.getClass(), "remove",
                    new Class<?>[]{plugin.getPluginMeta().getClass()});
            remove.invoke(tree, plugin.getPluginMeta());
        } catch (Exception exception) {
            failure = exception;
        }
        try {
            releaseClassLoader(plugin.getClass().getClassLoader());
        } catch (Exception exception) {
            if (failure == null) failure = exception; else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    private void removeCommands(Plugin plugin) {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Map<String, Command> known = CommandRegistryAccess.mutableKnownCommands();
        known.entrySet().stream().filter(entry -> {
            Command command = entry.getValue();
            if (command instanceof PluginIdentifiableCommand identifiable && identifiable.getPlugin() == plugin) return true;
            return command.getClass().getClassLoader() == loader;
        }).map(Map.Entry::getKey).toList().forEach(known::remove);
    }

    /**
     * Paper's public runtime loader intentionally rejects paper-plugin.yml providers. A managed
     * reload therefore recreates the same provider pipeline (descriptor factory, dependency
     * context, JavaPlugin init, registry insertion, onLoad) after capability preflight.
     */
    private Plugin runtimeLoadPaperPlugin(Path deployedJar) throws Exception {
        JarFile jar = new JarFile(deployedJar.toFile());
        AtomicReference<Object> provider = new AtomicReference<>();
        Object handler = Proxy.newProxyInstance(
                entrypointHandlerClass.getClassLoader(),
                new Class<?>[]{entrypointHandlerClass},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("register") && arguments != null && arguments.length == 2) {
                        if (arguments[0] == bootstrapEntrypoint) {
                            throw new UnsupportedOperationException(
                                    "Runtime reload does not support Paper bootstrap entrypoints");
                        }
                        if (arguments[0] == pluginEntrypoint) provider.set(arguments[1]);
                        return null;
                    }
                    if (method.getName().equals("enter")) return null;
                    if (method.getName().equals("toString")) return "chyarolib-runtime-entrypoint";
                    return null;
                });

        Object parent = null;
        Object meta = null;
        Plugin plugin = null;
        try {
            parent = paperFileTypeRegisterMethod.invoke(paperPluginFileType, handler, jar, deployedJar);
            Object captured = provider.get();
            if (captured == null) throw new IllegalStateException("Paper descriptor produced no server plugin provider");

            Method getMeta = captured.getClass().getMethod("getMeta");
            getMeta.setAccessible(true);
            meta = getMeta.invoke(captured);
            Object tree = get(dependencyTreeField, instanceManager);
            Method add = findCompatibleMethod(tree.getClass(), "add", new Class<?>[]{meta.getClass()});
            add.invoke(tree, meta);

            Method setContext = findCompatibleMethod(captured.getClass(), "setContext",
                    new Class<?>[]{tree.getClass()});
            setContext.invoke(captured, tree);
            Method createInstance = captured.getClass().getMethod("createInstance");
            createInstance.setAccessible(true);
            Object created = createInstance.invoke(captured);
            if (!(created instanceof Plugin replacement)) {
                throw new IllegalStateException("Paper provider returned no plugin instance for " + deployedJar);
            }
            plugin = replacement;
            registryLoadMethod.invoke(instanceManager, plugin);
            plugin.onLoad();
            return plugin;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            cleanupFailedLoad(plugin, parent, meta);
            if (cause instanceof Exception checked) throw checked;
            throw new IllegalStateException("Paper runtime provider failed", cause);
        } catch (Exception exception) {
            cleanupFailedLoad(plugin, parent, meta);
            throw exception;
        } finally {
            if (parent == null) jar.close();
        }
    }

    private void cleanupFailedLoad(Plugin plugin, Object parent, Object meta) {
        if (plugin != null) {
            removeFailed(plugin);
            return;
        }
        if (meta != null) {
            try {
                Object tree = get(dependencyTreeField, instanceManager);
                findCompatibleMethod(tree.getClass(), "remove", new Class<?>[]{meta.getClass()}).invoke(tree, meta);
            } catch (Throwable ignored) { }
        }
        if (parent != null) {
            try {
                Field loader = findField(parent.getClass(), "classLoader");
                releaseClassLoader((ClassLoader) get(loader, parent));
            } catch (Throwable ignored) { }
        }
    }

    private void releaseClassLoader(ClassLoader loader) throws Exception {
        if (!configuredClassLoaderClass.isInstance(loader)) return;
        unregisterClassLoaderMethod.invoke(classLoaderStorage, loader);
        if (loader instanceof Closeable closeable) closeable.close();
        else {
            Method close = loader.getClass().getMethod("close");
            close.setAccessible(true);
            close.invoke(loader);
        }
    }

    private static String readPluginName(Path jarPath) throws Exception {
        return readPluginDescriptor(jarPath).getString("name");
    }

    private static YamlConfiguration readPluginDescriptor(Path jarPath) throws Exception {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("paper-plugin.yml");
            if (entry == null) entry = jar.getJarEntry("plugin.yml");
            if (entry == null) throw new IllegalArgumentException("Replacement has no Paper/Bukkit plugin descriptor");
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8));
            String name = yaml.getString("name");
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Replacement descriptor has no name");
            return yaml;
        }
    }

    private static Object resolvePaperManager() throws Exception {
        try {
            Class<?> managerClass = Class.forName("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            return managerClass.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException ignored) {
            Field field = findField(Bukkit.getServer().getClass(), "paperPluginManager");
            return get(field, Bukkit.getServer());
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { current = current.getSuperclass(); }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static Object get(Field field, Object target) throws IllegalAccessException {
        field.setAccessible(true);
        return field.get(target);
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?>[] candidates) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> expected = method.getParameterTypes()[0];
            for (Class<?> candidate : candidates) {
                if (expected.isAssignableFrom(candidate)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void syncCommands() {
        try { Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer()); }
        catch (ReflectiveOperationException ignored) { }
    }
}
