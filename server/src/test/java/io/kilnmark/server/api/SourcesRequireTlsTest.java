package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * With kilnmark.sources.require-tls on (ADR 0034), the API refuses a source whose connections
 * could go unencrypted, saying which setting to change, and takes one that always encrypts or
 * reaches no network.
 */
@QuarkusTest
@TestProfile(SourcesRequireTlsTest.RequireTls.class)
class SourcesRequireTlsTest {

    public static class RequireTls implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kilnmark.sources.require-tls", "true");
        }
    }

    @Test
    void aSourceThatCouldGoUnencryptedIsRefusedWhenSaved() throws Exception {
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "tls-plain", "kind", "postgresql",
                "settings", Map.of("url", "jdbc:postgresql://db.example.invalid:5432/sales", "username", "k")))
            .post("/api/v1/sources").then().statusCode(400)
            .body("error", allOf(containsString("sslmode"), containsString("kilnmark.sources.require-tls"),
                not(containsString("db.example.invalid"))));
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "tls-s3", "kind", "s3",
                "settings", Map.of("bucket", "b", "endpoint", "http://storage.example.invalid:9000",
                    "access_key_id", "AKIAEXAMPLE"), "password", "example-secret"))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("https://"));

        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "tls-required", "kind", "postgresql",
                "settings", Map.of("url", "jdbc:postgresql://db.example.invalid:5432/sales?sslmode=require",
                    "username", "k")))
            .post("/api/v1/sources").then().statusCode(201);
        admin().delete("/api/v1/sources/tls-required").then().statusCode(200);

        // A folder on this server reaches no network
        Path roots = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files"));
        Path dir = Files.createTempDirectory(roots, "require-tls");
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "tls-files", "kind", "files", "settings", Map.of("path", dir.toString())))
            .post("/api/v1/sources").then().statusCode(201);
        admin().delete("/api/v1/sources/tls-files").then().statusCode(200);
    }
}
