package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.graph.store.Records;
import jakarta.ws.rs.WebApplicationException;

/** Only admins change whether pipelines may write to a source. */
class SourceWritingTest {

    private static Records.Source source(Map<String, String> settings) {
        return new Records.Source(1, "warehouse", "postgresql", settings, true, Instant.now(), "Warehouse");
    }

    private static SourceForm form(Map<String, String> settings) {
        return new SourceForm(null, null, settings, null, null, null, null);
    }

    @Test
    void adminsMayChangeIt() {
        SourceForm f = form(Map.of("url", "jdbc:postgresql://h/db", "allow_writing", "true"));
        assertSame(f, SourceService.writingAsAllowed(f, source(Map.of()), true));
    }

    @Test
    void othersKeepTheStoredValueWhenTheyLeaveItOut() {
        SourceForm f = SourceService.writingAsAllowed(form(Map.of("url", "jdbc:postgresql://h/db")),
            source(Map.of("allow_writing", "true")), false);
        assertEquals("true", f.settings().get("allow_writing"));
        SourceForm none = SourceService.writingAsAllowed(form(Map.of("url", "x")), source(Map.of()), false);
        assertFalse(none.settings().containsKey("allow_writing"));
    }

    @Test
    void othersMaySendItUnchangedButNotChangeIt() {
        SourceService.writingAsAllowed(form(Map.of("allow_writing", "TRUE")), source(Map.of("allow_writing", "true")),
            false);
        SourceService.writingAsAllowed(form(Map.of("allow_writing", "false")), source(Map.of()), false);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> SourceService.writingAsAllowed(
            form(Map.of("allow_writing", "true")), source(Map.of()), false));
        assertEquals(403, e.getResponse().getStatus());
        assertThrows(WebApplicationException.class, () -> SourceService.writingAsAllowed(
            form(Map.of("allow_writing", "false")), source(Map.of("allow_writing", "true")), false));
    }

    @Test
    void onlyAdminsMoveASourceThatAllowsWriting() {
        Records.Source writable = new Records.Source(1, "share", "s3", Map.of("bucket", "exports", "endpoint",
            "http://minio:9000", "allow_writing", "true", "description", "Old"), true, Instant.now(), "Share");
        // Description and patterns stay open to stewards.
        SourceService.whereUnchangedOrAdmin(writable, Map.of("bucket", "exports", "endpoint", "http://minio:9000",
            "allow_writing", "true", "description", "New", "include", "*.csv"), false);
        WebApplicationException e = assertThrows(WebApplicationException.class, () ->
            SourceService.whereUnchangedOrAdmin(writable, Map.of("bucket", "elsewhere", "endpoint", "http://minio:9000",
                "allow_writing", "true"), false));
        assertEquals(403, e.getResponse().getStatus());
        assertTrue(String.valueOf(((ApiError) e.getResponse().getEntity()).error()).contains("(bucket)"));
        SourceService.whereUnchangedOrAdmin(writable, Map.of("bucket", "elsewhere"), true);
        // A read-only source can be moved by stewards as before.
        SourceService.whereUnchangedOrAdmin(source(Map.of("url", "jdbc:postgresql://a/db")),
            Map.of("url", "jdbc:postgresql://b/db"), false);
    }
}
