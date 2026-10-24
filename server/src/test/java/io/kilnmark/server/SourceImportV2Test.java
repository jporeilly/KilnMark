package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;

/** Bulk import version 2: re-runnable upserts, partial imports, host columns, remapping, tests and scans. */
@QuarkusTest
class SourceImportV2Test {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static JsonPath csv(String body, String query, int status) {
        return admin().contentType("text/csv").body(body).post("/api/v1/sources/import" + query)
            .then().statusCode(status).extract().jsonPath();
    }

    @Test
    void reRunningAFileCreatesThenLeavesUnchangedThenUpdates() {
        String file = """
            name,display_name,url,username,password,description,ai_egress,exclude
            up-one,Up one,jdbc:postgresql://one.example.com/db,ro,pw1,First,none,tmp_*;*_old
            up-two,,jdbc:postgresql://two.example.com/db,ro,pw2,,,
            """;
        JsonPath first = csv(file, "", 200);
        assertEquals(2, first.getInt("created"));
        assertTrue(first.getBoolean("applied"));
        admin().get("/api/v1/sources/up-one").then().statusCode(200)
            .body("displayName", equalTo("Up one"))
            .body("settings.ai_egress", equalTo("none"))
            .body("settings.exclude", equalTo("tmp_*;*_old"))
            .body("settings.description", equalTo("First"));
        admin().get("/api/v1/sources/up-two").then().body("displayName", equalTo("up-two"));

        JsonPath again = csv(file, "", 200);
        assertEquals(List.of("unchanged", "unchanged"), again.getList("rows.status"));
        assertEquals(0, again.getInt("created"));
        assertEquals(2, again.getInt("unchanged"));

        String changed = file.replace(",First,", ",\"First, edited\",").replace("pw2", "pw2-new");
        JsonPath third = csv(changed, "", 200);
        assertEquals(List.of("updated", "updated"), third.getList("rows.status"));
        admin().get("/api/v1/sources/up-one").then().body("settings.description", equalTo("First, edited"));

        // the password stays when a row leaves it out, unless the URL moves
        String noPassword = """
            name,url,username
            up-two,jdbc:postgresql://two.example.com/db,ro
            up-one,jdbc:postgresql://elsewhere.example.com/db,ro
            """;
        JsonPath fourth = csv(noPassword, "", 422);
        assertEquals("unchanged", fourth.getString("rows[0].status"));
        assertEquals("failed", fourth.getString("rows[1].status"));
        assertTrue(fourth.getString("rows[1].error").contains("enter the password again"));
        assertFalse(fourth.getBoolean("applied"));
        admin().get("/api/v1/sources/up-two").then().body("hasPassword", equalTo(true))
            .body("settings.description", nullValue());
    }

    @Test
    void skipAppliesTheRightRowsAndReportsTheRest() {
        String file = """
            name,kind,url,username
            skip-good,,jdbc:postgresql://g.example.com/db,ro
            Skip Bad,,jdbc:postgresql://b.example.com/db,ro
            skip-also,,jdbc:postgresql://a.example.com/db,ro
            """;
        JsonPath stop = csv(file, "", 422);
        assertEquals(1, stop.getInt("failed"));
        assertEquals(1, stop.getInt("errors"), "the earlier name still works");
        admin().get("/api/v1/sources/skip-good").then().statusCode(404);

        JsonPath skip = csv(file, "?onError=skip", 200);
        assertTrue(skip.getBoolean("applied"));
        assertEquals(2, skip.getInt("created"));
        assertEquals(1, skip.getInt("failed"));
        assertEquals("failed", skip.getString("rows[1].status"));
        admin().get("/api/v1/sources/skip-good").then().statusCode(200);
        admin().get("/api/v1/sources/skip-also").then().statusCode(200);

        assertTrue(csv(file, "?onError=maybe", 400).getString("error").contains("onError"));
        String kindChange = "name,kind,bucket\nskip-good,s3,b\n";
        assertTrue(csv(kindChange, "", 422).getString("rows[0].error").contains("kind cannot change"));
    }

