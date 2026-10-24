package io.kilnmark.server.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.KilnmarkConfig;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;

/** {@code kilnmark.auth.mode}: a typo stops the server instead of turning sign-in off. */
class AuthModeTest {

    /** Reads the settings the way the server does at start-up. */
    private static KilnmarkConfig load(String mode) {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
            .withMapping(KilnmarkConfig.class)
            .withConverter(java.time.Duration.class, 100, new io.quarkus.runtime.configuration.DurationConverter())
            .withDefaultValues(Map.of("kilnmark.secret-key-file", "/tmp/kilnmark/secret.key", "kilnmark.auth.mode", mode))
            .build();
        return config.getConfigMapping(KilnmarkConfig.class);
    }

    @Test
    void anUnknownModeStopsStartUp() {
        for (String typo : new String[] {"enabled", "ture", "false", "none"}) {
            RuntimeException e = assertThrows(RuntimeException.class, () -> load(typo), typo);
            assertTrue(String.valueOf(e.getMessage()).contains("kilnmark.auth.mode"), e.getMessage());
        }
    }

    @Test
    void theKnownModesAreRead() {
        assertEquals(AuthMode.TOKENS, load("tokens").auth().mode());
        assertEquals(AuthMode.OFF, load("off").auth().mode());
        assertEquals(AuthMode.OFF, load("OFF").auth().mode());
    }

    @Test
    void offIsOnlyAllowedOnALoopbackAddress() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> AuthMode.check(AuthMode.OFF,
            "0.0.0.0"));
        assertTrue(e.getMessage().contains("QUARKUS_HTTP_HOST=127.0.0.1"), e.getMessage());
        assertThrows(IllegalStateException.class, () -> AuthMode.check(AuthMode.OFF, "10.1.2.3"));
        assertThrows(IllegalStateException.class, () -> AuthMode.check(AuthMode.OFF, ""));
        assertDoesNotThrow(() -> AuthMode.check(AuthMode.OFF, "127.0.0.1"));
        assertDoesNotThrow(() -> AuthMode.check(AuthMode.OFF, "localhost"));
        assertDoesNotThrow(() -> AuthMode.check(AuthMode.OFF, "::1"));
        assertDoesNotThrow(() -> AuthMode.check(AuthMode.TOKENS, "0.0.0.0"));
    }

    @Test
    void controlCharactersNeverReachALogLine() {
        assertEquals("ci?INFO forged?", ApiTokens.loggable("ci\nINFO forged "));
        assertEquals("", ApiTokens.loggable(null));
        assertEquals("Priya's laptop", ApiTokens.loggable("Priya's laptop"));
    }
}
