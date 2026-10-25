package hr.chyaro.lib.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.lang.reflect.Field;
import java.util.Map;

/** Capability-checked access to Paper's mutable command lookup for managed cleanup/restoration. */
public final class CommandRegistryAccess {
    private CommandRegistryAccess() { }

    @SuppressWarnings("unchecked")
    public static Map<String, Command> mutableKnownCommands() {
        CommandMap commandMap = Bukkit.getCommandMap();
        Class<?> current = commandMap.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField("knownCommands");
                field.setAccessible(true);
                Object value = field.get(commandMap);
                if (value instanceof Map<?, ?> map) return (Map<String, Command>) map;
                throw new IllegalStateException("knownCommands is not a Map");
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Could not access Paper's mutable command registry", exception);
            }
        }
        throw new IllegalStateException("Paper's mutable command registry is unavailable");
    }
}
