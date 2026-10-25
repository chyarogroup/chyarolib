package hr.chyaro.lib.api;

import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.Objects;

public final class ManagedHostRegistration {
    private final String id;
    private final JavaPlugin plugin;
    private final Path deployedJar;
    private final Path addonsDirectory;
    private final GenSproutBridge bridge;
    private final Runnable prepareReload;
    private final Runnable closeDialogs;

    private ManagedHostRegistration(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id");
        this.plugin = Objects.requireNonNull(builder.plugin, "plugin");
        this.deployedJar = Objects.requireNonNull(builder.deployedJar, "deployedJar").toAbsolutePath().normalize();
        this.addonsDirectory = Objects.requireNonNull(builder.addonsDirectory, "addonsDirectory").toAbsolutePath().normalize();
        this.bridge = Objects.requireNonNull(builder.bridge, "bridge");
        this.prepareReload = builder.prepareReload;
        this.closeDialogs = builder.closeDialogs;
    }

    public String id() { return id; }
    public JavaPlugin plugin() { return plugin; }
    public Path deployedJar() { return deployedJar; }
    public Path addonsDirectory() { return addonsDirectory; }
    public GenSproutBridge bridge() { return bridge; }
    public void prepareReload() { prepareReload.run(); }
    public void closeDialogs() { closeDialogs.run(); }

    public static Builder builder(String id, JavaPlugin plugin, Path deployedJar, Path addonsDirectory,
                                  GenSproutBridge bridge) {
        return new Builder(id, plugin, deployedJar, addonsDirectory, bridge);
    }

    public static final class Builder {
        private final String id;
        private final JavaPlugin plugin;
        private final Path deployedJar;
        private final Path addonsDirectory;
        private final GenSproutBridge bridge;
        private Runnable prepareReload = () -> { };
        private Runnable closeDialogs = () -> { };

        private Builder(String id, JavaPlugin plugin, Path deployedJar, Path addonsDirectory, GenSproutBridge bridge) {
            this.id = id;
            this.plugin = plugin;
            this.deployedJar = deployedJar;
            this.addonsDirectory = addonsDirectory;
            this.bridge = bridge;
        }

        public Builder prepareReload(Runnable action) { this.prepareReload = Objects.requireNonNull(action); return this; }
        public Builder closeDialogs(Runnable action) { this.closeDialogs = Objects.requireNonNull(action); return this; }
        public ManagedHostRegistration build() { return new ManagedHostRegistration(this); }
    }
}
