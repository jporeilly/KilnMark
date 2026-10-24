package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** Bulk registration of sources from CSV and JSON. */
@QuarkusTest
class SourceImportTest {

    private static final String CSV = """
        name,url,username,password,schemas
        sales-eu,jdbc:postgresql://eu.example.com:5432/sales,reader,"pa,ss""word",public;orders
        sales-us,jdbc:postgresql://us.example.com:5432/sales,reader,secret,
        """;

    @Test
    void aDryRunChecksWithoutCreating() {
        admin().contentType("text/csv").body(CSV).queryParam("dryRun", true)
            .post("/api/v1/sources/import")
            .then().statusCode(200)
            .body("dryRun", equalTo(true))
            .body("created", equalTo(0))
            .body("rows.status", hasItems("created"));
        admin().get("/api/v1/sources/sales-eu").then().statusCode(404);
    }

    @Test
    void aBadRowStopsTheWholeImportAndSaysWhere() {
        String csv = """
            name,url,password_ref
            good-one,jdbc:postgresql://a.example.com/db,
            Bad Name,jdbc:postgresql://b.example.com/db,
            no-env,jdbc:postgresql://c.example.com/db,env:KILNMARK_TEST_NO_SUCH_VARIABLE
            twice,jdbc:postgresql://d.example.com/db,
            twice,jdbc:postgresql://e.example.com/db,
            sneaky,jdbc:postgresql://f.example.com/db,secret:../../etc/passwd
            """;
        admin().contentType("text/csv").body(csv)
            .post("/api/v1/sources/import")
            .then().statusCode(422)
            .body("created", equalTo(0))
            .body("errors", equalTo(4))
            .body("rows.find { it.line == 3 }.error", containsString("name"))
            .body("rows.find { it.line == 4 }.error", containsString("no environment variable"))
            .body("rows.find { it.line == 6 }.error", containsString("more than once"))
            .body("rows.find { it.line == 7 }.error", containsString("not a valid secret name"));
        admin().get("/api/v1/sources/good-one").then().statusCode(404);
    }

    @Test
    void createsEverySourceWithQuotedValuesIntact() {
        admin().contentType("text/csv").body(CSV)
            .post("/api/v1/sources/import")
            .then().statusCode(200).body("created", equalTo(2));
        admin().get("/api/v1/sources/sales-eu").then().statusCode(200)
            .body("hasPassword", equalTo(true))
            .body("settings.schemas", equalTo("public,orders"))
            .body("settings.username", equalTo("reader"));
        admin().get("/api/v1/sources/sales-us").then().statusCode(200).body("settings.schemas", equalTo(null));
    }

    @Test
    void acceptsAJsonArray() {
        String body = """
            [{"name": "hr-db", "url": "jdbc:postgresql://hr.example.com/hr", "username": "ro",
              "password": "x", "schemas": ["people"]},
             {"name": "ops-db", "url": "jdbc:postgresql://ops.example.com/ops"}]""";
        admin().contentType("application/json").body(body)
            .post("/api/v1/sources/import")
            .then().statusCode(200).body("created", equalTo(2));
        admin().get("/api/v1/sources/hr-db").then().statusCode(200)
            .body("settings.username", equalTo("ro"))
            .body("settings.schemas", equalTo("people"));
    }

    @Test
    void rejectsAFileWithoutTheRequiredColumns() {
        admin().contentType("text/csv").body("host,port\nx,5432\n")
            .post("/api/v1/sources/import")
            .then().statusCode(400).body("error", containsString("'name'"));
    }
}
