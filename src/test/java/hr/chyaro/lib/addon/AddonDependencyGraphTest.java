package hr.chyaro.lib.addon;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddonDependencyGraphTest {
    @Test
    void ordersDependenciesBeforeDependents() {
        assertEquals(List.of("economy", "missions", "skyblock"), AddonDependencyGraph.sort(Map.of(
                "skyblock", List.of("missions"),
                "missions", List.of("economy"),
                "economy", List.of()
        )));
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> AddonDependencyGraph.sort(Map.of("skyblock", List.of("missing"))));
    }

    @Test
    void rejectsCycles() {
        assertThrows(IllegalArgumentException.class, () -> AddonDependencyGraph.sort(Map.of(
                "one", List.of("two"), "two", List.of("one"))));
    }
}
