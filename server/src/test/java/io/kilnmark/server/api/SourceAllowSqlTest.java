package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.graph.store.Records;
import io.kilnmark.server.sources.CommonSettings;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.ws.rs.WebApplicationException;

/**
 * Allow SQL (ADR 0028): whether SQL a person writes may run on a source. Off unless set, PostgreSQL
 * sources only, and only designers and admins change it, as for Allow writing.
 */
@QuarkusTest
class SourceAllowSqlTest {

    private static Records.Source source(Map<String, String> settings) {
        return new Records.Source(1, "warehouse", "postgresql", settings, true, Instant.now(), "Warehouse");
    }

    private static SourceForm form(Map<String, String> settings) {
        return new SourceForm(null, null, settings, null, null, null, null);
    }

    @Test
    void offUnlessSetToTrue() {
        assertFalse(CommonSettings.allowsSql(Map.of()));
        assertFalse(CommonSettings.allowsSql(Map.of("allow_sql", "false")));
        assertTrue(CommonSettings.allowsSql(Map.of("allow_sql", " TRUE ")));
        assertFalse(CommonSettings.allowsSql(null));
    }

    @Test
    void designersChangeItOthersKeepIt() {
        SourceForm on = form(Map.of("url", "jdbc:postgresql://h/db", "allow_sql", "true"));
        assertSame(on, SourceService.sqlAsAllowed(on, source(Map.of()), true));
        // Below a designer: left out keeps the stored value, sent unchanged is fine, a change is refused.
        SourceForm kept = SourceService.sqlAsAllowed(form(Map.of("url", "x")), source(Map.of("allow_sql", "true")), false);
        assertEquals("true", kept.settings().get("allow_sql"));
        SourceService.sqlAsAllowed(form(Map.of("allow_sql", "true")), source(Map.of("allow_sql", "true")), false);
        WebApplicationException e = assertThrows(WebApplicationException.class,
            () -> SourceService.sqlAsAllowed(form(Map.of("allow_sql", "true")), source(Map.of()), false));
        assertEquals(403, e.getResponse().getStatus());
        assertThrows(WebApplicationException.class, () -> SourceService.sqlAsAllowed(form(Map.of("allow_sql", "false")),
            source(Map.of("allow_sql", "true")), false));
    }

    @Test
    void onlyPostgresqlSourcesMayAllowIt() throws Exception {
        Path roots = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "kilnmark-test-files"));
        Path dir = Files.createTempDirectory(roots, "allow-sql");
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", "allow-sql-files", "kind", "files", "settings",
                Map.of("path", dir.toString(), "allow_sql", "true")))
            .post("/api/v1/sources").then().statusCode(400).body("error", containsString("only PostgreSQL sources"));
        admin().get("/api/v1/common-settings").then().statusCode(200)
            .body("find { it.key == 'allow_sql' }.kind", org.hamcrest.Matchers.equalTo("boolean"))
            .body("find { it.key == 'allow_sql' }.defaultValue", org.hamcrest.Matchers.equalTo("false"));
    }
}
