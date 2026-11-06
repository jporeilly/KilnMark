package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * What a request key is tied to (ADR 0047): the same request, its map keys in any order at any
 * depth, hashes the same; any other kind, name, version or value, or a list in another order, does
 * not. And which keys are taken.
 */
class RunRequestKeysUnitTest {

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void theSameRequestInAnyOrderHashesTheSame() {
        Map<String, Object> one = ordered("b", 1, "a", ordered("y", "z", "x", List.of(1, 2)));
        Map<String, Object> two = ordered("a", ordered("x", List.of(1, 2), "y", "z"), "b", 1);
        assertEquals(RunRequestKeys.requestHash("flow", "f", 3, one), RunRequestKeys.requestHash("flow", "f", 3, two));
        assertEquals(RunRequestKeys.requestHash("flow", "f", null, null),
            RunRequestKeys.requestHash("flow", "f", null, Map.of()), "no parameters is an empty map");
    }

    @Test
    void anythingElseHashesOtherwise() {
        Map<String, Object> p = ordered("a", List.of(1, 2));
        String base = RunRequestKeys.requestHash("flow", "f", null, p);
        assertNotEquals(base, RunRequestKeys.requestHash("pipeline", "f", null, p));
        assertNotEquals(base, RunRequestKeys.requestHash("flow", "g", null, p));
        assertNotEquals(base, RunRequestKeys.requestHash("flow", "f", 1, p));
        assertNotEquals(base, RunRequestKeys.requestHash("flow", "f", null, ordered("a", List.of(2, 1))));
        assertNotEquals(base, RunRequestKeys.requestHash("flow", "f", null, ordered("a", List.of(1, 2), "b", true)));
    }

    @Test
    void aKeyIsOneTo200PrintableCharacters() {
        assertEquals("daily-load 2026-11-06", RunRequestKeys.check("daily-load 2026-11-06"));
        assertEquals(null, RunRequestKeys.check(null));
        for (String bad : List.of("", "   ", "x".repeat(201), "tab\there", "café")) {
            assertThrows(IllegalArgumentException.class, () -> RunRequestKeys.check(bad), bad);
        }
    }
}
