package io.kilnmark.server.audit;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/** The note {@code restore.sh} leaves becomes a {@code database.restored} event, once (ADR 0039). */
@QuarkusTest
class RestoreRecordTest {

    @Inject
    AuditLog audit;

    @Inject
    RestoreRecord restores;

    @Test
    void aRestoreIsRecordedOnceFromItsNote() throws Exception {
        Path dir = Files.createTempDirectory("kilnmark-restore");
        Path note = dir.resolve(RestoreRecord.FILE);
        String backup = "kilnmark-" + System.nanoTime();
        Files.writeString(note, "backup=" + backup + "\ntakenAt=2026-11-03T20:00:00Z\nversion=0.0.9\n"
            + "previousDatabase=kilnmark_before_20261104T000000Z\nkeys=true\nsecret=never kept\n",
            StandardCharsets.UTF_8);

        restores.record(note);
        assertFalse(Files.exists(note), "the note is deleted once recorded");
        restores.record(note);     // nothing left: recorded once

        audit.awaitStored(5000);
        JsonPath events = admin().queryParam("action", AuditLog.DATABASE_RESTORED).queryParam("limit", 50)
            .get("/api/v1/audit").then().statusCode(200).extract().jsonPath();
        String find = "events.findAll { it.detail.backup == '" + backup + "' }";
        assertEquals(1, events.getList(find).size(), events.prettify());
        Map<String, Object> detail = events.getMap(find + "[0].detail");
        assertNotNull(detail);
        assertEquals(Map.of("backup", backup, "takenAt", "2026-11-03T20:00:00Z", "version", "0.0.9",
            "previousDatabase", "kilnmark_before_20261104T000000Z", "keys", "true"), detail,
            "only the known details are kept");
        assertEquals("server (restore)", events.getString(find + "[0].actor"));
        Files.delete(dir);
    }
}
