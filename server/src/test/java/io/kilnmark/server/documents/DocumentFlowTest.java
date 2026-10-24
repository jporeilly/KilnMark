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
            for (String source : List.of("docs", "meta")) {
                Path dir = Files.createDirectories(ROOT.resolve(source));
                try (var lab = Files.list(Path.of("../lab/files/share/documents"))) {
                    for (Path f : lab.toList()) {
                        Files.copy(f, dir.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Files.writeString(ROOT.resolve("meta/private-note.txt"), "The quarterly zebra census is kept private.\n");
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
        assertEquals(8, run.getInt("assetsSeen"), run.getString("detail")); // 5 files and 3 sheets
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
        admin().queryParam("urn", note).get("/api/v1/documents/text").then().statusCode(200)
            .body("extractedNow", equalTo(true)).body("text", containsString("zebra census"));
        String attachment = "urn:kilnmark:meta:2025-04-delivery-schedule.eml#attachments/1/may-schedule.md";
        admin().queryParam("urn", attachment).get("/api/v1/documents/text").then().statusCode(200)
            .body("text", containsString("May delivery schedule"));
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
    @Order(11)
    void deletingASourceLeavesNoTextBehind() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.data_source WHERE name = 'docs'");
        }
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document_chunk WHERE asset_urn LIKE 'urn:kilnmark:docs:%'"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.document WHERE asset_urn LIKE 'urn:kilnmark:docs:%'"));
    }
}
