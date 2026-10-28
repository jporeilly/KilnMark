package io.kilnmark.server.drivers;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Settings › JDBC drivers through the API: admins only; a jar uploaded to the drivers folder and
 * registered has its checksum recorded, with who and when; a changed jar is not used; a driver in
 * use cannot be removed; and only admins set up or change generic JDBC sources.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegisteredDriversApiTest {

    private byte[] jar;
    private String fileName;
    private String name;
    private String folder;

    @BeforeAll
    void compile() throws IOException {
        Path work = Files.createTempDirectory("kilnmark-fake-driver");
        Path src = work.resolve("src/acme/AcmeDriver.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
            package acme;
            public class AcmeDriver implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties info) { return null; }
                public boolean acceptsURL(String url) { return url.startsWith("jdbc:acme:"); }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties i) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { return null; }
            }
            """);
        Path out = Files.createDirectories(work.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), src.toString()));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (JarOutputStream j = new JarOutputStream(b)) {
            j.putNextEntry(new JarEntry("acme/AcmeDriver.class"));
            j.write(Files.readAllBytes(out.resolve("acme/AcmeDriver.class")));
            j.closeEntry();
        }
        jar = b.toByteArray();
        String id = UUID.randomUUID().toString().substring(0, 8);
        fileName = "acme-jdbc-" + id + ".jar";
        name = "acme-" + id;
    }

    @AfterAll
    void tidy() throws IOException {
        // the drivers folder is shared with the other driver tests, which expect it empty
        if (folder != null) {
            Files.deleteIfExists(Path.of(folder, fileName));
        }
    }

    private static String token(String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", "jdbc-" + role + "-" + UUID.randomUUID(),
            "role", role)).post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    private Map<String, String> registration(String driverClass) {
        return Map.of("name", name, "fileName", fileName, "driverClass", driverClass, "urlPrefix", "jdbc:acme:");
    }

    @Test
    void registeringRecordsTheChecksumAndOnlyAdminsMayDoIt() throws Exception {
        String steward = token("steward");
        Tokens.as(steward).get("/api/v1/drivers/registered").then().statusCode(403);
        Tokens.as(steward).get("/api/v1/drivers/files").then().statusCode(403);
        Tokens.as(steward).contentType(ContentType.BINARY).body(jar).post("/api/v1/drivers/files/" + fileName)
            .then().statusCode(403);
        Tokens.as(steward).contentType(ContentType.JSON).body(registration("acme.AcmeDriver"))
            .post("/api/v1/drivers/registered").then().statusCode(403);

        // upload, never over a file already there
        admin().contentType(ContentType.BINARY).body(jar).post("/api/v1/drivers/files/" + fileName).then()
            .statusCode(201).body("fileName", equalTo(fileName)).body("size", equalTo(jar.length));
        admin().contentType(ContentType.BINARY).body(new byte[] {1, 2, 3}).post("/api/v1/drivers/files/" + fileName)
            .then().statusCode(409).body("error", containsString("already in the drivers folder"));
        admin().contentType(ContentType.BINARY).body(jar).post("/api/v1/drivers/files/..%2Fescape.jar").then()
            .statusCode(org.hamcrest.Matchers.anyOf(equalTo(400), equalTo(404)));
        folder = admin().get("/api/v1/drivers/files").then().statusCode(200)
            .body("files.fileName", hasItem(fileName)).extract().jsonPath().getString("folder");

        // a class the jar does not hold, or bad input, is refused before anything is recorded
        admin().contentType(ContentType.JSON).body(registration("acme.Missing")).post("/api/v1/drivers/registered")
            .then().statusCode(400).body("error", containsString("holds no class acme.Missing"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "Bad Name", "fileName", "../x.jar",
            "driverClass", "x", "urlPrefix", "http:")).post("/api/v1/drivers/registered").then().statusCode(400)
            .body("error", containsString("name: ")).body("error", containsString("fileName: "));

        String sha = admin().contentType(ContentType.JSON).body(registration("acme.AcmeDriver"))
            .post("/api/v1/drivers/registered").then().statusCode(201)
            .body("state", equalTo("ready")).body("registeredBy", not(equalTo(null)))
            .extract().jsonPath().getString("sha256");
        assertEquals(io.kilnmark.kiln.connect.DriverFolder.sha256(jar), sha);
        admin().contentType(ContentType.JSON).body(registration("acme.AcmeDriver"))
            .post("/api/v1/drivers/registered").then().statusCode(409);

        // the generic JDBC connector offers it at once
        admin().get("/api/v1/connectors").then().statusCode(200)
            .body("find { it.kind == 'jdbc' }.settings.find { it.key == 'driver' }.choices", hasItem(name));
        admin().get("/api/v1/drivers/registered/history").then().statusCode(200)
            .body("find { it.name == '" + name + "' && it.action == 'registered' }.sha256", equalTo(sha));

        // only admins set up generic JDBC sources, or check unsaved ones
        Map<String, Object> source = Map.of("name", "acme-src-" + name.substring(5), "kind", "jdbc",
            "settings", Map.of("driver", name, "url", "jdbc:acme:sales"));
        Tokens.as(steward).contentType(ContentType.JSON).body(source).post("/api/v1/drafts/test").then()
            .statusCode(403).body("error", containsString("Only admins can set up Generic JDBC sources"));
        String sourceName = (String) source.get("name");
        admin().contentType(ContentType.JSON).body(source).post("/api/v1/sources").then().statusCode(201);
        admin().contentType(ContentType.JSON).body(Map.of("name", "x", "kind", "jdbc", "settings",
            Map.of("driver", name, "url", "jdbc:other:sales"))).post("/api/v1/sources").then().statusCode(400)
            .body("error", containsString("takes URLs starting jdbc:acme:"));
        Tokens.as(steward).contentType(ContentType.JSON).body(Map.of("settings", Map.of("driver", name, "url",
            "jdbc:acme:other"))).put("/api/v1/sources/" + sourceName).then().statusCode(403)
            .body("error", containsString("Only admins can change the url"));
        Tokens.as(steward).contentType(ContentType.JSON).body(Map.of("settings", Map.of("driver", name, "url",
            "jdbc:acme:sales", "description", "Synthetic sales"))).put("/api/v1/sources/" + sourceName).then()
            .statusCode(200);

        // a driver in use cannot be removed
        admin().delete("/api/v1/drivers/registered/" + name).then().statusCode(409)
            .body("error", containsString(sourceName));
        Tokens.as(steward).delete("/api/v1/drivers/registered/" + name).then().statusCode(403);

        // a changed jar is not used
        Path file = Path.of(folder, fileName);
        byte[] changed = java.util.Arrays.copyOf(jar, jar.length + 1);
        Files.write(file, changed);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(60)));
        admin().get("/api/v1/drivers/registered").then().statusCode(200)
            .body("find { it.name == '" + name + "' }.state", equalTo("mismatch"))
            .body("find { it.name == '" + name + "' }.message", containsString("has changed since it was registered"))
            .body("find { it.name == '" + name + "' }.usedBy", hasItem(sourceName));
        admin().post("/api/v1/sources/" + sourceName + "/test").then().statusCode(200)
            .body("ok", equalTo(false)).body("problem", containsString("has changed since it was registered"));
        Files.write(file, jar);

        admin().delete("/api/v1/sources/" + sourceName).then().statusCode(200);
        admin().delete("/api/v1/drivers/registered/" + name).then().statusCode(204);
        admin().delete("/api/v1/drivers/registered/" + name).then().statusCode(404);
        List<String> actions = admin().get("/api/v1/drivers/registered/history").then().statusCode(200).extract()
            .jsonPath().getList("findAll { it.name == '" + name + "' }.action");
        assertEquals(List.of("removed", "registered"), actions, "newest first, kept for good");
        assertTrue(Files.exists(file), "removing a registration leaves the jar");
    }
}
