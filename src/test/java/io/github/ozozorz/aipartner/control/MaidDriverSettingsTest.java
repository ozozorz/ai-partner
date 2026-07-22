package io.github.ozozorz.aipartner.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies that configuration accepts environment variable names, not typical secret values. */
class MaidDriverSettingsTest {
    @Test
    void validatesPortableEnvironmentVariableNames() {
        assertTrue(MaidDriverSettings.isValidEnvironmentVariableName("DEEPSEEK_API_KEY"));
        assertTrue(MaidDriverSettings.isValidEnvironmentVariableName("_LOCAL_KEY_2"));
        assertFalse(MaidDriverSettings.isValidEnvironmentVariableName("sk-secret-value"));
        assertFalse(MaidDriverSettings.isValidEnvironmentVariableName("2API_KEY"));
        assertFalse(MaidDriverSettings.isValidEnvironmentVariableName("API KEY"));
        assertThrows(
                IllegalArgumentException.class,
                () -> MaidDriverSettings.requireEnvironmentVariableName("sk-secret-value")
        );
    }

    @Test
    void parsesDriverModesAndFallsBackSafely() {
        assertEquals(MaidDriveMode.LLM, MaidDriveMode.parse("LLM").orElseThrow());
        assertEquals(MaidDriveMode.LOCAL, MaidDriveMode.fromSavedName("future-mode"));
        assertEquals(MaidDriveMode.LLM, MaidDriveMode.LOCAL.next());
    }
}
