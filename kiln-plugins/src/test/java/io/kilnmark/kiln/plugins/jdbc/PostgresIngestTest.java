package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.plugins.profile.ProfileStep;

@Testcontainers
class PostgresIngestTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
        DockerImageName.parse("docker.io/library/postgres:18").asCompatibleSubstituteFor("postgres"));

    private static final String URN = "urn:kilnmark:lab:crm.customers";

    @BeforeAll
    static void load() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA crm");
            s.execute("""
                CREATE TABLE crm.customers (
                    customer_id integer PRIMARY KEY,
                    email       text,
                    segment     varchar(20) NOT NULL,
                    balance     numeric,
                    limit_gbp   numeric(10,2),
                    opted_in    boolean,
                    born        date,
                    created_at  timestamptz,
                    profile     jsonb
                )""");
            s.execute("COMMENT ON TABLE crm.customers IS 'People who hold an account'");
            s.execute("""
                INSERT INTO crm.customers
                SELECT n,
                       CASE WHEN n % 50 = 0 THEN NULL ELSE 'person' || n || '@example.com' END,
                       (ARRAY['Retail','Retail','Premier','Business'])[1 + n % 4],
                       n * 1.123456789012,
                       (n % 1000) + 0.25,
                       n % 3 = 0,
                       date '1950-01-01' + n,
                       timestamptz '2024-01-01 00:00:00+00' + n * interval '1 minute',
                       jsonb_build_object('n', n)
                FROM generate_series(1, 20000) n""");
            s.execute("CREATE VIEW crm.premier AS SELECT * FROM crm.customers WHERE segment = 'Premier'");
        }
    }

    @Test
    void catalogListsTablesViewsColumnsAndKeys() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            List<JdbcCatalog.Table> tables = JdbcCatalog.read(c, List.of());
            assertEquals(List.of("crm.customers", "crm.premier"), tables.stream().map(JdbcCatalog.Table::path).toList());
            JdbcCatalog.Table customers = tables.get(0);
            assertEquals("table", customers.type());
            assertEquals("view", tables.get(1).type());
            assertEquals("People who hold an account", customers.comment());
            assertEquals(List.of("customer_id"), customers.primaryKey());
            assertEquals(9, customers.columns().size());
            assertEquals("email", customers.columns().get(1).name());
            assertTrue(!customers.columns().get(2).nullable(), "segment is NOT NULL");
            assertEquals(JdbcColumn.Kind.TIMESTAMP_TZ, customers.columns().get(7).kind());
        }
    }

    @Test
    void readsAndProfilesEveryRowInStream() throws Exception {
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> input = new HashMap<>(Map.of("url", PG.getJdbcUrl(), "user", PG.getUsername(),
            "password", PG.getPassword(), "schema", "crm", "table", "customers", "asset-urn", URN,
            "batch-size", "4096"));
        Pipeline p = new Pipeline("ingest", List.of(
            new StepSpec("read", new JdbcTableInput(), input),
            new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", URN))));
        RunResult r = new Runner(256L << 20, 4).run(p, events::add);
        assertTrue(r.succeeded(), r.error());
        assertEquals(20_000, r.steps().get(0).rowsOut());

        MetadataEvent rows = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
            .orElseThrow();
        assertEquals("20000", rows.attributes().get("rows"));
        assertEquals("true", rows.attributes().get("complete"));

        Map<String, Map<String, String>> profile = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> profile.put(e.attributes().get("field"), e.attributes()));
        assertEquals(9, profile.size());

        Map<String, String> email = profile.get("email");
        assertEquals("400", email.get("nulls"));
        assertWithin(19_600, Long.parseLong(email.get("distinct")), 0.03);
        assertEquals("text", email.get("shape"));

        Map<String, String> segment = profile.get("segment");
        assertEquals("3", segment.get("distinct"));
        assertTrue(segment.get("top").startsWith("[{\"value\":\"Retail\",\"count\":10000}"), segment.get("top"));

        Map<String, String> balance = profile.get("balance"); // unconstrained numeric
        assertEquals("number", balance.get("shape"));
        assertEquals("1.123457", balance.get("min"));

        Map<String, String> limit = profile.get("limit_gbp");
        assertEquals("0.25", limit.get("min"));
        assertEquals("999.25", limit.get("max"));

        assertEquals("6666", profile.get("opted_in").get("true"));
        assertEquals("1950-01-02", profile.get("born").get("min"));
        assertEquals("2024-01-01T00:01:00Z", profile.get("created_at").get("min"));
        assertEquals("text", profile.get("profile").get("shape")); // jsonb read as text
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        Map<String, String> input = Map.of("url", PG.getJdbcUrl(), "user", PG.getUsername(),
            "password", PG.getPassword(), "schema", "crm", "table", "customers", "asset-urn", URN,
            "row-limit", "1000");
        RunResult r = new Runner(64L << 20, 2).run(new Pipeline("sample", List.of(
            new StepSpec("read", new JdbcTableInput(), input),
            new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", URN)))), events::add);
        assertTrue(r.succeeded(), r.error());
        MetadataEvent rows = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
            .orElseThrow();
        assertEquals("1000", rows.attributes().get("rows"));
        assertEquals("false", rows.attributes().get("complete"));
    }

    @Test
    void aMissingTableFailsWithAClearMessage() throws Exception {
        Map<String, String> input = Map.of("url", PG.getJdbcUrl(), "user", PG.getUsername(),
            "password", PG.getPassword(), "schema", "crm", "table", "nope", "asset-urn", URN);
        RunResult r = new Runner(64L << 20, 2).run(new Pipeline("missing", List.of(
            new StepSpec("read", new JdbcTableInput(), input),
            new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", URN)))), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Cannot read crm.nope"), r.error());
    }

    private static void assertWithin(long expected, long actual, double fraction) {
        assertTrue(Math.abs(actual - expected) <= expected * fraction, "expected ~" + expected + " but was " + actual);
    }
}
