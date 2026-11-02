package io.kilnmark.server.flows;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static io.kilnmark.server.Tokens.newToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

/**
 * A flow's sql node (ADR 0028), against the test's PostgreSQL: saved only by a designer who may see
 * unmasked values, run only on a source that allows SQL and writing, under a login that is not a
 * superuser, all its statements in one transaction, reporting the rows they changed. Versions saved
 * before the check (V29), and pipeline versions with a query read saved before theirs (V27), are
 * refused when queued and when they start.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowSqlNodeTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Inject
    FlowService flows;

    @Inject
    FlowStore store;

    @Inject
    PipelineStore pipelineStore;

    @Inject
    io.kilnmark.server.Db db;

    private static final String WRITER = "fsql_writer";
    private static final String WRITER_PW = "fsql-writer-pw";
    private static String designer;

    private void superuser(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private long count(String sql) {
        return db.tx(c -> {
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private void source(String name, String login, String pw, boolean sql, boolean writing) {
        Map<String, String> settings = new HashMap<>(Map.of("url", url, "username", login, "schemas", "fsql"));
        if (sql) {
            settings.put("allow_sql", "true");
        }
        if (writing) {
            settings.put("allow_writing", "true");
        }
        admin().contentType(ContentType.JSON).body(Map.of("name", name, "kind", "postgresql", "password", pw,
            "settings", settings)).post("/api/v1/sources").then().statusCode(201);
    }

    private static <T> T await(String what, Supplier<T> look, Predicate<T> done) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        T last = null;
        while (System.nanoTime() < deadline) {
            last = look.get();
            if (done.test(last)) {
                return last;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last seen: " + last);
    }

    private FlowStore.Run finished(long id) throws InterruptedException {
        return await("flow run " + id, () -> flows.run(id).orElseThrow(), FlowStore.Run::finished);
    }

    private FlowStore.NodeRun node(long run, String id) {
        return flows.nodeRuns(run).stream().filter(n -> n.nodeId().equals(id)).findFirst().orElseThrow();
    }

    private FlowStore.Flow save(String yaml) {
        return flows.save(yaml, "test", "tester", AccessRole.DESIGNER, true, null);
    }

    private long start(String flow) {
        return flows.queueRun(flow, null, Map.of(), "tester", AccessRole.STEWARD).id();
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        superuser("DROP SCHEMA IF EXISTS fsql CASCADE", "CREATE SCHEMA fsql",
            "CREATE TABLE fsql.staging (id integer)", "INSERT INTO fsql.staging SELECT n FROM generate_series(1, 5) n",
            "CREATE TABLE fsql.target (id integer)",
            "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + WRITER + "') THEN "
                + "EXECUTE 'DROP OWNED BY " + WRITER + "'; EXECUTE 'DROP ROLE " + WRITER + "'; END IF; END $$",
            // A login limited to what the SQL needs: never a superuser (ADR 0028).
            "CREATE ROLE " + WRITER + " LOGIN PASSWORD '" + WRITER_PW + "'",
            "GRANT USAGE, CREATE ON SCHEMA fsql TO " + WRITER,
            "GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA fsql TO " + WRITER);
        source("fsql-db", WRITER, WRITER_PW, true, true);
        source("fsql-no-writing", WRITER, WRITER_PW, true, false);
        source("fsql-super", user, password, true, true);
        designer = newToken("designer", true);
    }

    static final String LOAD = """
        kilnmark: flow/v1
        name: fsql-load
        nodes:
          - id: load
            type: sql
            source: fsql-db
            statements:
              - TRUNCATE fsql.target
              - INSERT INTO fsql.target SELECT id FROM fsql.staging
          - id: trim
            type: sql
            source: fsql-db
            statements: ["DELETE FROM fsql.staging WHERE id > 3"]
          - { id: halt, type: stop, message: Too few rows were loaded }
        connections:
          - { from: load, to: trim, when: "load.rows_affected >= 5" }
          - { from: load, to: halt, when: "load.rows_affected < 5" }
        """;

    @Test
    @Order(2)
    void onlyADesignerWhoSeesUnmaskedValuesSavesASqlNode() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
            () -> flows.save(LOAD, "test", "tester", AccessRole.DESIGNER, false, null));
        assertEquals(403, e.getResponse().getStatus());
        assertThrows(WebApplicationException.class, () -> flows.save(LOAD, "test", "admin", AccessRole.ADMIN, null),
            "admins see values masked, so they do not write SQL either");
        FlowStore.Flow f = save(LOAD);
        assertTrue(store.sqlChecked("fsql-load", f.latestVersion()));
        assertTrue(flows.validate(LOAD).valid(), flows.validate(LOAD).problems().toString());
    }

    @Test
    @Order(3)
    void whatASqlNodeMayNotDoIsAProblemWhenSaved() {
        String noWriting = LOAD.replace("name: fsql-load", "name: fsql-x").replace("source: fsql-db\n    statements:\n"
            + "      - TRUNCATE", "source: fsql-no-writing\n    statements:\n      - TRUNCATE");
        assertTrue(flows.validate(noWriting).problems().stream().anyMatch(p -> p.message().contains("does not allow "
            + "writing")), flows.validate(noWriting).problems().toString());
        for (String bad : List.of("SELECT * FROM fsql.staging", "GRANT ALL ON fsql.target TO public",
            "BEGIN", "COPY fsql.target FROM '/etc/passwd'", "SET ROLE postgres", "ALTER TABLE fsql.target OWNER TO x",
            "DELETE FROM fsql.target RETURNING id", "INSERT INTO fsql.target SELECT pg_read_file('x')::int",
            "TRUNCATE fsql.target; DROP TABLE fsql.staging")) {
            String yaml = LOAD.replace("name: fsql-load", "name: fsql-x")
                .replace("[\"DELETE FROM fsql.staging WHERE id > 3\"]", "['" + bad.replace("'", "''") + "']");
            assertTrue(yaml.contains(bad.replace("'", "''")), yaml);
            assertFalse(flows.validate(yaml).valid(), bad);
        }
    }

    @Test
    @Order(4)
    void aSqlNodeRunsItsStatementsAndReportsTheRowsTheyChanged() throws Exception {
        long run = start("fsql-load");
        FlowStore.Run done = finished(run);
        assertEquals("succeeded", done.status(), done.error());
        FlowStore.NodeRun load = node(run, "load");
        assertEquals("succeeded", load.status(), load.message());
        assertTrue(load.reported().contains("\"rows_affected\":5.0") || load.reported().contains("\"rows_affected\": 5.0"),
            load.reported());
        assertFalse(load.message().contains("INSERT"), "never the statement: " + load.message());
        assertEquals("succeeded", node(run, "trim").status());
        assertEquals("skipped", node(run, "halt").status());
        assertEquals(5, count("SELECT count(*) FROM fsql.target"));
        assertEquals(3, count("SELECT count(*) FROM fsql.staging"));
        io.kilnmark.server.audit.AuditLog audit = io.quarkus.arc.Arc.container()
            .instance(io.kilnmark.server.audit.AuditLog.class).get();
        audit.awaitStored(5_000);
        assertEquals(2, count("SELECT count(*) FROM kilnmark.audit_event WHERE action = 'flow.sql' AND target LIKE "
            + "'fsql-load/" + run + "/%'"));
    }

    @Test
    @Order(5)
    void aFailingStatementChangesNothingAndIsNotQuoted() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fsql-fails
            nodes:
              - id: half
                type: sql
                source: fsql-db
                statements:
                  - INSERT INTO fsql.target VALUES (100)
                  - INSERT INTO fsql.missing_table VALUES (1)
            """);
        long run = start("fsql-fails");
        assertEquals("failed", finished(run).status());
        FlowStore.NodeRun half = node(run, "half");
        assertEquals("failed", half.status());
        assertTrue(half.message().contains("statement 2 of 2 failed (SQLSTATE 42P01)"), half.message());
        assertFalse(half.message().contains("missing_table"), "never what the database said: " + half.message());
        assertEquals(0, count("SELECT count(*) FROM fsql.target WHERE id = 100"), "the first statement rolled back");
    }

    @Test
    @Order(6)
    void cancellingTheFlowStopsTheStatementAndRollsBack() throws Exception {
        save("""
            kilnmark: flow/v1
            name: fsql-blocked
            nodes:
              - id: blocked
                type: sql
                source: fsql-db
                statements:
                  - INSERT INTO fsql.target VALUES (300)
                  - INSERT INTO fsql.target VALUES (301)
            """);
        long run;
        FlowStore.Run done;
        try (Connection hold = DriverManager.getConnection(url, user, password)) {
            // Another session holds the table, so the node's first statement waits on it.
            hold.setAutoCommit(false);
            try (Statement s = hold.createStatement()) {
                s.execute("LOCK TABLE fsql.target IN ACCESS EXCLUSIVE MODE");
            }
            run = start("fsql-blocked");
            long id = run;
            await("the sql node to start", () -> flows.nodeRuns(id).stream().anyMatch(n -> n.nodeId().equals("blocked")
                && n.status().equals("running")), Boolean::booleanValue);
            Thread.sleep(500);
            flows.cancel(run, "tester", AccessRole.STEWARD);
            done = finished(run);
            hold.rollback();
        }
        assertEquals("cancelled", done.status(), done.error());
        FlowStore.NodeRun blocked = node(run, "blocked");
        assertEquals("cancelled", blocked.status(), blocked.message());
        assertTrue(blocked.message().contains("nothing was changed"), blocked.message());
        assertEquals(0, count("SELECT count(*) FROM fsql.target WHERE id IN (300, 301)"));
    }

    @Test
    @Order(7)
    void aSuperuserLoginIsRefusedBeforeAnythingRuns() throws Exception {
        save(LOAD.replace("name: fsql-load", "name: fsql-super-load").replace("source: fsql-db", "source: fsql-super"));
        long before = count("SELECT count(*) FROM fsql.target");
        superuser("INSERT INTO fsql.target VALUES (200)");
        long run = start("fsql-super-load");
        assertEquals("failed", finished(run).status());
        FlowStore.NodeRun load = node(run, "load");
        assertTrue(load.message().contains("SQL does not run under this source's login")
            && load.message().contains("a login limited to what its SQL needs"), load.message());
        assertEquals(before + 1, count("SELECT count(*) FROM fsql.target"), "the TRUNCATE never ran");
    }

    @Test
    @Order(8)
    void aVersionSavedBeforeSqlNodesWereCheckedDoesNotRun() throws Exception {
        superuser("UPDATE kilnmark.flow_version SET sql_checked = false WHERE flow = 'fsql-load'");
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> start("fsql-load"));
        assertEquals(409, e.getResponse().getStatus());
        // Queued past the check (as a run queued just before an upgrade would be): refused as it starts.
        FlowStore.Flow f = flows.find("fsql-load").orElseThrow();
        long run = store.queueRun("fsql-load", f.latestVersion(), "tester", "steward", "{}", "{}").id();
        FlowStore.Run done = finished(run);
        assertEquals("failed", done.status());
        assertTrue(done.error().contains("was saved before Kilnmark checked who writes them"), done.error());
    }

    static final String QUERY = """
        kilnmark: pipeline/v1
        name: fsql-query
        nodes:
          - id: q
            type: read
            source: fsql-db
            query: SELECT id FROM fsql.staging
          - id: out
            type: write
            input: q
            source: fsql-db
            table: fsql.query_out
            mode: replace
        """;

    @Test
    @Order(9)
    void aPipelineVersionSavedBeforeQueriesWereCheckedDoesNotRun() throws Exception {
        as(designer).contentType(ContentType.JSON).body(Map.of("name", "fsql-query", "yaml", QUERY))
            .post("/api/v1/pipelines").then().statusCode(201).body("latest.valid", org.hamcrest.Matchers.is(true));
        superuser("UPDATE kilnmark.pipeline_version SET queries_checked = false WHERE pipeline = 'fsql-query'");
        as(designer).contentType(ContentType.JSON).body(Map.of()).post("/api/v1/pipelines/fsql-query/runs").then()
            .statusCode(409).body("error", org.hamcrest.Matchers.containsString("saved before queries were checked"));
        long id = pipelineStore.queueRun("fsql-query", 1, "tester", "designer").id();
        PipelineStore.Run done = await("pipeline run " + id, () -> pipelineStore.run(id).orElseThrow(),
            PipelineStore.Run::finished);
        assertEquals("failed", done.status());
        assertTrue(done.error().contains("saved before queries were checked"), done.error());
        assertEquals(0, count("SELECT count(*) FROM pg_tables WHERE schemaname = 'fsql' AND tablename = 'query_out'"));
    }

    /**
     * SQL a person wrote is checked as PostgreSQL reads it with standard_conforming_strings on, so it
     * never runs with it off, however it was turned off (the third review of the sql node).
     */
    @Test
    @Order(10)
    void sqlAPersonWroteNeverRunsWithBackslashesAsEscapes() throws Exception {
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SET standard_conforming_strings = off");
            assertThrows(SQLException.class,
                () -> io.kilnmark.kiln.plugins.jdbc.PostgresConnector.requireConformingStrings(c));
            io.kilnmark.kiln.plugins.jdbc.PostgresConnector.conformingStrings(c);
            io.kilnmark.kiln.plugins.jdbc.PostgresConnector.requireConformingStrings(c);
            c.rollback();
        }
        // A source whose URL turns it off: the query that would have ended its string at the backslash, and
        // deleted rows, reads as the one SELECT it was checked as.
        String off = url + (url.contains("?") ? "&" : "?") + "options=-c%20standard_conforming_strings%3Doff";
        Map<String, String> settings = new HashMap<>(Map.of("url", off, "username", WRITER, "schemas", "fsql",
            "allow_sql", "true", "allow_writing", "true"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "fsql-off", "kind", "postgresql", "password",
            WRITER_PW, "settings", settings)).post("/api/v1/sources").then().statusCode(201);
        long staging = count("SELECT count(*) FROM fsql.staging");
        String yaml = """
            kilnmark: pipeline/v1
            name: fsql-backslash
            nodes:
              - id: q
                type: read
                source: fsql-off
                query: >-
                  SELECT '\\' AS a, '; COMMIT; DELETE FROM fsql.staging; SELECT 1 -- ' AS b
              - id: out
                type: write
                input: q
                source: fsql-db
                table: fsql.backslash_out
                mode: replace
            """;
        io.restassured.path.json.JsonPath saved = as(designer).contentType(ContentType.JSON)
            .body(Map.of("name", "fsql-backslash", "yaml", yaml)).post("/api/v1/pipelines").then().statusCode(201)
            .extract().jsonPath();
        assertTrue(saved.getBoolean("latest.valid"), saved.prettify());
        long id = as(designer).contentType(ContentType.JSON).body(Map.of()).post("/api/v1/pipelines/fsql-backslash/runs")
            .then().statusCode(202).extract().jsonPath().getLong("id");
        PipelineStore.Run done = await("pipeline run " + id, () -> pipelineStore.run(id).orElseThrow(),
            PipelineStore.Run::finished);
        assertEquals(staging, count("SELECT count(*) FROM fsql.staging"), "nothing deleted: " + done.status()
            + " " + done.error());
        assertEquals("succeeded", done.status(), done.error());
        assertEquals(1, count("SELECT count(*) FROM fsql.backslash_out"), "the one row the SELECT returns");
    }

    /**
     * A superuser login that acts as another role from its first moment (role set in the URL's options) is
     * still refused: its session's settings came from the superuser (the sixth review of the sql node).
     */
    @Test
    @Order(11)
    void aLoginActingAsAnotherRoleIsRefused() throws Exception {
        save(LOAD.replace("name: fsql-load", "name: fsql-as-other").replace("source: fsql-db", "source: fsql-as"));
        String as = url + (url.contains("?") ? "&" : "?") + "options=-c%20role=" + WRITER;
        Map<String, String> settings = new HashMap<>(Map.of("url", as, "username", user, "schemas", "fsql",
            "allow_sql", "true", "allow_writing", "true"));
        admin().contentType(ContentType.JSON).body(Map.of("name", "fsql-as", "kind", "postgresql", "password",
            password, "settings", settings)).post("/api/v1/sources").then().statusCode(201);
        long target = count("SELECT count(*) FROM fsql.target");
        long run = start("fsql-as-other");
        assertEquals("failed", finished(run).status());
        FlowStore.NodeRun load = node(run, "load");
        assertTrue(load.message().contains("acts as another role than the one it signed in as"), load.message());
        assertEquals(target, count("SELECT count(*) FROM fsql.target"), "the TRUNCATE never ran");
    }

    @Test
    @Order(12)
    void cleanUp() throws Exception {
        superuser("DROP SCHEMA fsql CASCADE", "DROP OWNED BY " + WRITER);
    }
}
