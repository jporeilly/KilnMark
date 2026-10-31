package io.kilnmark.server.documents;

import static io.kilnmark.server.Tokens.admin;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * Documents end to end through the REST API: a folder of the lab's documents is scanned, then
 * read back as profiles, sheets, attachments, pages, found words, chunks and search snippets; a
 * second source keeps no text.
 */
@QuarkusTest
@TestProfile(DocumentFlowTest.Folders.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentFlowTest {

    static final Path ROOT;

    static {
        try {
            // the same folder in every class loader that runs this (the test profile is read in another)
            ROOT = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"),
                "kilnmark-documents-test-" + ProcessHandle.current().pid())).toRealPath();
            for (String source : List.of("docs", "meta", "meta-od")) {
                Path dir = Files.createDirectories(ROOT.resolve(source));
                try (var lab = Files.list(Path.of("../lab/files/share/documents"))) {
                    for (Path f : lab.toList()) {
                        Files.copy(f, dir.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.writeString(ROOT.resolve("meta/private-note.txt"), "The quarterly zebra census is kept private.\n");
            Files.writeString(ROOT.resolve("docs/offices.txt"), "Our offices are in İSTANBUL, İzmir and Ankara. "
                + "The İstanbul office opens first.\n");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Folder sources may read the test's own folder. */
    public static class Folders implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.files.roots", ROOT.toString());
        }
    }

    static final String DOCS = "urn:kilnmark:docs:";

    @Inject
    AgroalDataSource db;

    static JsonPath waitForRun(long id) throws InterruptedException {
        for (int i = 0; i < 240; i++) {
            JsonPath run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            String status = run.getString("status");
            if (!status.equals("queued") && !status.equals("running")) {
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    static JsonPath scan(String name, Map<String, String> settings) throws InterruptedException {
        String created = admin().contentType(ContentType.JSON).body(Map.of("name", name, "kind", "files",
            "settings", settings)).post("/api/v1/sources").then().extract().asString();
        assertTrue(created.contains("\"name\""), created);
        long id = admin().post("/api/v1/sources/" + name + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        return waitForRun(id);
    }

    static JsonPath asset(String urn) {
        return admin().urlEncodingEnabled(false).get("/api/v1/assets/" + urn.replace("#", "%23")).then()
            .statusCode(200).extract().jsonPath();
    }

    int count(String sql) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Order(1)
    void aFolderOfDocumentsIsScanned() throws Exception {
        JsonPath run = scan("docs", Map.of("path", ROOT.resolve("docs").toString()));
        assertEquals("succeeded", run.getString("status"), run.getString("detail"));
        assertEquals(9, run.getInt("assetsSeen"), run.getString("detail")); // 6 files and 3 sheets
    }

    @Test
    @Order(2)
    void aPdfHasItsProfile() {
        JsonPath a = asset(DOCS + "remote-working-policy.pdf");
        assertEquals("document", a.getString("kind"));
        assertTrue(a.getBoolean("document.readable"));
        assertEquals("Remote working policy", a.getString("document.title"));
        assertEquals("Harbour Logistics Policy Team", a.getString("document.author"));
        assertEquals(2, a.getInt("document.pages"));
        assertEquals("en", a.getString("document.language"));
        assertEquals("application/pdf", a.getString("document.contentType"));
        assertEquals("2025-03-03T09:30:00Z", a.getString("document.created"));
        assertTrue(a.getInt("document.words") > 60);
        assertTrue(a.getBoolean("document.textKept"));
        assertTrue(a.getInt("document.chunks") >= 1);
        assertNull(a.get("document.mail"));
        assertNull(a.get("parent"));
        assertEquals(List.of(), a.getList("parts"));
        assertTrue(a.getString("lastScannedAt") != null);
    }

    @Test
    @Order(3)
    void aWorkbookListsItsSheetsAndEachSheetIsATable() {
        JsonPath book = asset(DOCS + "depot-budget-2025.xlsx");
        assertEquals(List.of("Expenses", "Headcount", "Notes"), book.getList("parts.name"));
        assertEquals(List.of(true, true, false), book.getList("parts.readable"));
        assertEquals(List.of("Expenses", "Headcount", "Notes"), book.getList("document.pageList.label"));

        JsonPath sheet = asset(DOCS + "depot-budget-2025.xlsx#Expenses");
        assertEquals("sheet", sheet.getString("kind"));
        assertEquals(18, sheet.getLong("rowCount"));
        assertEquals(DOCS + "depot-budget-2025.xlsx", sheet.getString("parent.urn"));
        assertEquals("float64", sheet.getString("fields.find { it.name == 'amount' }.kind"));
        assertEquals(6, sheet.getInt("fields.find { it.name == 'amount' }.profile.distinct") / 3);
    }

    @Test
    @Order(4)
    void aMailMessageHasHeadersAndAttachmentsAsAssets() {
        JsonPath mail = asset(DOCS + "2025-04-delivery-schedule.eml");
        assertTrue(mail.getString("document.mail.from").contains("planning@example.com"));
        assertTrue(mail.getString("document.mail.to").contains("stores@example.org"));
        assertTrue(mail.getString("document.mail.cc").contains("finance@example.net"));
        assertEquals("Delivery schedule for May", mail.getString("document.mail.subject"));
        assertEquals("2025-04-29T07:45:00Z", mail.getString("document.mail.date"));
        assertEquals(List.of("may-schedule.md", "remote-working-policy.pdf"), mail.getList("parts.name"));

        String pdf = mail.getString("parts[1].urn");
        JsonPath attached = asset(pdf);
        assertEquals(DOCS + "2025-04-delivery-schedule.eml", attached.getString("parent.urn"));
        assertEquals("Remote working policy", attached.getString("document.title"));
        admin().queryParam("urn", pdf).queryParam("page", 2).get("/api/v1/documents/text")
            .then().statusCode(200).body("text", containsString("Broadband costs are not"));
    }

    @Test
    @Order(5)
    void textReadsPageByPageAndSheetBySheet() {
        admin().queryParam("urn", DOCS + "remote-working-policy.pdf").queryParam("page", 2)
            .get("/api/v1/documents/text").then().statusCode(200)
            .body("page", equalTo(2)).body("pageCount", equalTo(2)).body("pageKind", equalTo("page"))
            .body("text", containsString("Equipment and expenses"))
            .body("extractedNow", equalTo(false));
        JsonPath sheet = admin().queryParam("urn", DOCS + "depot-budget-2025.xlsx").queryParam("page", 3)
            .get("/api/v1/documents/text").then().statusCode(200).extract().jsonPath();
        assertEquals("sheet", sheet.getString("pageKind"));
        assertEquals("Notes", sheet.getString("pages[2].label"));
        assertTrue(sheet.getString("text").contains("draft for the spring budget meeting"));
        admin().queryParam("urn", DOCS + "team-handbook.md").get("/api/v1/documents/text").then().statusCode(200)
            .body("pageKind", equalTo("part")).body("text", containsString("Book holidays"));
        admin().queryParam("urn", DOCS + "remote-working-policy.pdf").queryParam("page", 9)
            .get("/api/v1/documents/text").then().statusCode(400).body("error", containsString("from 1 to 2"));
        admin().queryParam("urn", DOCS + "depot-budget-2025.xlsx#Expenses").get("/api/v1/documents/text")
            .then().statusCode(400).body("error", containsString("not a document"));
    }

    @Test
    @Order(6)
    void findShowsWhereWordsAre() {
        JsonPath f = admin().queryParam("urn", DOCS + "depot-budget-2025.xlsx").queryParam("q", "north depot")
            .get("/api/v1/documents/find").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("Expenses", "Headcount"), f.getList("pages.label"));
        assertTrue(f.getInt("total") >= 12);
        String snippet = f.getString("pages[0].snippet.text");
        int at = f.getInt("pages[0].snippet.highlights[0].start");
        assertEquals("north depot", snippet.substring(at, at + 11).toLowerCase());
        admin().queryParam("urn", DOCS + "remote-working-policy.pdf").queryParam("q", "x")
            .get("/api/v1/documents/find").then().statusCode(400);
    }

    @Test
    @Order(7)
    void searchFindsDocumentTextWithASnippetBelowNameMatches() {
        JsonPath hits = admin().queryParam("q", "broadband").get("/api/v1/search").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(hits.getList("hits.urn").contains(DOCS + "remote-working-policy.pdf"), hits.prettify());
        Map<String, Object> pdf = hits.getMap("hits.find { it.urn == '" + DOCS + "remote-working-policy.pdf' }");
        @SuppressWarnings("unchecked")
        Map<String, Object> snippet = (Map<String, Object>) pdf.get("snippet");
        String text = (String) snippet.get("text");
        @SuppressWarnings("unchecked")
        Map<String, Integer> h = ((List<Map<String, Integer>>) snippet.get("highlights")).get(0);
        assertEquals("broadband", text.substring(h.get("start"), h.get("start") + h.get("length")).toLowerCase());
        assertFalse(text.contains(""));

        JsonPath byName = admin().queryParam("q", "handbook").get("/api/v1/search").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(DOCS + "team-handbook.md", byName.getString("hits[0].urn"));
        assertNull(byName.get("hits.find { it.urn == '" + DOCS + "remote-working-policy.pdf' }"));
    }

    @Test
    @Order(8)
    void chunksAreServedInOrderAndReplacedOnRescan() throws Exception {
        JsonPath c = admin().queryParam("urn", DOCS + "remote-working-policy.pdf").queryParam("limit", 2)
            .get("/api/v1/documents/chunks").then().statusCode(200).extract().jsonPath();
        assertEquals(0, c.getInt("chunks[0].sequence"));
        assertEquals(1, c.getInt("chunks[0].page"));
        assertTrue(c.getString("chunks[0].text").startsWith("Remote working policy"));
        int before = count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:docs:%'");
        long id = admin().post("/api/v1/sources/docs/scans").then().statusCode(202).extract().jsonPath().getLong("id");
        assertEquals("succeeded", waitForRun(id).getString("status"));
        assertEquals(before, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE "
            + "'urn:kilnmark:docs:%'"), "a rescan replaces the chunks");
    }

    @Test
    @Order(9)
    void aSourceThatKeepsNoTextStillHasProfilesAndReadsOnDemand() throws Exception {
        JsonPath run = scan("meta", Map.of("path", ROOT.resolve("meta").toString(), "keep_text", "false"));
        assertEquals("succeeded", run.getString("status"), run.getString("detail"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:meta:%'"));
        String note = "urn:kilnmark:meta:private-note.txt";
        JsonPath a = asset(note);
        assertFalse(a.getBoolean("document.textKept"));
        assertTrue(a.getInt("document.words") > 5);
        admin().queryParam("q", "zebra").get("/api/v1/search").then().statusCode(200)
            .body("hits.size()", equalTo(0));
        admin().queryParam("urn", note).get("/api/v1/documents/chunks").then().statusCode(200)
            .body("textKept", equalTo(false)).body("chunks.size()", equalTo(0));
        assertFalse(a.getBoolean("document.textOnDemand"));
        admin().queryParam("urn", note).get("/api/v1/documents/text").then().statusCode(409)
            .body("error", containsString("keeps no text"));
    }

    @Test
    @Order(9)
    void stewardsCanReadTextOnDemandWhenTheSourceAllowsIt() throws Exception {
        // Its own folder: two sources over the same files are refused, as they would keep separate marks.
        JsonPath run = scan("meta-od", Map.of("path", ROOT.resolve("meta-od").toString(), "keep_text", "false",
            "read_text_on_demand", "true"));
        assertEquals("succeeded", run.getString("status"), run.getString("detail"));
        String note = "urn:kilnmark:meta-od:private-note.txt";
        assertTrue(asset(note).getBoolean("document.textOnDemand"));
        admin().queryParam("urn", note).get("/api/v1/documents/text").then().statusCode(200)
            .body("extractedNow", equalTo(true)).body("text", containsString("zebra census"));
        String attachment = "urn:kilnmark:meta-od:2025-04-delivery-schedule.eml#attachments/1/may-schedule.md";
        admin().queryParam("urn", attachment).get("/api/v1/documents/text").then().statusCode(200)
            .body("text", containsString("May delivery schedule"));
        String viewer = admin().contentType(ContentType.JSON).body(Map.of("name", "od-viewer", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        Tokens.as(viewer).queryParam("urn", note).get("/api/v1/documents/text").then().statusCode(403);
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE "
            + "'urn:kilnmark:meta-od:%'"), "text read on demand is never stored");
    }

    @Test
    @Order(10)
    void viewersReadDocumentsAndStrangersDoNot() {
        String viewer = admin().contentType(ContentType.JSON).body(Map.of("name", "doc-reader", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
        Tokens.as(viewer).queryParam("urn", DOCS + "team-handbook.md").get("/api/v1/documents/text")
            .then().statusCode(200);
        given().queryParam("urn", DOCS + "team-handbook.md").get("/api/v1/documents/text").then().statusCode(401);
        admin().queryParam("urn", "not-a-urn").get("/api/v1/documents/text").then().statusCode(400);
        admin().queryParam("urn", DOCS + "nothing.pdf").get("/api/v1/documents/text").then().statusCode(404);
    }

    @Test
    @Order(10)
    void findMatchesWhateverCaseChangesDo() {
        for (String q : List.of("İstanbul", "istanbul", "İSTANBUL", "izmir")) {
            JsonPath f = admin().queryParam("urn", DOCS + "offices.txt").queryParam("q", q)
                .get("/api/v1/documents/find").then().statusCode(200).extract().jsonPath();
            assertTrue(f.getInt("total") >= 1, q + ": " + f.prettify());
            String text = f.getString("pages[0].snippet.text");
            int at = f.getInt("pages[0].snippet.highlights[0].start");
            int length = f.getInt("pages[0].snippet.highlights[0].length");
            assertTrue(text.substring(at, at + length).toLowerCase(java.util.Locale.ROOT).contains("zmir")
                || text.substring(at, at + length).toLowerCase(java.util.Locale.ROOT).contains("stanbul"), text);
        }
    }

    @Inject
    DocumentScan documentScan;

    @Inject
    io.kilnmark.graph.store.GraphStore graph;

    @Test
    @Order(10)
    void aPassingFailureKeepsWhatAnEarlierScanStored() throws Exception {
        String mail = DOCS + "2025-04-delivery-schedule.eml";
        int chunks = count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE '" + mail + "%'");
        assertTrue(chunks > 0);
        long sourceId = graph.source("docs").orElseThrow().id();
        long runId = graph.queueRun(sourceId, "scan");
        // a reader whose read failed because every reader was busy: nothing learnt about the document
        io.kilnmark.kiln.connect.Connector busy = new io.kilnmark.kiln.connect.Connector() {
            @Override
            public String kind() {
                return "files";
            }

            @Override
            public String displayName() {
                return "busy";
            }

            @Override
            public List<Setting> settings() {
                return List.of();
            }

            @Override
            public List<String> validate(io.kilnmark.kiln.connect.SourceConfig s) {
                return List.of();
            }

            @Override
            public List<io.kilnmark.kiln.connect.AssetInfo> catalog(io.kilnmark.kiln.connect.SourceConfig s) {
                return List.of();
            }

            @Override
            public io.kilnmark.kiln.NodeSpec reader(io.kilnmark.kiln.connect.SourceConfig s,
                                                    io.kilnmark.kiln.connect.AssetInfo a, ReadOptions read) {
                return new io.kilnmark.kiln.NodeSpec("read", new PassingFailure(read.assetUrn()), Map.of());
            }
        };
        var asset = new io.kilnmark.kiln.connect.AssetInfo("2025-04-delivery-schedule.eml", "document", "",
            "2025-04-delivery-schedule.eml", null, List.of(), List.of(), false, true, Map.of());
        var r = documentScan.read(busy, new io.kilnmark.kiln.connect.SourceConfig("docs", "files", Map.of(), null),
            asset, mail, sourceId, runId, () -> false);
        graph.finishRun(runId, "succeeded", 1, 0, 0, "test"); // so the source can be scanned again
        assertTrue(r.succeeded(), r.error());
        JsonPath a = asset(mail);
        assertTrue(a.getBoolean("document.readable"), "the earlier profile is kept");
        assertTrue(a.getString("document.notes").contains("The last scan could not read it again"));
        assertEquals(chunks, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE '" + mail
            + "%'"), "and its text, and its attachments' text");
        assertEquals(2, count("SELECT count(*) FROM kilnmark.asset WHERE parent_urn = '" + mail
            + "' AND last_seen_run = " + runId), "its attachments count as seen, so they are not marked removed");
    }

    /** A document reader that reports a passing failure and no text. */
    static final class PassingFailure implements io.kilnmark.kiln.Node {
        private final String urn;

        PassingFailure(String urn) {
            this.urn = urn;
        }

        @Override
        public String type() {
            return "passing-failure";
        }

        @Override
        public void init(io.kilnmark.kiln.NodeContext context) {
        }

        @Override
        public boolean process(io.kilnmark.kiln.NodeContext context) throws io.kilnmark.kiln.NodeException {
            context.metadata().emit(new io.kilnmark.kiln.MetadataEvent(urn, io.kilnmark.kiln.MetadataEvent.Kind.DOCUMENT,
                Map.of("readable", "false", "passing", "true", "problem", "every document reader was busy"),
                java.time.Instant.now()));
            var batch = org.apache.arrow.vector.VectorSchemaRoot.create(
                io.kilnmark.kiln.plugins.documents.DocumentEvents.CHUNK_SCHEMA, context.allocator());
            batch.allocateNew();
            batch.setRowCount(0);
            try {
                context.output().put(batch);
            } catch (InterruptedException e) {
                batch.close();
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    @Test
    @Order(10)
    void searchStaysQuickWithManyChunks() throws Exception {
        String urn = DOCS + "team-handbook.md";
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO kilnmark.document_chunk (asset_urn, seq, text, page, start_offset, end_offset) "
                + "SELECT '" + urn + "', 1000 + n, 'filler paragraph number ' || n || ' about warehouse rotas and "
                + "common words', NULL, 100000 + n * 100, 100000 + n * 100 + 60 FROM generate_series(1, 4000) n");
            s.execute("ANALYZE kilnmark.document_chunk");
        }
        long started = System.nanoTime();
        JsonPath hits = admin().queryParam("q", "warehouse").get("/api/v1/search").then().statusCode(200)
            .extract().jsonPath();
        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(hits.getList("hits.urn").contains(urn));
        assertTrue(millis < 3000, "search over 4,000 matching chunks took " + millis + " ms");
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.document_chunk WHERE asset_urn = '" + urn + "' AND seq >= 1000");
        }
    }

    @Test
    @Order(10)
    void aCommonWordDoesNotHideTheNewestDocuments() throws Exception {
        long sourceId = graph.source("docs").orElseThrow().id();
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            // 260 older documents with 20 chunks each that mention the word once: 5,200 matching chunks
            s.execute("INSERT INTO kilnmark.asset (urn, source_id, kind, container, name) SELECT "
                + "'urn:kilnmark:docs:bulk/old-' || n || '.txt', " + sourceId + ", 'document', 'bulk', 'old-' || n "
                + "|| '.txt' FROM generate_series(1, 260) n");
            s.execute("INSERT INTO kilnmark.document_chunk (asset_urn, seq, text, start_offset, end_offset) SELECT "
                + "'urn:kilnmark:docs:bulk/old-' || n || '.txt', k, 'Paragraph ' || k || ' of an older note that "
                + "mentions a sprocket once among many other ordinary words about rotas', k * 100, k * 100 + 90 "
                + "FROM generate_series(1, 260) n, generate_series(0, 19) k");
            // the newest document, added last, about the word above all
            s.execute("INSERT INTO kilnmark.asset (urn, source_id, kind, container, name) VALUES "
                + "('urn:kilnmark:docs:bulk/newest.txt', " + sourceId + ", 'document', 'bulk', 'newest.txt')");
            s.execute("INSERT INTO kilnmark.document_chunk (asset_urn, seq, text, start_offset, end_offset) SELECT "
                + "'urn:kilnmark:docs:bulk/newest.txt', k, 'sprocket sprocket sprocket: the sprocket order, sprocket "
                + "sizes and sprocket prices', k * 100, k * 100 + 80 FROM generate_series(0, 24) k");
            s.execute("ANALYZE kilnmark.document_chunk");
        }
        try {
            long started = System.nanoTime();
            JsonPath hits = admin().queryParam("q", "sprocket").get("/api/v1/search").then().statusCode(200)
                .extract().jsonPath();
            long millis = (System.nanoTime() - started) / 1_000_000;
            assertEquals("urn:kilnmark:docs:bulk/newest.txt", hits.getString("hits[0].urn"), hits.prettify());
            assertEquals(20, hits.getList("hits").size(), "older documents still fill the rest");
            assertTrue(millis < 3000, "search over 5,225 matching chunks took " + millis + " ms");
        } finally {
            try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
                s.execute("DELETE FROM kilnmark.asset WHERE urn LIKE 'urn:kilnmark:docs:bulk/%'");
            }
        }
    }

    @Test
    @Order(11)
    void turningKeepTextOffDeletesStoredTextAtTheNextScan() throws Exception {
        assertTrue(count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:docs:%'") > 0);
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE kilnmark.data_source SET settings = settings || '{\"keep_text\": \"false\"}' "
                + "WHERE name = 'docs'");
        }
        long id = admin().post("/api/v1/sources/docs/scans").then().statusCode(202).extract().jsonPath().getLong("id");
        assertEquals("succeeded", waitForRun(id).getString("status"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:docs:%'"));
        assertFalse(asset(DOCS + "remote-working-policy.pdf").getBoolean("document.textKept"));
    }

    @Test
    @Order(12)
    void deletingASourceLeavesNoTextBehind() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.data_source WHERE name = 'docs'");
        }
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:docs:%'"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document WHERE asset_urn LIKE 'urn:kilnmark:docs:%'"));
    }
}
