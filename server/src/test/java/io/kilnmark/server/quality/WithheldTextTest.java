package io.kilnmark.server.quality;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static io.kilnmark.server.Tokens.designer;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * A workbook's text holds its sheets' cells (container test, run in CI). When a sheet has a column
 * marked sensitive, the workbook's text, find and chunks are for designers only (ADR 0027: not
 * admins), and its text is not
 * matched by others' searches at all; the same up the chain, for a mail whose attached workbook's
 * sheet is marked. The catalogue rows are written directly: a synthetic workbook, sheet, mail and
 * attachment, with chunks of text.
 */
@QuarkusTest
class WithheldTextTest {

    private static final String SOURCE = "withheld-text";
    private static final String BOOK = "urn:kilnmark:withheld-text:books/plan.xlsx";
    private static final String SHEET = BOOK + "#Pay";
    private static final String MAIL = "urn:kilnmark:withheld-text:mail/offer.eml";
    private static final String ATTACHED = MAIL + "#att/terms.xlsx";
    private static final String ATTACHED_SHEET = ATTACHED + "#Rates";
    private static final String OPEN = "urn:kilnmark:withheld-text:notes/readme.txt";

    @Inject
    AgroalDataSource db;

    private String steward;

    private void sql(String... statements) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    @BeforeEach
    void load() throws Exception {
        String src = "(SELECT id FROM kilnmark.data_source WHERE name = '" + SOURCE + "')";
        sql("INSERT INTO kilnmark.data_source (name, display_name, kind) VALUES ('" + SOURCE + "', 'Withheld', 'files')",
            "INSERT INTO kilnmark.asset (urn, source_id, kind, container, name) VALUES "
                + "('" + BOOK + "', " + src + ", 'document', 'books', 'plan.xlsx'), "
                + "('" + MAIL + "', " + src + ", 'document', 'mail', 'offer.eml'), "
                + "('" + OPEN + "', " + src + ", 'document', 'notes', 'readme.txt')",
            "INSERT INTO kilnmark.asset (urn, source_id, kind, container, name, parent_urn) VALUES "
                + "('" + SHEET + "', " + src + ", 'table', 'books', 'Pay', '" + BOOK + "'), "
                + "('" + ATTACHED + "', " + src + ", 'document', 'mail', 'terms.xlsx', '" + MAIL + "'), "
                + "('" + ATTACHED_SHEET + "', " + src + ", 'table', 'mail', 'Rates', '" + ATTACHED + "')",
            "INSERT INTO kilnmark.field (asset_urn, name, position, source_type, kind, nullable) VALUES "
                + "('" + SHEET + "', 'salary', 1, 'number', 'float64', true), "
                + "('" + ATTACHED_SHEET + "', 'rate', 1, 'number', 'float64', true)",
            "INSERT INTO kilnmark.document_chunk (asset_urn, seq, text, start_offset, end_offset) VALUES "
                + "('" + BOOK + "', 0, 'Pay sheet: zanzibarite earns 91000', 0, 34), "
                + "('" + MAIL + "', 0, 'Offer for zanzibarite at the attached rates', 0, 43), "
                + "('" + OPEN + "', 0, 'Readme about zanzibarite onboarding', 0, 35)",
            "INSERT INTO kilnmark.column_mark (asset_urn, name) VALUES ('" + SHEET + "', 'salary'), "
                + "('" + ATTACHED_SHEET + "', 'rate')");
        steward = admin().contentType(ContentType.JSON).body(Map.of("name", "withheld-steward", "role", "steward"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    @AfterEach
    void drop() throws Exception {
        sql("DELETE FROM kilnmark.data_source WHERE name = '" + SOURCE + "'");
    }

    @Test
    void aWorkbookWithAMarkedSheetKeepsItsTextForDesigners() {
        for (String urn : new String[] {BOOK, MAIL}) {
            as(steward).queryParam("urn", urn).get("/api/v1/documents/text").then().statusCode(403)
                .body("error", containsString("sensitive"));
            as(steward).queryParam("urn", urn).queryParam("q", "zanzibarite").get("/api/v1/documents/find").then()
                .statusCode(403);
            as(steward).queryParam("urn", urn).get("/api/v1/documents/chunks").then().statusCode(403);
            admin().queryParam("urn", urn).get("/api/v1/documents/chunks").then().statusCode(403);
            assertNotEquals(403, designer().queryParam("urn", urn).get("/api/v1/documents/chunks").statusCode(),
                "designers read it");
        }
        assertNotEquals(403, as(steward).queryParam("urn", OPEN).get("/api/v1/documents/chunks").statusCode(),
            "a document without marks is open");

        // Not matched at all for others: no hit, so a search cannot tell what the text says.
        as(steward).queryParam("q", "zanzibarite").queryParam("mode", "keyword").get("/api/v1/search").then()
            .statusCode(200).body("hits.urn", not(hasItem(BOOK))).body("hits.urn", not(hasItem(MAIL)))
            .body("hits.urn", hasItem(OPEN));
        as(steward).contentType(ContentType.JSON).body(Map.of("question", "zanzibarite")).post("/api/v1/search/ask")
            .then().statusCode(200).body("hits.urn", not(hasItem(BOOK))).body("hits.urn", not(hasItem(MAIL)));
        admin().queryParam("q", "zanzibarite").queryParam("mode", "keyword").get("/api/v1/search").then()
            .statusCode(200).body("hits.urn", not(hasItem(BOOK))).body("hits.urn", not(hasItem(MAIL)));
        String designerHits = designer().queryParam("q", "zanzibarite").queryParam("mode", "keyword")
            .get("/api/v1/search").then().statusCode(200).extract().asString();
        assertEquals(true, designerHits.contains(BOOK) && designerHits.contains(MAIL), designerHits);
    }
}
