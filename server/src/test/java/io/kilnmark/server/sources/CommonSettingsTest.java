package io.kilnmark.server.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CommonSettingsTest {

    @Test
    void aiEgressDefaultsToLocalOnly() {
        assertEquals(CommonSettings.AiEgress.LOCAL_ONLY, CommonSettings.AiEgress.of(Map.of()));
        assertEquals(CommonSettings.AiEgress.NONE, CommonSettings.AiEgress.of(Map.of("ai_egress", "NONE")));
        assertEquals("allowed", CommonSettings.normalise(Map.of("ai_egress", " Allowed ")).get("ai_egress"));
    }

    @Test
    void problemsNameTheSetting() {
        assertTrue(CommonSettings.problems(Map.of()).isEmpty());
        assertTrue(CommonSettings.problems(Map.of("ai_egress", "cloud")).get(0).startsWith("ai_egress:"));
        assertTrue(CommonSettings.problems(Map.of("description", "x".repeat(2001))).get(0).startsWith("description:"));
        assertTrue(CommonSettings.problems(Map.of("include", "*.{csv")).get(0).startsWith("include:"));
        assertEquals(List.of("description", "ai_egress", "include", "exclude"),
            CommonSettings.definitions().stream().map(CommonSettings.Definition::key).toList());
    }
}
