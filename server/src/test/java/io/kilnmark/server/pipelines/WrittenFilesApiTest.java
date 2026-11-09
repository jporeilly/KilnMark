package io.kilnmark.server.pipelines;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.newToken;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.audit.AuditLog;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Downloading the files a pipeline run wrote (ADR 0055), end to end: a run writes a CSV file to a
 * folder source; a steward downloads it, streamed as the run wrote it, as an attachment; a viewer
 * may not; once a column it holds is marked sensitive (by the run carrying a mark, or a mark set
 * on what it was made from after the run), and when its columns are not in the catalogue, only a
 * designer with the unmask grant may, never an admin; a path is never taken from the caller, and
 * neither a changed target, a link nor a moved folder reaches another file; a file deleted or
 * replaced since is refused, saying so; and every download and refusal is in the audit log.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WrittenFilesApiTest {

    static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files");
    static final Path FOLDER = ROOT.resolve("dl-" + Long.toHexString(System.nanoTime()));
    static final String READ_URN = "urn:kilnmark:dl-mem:lab.people";

    static String pipeline(String name, String path) {
        return """
            kilnmark: pipeline/v1
            name: %s
            nodes:
              - id: people
                type: read
                source: dl-mem
                asset: lab.people

              - id: out
                type: write
                input: people
                source: dl-files
                path: %s
                mode: replace
            """.formatted(name, path);
    }

    static String designer;
    static String unmask;
    static String steward;
    static String viewer;
    /** The first run of dl-people, and its file's URN. */
    static long first;
    static String urn;
    /** The run of dl-plain. */
    static long plain;

    @jakarta.inject.Inject
    io.agroal.api.AgroalDataSource db;

    @jakarta.inject.Inject
    AuditLog audit;

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    private static JsonPath waitForRun(long id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        JsonPath run = null;
        while (System.nanoTime() < deadline) {
            run = as(designer).get("/api/v1/pipeline-runs/" + id).then().statusCode(200).extract().jsonPath();
            if (List.of("succeeded", "failed", "cancelled").contains(run.getString("run.status"))) {
                return run;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Run " + id + " did not finish: " + (run == null ? null : run.prettify()));
    }

    private static long run(String name) throws InterruptedException {
        long id = as(designer).body(Map.of()).post("/api/v1/pipelines/" + name + "/runs").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        JsonPath r = waitForRun(id);
        assertEquals("succeeded", r.getString("run.status"), r.prettify());
        return id;
    }

    private static Response download(String token, long run, Object index) {
        return Tokens.as(token).accept("text/html,*/*").get("/api/v1/pipeline-runs/" + run + "/files/" + index);
    }

    private static void mark(String token, String asset, String field, boolean on) {
        as(token).body(Map.of("field", field, "sensitive", on)).queryParam("urn", asset)
            .put("/api/v1/quality/asset/sensitive").then().statusCode(204);
    }

    private long count(String sql, Object... args) throws Exception {
        audit.awaitStored(5000);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void sql(String sql, Object... args) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    /** Last, while the server is still up: what this class made goes (best effort; marks may keep a source). */
    @Test
    @Order(99)
    void tidy() {
        admin().delete("/api/v1/pipelines/dl-people");
        admin().delete("/api/v1/pipelines/dl-plain");
        admin().delete("/api/v1/sources/dl-files");
        admin().delete("/api/v1/sources/dl-mem");
    }

    @Test
    @Order(1)
    void aRunWritesAFile() throws Exception {
        Files.createDirectories(FOLDER);
        designer = newToken("designer", false);
        unmask = newToken("designer", true);
        steward = newToken("steward", false);
        viewer = newToken("viewer", false);
        admin().contentType(ContentType.JSON).body(Map.of("name", "dl-mem", "kind", MemoryConnector.KIND,
            "settings", Map.of("rows", "40"), "password", "dl-mem-secret-58")).post("/api/v1/sources").then()
            .statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "dl-files", "kind", "files", "settings",
            Map.of("path", FOLDER.toString(), "allow_writing", "true"))).post("/api/v1/sources").then().statusCode(201);
        long scan = as(designer).post("/api/v1/sources/dl-mem/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!"succeeded".equals(as(designer).get("/api/v1/runs/" + scan).then().extract().jsonPath()
            .getString("status"))) {
            assertTrue(System.nanoTime() < deadline, "the scan did not finish");
            Thread.sleep(100);
        }
        as(designer).body(Map.of("name", "dl-people", "yaml", pipeline("dl-people", "out/people.csv")))
            .post("/api/v1/pipelines").then().statusCode(201);
        first = run("dl-people");
        JsonPath files = as(viewer).get("/api/v1/pipeline-runs/" + first + "/files").then().statusCode(200).extract()
            .jsonPath();
        assertEquals(1, files.getList("files").size(), files.prettify());
        assertEquals(0, files.getInt("files[0].index"));
        assertEquals("out/people.csv", files.getString("files[0].path"));
        assertEquals("dl-files", files.getString("files[0].source"));
        assertEquals("ready", files.getString("files[0].state"), files.prettify());
        assertEquals(Files.size(FOLDER.resolve("out/people.csv")), files.getLong("files[0].bytes"));
        assertFalse(files.getString("files").contains(FOLDER.toString()), "the folder on the server is not shown");
        urn = as(designer).get("/api/v1/pipeline-runs/" + first).then().extract().jsonPath().getString("written[0].urn");
        assertEquals("urn:kilnmark:dl-files:out/people.csv", urn);
    }

    @Test
    @Order(2)
    void aStewardDownloadsItAsTheRunWroteIt() throws Exception {
        assertTrue(as(steward).get("/api/v1/pipeline-runs/" + first + "/files").then().statusCode(200).extract()
            .jsonPath().getBoolean("files[0].downloadable"));
        Response r = download(steward, first, 0);
        r.then().statusCode(200)
            .header("Content-Type", containsString("text/csv"))
            .header("Content-Disposition", equalTo("attachment; filename=\"people.csv\""))
            .header("X-Content-Type-Options", equalTo("nosniff"))
            .header("Cache-Control", containsString("no-store"));
        assertArrayEquals(Files.readAllBytes(FOLDER.resolve("out/people.csv")), r.asByteArray());
        assertEquals(1, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.downloaded' AND target = ? "
            + "AND detail->>'path' = 'out/people.csv' AND detail->>'source' = 'dl-files' AND (detail->>'bytes')::bigint = ?",
            "pipeline-run/" + first, Files.size(FOLDER.resolve("out/people.csv"))));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.audit_event WHERE action LIKE 'file.%' "
            + "AND detail::text LIKE '%Person %'"), "never the file's contents");
    }

    @Test
    @Order(3)
    void anotherSitesPageCannotStartADownloadNorCanHead() throws Exception {
        for (String site : List.of("cross-site", "same-site")) {
            Tokens.as(steward).header("Sec-Fetch-Site", site).get("/api/v1/pipeline-runs/" + first + "/files/0")
                .then().statusCode(403).body("error", equalTo(WrittenFiles.CROSS_SITE));
        }
        for (String site : List.of("same-origin", "none")) {
            Tokens.as(steward).header("Sec-Fetch-Site", site).get("/api/v1/pipeline-runs/" + first + "/files/0")
                .then().statusCode(200);
        }
        Tokens.as(steward).head("/api/v1/pipeline-runs/" + first + "/files/0").then().statusCode(405);
        assertEquals(2, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.download_refused' "
            + "AND target = ? AND detail->>'refused' = 'cross_site'", "pipeline-run/" + first));
        // The run recorded the file as the file system knows it, where it says (not shown by the API).
        assertTrue(count("SELECT count(*) FROM kilnmark.pipeline_run WHERE id = ? "
            + "AND (result->'written'->0->'file'->>'bytes') IS NOT NULL", first) == 1);
    }

    @Test
    @Order(3)
    void aViewerSeesNoValues() throws Exception {
        JsonPath files = as(viewer).get("/api/v1/pipeline-runs/" + first + "/files").then().statusCode(200).extract()
            .jsonPath();
        assertFalse(files.getBoolean("files[0].downloadable"));
        assertEquals(WrittenFiles.VIEWER, files.getString("files[0].reason"));
        download(viewer, first, 0).then().statusCode(403).contentType(ContentType.JSON)
            .body("error", equalTo(WrittenFiles.VIEWER));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.download_refused' "
            + "AND target = ? AND detail->>'refused' = 'no_values' AND detail->>'status' = '403'", "pipeline-run/" + first));
    }

    @Test
    @Order(4)
    void aMarkOnWhatItWasMadeFromSinceTheRunMakesItSensitive() throws Exception {
        mark(steward, READ_URN, "name", true);
        download(steward, first, 0).then().statusCode(403).body("error", equalTo(WrittenFiles.MARKED_SINCE));
        download(Tokens.ADMIN, first, 0).then().statusCode(403);
        download(unmask, first, 0).then().statusCode(200);
    }

    @Test
    @Order(5)
    void aMarkCarriedByTheRunIsForUnmaskOnlyAndTheOlderFileHasChanged() throws Exception {
        // Run again with the mark in place: it is carried to the written file's name column.
        long second = run("dl-people");
        mark(Tokens.ADMIN, READ_URN, "name", false);
        for (String token : List.of(steward, designer, Tokens.ADMIN)) {
            JsonPath files = as(token).get("/api/v1/pipeline-runs/" + second + "/files").then().statusCode(200)
                .extract().jsonPath();
            assertFalse(files.getBoolean("files[0].downloadable"), files.prettify());
            assertEquals(WrittenFiles.SENSITIVE, files.getString("files[0].reason"));
            download(token, second, 0).then().statusCode(403).body("error", equalTo(WrittenFiles.SENSITIVE));
        }
        download(unmask, second, 0).then().statusCode(200);
        assertTrue(as(unmask).get("/api/v1/pipeline-runs/" + second + "/files").then().extract().jsonPath()
            .getBoolean("files[0].downloadable"));
        // The first run's file was replaced by the second's: it is not the first run's any more.
        JsonPath older = as(unmask).get("/api/v1/pipeline-runs/" + first + "/files").then().extract().jsonPath();
        assertEquals("changed", older.getString("files[0].state"), older.prettify());
        download(unmask, first, 0).then().statusCode(409).body("error", equalTo(WrittenFiles.CHANGED));
        assertEquals(3, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.download_refused' "
            + "AND target = ? AND detail->>'refused' = 'sensitive' AND detail->>'path' = 'out/people.csv'",
            "pipeline-run/" + second));
    }

    @Test
    @Order(6)
    void columnsNotInTheCatalogueAreTreatedAsSensitive() throws Exception {
        as(designer).body(Map.of("name", "dl-plain", "yaml", pipeline("dl-plain", "out/plain.csv")))
            .post("/api/v1/pipelines").then().statusCode(201);
        plain = run("dl-plain");
        download(steward, plain, 0).then().statusCode(200);
        String plainUrn = as(designer).get("/api/v1/pipeline-runs/" + plain).then().extract().jsonPath()
            .getString("written[0].urn");
        sql("DELETE FROM kilnmark.field WHERE asset_urn = ?", plainUrn);
        download(steward, plain, 0).then().statusCode(403).body("error", equalTo(WrittenFiles.UNKNOWN));
        download(Tokens.ADMIN, plain, 0).then().statusCode(403).body("error", equalTo(WrittenFiles.UNKNOWN));
        download(unmask, plain, 0).then().statusCode(200);
    }

    @Test
    @Order(7)
    void noPathIsTakenFromTheCaller() throws Exception {
        for (Object index : List.of("1", "-1", "99", "x", "0x0", "plain.csv")) {
            int status = download(unmask, plain, index).then().extract().statusCode();
            assertEquals(404, status, "index " + index);
        }
        download(unmask, 999_999_999L, 0).then().statusCode(404);
        assertTrue(count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.download_refused' "
            + "AND target = ? AND detail->>'refused' = 'no_file'", "pipeline-run/" + plain) >= 3);
    }

    @Test
    @Order(8)
    void aChangedTargetALinkOrAMovedFolderReachNoOtherFile() throws Exception {
        Path outside = ROOT.resolve("dl-outside-" + Long.toHexString(System.nanoTime()) + ".csv");
        Files.writeString(outside, "secret\n1\n");
        String result = "SELECT result::text FROM kilnmark.pipeline_run WHERE id = " + plain;
        String before;
        try (Connection c = db.getConnection(); ResultSet rs = c.createStatement().executeQuery(result)) {
            rs.next();
            before = rs.getString(1);
        }
        try {
            // The run's own record says another path, leading out of the folder: refused, whatever it says.
            for (String target : List.of("../" + outside.getFileName(), outside.toString().replace('\\', '/'),
                "out/../../" + outside.getFileName())) {
                sql("UPDATE kilnmark.pipeline_run SET result = jsonb_set(jsonb_set(result, '{written,0,target}', "
                    + "to_jsonb(?::text)), '{written,0,file}', 'null'::jsonb) WHERE id = ?", target, plain);
                Response r = download(unmask, plain, 0);
                assertEquals(409, r.statusCode(), target);
                assertEquals(WrittenFiles.UNREACHABLE, r.jsonPath().getString("error"));
                assertFalse(r.asString().contains("secret"));
            }
        } finally {
            sql("UPDATE kilnmark.pipeline_run SET result = ?::jsonb WHERE id = ?", before, plain);
        }
        download(unmask, plain, 0).then().statusCode(200);

        // The source moved to another folder holding a file of the same name, size and time: not the run's.
        Path elsewhere = Files.createDirectories(ROOT.resolve("dl-elsewhere-" + Long.toHexString(System.nanoTime())));
        Files.createDirectories(elsewhere.resolve("out"));
        Path copy = Files.copy(FOLDER.resolve("out/plain.csv"), elsewhere.resolve("out/plain.csv"));
        Files.setLastModifiedTime(copy, Files.getLastModifiedTime(FOLDER.resolve("out/plain.csv")));
        // Only an admin moves a source pipelines write to, and only once it has no marks.
        mark(Tokens.ADMIN, urn, "name", false);
        admin().contentType(ContentType.JSON).body(Map.of("settings", Map.of("path", elsewhere.toString(),
            "allow_writing", "true"))).put("/api/v1/sources/dl-files").then().statusCode(200);
        try {
            download(unmask, plain, 0).then().statusCode(409).body("error", equalTo(WrittenFiles.CHANGED));
        } finally {
            admin().contentType(ContentType.JSON).body(Map.of("settings", Map.of("path", FOLDER.toString(),
                "allow_writing", "true"))).put("/api/v1/sources/dl-files").then().statusCode(200);
        }
        download(unmask, plain, 0).then().statusCode(200);

        // The file swapped for a link to a file outside the folder (where the system lets tests make links).
        Path file = FOLDER.resolve("out/plain.csv");
        Path kept = FOLDER.resolve("out/plain.kept");
        Files.move(file, kept);
        boolean linked = false;
        try {
            Files.createSymbolicLink(file, outside);
            linked = true;
        } catch (java.io.IOException | UnsupportedOperationException e) {
            // Windows without the right to make links: the link cannot be made, so it cannot be followed either
        }
        try {
            if (linked) {
                Response r = download(unmask, plain, 0);
                assertEquals(409, r.statusCode(), r.asString());
                assertFalse(r.asString().contains("secret"));
            }
        } finally {
            Files.deleteIfExists(file);
            Files.move(kept, file);
        }
        download(unmask, plain, 0).then().statusCode(200).body(not(containsString("secret")));
    }

    @Test
    @Order(9)
    void aDeletedFileSaysSo() throws Exception {
        Files.delete(FOLDER.resolve("out/plain.csv"));
        JsonPath files = as(unmask).get("/api/v1/pipeline-runs/" + plain + "/files").then().statusCode(200).extract()
            .jsonPath();
        assertEquals("deleted", files.getString("files[0].state"));
        assertFalse(files.getBoolean("files[0].downloadable"));
        assertEquals(WrittenFiles.DELETED, files.getString("files[0].reason"));
        download(unmask, plain, 0).then().statusCode(410).body("error", equalTo(WrittenFiles.DELETED));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'file.download_refused' "
            + "AND target = ? AND detail->>'refused' = 'deleted' AND detail->>'status' = '410'", "pipeline-run/" + plain));
    }
}
