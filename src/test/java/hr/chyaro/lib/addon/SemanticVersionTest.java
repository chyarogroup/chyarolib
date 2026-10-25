package hr.chyaro.lib.addon;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemanticVersionTest {
    @Test
    void ordersBetaNumbersAndStableRelease() {
        assertTrue(SemanticVersion.parse("1.3.0-beta1").compareTo(SemanticVersion.parse("1.3.0-beta2")) < 0);
        assertTrue(SemanticVersion.parse("1.3.0-beta2").compareTo(SemanticVersion.parse("1.3.0-beta10")) < 0);
        assertTrue(SemanticVersion.parse("1.3.0-beta10").compareTo(SemanticVersion.parse("1.3.0")) < 0);
    }

    @Test
    void evaluatesAddonRanges() {
        assertTrue(SemanticVersion.satisfies("1.3.0-beta1", ">=1.3.0-beta1 <1.4.0"));
        assertTrue(SemanticVersion.satisfies("1.3.0", ">=1.3.0-beta1 <1.4.0"));
        assertTrue(SemanticVersion.satisfies("1.4.0-beta1", ">=1.3.0-beta1 <1.4.0"));
        assertFalse(SemanticVersion.satisfies("1.4.0", ">=1.3.0-beta1 <1.4.0"));
    }

    @Test
    void rejectsMalformedVersions() {
        assertThrows(IllegalArgumentException.class, () -> SemanticVersion.parse("beta1"));
    }
}
