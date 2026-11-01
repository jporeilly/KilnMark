package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;

/** Looking before scanning: lists of tables and files, and previews of their first rows. */
@QuarkusTest
class SourcePreviewTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static String designer;
    private static String viewer;
    private static Path folder;

    @BeforeEach
    void setUp() throws Exception {
        if (designer != null) {
            return;
        }
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA prev");
            s.execute("CREATE TABLE prev.people (id integer PRIMARY KEY, name text, photo bytea, notes text)");
            s.execute("INSERT INTO prev.people SELECT n, 'person ' || n, CASE WHEN n = 1 THEN '\\xcafe'::bytea END, "
                + "CASE WHEN n = 2 THEN repeat('x', 700) END FROM generate_series(1, 250) n");
            s.execute("CREATE TABLE prev.other (id integer)");
        }
        folder = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files",
            "preview"));
        Files.writeString(folder.resolve("data.csv"), "1;alpha\n2;beta\n3;gamma\n");
        Files.writeString(folder.resolve(".hidden.csv"), "secret\n1\n");
        Files.createDirectories(folder.resolve("letters"));
        Files.writeString(folder.resolve("letters/note.txt"), "hello");
        designer = token("prev-designer", "designer");
        viewer = token("prev-viewer", "viewer");
        admin().contentType(ContentType.JSON).body(Map.of("name", "prev-db", "password", password, "settings",
            Map.of("url", url, "username", user))).post("/api/v1/sources").then().statusCode(201);
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static RequestSpecification designer() {
        return Tokens.as(designer);
    }

    @Test
    void previewsTheFirstRowsOfATableWithColumnsAndKinds() {
        JsonPath p = designer().queryParam("asset", "prev.people").queryParam("rows", 5)
            .get("/api/v1/sources/prev-db/preview").then().statusCode(200)
            .header("Cache-Control", containsString("no-store")).extract().jsonPath();
        assertEquals(5, p.getInt("rowCount"));
        assertEquals(List.of("id", "name", "photo", "notes"), p.getList("columns.name"));
        assertEquals("int32", p.getString("columns[0].kind"));
        assertEquals("1", p.getString("rows[0][0]"));
        assertEquals("0xCAFE (2 bytes)", p.getString("rows[0][2]"), "bytes as hex with their length");
        assertEquals(null, p.getString("rows[1][2]"), "null stays null");
        assertTrue(p.getString("rows[1][3]").startsWith("x".repeat(500) + "…"), "long values are cut");
        assertFalse(p.getBoolean("truncated"));

        designer().queryParam("asset", "prev.people").get("/api/v1/sources/prev-db/preview")
            .then().statusCode(200).body("rowCount", equalTo(100));
        designer().queryParam("asset", "prev.people").queryParam("rows", 1001).get("/api/v1/sources/prev-db/preview")
            .then().statusCode(400).body("error", containsString("rows"));
        designer().queryParam("asset", "prev.nothing").get("/api/v1/sources/prev-db/preview")
            .then().statusCode(502).body("error", containsString("prev.nothing"));
    }

    @Test
    void listsAContainersTablesInPages() {
        designer().queryParam("container", "prev").queryParam("limit", 1)
            .get("/api/v1/sources/prev-db/assets").then().statusCode(200)
            .body("total", equalTo(2)).body("items[0].path", equalTo("prev.other"));
        designer().queryParam("container", "prev").queryParam("offset", 1)
            .get("/api/v1/sources/prev-db/assets").then().statusCode(200)
            .body("items[0].name", equalTo("people"));
    }

    @Test
    void viewersCannotPreview() {
        Tokens.as(viewer).queryParam("asset", "prev.people").get("/api/v1/sources/prev-db/preview")
            .then().statusCode(403);
        Tokens.as(viewer).queryParam("container", "prev").get("/api/v1/sources/prev-db/assets").then().statusCode(403);
        Tokens.as(viewer).contentType(ContentType.JSON).body(Map.of("settings", Map.of("url", url)))
            .post("/api/v1/drafts/preview?asset=prev.people").then().statusCode(403);
    }

    @Test
    void aCsvWithoutAHeaderCanBePreviewedWithTheRightSettings() throws Exception {
        Map<String, Object> draft = new HashMap<>(Map.of("kind", "files", "settings",
            Map.of("path", folder.toString())));
        JsonPath detected = designer().contentType(ContentType.JSON).body(draft)
            .post("/api/v1/drafts/preview?asset=data.csv").then().statusCode(200).extract().jsonPath();
        assertEquals("true", detected.getString("details.header"));
        assertEquals(2, detected.getInt("rowCount"), "the first row taken as names");

        draft.put("settings", Map.of("path", folder.toString(), "delimiter", ";", "header", "false"));
        JsonPath p = designer().contentType(ContentType.JSON).body(draft)
            .post("/api/v1/drafts/preview?asset=data.csv").then().statusCode(200).extract().jsonPath();
        assertEquals(List.of("column_1", "column_2"), p.getList("columns.name"));
        assertEquals(3, p.getInt("rowCount"));
        assertEquals("alpha", p.getString("rows[0][1]"));
        assertEquals("false", p.getString("details.header"));
        assertEquals(";", p.getString("details.delimiter"));
        assertEquals("csv", p.getString("details.format"));

        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/assets?container=/")
            .then().statusCode(200).body("items.path", hasItem("data.csv"));
        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/assets?container=letters")
            .then().statusCode(200).body("items[0].readable", equalTo(false));
        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/preview?asset=.hidden.csv")
            .then().statusCode(502).body("error", containsString("hidden"));
        draft.put("settings", Map.of("path", folder.toString(), "recursive", "false"));
        Files.createDirectories(folder.resolve("sub"));
        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/preview?asset=letters/note.txt")
            .then().statusCode(502).body("error", containsString("sub-folders"));
        draft.put("settings", Map.of("path", folder.toString(), "delimiter", "ab"));
        designer().contentType(ContentType.JSON).body(draft).post("/api/v1/drafts/preview?asset=data.csv")
            .then().statusCode(400).body("error", containsString("delimiter"));
    }

    @Test
    void aSilentServerOrAWrongPasswordEndsAPreviewWithoutTheSecret() throws Exception {
        String secret = "prev-secret-0042";
        String error = designer().contentType(ContentType.JSON).body(Map.of("kind", "postgresql", "password", secret,
                "settings", Map.of("url", url, "username", user)))
            .post("/api/v1/drafts/preview?asset=prev.people").then().statusCode(502).extract().jsonPath()
            .getString("error");
        assertFalse(error.contains(secret), error);
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50,
            java.net.InetAddress.getLoopbackAddress())) {
            long start = System.nanoTime();
            designer().contentType(ContentType.JSON).body(Map.of("kind", "postgresql", "password", "pw",
                    "settings", Map.of("url", "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/x",
                        "username", "u")))
                .post("/api/v1/drafts/preview?asset=a.b").then().statusCode(502);
            long ms = (System.nanoTime() - start) / 1_000_000;
            assertTrue(ms < 6000, "took " + ms + " ms");
        }
    }

    @Test
    void theCatalogueGroupsConnectorsByCategory() {
        JsonPath c = Tokens.as(viewer).get("/api/v1/connectors/catalogue").then().statusCode(200)
            .extract().jsonPath();
        assertTrue(c.getBoolean("showPlanned"));
        assertEquals(List.of("databases", "nosql", "files", "streaming", "applications", "lakehouse"),
            c.getList("categories.id"));
        // Test-only connectors (kind test-...) are registered on the test class path; leave them out.
        assertEquals(List.of("jdbc", "mariadb", "mysql", "oracle", "postgresql", "sqlserver"),
            c.<String>getList("categories[0].connectors.kind").stream()
            .filter(k -> !k.startsWith("test-")).toList());
        String pg = "categories[0].connectors.find { it.kind == 'postgresql' }";
        assertEquals(List.of("discover", "read", "write"), c.getList(pg + ".capabilities"));
        assertTrue(c.getBoolean(pg + ".addressFields"));
        assertTrue(c.getBoolean(pg + ".driverBundled"));
        String maria = "categories[0].connectors.find { it.kind == 'mariadb' }";
        assertFalse(c.getBoolean(maria + ".addressFields"), "host, port and database are its own settings");
        assertTrue(c.getBoolean(maria + ".driverBundled"));
        assertEquals(List.of("files", "s3", "sample-data"), c.getList("categories[2].connectors.kind"));
        assertTrue(c.getList("categories[2].connectors[0].formats").contains("parquet"));
        assertEquals("object storage", c.getString("categories[2].connectors[1].family"));
        assertTrue(c.getList("categories[0].planned.name").contains("IBM Db2"));
        assertFalse(c.getList("categories[0].planned.name").contains("Generic JDBC"), "built now");
        String jdbc = "categories[0].connectors.find { it.kind == 'jdbc' }";
        assertEquals(List.of("discover", "read", "write"), c.getList(jdbc + ".capabilities"));
        assertFalse(c.getBoolean(jdbc + ".addressFields"), "a JDBC URL is the driver's own business");
        assertTrue(c.getList("categories[3].connectors").isEmpty(), "nothing built for streaming yet");
        Tokens.as(viewer).get("/api/v1/connectors").then().statusCode(200)
            .body("find { it.kind == 's3' }.category", equalTo("files"))
            .body("find { it.kind == 'postgresql' }.settings.find { it.key == 'url' }.example",
                containsString("jdbc:postgresql://"));
        Tokens.as(viewer).queryParam("host", "db.example.com").queryParam("port", "5433").queryParam("database", "x")
            .get("/api/v1/connectors/postgresql/address").then().statusCode(200)
            .body("url", equalTo("jdbc:postgresql://db.example.com:5433/x"));
        Tokens.as(viewer).queryParam("host", "share").get("/api/v1/connectors/files/address").then().statusCode(400);
    }
}
