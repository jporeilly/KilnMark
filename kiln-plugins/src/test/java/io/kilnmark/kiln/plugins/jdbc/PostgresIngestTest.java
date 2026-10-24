package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
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
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
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
            // _ is a LIKE wildcard: a_b must not pick up axb's table of the same name
            s.execute("CREATE SCHEMA a_b");
            s.execute("CREATE TABLE a_b.t (one integer PRIMARY KEY)");
            s.execute("CREATE SCHEMA axb");
            s.execute("CREATE TABLE axb.t (two text, three text)");
            // SQL Server's system schema names are ordinary names in PostgreSQL
            s.execute("CREATE SCHEMA sys");
            s.execute("CREATE TABLE sys.settings (k text)");
        }
    }

    @Test
    void catalogListsTablesViewsColumnsAndKeys() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            List<JdbcCatalog.Table> tables = new PostgresConnector().readCatalog(c, AssetFilter.ALL);
            assertEquals(List.of("a_b.t", "axb.t", "crm.customers", "crm.premier", "sys.settings"),
                tables.stream().map(JdbcCatalog.Table::path).sorted().toList());
            Map<String, JdbcCatalog.Table> byPath = new HashMap<>();
            tables.forEach(t -> byPath.put(t.path(), t));
            JdbcCatalog.Table customers = byPath.get("crm.customers");
            assertEquals("table", customers.type());
            assertEquals("view", byPath.get("crm.premier").type());
            assertEquals("People who hold an account", customers.comment());
            assertEquals(List.of("customer_id"), customers.primaryKey());
            assertEquals(9, customers.columns().size());
            assertEquals("email", customers.columns().get(1).name());
            assertTrue(!customers.columns().get(2).nullable(), "segment is NOT NULL");
            assertEquals(JdbcColumn.Kind.TIMESTAMP_TZ, customers.columns().get(7).kind());
        }
    }

    @Test
    void schemasWhoseNamesHoldWildcardsKeepTheirOwnTables() throws Exception {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            PostgresConnector pg = new PostgresConnector();
            Map<String, List<String>> columns = new HashMap<>();
            pg.readCatalog(c, AssetFilter.ALL).forEach(t -> columns.put(t.path(),
                t.columns().stream().map(JdbcCatalog.Column::name).toList()));
            assertEquals(List.of("one"), columns.get("a_b.t"));
            assertEquals(List.of("two", "three"), columns.get("axb.t"));

            List<JdbcCatalog.Table> only = pg.readCatalog(c, AssetFilter.of(List.of("a_b"), null, null));
            assertEquals(List.of("a_b.t"), only.stream().map(JdbcCatalog.Table::path).toList());
            assertEquals(List.of("one"), only.get(0).primaryKey());

            // the escaped pattern itself matches only a_b, so the driver does the filtering
            String pattern = pg.schemaPattern(c.getMetaData(), "a_b");
            assertEquals("a\\_b", pattern);
            List<String> schemas = new ArrayList<>();
            try (var rs = c.getMetaData().getTables(null, pattern, "%", new String[] {"TABLE"})) {
                while (rs.next()) {
                    schemas.add(rs.getString("TABLE_SCHEM"));
                }
            }
            assertEquals(List.of("a_b"), schemas);
        }
    }

    private static SourceConfig source(Map<String, String> extra) {
        Map<String, String> settings = new HashMap<>(Map.of("url", PG.getJdbcUrl(), "username", PG.getUsername()));
        settings.putAll(extra);
        return new SourceConfig("lab", "postgresql", settings, PG.getPassword());
    }

    @Test
    void listsUserSchemasWithTableCountsFromMetadata() throws Exception {
        PostgresConnector pg = new PostgresConnector();
        Map<String, Long> counts = new HashMap<>();
        pg.containers(source(Map.of())).forEach(c -> counts.put(c.name(), c.count()));
        assertEquals(Map.of("public", 0L, "crm", 2L, "a_b", 1L, "axb", 1L, "sys", 1L), counts,
            "user schemas only, empty ones too, views counted");
        Map<String, Long> filtered = new HashMap<>();
        pg.containers(source(Map.of("include", "crm.*,T", "exclude", "*premier", "schemas", "crm")))
            .forEach(c -> filtered.put(c.name(), c.count()));
        assertEquals(1L, filtered.get("crm"), "patterns apply, ignoring case; the chosen schemas do not");
        assertEquals(1L, filtered.get("axb"));
        assertEquals(0L, filtered.get("sys"));
    }

    @Test
    void theCatalogueHonoursSchemasAndPatterns() throws Exception {
        PostgresConnector pg = new PostgresConnector();
        assertEquals(List.of("crm.customers"), pg.catalog(source(Map.of("schemas", "crm", "exclude", "PREMIER")))
            .stream().map(io.kilnmark.kiln.connect.AssetInfo::path).toList());
        assertEquals(List.of("a_b.t", "axb.t"), pg.catalog(source(Map.of("include", "t"))).stream()
            .map(io.kilnmark.kiln.connect.AssetInfo::path).sorted().toList());
    }

    @Test
    void aConnectionTestSaysWhatIsWrongWithoutTheSecret() throws Exception {
        PostgresConnector pg = new PostgresConnector();
        pg.test(source(Map.of()));
        String wrong = "not-the-password-4471";
        ConnectorException e = org.junit.jupiter.api.Assertions.assertThrows(ConnectorException.class, () -> pg.test(
            new SourceConfig("lab", "postgresql", Map.of("url", PG.getJdbcUrl(), "username", PG.getUsername()), wrong)));
        assertTrue(e.getMessage().contains("Cannot connect to lab"), e.getMessage());
        assertTrue(!e.getMessage().contains(wrong));
        assertEquals("schemas", pg.containerKind().setting());
        assertEquals(Map.of("url", "jdbc:postgresql://db.example.com:5433/sales"),
            pg.addressSettings("db.example.com", 5433, "sales"));
        assertEquals(java.util.Set.of("url", "username"), pg.secretScope());
        Connector plain = pg;
        assertTrue(plain.secretScope().contains("url"));
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