    @Test
    void hostPortAndDatabaseBuildTheUrl() {
        String file = """
            name,kind,host,port,database,username
            parts-pg,postgresql,db.example.com,5433,sales,ro
            parts-sql,sqlserver,sql.example.com,,crm,ro
            parts-bad,postgresql,db.example.com,99999,sales,ro
            parts-both,postgresql,db.example.com,,sales,ro
            parts-files,files,share.example.com,,,
            parts-inject,postgresql,"evil;password=x",,db,ro
            """;
        JsonPath r = csv(file, "?onError=skip&dryRun=true", 200);
        assertEquals(List.of("created", "created", "failed", "created", "failed", "failed"), r.getList("rows.status"));
        assertTrue(r.getString("rows[2].error").startsWith("port:"));
        assertTrue(r.getString("rows[4].error").contains("do not take host"));
        assertTrue(r.getString("rows[5].error").startsWith("host:"));
        csv(file, "?onError=skip", 200);
        admin().get("/api/v1/sources/parts-pg").then()
            .body("settings.url", equalTo("jdbc:postgresql://db.example.com:5433/sales"))
            .body("settings", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("host")));
        admin().get("/api/v1/sources/parts-sql").then()
            .body("settings.url", equalTo("jdbc:sqlserver://sql.example.com:1433;databaseName=crm"));
        String both = "name,url,host\nparts-x,jdbc:postgresql://a.example.com/db,a.example.com\n";
        assertTrue(csv(both, "", 422).getString("rows[0].error").contains("not both"));
    }

    @Test
    void remapRewritesHostsOnlyWhenAsked() {
        String json = """
            [{"name": "remap-pg", "url": "jdbc:postgresql://localhost:5433/lab", "username": "lab"},
             {"name": "remap-parts", "host": "LOCALHOST", "database": "lab"},
             {"name": "remap-s3", "kind": "s3", "endpoint": "http://localhost:9000", "bucket": "raw",
              "access_key_id": "AK", "password": "secret-key"}]""";
        JsonPath r = admin().contentType("application/json").body(json).post("/api/v1/sources/import?remapHosts=true")
            .then().statusCode(200).extract().jsonPath();
        assertTrue(r.getList("rows[0].notes").toString().contains("db.internal"));
        admin().get("/api/v1/sources/remap-pg").then()
            .body("settings.url", equalTo("jdbc:postgresql://db.internal:5433/lab"));
        admin().get("/api/v1/sources/remap-parts").then()
            .body("settings.url", equalTo("jdbc:postgresql://db.internal:5432/lab"));
        admin().get("/api/v1/sources/remap-s3").then().body("settings.endpoint", equalTo("http://db.internal:9000"));

        String plain = "name,url\nremap-off,jdbc:postgresql://localhost/x\n";
        csv(plain, "", 200);
        admin().get("/api/v1/sources/remap-off").then()
            .body("settings.url", equalTo("jdbc:postgresql://localhost/x"));
    }

    @Test
    void testsAndScansEachRowWithProgressToPoll() throws Exception {
        String file = "name,url,username,password,schemas\n"
            + "batch-good," + url + "," + user + "," + password + ",batch_s\n"
            + "batch-bad," + url + "," + user + ",wrong-pw-5521,batch_s\n";
        try (var c = java.sql.DriverManager.getConnection(url, user, password); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS batch_s");
            s.execute("CREATE TABLE IF NOT EXISTS batch_s.t (id integer)");
        }
        JsonPath dry = csv(file, "?dryRun=true&test=true&scan=true", 200);
        assertNotNull(dry.get("batchId"));
        JsonPath dryBatch = waitForBatch(dry.getLong("batchId"));
        assertEquals("ok", dryBatch.getString("rows.find { it.name == 'batch-good' }.test"));
        assertEquals("skipped", dryBatch.getString("rows.find { it.name == 'batch-good' }.scan"),
            "a dry run saves nothing, so scans nothing");
        admin().get("/api/v1/sources/batch-good").then().statusCode(404);

        JsonPath r = csv(file, "?test=true&scan=true", 200);
        assertEquals(2, r.getInt("created"));
        JsonPath batch = waitForBatch(r.getLong("batchId"));
        assertEquals("finished", batch.getString("status"));
        assertEquals("ok", batch.getString("rows.find { it.name == 'batch-good' }.test"));
        assertEquals("queued", batch.getString("rows.find { it.name == 'batch-good' }.scan"));
        assertNotNull(batch.get("rows.find { it.name == 'batch-good' }.runId"));
        assertEquals("failed", batch.getString("rows.find { it.name == 'batch-bad' }.test"));
        assertEquals("skipped", batch.getString("rows.find { it.name == 'batch-bad' }.scan"));
        String problem = batch.getString("rows.find { it.name == 'batch-bad' }.problem");
        assertTrue(problem.contains("Cannot connect"), problem);
        assertFalse(problem.contains("wrong-pw-5521"));
        admin().get("/api/v1/imports/999999").then().statusCode(404).body("error", containsString("No import batch"));
    }

    private static JsonPath waitForBatch(long id) throws InterruptedException {
        for (int i = 0; i < 160; i++) {
            JsonPath b = admin().get("/api/v1/imports/" + id).then().statusCode(200)
                .body("rows", notNullValue()).extract().jsonPath();
            if (!b.getString("status").equals("running")) {
                return b;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("batch " + id + " did not finish");
    }

    @Test
    void reportsNotesAndStatusesForOldFiles() {
        JsonPath r = csv("name,url,password_ref,schemas\nold-style,jdbc:postgresql://o.example.com/db,,a;b\n", "", 200);
        assertEquals("created", r.getString("rows[0].status"));
        admin().get("/api/v1/sources/old-style").then().body("settings.schemas", equalTo("a,b"));
        admin().get("/api/v1/sources").then().body("name", hasItem("old-style"));
    }
}
