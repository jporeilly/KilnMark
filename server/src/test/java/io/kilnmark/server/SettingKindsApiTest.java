package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Setting kinds through the API: the connectors and common settings say each setting's kind,
 * choices and default; a save checks values by kind; and values saved before kinds still load.
 */
@QuarkusTest
class SettingKindsApiTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    void connectorsSayEachSettingsKind() {
        admin().get("/api/v1/connectors").then().statusCode(200)
            .body("find { it.kind == 'sqlserver' }.settings.find { it.key == 'trust_server_certificate' }.kind",
                equalTo("boolean"))
            .body("find { it.kind == 'sqlserver' }.settings.find { it.key == 'trust_server_certificate' }.defaultValue",
                equalTo("false"))
            .body("find { it.kind == 'sqlserver' }.settings.find { it.key == 'isolation' }.kind", equalTo("choice"))
            .body("find { it.kind == 'sqlserver' }.settings.find { it.key == 'isolation' }.choices",
                contains("read-uncommitted", "snapshot", "read-committed"))
            .body("find { it.kind == 'postgresql' }.settings.find { it.key == 'url' }.kind", equalTo("url"))
            .body("find { it.kind == 'postgresql' }.settings.find { it.key == 'username' }.kind", equalTo("text"))
            .body("find { it.kind == 'postgresql' }.settings.find { it.key == 'username' }.defaultValue", nullValue())
            .body("find { it.kind == 'files' }.settings.find { it.key == 'header' }.kind", equalTo("boolean"))
            .body("find { it.kind == 'files' }.settings.find { it.key == 'header' }.defaultValue", equalTo("true"))
            .body("find { it.kind == 's3' }.settings.find { it.key == 'path_style' }.kind", equalTo("boolean"))
            .body("find { it.kind == 's3' }.settings.find { it.key == 'endpoint' }.kind", equalTo("url"))
            // still there for older clients
            .body("find { it.kind == 's3' }.settings.find { it.key == 'path_style' }.hint", containsString("off for AWS"));
        admin().get("/api/v1/common-settings").then().statusCode(200)
            .body("find { it.key == 'allow_writing' }.kind", equalTo("boolean"))
            .body("find { it.key == 'keep_samples' }.kind", equalTo("boolean"))
            .body("find { it.key == 'keep_samples' }.defaultValue", equalTo("true"))
            .body("find { it.key == 'sample_rows' }.kind", equalTo("number"))
            .body("find { it.key == 'ai_egress' }.kind", equalTo("choice"))
            .body("find { it.key == 'description' }.kind", equalTo("text"));
    }

    private Map<String, Object> source(String name, Map<String, String> extra) {
        Map<String, String> settings = new HashMap<>(Map.of("url", url, "username", user));
        settings.putAll(extra);
        return new HashMap<>(Map.of("name", name, "kind", "postgresql", "settings", settings, "password", password));
    }

    @Test
    void aSaveChecksValuesByKind() {
        admin().contentType(ContentType.JSON).body(source("kinds-bad", Map.of("keep_samples", "yes")))
            .post("/api/v1/sources").then().statusCode(400)
            .body("error", equalTo("keep_samples: true or false, not 'yes'"));
        admin().contentType(ContentType.JSON).body(source("kinds-bad", Map.of("sample_rows", "lots")))
            .post("/api/v1/sources").then().statusCode(400)
            .body("error", containsString("sample_rows: a whole number"));
        Map<String, Object> files = Map.of("name", "kinds-files", "kind", "files",
            "settings", Map.of("path", "/nowhere", "header", "yes"));
        admin().contentType(ContentType.JSON).body(files).post("/api/v1/sources").then().statusCode(400)
            .body("error", equalTo("header: true or false, not 'yes'"));
        admin().contentType(ContentType.JSON).body(source("kinds-ok", Map.of("keep_samples", "FALSE")))
            .post("/api/v1/sources").then().statusCode(201)
            .body("settings.keep_samples", equalTo("false"));
        admin().delete("/api/v1/sources/kinds-ok").then().statusCode(200);
    }

    @Test
    void valuesSavedBeforeKindsStillLoad() throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
            "INSERT INTO kilnmark.data_source (name, display_name, kind, settings) VALUES (?, ?, 'postgresql', "
                + "?::jsonb)")) {
            ps.setString(1, "kinds-legacy");
            ps.setString(2, "Legacy");
            ps.setString(3, "{\"url\": \"" + url + "\", \"keep_samples\": \"no\", \"allow_writing\": \"FALSE\"}");
            ps.executeUpdate();
        }
        admin().get("/api/v1/sources/kinds-legacy").then().statusCode(200)
            .body("settings.keep_samples", equalTo("no"))
            .body("settings.allow_writing", equalTo("FALSE"));
        admin().get("/api/v1/sources").then().statusCode(200);
        // An edit names the old value; corrected, it saves.
        Map<String, Object> edit = Map.of("settings", Map.of("url", url, "keep_samples", "no"));
        admin().contentType(ContentType.JSON).body(edit).put("/api/v1/sources/kinds-legacy").then().statusCode(400)
            .body("error", equalTo("keep_samples: true or false, not 'no'"));
        Map<String, Object> fixed = Map.of("settings", Map.of("url", url, "keep_samples", "false"));
        admin().contentType(ContentType.JSON).body(fixed).put("/api/v1/sources/kinds-legacy").then().statusCode(200)
            .body("settings.keep_samples", equalTo("false"));
        admin().delete("/api/v1/sources/kinds-legacy").then().statusCode(200);
    }
}
