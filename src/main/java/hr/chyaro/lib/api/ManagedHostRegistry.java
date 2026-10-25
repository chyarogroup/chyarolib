package hr.chyaro.lib.api;

import java.util.Collection;
import java.util.Optional;

public interface ManagedHostRegistry {
    Registration register(ManagedHostRegistration registration);
    Optional<ManagedHostRegistration> find(String id);
    Collection<String> hostIds();
    Collection<String> addonIds(String hostId);
    ReloadResult reloadHost(String hostId);
    ReloadResult reloadAddons(String hostId, String addonId);

    record ReloadResult(boolean success, boolean rolledBack, String message) { }
}
