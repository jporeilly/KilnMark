package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.designer;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
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
    void anAccessPointMayBeAnyBucketAtItsEndpoint() {
        String alias = "payroll-ap-abc123def456ghi789jkl0mnopq-s3alias";
        String arn = "arn:aws:s3:eu-west-2:123456789012:accesspoint/payroll-ap";
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", alias), Map.of("bucket", "hr")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "hr", "prefix", "pay/"),
            Map.of("bucket", arn, "prefix", "raw/")), "an access point refuses whatever the prefixes");
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", alias.toUpperCase(java.util.Locale.ROOT)),
            Map.of("bucket", arn, "endpoint", "https://s3.eu-west-2.amazonaws.com")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "mfzwi23gnjvgw.mrap"), Map.of("bucket", "hr")));
        // At another endpoint they cannot be the same bucket.
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", alias),
            Map.of("bucket", "hr", "endpoint", "http://minio.local:9000")));
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", "hr-s3aliases"), Map.of("bucket", "hr")));
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", arn), Map.of("prefix", "raw/")));
        // Object Lambda and Outposts aliases are access points too.
        for (String other : List.of("payroll-ol-abc123def456ghi789jkl0mnopq--ol-s3",
            "payroll-op-0123456789abcdef0abc123def456ghi789jkl0mn--op-s3")) {
            assertTrue(SourceService.accessPoint(other), other);
            assertTrue(SourceService.sameFiles("s3", Map.of("bucket", other, "prefix", "a/"),
                Map.of("bucket", "hr", "prefix", "b/")), other);
        }
        assertFalse(SourceService.accessPoint("hr--x-s3"), "a directory bucket is a bucket");
    }

    @Test
    void anEndpointHostThatNamesABucketOrAccessPointIsAnAccessPoint() {
        for (String host : List.of("https://payroll.s3.eu-west-2.amazonaws.com", "https://payroll.s3.amazonaws.com",
            "payroll.s3-eu-west-2.amazonaws.com", "https://s3-data.s3.dualstack.eu-west-2.amazonaws.com",
            "https://payroll-ap-123456789012.s3-accesspoint.eu-west-2.amazonaws.com",
            "https://payroll-ap-123456789012.s3-accesspoint.dualstack.eu-west-2.amazonaws.com",
            "https://payroll-olap-123456789012.s3-object-lambda.eu-west-2.amazonaws.com",
            "https://mfzwi23gnjvgw.mrap.accesspoint.s3-global.amazonaws.com",
            "https://bucket.vpce-1a2b3c4d-5e6f.s3.eu-west-2.vpce.amazonaws.com",
            "https://payroll.s3-website-eu-west-2.amazonaws.com", "https://payroll.s3.cn-north-1.amazonaws.com.cn")) {
            assertTrue(SourceService.namesBucket(host), host);
            assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "x", "endpoint", host, "prefix", "a/"),
                Map.of("bucket", "hr", "prefix", "b/")), host);
            assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "hr"),
                Map.of("bucket", "arn:aws:s3:us-east-1:123456789012:accesspoint/x", "endpoint", host)), host);
        }
        for (String host : List.of("https://s3.amazonaws.com", "https://s3.eu-west-2.amazonaws.com",
            "s3-eu-west-2.amazonaws.com", "https://s3.dualstack.eu-west-2.amazonaws.com",
            "https://s3-fips.us-gov-west-1.amazonaws.com", "https://s3-fips.dualstack.us-east-1.amazonaws.com",
            "https://s3-external-1.amazonaws.com", "https://s3.cn-north-1.amazonaws.com.cn",
            "https://s3.eu-west-2.amazonaws.com.", "http://minio.local:9000", "https://payroll.minio.local")) {
            assertFalse(SourceService.namesBucket(host), host);
        }
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", "hr", "endpoint",
            "https://s3.eu-west-2.amazonaws.com"), Map.of("bucket", "sales")), "a plain service host names no bucket");
        // A trailing dot names the same host.
        assertEquals("aws", SourceService.endpoint("https://payroll.s3.eu-west-2.amazonaws.com."));
        assertEquals("minio.local:9000", SourceService.endpoint("http://MINIO.local.:9000/"));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "b", "endpoint", "https://minio.local."),
            Map.of("bucket", "b", "endpoint", "https://minio.local")));
        assertTrue(SourceService.namesBucket("https://payroll.s3.eu-west-2.amazonaws.com."));
        assertEquals("aws", SourceService.endpoint("https://s3.cn-north-1.amazonaws.com.cn"));
    }

    @Test
    void twoAccessPointArnsInDifferentRegionsOrPartitionsAreApart() {
        String london = "arn:aws:s3:eu-west-2:123456789012:accesspoint/payroll";
        String londonOther = "arn:aws:s3:eu-west-2:210987654321:accesspoint/sales";
        String virginia = "arn:aws:s3:us-east-1:123456789012:accesspoint/payroll";
        String china = "arn:aws-cn:s3:eu-west-2:123456789012:accesspoint/payroll";
        String lambda = "arn:aws:s3-object-lambda:us-east-1:123456789012:accesspoint/payroll-olap";
        String global = "arn:aws:s3::123456789012:accesspoint/mfzwi23gnjvgw.mrap";
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", london), Map.of("bucket", virginia)),
            "an access point reads only a bucket in its own region");
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", london), Map.of("bucket", china)),
            "nor outside its partition");
        assertFalse(SourceService.sameFiles("s3", Map.of("bucket", london), Map.of("bucket", lambda)));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", london), Map.of("bucket", londonOther)),
            "another account's access point may front the same bucket");
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", virginia), Map.of("bucket", lambda)),
            "an Object Lambda access point reads through one in its region");
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", london, "prefix", "a/"),
            Map.of("bucket", london.toUpperCase(java.util.Locale.ROOT), "prefix", "b/")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", global), Map.of("bucket", london)),
            "a multi-Region access point names no region");
        // Kept conservative: an alias or a bucket name beside an ARN, whatever its region.
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", london),
            Map.of("bucket", "payroll-ap-abc123def456ghi789jkl0mnopq-s3alias")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", london),
            Map.of("bucket", "hr", "region", "us-east-1")));
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", "arn:aws:s3:eu-west-2"), Map.of("bucket", virginia)),
            "an ARN Kilnmark cannot read may be anything");
        // and a host that names an access point is not told apart by the ARN beside it
        assertTrue(SourceService.sameFiles("s3", Map.of("bucket", london, "endpoint",
            "https://payroll-123456789012.s3-accesspoint.eu-west-2.amazonaws.com"), Map.of("bucket", virginia)));
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

    private static JsonPath importJson(String body, String query, int status) {
        return admin().contentType(ContentType.JSON).body(body).post("/api/v1/sources/import" + query)
            .then().statusCode(status).extract().jsonPath();
    }

    private static String filesRow(String name, Path path) {
        return "{\"name\": \"" + name + "\", \"kind\": \"files\", \"path\": \""
            + path.toString().replace("\\", "\\\\") + "\"}";
    }

    @Test
    void twoRowsOfOneImportOverTheSameFilesAreRefused() throws Exception {
        Path root = folder("overlap-rows");
        Path inside = Files.createDirectories(root.resolve("sub"));
        // One folder inside the other, and the very same folder: the later row fails, in a dry run too.
        for (Path second : List.of(inside, root)) {
            String file = "[" + filesRow("overlap-row-one", root) + ", " + filesRow("overlap-row-two", second) + "]";
            JsonPath dry = importJson(file, "?dryRun=true", 200);
            assertEquals(List.of("created", "failed"), dry.getList("rows.status"));
            assertTrue(dry.getString("rows[1].error").contains("read the same files"), dry.getString("rows[1].error"));
            JsonPath real = importJson(file, "", 422);
            assertFalse(real.getBoolean("applied"));
            assertTrue(real.getString("rows[1].error").contains("read the same files"), real.getString("rows[1].error"));
            admin().get("/api/v1/sources/overlap-row-one").then().statusCode(404);
        }
    }

    @Test
    void anUpdateRowMovingASourceOntoAnotherRowsFolderIsRefused() throws Exception {
        Path root = folder("overlap-move");
        Path apart = folder("overlap-move-apart");
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "overlap-row-moved", "kind", "files", "settings", Map.of("path", apart.toString())))
            .post("/api/v1/sources").then().statusCode(201);
        String file = "[" + filesRow("overlap-row-new", root) + ", "
            + filesRow("overlap-row-moved", Files.createDirectories(root.resolve("sub"))) + "]";
        JsonPath r = importJson(file, "?dryRun=true", 200);
        assertEquals(List.of("created", "failed"), r.getList("rows.status"));
        assertTrue(r.getString("rows[1].error").contains("read the same files"), r.getString("rows[1].error"));
        importJson(file, "", 422);
        admin().get("/api/v1/sources/overlap-row-new").then().statusCode(404);
        admin().get("/api/v1/sources/overlap-row-moved").then().statusCode(200)
            .body("settings.path", org.hamcrest.Matchers.equalTo(apart.toString()));
    }

    @AfterEach
    void drop() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.data_source WHERE name IN ('" + MARKED + "', 'overlap-copy', "
                + "'overlap-apart', 'overlap-first', 'overlap-second', 'overlap-row-one', 'overlap-row-two', "
                + "'overlap-row-new', 'overlap-row-moved')");
        }
    }
}
