package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** If-Match on rule edits: the rule's updatedAt as last read, with or without ETag quoting. */
class QualityRuleVersionUnitTest {

    @Test
    void readsTheUpdatedAtSentInIfMatch() {
        Instant t = Instant.parse("2026-10-25T09:30:00.123456Z");
        assertEquals(t, QualityResource.readVersion("2026-10-25T09:30:00.123456Z"));
        assertEquals(t, QualityResource.readVersion("\"2026-10-25T09:30:00.123456Z\""));
        assertEquals(t, QualityResource.readVersion("W/\"2026-10-25T09:30:00.123456Z\""));
        assertEquals(t, QualityResource.readVersion("  2026-10-25T09:30:00.123456Z "));
    }

    @Test
    void noneOrAnyMeansNoCheck() {
        assertNull(QualityResource.readVersion(null));
        assertNull(QualityResource.readVersion(""));
        assertNull(QualityResource.readVersion("*"));
    }

    @Test
    void anythingElseIsRefusedInPlainWords() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> QualityResource.readVersion("yesterday"));
        assertTrue(e.getMessage().startsWith("If-Match: "), e.getMessage());
    }
}
