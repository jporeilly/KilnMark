package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.designer;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Two folder or bucket sources over the same files keep separate marks, so a column marked sensitive
 * in one would show unmasked in the other: a second one is refused for everyone, designers with the
 * unmask grant and admins included, whether or not the first has marks yet (ADR 0027).
 */
@QuarkusTest
class SourceOverlapTest {

    private static final String MARKED = "overlap-marked";

    @Inject
    AgroalDataSource db;

    /** Folders sources may read in tests: under %test.kilnmark.files.roots. */
    private static Path folder(String prefix) throws java.io.IOException {
        Path roots = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files"));
        return Files.createTempDirectory(roots, prefix);
    }

    @Test
    void foldersAndPrefixesOverlapOneInsideTheOther() {
        assertTrue(SourceService.sameFiles("files", Map.of("path", "/srv/hr"), Map.of("path", "/srv/hr/pay")));
        assertTrue(SourceService.sameFiles("files", Map.of("path", "/srv/hr/pay"), Map.of("path", "/srv/hr/")));
        assertTrue(SourceService.sameFiles("files", Map.of("path", "/srv/hr/../hr"), Map.of("path", "/srv/hr")));
        assertFalse(SourceService.sameFiles("files", Map.of("path", "/srv/hr"), Map.of("path", "/srv/hrx")));
        assertFalse(SourceService.sameFiles("files", Map.of("path", "/srv/hr"), Map.of("path", "/srv/sales")));

        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "b"), Map.of("bucket", "b", "prefix", "raw/")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "b", "endpoint", "http://s3.local:9000/"),
            Map.of("bucket", "b", "endpoint", "HTTP://S3.local:9000")));
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", "b", "prefix", "raw/"),
            Map.of("bucket", "b", "prefix", "clean/")));
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", "b"), Map.of("bucket", "c")));
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", "b", "endpoint", "http://one"),
            Map.of("bucket", "b", "endpoint", "http://two")));
        assertFalse(SourceService.sameFiles("postgresql", Map.of("path", "/x"), Map.of("path", "/x")));
        // Case is ignored; every way of naming AWS's own service is one endpoint; default ports drop.
        assertTrue(SourceService.sameFiles("files", Map.of("path", "/srv/HR"), Map.of("path", "/srv/hr/pay")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "b"),
            Map.of("bucket", "b", "endpoint", "https://s3.us-east-1.amazonaws.com")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "b", "endpoint", "https://minio.local"),
            Map.of("bucket", "b", "endpoint", "https://minio.local:443/")));
    }

    @Test
    void aLinkIsFollowedBeforeFoldersAreCompared() throws Exception {
        Path real = Files.createTempDirectory("overlap-real");
        Path link = real.resolveSibling(real.getFileName() + "-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // no links on this file system
        }
        try {
            assertTrue(SourceService.sameFiles("files", Map.of("path", link.toString()), Map.of("path", real.toString())));
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void anOverlapIsRefusedEvenBeforeAnythingIsMarked() throws Exception {
        Path root = folder("overlap-unmarked");
        Path inside = Files.createDirectories(root.resolve("sub"));
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "overlap-first", "kind", "files", "settings", Map.of("path", root.toString())))
            .post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "overlap-second", "kind", "files", "settings", Map.of("path", inside.toString())))
            .post("/api/v1/sources").then().statusCode(403).body("error", containsString("overlap-first already reads"));
    }

    @Test
    void aSecondSourceOverMarkedFilesIsRefusedForEveryone() throws Exception {
        Path root = folder("overlap");
        Path inside = Files.createDirectories(root.resolve("pay"));
        Path elsewhere = folder("overlap-other");
        String urn = "urn:kilnmark:" + MARKED + ":pay/salaries.csv";
        try (Connection c = db.getConnection(); Statement s = c.createStatement();
             PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.data_source (name, display_name, kind, "
                 + "settings) VALUES (?, 'Marked', 'files', ?::jsonb)")) {
            ps.setString(1, MARKED);
            ps.setString(2, "{\"path\": \"" + root.toString().replace("\\", "\\\\") + "\"}");
            ps.executeUpdate();
            s.execute("INSERT INTO kilnmark.asset (urn, source_id, kind, container, name) VALUES ('" + urn
                + "', (SELECT id FROM kilnmark.data_source WHERE name = '" + MARKED + "'), 'table', 'pay', "
                + "'salaries.csv')");
            s.execute("INSERT INTO kilnmark.field (asset_urn, name, position, source_type, kind, nullable) VALUES ('"
                + urn + "', 'salary', 1, 'number', 'float64', true)");
            s.execute("INSERT INTO kilnmark.column_mark (asset_urn, name) VALUES ('" + urn + "', 'salary')");
        }

        for (var who : new io.restassured.specification.RequestSpecification[] {admin(), designer()}) {
            who.contentType(ContentType.JSON)
                .body(Map.of("name", "overlap-copy", "kind", "files", "settings", Map.of("path", inside.toString())))
                .post("/api/v1/sources").then().statusCode(403)
                .body("error", containsString(MARKED + " already reads these files"));
        }
        // Deleting the marked source would clear its marks: admins only.
        designer().delete("/api/v1/sources/" + MARKED).then().statusCode(403)
            .body("error", containsString("only an admin can delete it"));
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "overlap-apart", "kind", "files", "settings", Map.of("path", elsewhere.toString())))
            .post("/api/v1/sources").then().statusCode(201);
    }

    @AfterEach
    void drop() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.data_source WHERE name IN ('" + MARKED + "', 'overlap-copy', "
                + "'overlap-apart', 'overlap-first', 'overlap-second')");
        }
    }
}
