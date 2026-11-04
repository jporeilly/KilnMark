package io.kilnmark.server.sources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals(List.of("description", "ai_egress", "include", "exclude", "allow_writing", "allow_sql",
                "keep_samples", "sample_rows", "table_sample_above_rows", "max_runs"),
            CommonSettings.definitions().stream().map(CommonSettings.Definition::key).toList());
    }

    @Test
    void everyCommonSettingHasAKind() {
        Map<String, String> kinds = new java.util.LinkedHashMap<>();
        CommonSettings.definitions().forEach(d -> kinds.put(d.key(), d.kind().name()));
        assertEquals(Map.of("description", "TEXT", "ai_egress", "CHOICE", "include", "TEXT", "exclude", "TEXT",
            "allow_writing", "BOOLEAN", "allow_sql", "BOOLEAN", "keep_samples", "BOOLEAN", "sample_rows", "NUMBER",
            "table_sample_above_rows", "NUMBER", "max_runs", "NUMBER"), kinds);
        // The kind follows the choices when a definition gives none, as before kinds.
        assertEquals("CHOICE", new CommonSettings.Definition("k", "K", "", false, List.of("a"), "a").kind().name());
        assertEquals("TEXT", new CommonSettings.Definition("k", "K", "", false, List.of(), null).kind().name());
    }

    @Test
    void samplingSettingsAreCheckedByKind() {
        assertTrue(CommonSettings.problems(Map.of("keep_samples", "false", "sample_rows", "500",
            "table_sample_above_rows", "1000000")).isEmpty());
        assertEquals(List.of("keep_samples: true or false, not 'no'"),
            CommonSettings.problems(Map.of("keep_samples", "no")));
        assertEquals(List.of("sample_rows: a whole number from 0 to 10000, not 'lots'"),
            CommonSettings.problems(Map.of("sample_rows", "lots")));
        assertEquals(List.of("sample_rows: a whole number from 0 to 10000, not '20000'"),
            CommonSettings.problems(Map.of("sample_rows", "20000")));
        assertEquals(List.of("table_sample_above_rows: a number, not 'big'"),
            CommonSettings.problems(Map.of("table_sample_above_rows", "big")));
        assertEquals("false", CommonSettings.normalise(Map.of("keep_samples", "FALSE ")).get("keep_samples"));
        assertEquals("No", CommonSettings.normalise(Map.of("keep_samples", "No")).get("keep_samples"),
            "only true and false are rewritten; anything else is left for the check to name");
    }

    @Test
    void mostRunsAtOnceIsAWholeNumberFromOneToAHundredOrNothing() {
        assertEquals(null, CommonSettings.maxRuns(Map.of()));
        assertEquals(null, CommonSettings.maxRuns(null));
        assertEquals(null, CommonSettings.maxRuns(Map.of("max_runs", " ")));
        assertEquals(3, CommonSettings.maxRuns(Map.of("max_runs", " 3 ")));
        assertEquals(100, CommonSettings.maxRuns(Map.of("max_runs", "100")));
        assertTrue(CommonSettings.problems(Map.of("max_runs", "1")).isEmpty());
        assertTrue(CommonSettings.problems(Map.of("max_runs", "")).isEmpty(), "empty: no limit");
        for (String bad : List.of("0", "101", "-1", "2.5", "many")) {
            assertEquals(List.of("max_runs: a whole number from 1 to 100, or empty for no limit"),
                CommonSettings.problems(Map.of("max_runs", bad)), bad);
            assertEquals(null, CommonSettings.maxRuns(Map.of("max_runs", bad)), "a value a save refuses is no limit");
        }
    }

    @Test
    void sourcesAreReadOnlyUnlessAllowWritingIsTrue() {
        assertFalse(CommonSettings.allowsWriting(Map.of()));
        assertFalse(CommonSettings.allowsWriting(null));
        assertFalse(CommonSettings.allowsWriting(Map.of("allow_writing", "false")));
        assertFalse(CommonSettings.allowsWriting(Map.of("allow_writing", "yes")));
        assertTrue(CommonSettings.allowsWriting(Map.of("allow_writing", " TRUE ")));
        assertEquals("true", CommonSettings.normalise(Map.of("allow_writing", " True")).get("allow_writing"));
        assertEquals(List.of("allow_writing: true or false, not 'yes'"),
            CommonSettings.problems(Map.of("allow_writing", "yes")));
        assertTrue(CommonSettings.problems(Map.of("allow_writing", "false")).isEmpty());
    }
}
