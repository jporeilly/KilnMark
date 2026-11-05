package io.kilnmark.server.flows;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.pipelines.PipelineService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * A flow's sql node takes a place on its source when the source limits runs at once (ADR 0040): it
 * waits, saying so, while the source is full, starts when a place is free, and a flow stopped
 * meanwhile does not start it.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowSqlPlacesTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Inject
    FlowService flows;

    @Inject
    JobQueue queue;

    @Inject
    io.kilnmark.graph.store.GraphStore graph;

    @Inject
    io.kilnmark.server.Db db;

    private static final String WRITER = "fsqlp_writer";
    private static final String WRITER_PW = "fsqlp-writer-pw";
    private static final String SOURCE = "fsqlp-db";

    static final String FLOW = """
        kilnmark: flow/v1
        name: fsqlp-load
        nodes:
          - id: load
            type: sql
            source: fsqlp-db
            statements:
              - TRUNCATE fsqlp.target
              - INSERT INTO fsqlp.target SELECT id FROM fsqlp.staging
        """;

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

    private FlowStore.NodeRun node(long run, String id) {
        return flows.nodeRuns(run).stream().filter(n -> n.nodeId().equals(id)).findFirst().orElse(null);
    }

    /** Holds the source's only place, as another run using it would. */
    private JobQueue.ScanLease holdThePlace() {
        long id = graph.source(SOURCE).orElseThrow().id();
        JobQueue.ScanLease lease = queue.lockPipelineJob(-ThreadLocalRandom.current().nextLong(1, 1L << 40))
            .orElseThrow();
        assertEquals(true, lease.takePlaces(List.of(new JobQueue.SourceLimit(id, SOURCE, 1))).taken());
        return lease;
    }

    @Test
    @Order(1)
    void setUp() throws Exception {
        superuser("DROP SCHEMA IF EXISTS fsqlp CASCADE", "CREATE SCHEMA fsqlp",
            "CREATE TABLE fsqlp.staging (id integer)", "INSERT INTO fsqlp.staging SELECT n FROM generate_series(1, 5) n",
            "CREATE TABLE fsqlp.target (id integer)",
            "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + WRITER + "') THEN "
                + "EXECUTE 'DROP OWNED BY " + WRITER + "'; EXECUTE 'DROP ROLE " + WRITER + "'; END IF; END $$",
            "CREATE ROLE " + WRITER + " LOGIN PASSWORD '" + WRITER_PW + "'",
            "GRANT USAGE, CREATE ON SCHEMA fsqlp TO " + WRITER,
            "GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE ON ALL TABLES IN SCHEMA fsqlp TO " + WRITER);
        admin().contentType(ContentType.JSON).body(Map.of("name", SOURCE, "kind", "postgresql", "password", WRITER_PW,
            "settings", Map.of("url", url, "username", WRITER, "schemas", "fsqlp", "allow_sql", "true",
                "allow_writing", "true", "max_runs", "1"))).post("/api/v1/sources").then().statusCode(201);
        flows.save(FLOW, "test", "tester", AccessRole.DESIGNER, true, null);
    }

    @Test
    @Order(2)
    void aSqlNodeWaitsForAFreePlaceThenRuns() throws Exception {
        JobQueue.ScanLease other = holdThePlace();
        long run;
        try {
            run = flows.queueRun("fsqlp-load", null, Map.of(), "tester", AccessRole.STEWARD).id();
            FlowStore.NodeRun waiting = await("the node to wait", () -> node(run, "load"),
                n -> n != null && PipelineService.waitingFor(SOURCE).equals(n.message()));
            assertEquals("running", waiting.status());
            Thread.sleep(1_500);
            assertEquals(0, count("SELECT count(*) FROM fsqlp.target"), "nothing ran while it waited");
            assertEquals("running", flows.run(run).orElseThrow().status());
        } finally {
            other.close();
        }
        FlowStore.Run done = await("the flow to end", () -> flows.run(run).orElseThrow(), FlowStore.Run::finished);
        assertEquals("succeeded", done.status(), done.error());
        assertEquals("succeeded", node(run, "load").status());
        assertEquals(5, count("SELECT count(*) FROM fsqlp.target"));
    }

    @Test
    @Order(3)
    void aFlowStoppedWhileItsSqlNodeWaitsNeverRunsIt() throws Exception {
        superuser("TRUNCATE fsqlp.target");
        JobQueue.ScanLease other = holdThePlace();
        try {
            long run = flows.queueRun("fsqlp-load", null, Map.of(), "tester", AccessRole.STEWARD).id();
            await("the node to wait", () -> node(run, "load"),
                n -> n != null && PipelineService.waitingFor(SOURCE).equals(n.message()));
            flows.cancel(run, "tester", AccessRole.STEWARD);
            FlowStore.Run done = await("the flow to end", () -> flows.run(run).orElseThrow(), FlowStore.Run::finished);
            assertEquals("cancelled", done.status(), done.error());
            assertEquals("cancelled", node(run, "load").status());
            assertEquals(0, count("SELECT count(*) FROM fsqlp.target"));
        } finally {
            other.close();
        }
    }

    @Test
    @Order(4)
    void cleanUp() throws Exception {
        admin().delete("/api/v1/sources/" + SOURCE).then().statusCode(200);
        superuser("DROP SCHEMA IF EXISTS fsqlp CASCADE",
            "DO $$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + WRITER + "') THEN "
                + "EXECUTE 'DROP OWNED BY " + WRITER + "'; EXECUTE 'DROP ROLE " + WRITER + "'; END IF; END $$");
    }
}
