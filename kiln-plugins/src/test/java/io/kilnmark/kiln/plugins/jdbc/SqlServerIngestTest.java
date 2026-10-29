package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileNode;

/**
 * The SQL Server connector against a real server: catalogue, a profiled read, a sample and
 * failures. Tagged {@code sqlserver}: the server needs about 2 GB of memory.
 */
@Tag("sqlserver")
@Testcontainers
class SqlServerIngestTest {

    /** SQL Server 2022 CU27 GDR1, pinned so results do not change under us. */
    @Container
    static final MSSQLServerContainer DB = new MSSQLServerContainer(
        DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04"))
        .acceptLicense()
        .withEnv("MSSQL_MEMORY_LIMIT_MB", "2048");

    private static final String URN = "urn:kilnmark:lab:crm.customers";
    private static final SqlServerConnector CONNECTOR = new SqlServerConnector();

    private static SourceConfig source;
    private static Map<String, AssetInfo> assets;

    @BeforeAll
    static void load() throws Exception {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE sales");
            s.execute("ALTER DATABASE sales SET ALLOW_SNAPSHOT_ISOLATION ON");
        }
        String url = DB.getJdbcUrl() + ";databaseName=sales";
        try (Connection c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA crm");
            s.execute("CREATE SCHEMA [Ops]");
            s.execute("""
                CREATE TABLE crm.customers (
                    customer_id int           NOT NULL PRIMARY KEY,
                    email       nvarchar(200) NULL,
                    segment     varchar(20)   NOT NULL,
                    city        nvarchar(40),
                    balance     decimal(18,6),
                    limit_gbp   money,
                    fee         smallmoney,
                    opted_in    bit,
                    age         tinyint,
                    rank_no     smallint,
                    visits      bigint,
                    score       real,
                    ratio       float,
                    born        date,
                    wakes       time(7),
                    created_at  datetime2(7),
                    legacy_at   datetime,
                    small_at    smalldatetime,
                    seen_at     datetimeoffset(7),
                    token       uniqueidentifier,
                    doc         xml,
                    photo       varbinary(max),
                    place       geography,
                    org         hierarchyid,
                    extra       sql_variant
                )""");
            s.execute("""
                EXEC sp_addextendedproperty N'MS_Description', N'People who hold an account',
                    N'SCHEMA', N'crm', N'TABLE', N'customers'""");
            s.execute("""
                EXEC sp_addextendedproperty N'MS_Description', N'Where we write to them',
                    N'SCHEMA', N'crm', N'TABLE', N'customers', N'COLUMN', N'email'""");
            s.execute("""
                WITH numbers AS (
                    SELECT TOP (20000) CAST(ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS int) AS n
                    FROM sys.all_columns a CROSS JOIN sys.all_columns b)
                INSERT INTO crm.customers
                SELECT n,
                       CASE WHEN n % 50 = 0 THEN NULL ELSE CONCAT(N'person', n, N'@example.com') END,
                       CHOOSE(1 + n % 4, 'Retail', 'Premier', 'Business', 'Retail'),
                       CHOOSE(1 + n % 4, N'London', N'Zürich', N'東京', N'London'),
                       n * 1.123456789012,
                       (n % 1000) + 0.25,
                       -(n % 100) - 0.5,
                       CASE WHEN n % 3 = 0 THEN 1 ELSE 0 END,
                       n % 256,
                       n % 1000 - 500,
                       CAST(n AS bigint) * 1000000000,
                       n * 0.5,
                       n * 0.25,
                       DATEADD(day, n, CAST('1950-01-01' AS date)),
                       DATEADD(second, n, CAST('00:00:00.1234567' AS time(7))),
                       DATEADD(minute, n, CAST('2024-01-01T00:00:00.1234567' AS datetime2(7))),
                       DATEADD(minute, n, CAST('2024-01-01T00:00:00' AS datetime)),
                       DATEADD(minute, n, CAST('2024-01-01T00:00:00' AS smalldatetime)),
                       DATEADD(minute, n, CAST('2024-01-01T05:30:00.5+05:30' AS datetimeoffset(7))),
                       NEWID(),
                       CASE WHEN n % 10 = 0 THEN NULL ELSE CAST(CONCAT('<n>', n, '</n>') AS xml) END,
                       CAST(n AS varbinary(4)),
                       CASE WHEN n % 2 = 0 THEN NULL ELSE geography::Point(51.5, -0.125, 4326) END,
                       hierarchyid::Parse(CONCAT('/', 1 + n % 5, '/')),
                       CAST(n % 7 AS sql_variant)
                FROM numbers""");
            s.execute("CREATE VIEW crm.premier AS SELECT customer_id, email, city FROM crm.customers "
                + "WHERE segment = 'Premier'");
            s.execute("CREATE TABLE [Ops].[odd]]name] ([key] int, [value] nvarchar(10))");
            s.execute("INSERT INTO [Ops].[odd]]name] VALUES (1, N'één'), (2, NULL)");
            s.execute("CREATE TABLE [Ops].[variants] (v sql_variant)");
            // one row per statement: a multi-row VALUES would convert them all to one type
            // twice each, so each is among the most common values profiled
            for (String v : List.of("CAST(0xCAFE AS varbinary(2))", "CAST(12 AS int)", "CAST(N'text' AS nvarchar(10))",
                "NULL")) {
                s.execute("INSERT INTO [Ops].[variants] VALUES (" + v + "), (" + v + ")");
            }
            // the isolation level of the session reading it
            s.execute("CREATE VIEW [Ops].[isolation_level] AS SELECT transaction_isolation_level AS level "
                + "FROM sys.dm_exec_sessions WHERE session_id = @@SPID");
            // _ and [ are LIKE wildcards in schema patterns: each schema must keep its own tables
            s.execute("CREATE SCHEMA a_b");
            s.execute("CREATE TABLE a_b.t (one int NOT NULL PRIMARY KEY)");
            s.execute("CREATE SCHEMA axb");
            s.execute("CREATE TABLE axb.t (two nvarchar(10), three nvarchar(10))");
            s.execute("CREATE SCHEMA [s[1]]]");
            s.execute("CREATE TABLE [s[1]]].[t] (four int NOT NULL PRIMARY KEY)");
            s.execute("INSERT INTO [s[1]]].[t] VALUES (4)");
        }
        source = new SourceConfig("lab", "sqlserver", Map.of("url", url, "username", DB.getUsername()),
            DB.getPassword());
        assets = CONNECTOR.catalog(source).stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    @Test
    void catalogListsUserTablesViewsColumnsKeysAndDescriptions() {
        assertEquals(List.of("a_b.t", "axb.t", "crm.customers", "crm.premier", "Ops.isolation_level", "Ops.odd]name",
            "Ops.variants", "s[1].t"), assets.keySet().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
            "user schemas only: no sys, INFORMATION_SCHEMA, guest or db_*");

        AssetInfo customers = assets.get("crm.customers");
        assertEquals("table", customers.kind());
        assertEquals("crm", customers.container());
        assertEquals("People who hold an account", customers.description());
        assertEquals(List.of("customer_id"), customers.primaryKey());
        assertTrue(!customers.bounded());
        assertEquals(25, customers.fields().size());
        Map<String, AssetInfo.FieldInfo> f = customers.fields().stream()
            .collect(Collectors.toMap(AssetInfo.FieldInfo::name, Function.identity()));
        assertEquals("Where we write to them", f.get("email").description());
        assertEquals(2, f.get("email").position());
        assertTrue(!f.get("segment").nullable(), "segment is NOT NULL");
        Map<String, String> kinds = Map.ofEntries(Map.entry("opted_in", "boolean"), Map.entry("age", "int16"),
            Map.entry("rank_no", "int16"), Map.entry("customer_id", "int32"), Map.entry("visits", "int64"),
            Map.entry("score", "float32"), Map.entry("ratio", "float64"), Map.entry("balance", "decimal"),
            Map.entry("limit_gbp", "decimal"), Map.entry("fee", "decimal"), Map.entry("city", "text"),
            Map.entry("token", "text"), Map.entry("doc", "text"), Map.entry("born", "date"), Map.entry("wakes", "time"),
            Map.entry("created_at", "timestamp"), Map.entry("legacy_at", "timestamp"),
            Map.entry("small_at", "timestamp"), Map.entry("seen_at", "timestamp_tz"), Map.entry("photo", "binary"),
            Map.entry("place", "text"), Map.entry("org", "text"), Map.entry("extra", "text"));
        kinds.forEach((name, kind) -> assertEquals(kind, f.get(name).kind(), name));
        assertEquals("datetimeoffset", f.get("seen_at").sourceType());

        AssetInfo premier = assets.get("crm.premier");
        assertEquals("view", premier.kind());
        assertTrue(premier.bounded(), "views are sampled");
        assertEquals(3, premier.fields().size());
    }

    @Test
    void schemasWhoseNamesHoldWildcardsKeepTheirOwnTables() throws Exception {
        assertEquals(List.of("one"), columnNames(assets.get("a_b.t")));
        assertEquals(List.of("one"), assets.get("a_b.t").primaryKey());
        assertEquals(List.of("two", "three"), columnNames(assets.get("axb.t")));
        assertEquals(List.of("four"), columnNames(assets.get("s[1].t")));
        assertEquals(List.of("four"), assets.get("s[1].t").primaryKey());
        assertEquals("1", rowCount(read(assets.get("s[1].t"), 0)).attributes().get("rows"));

        SourceConfig some = new SourceConfig("lab", "sqlserver", Map.of("url", source.setting("url"), "username",
            DB.getUsername(), "schemas", "a_b; S[1]"), DB.getPassword());
        assertEquals(List.of("a_b.t", "s[1].t"), CONNECTOR.catalog(some).stream().map(AssetInfo::path).toList(),
            "requested schemas match exactly, or failing that ignoring case");

        // the escaped patterns themselves match only their own schema, so the server does the filtering
        try (Connection c = DriverManager.getConnection(source.setting("url"), DB.getUsername(), DB.getPassword())) {
            for (String schema : List.of("a_b", "s[1]")) {
                List<String> found = new ArrayList<>();
                try (ResultSet rs = c.getMetaData().getTables(null, CONNECTOR.schemaPattern(c.getMetaData(), schema),
                    "%", new String[] {"TABLE"})) {
                    while (rs.next()) {
                        found.add(rs.getString("TABLE_SCHEM") + "." + rs.getString("TABLE_NAME"));
                    }
                }
                assertEquals(List.of(schema + ".t"), found, schema);
            }
        }
    }

    @Test
    void readsAndProfilesEveryRowInStream() throws Exception {
        List<MetadataEvent> events = read(assets.get("crm.customers"), 0);
        MetadataEvent rows = rowCount(events);
        assertEquals("20000", rows.attributes().get("rows"));
        assertEquals("true", rows.attributes().get("complete"));

        Map<String, Map<String, String>> profile = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> profile.put(e.attributes().get("field"), e.attributes()));
        assertEquals(25, profile.size());

        assertEquals("400", profile.get("email").get("nulls"));
        assertEquals("text", profile.get("email").get("shape"));
        Map<String, String> segment = profile.get("segment");
        assertEquals("3", segment.get("distinct"));
        assertTrue(segment.get("top").startsWith("[{\"value\":\"Retail\",\"count\":10000}"), segment.get("top"));
        String city = profile.get("city").get("top");
        assertTrue(city.contains("{\"value\":\"東京\",\"count\":5000}") && city.contains("\"Zürich\""), city);

        assertEquals("1.123457", profile.get("balance").get("min"));
        assertEquals("0.25", profile.get("limit_gbp").get("min"));
        assertEquals("999.25", profile.get("limit_gbp").get("max"));
        assertEquals("-99.5", profile.get("fee").get("min"));
        assertEquals("6666", profile.get("opted_in").get("true"));
        assertEquals("255", profile.get("age").get("max"), "tinyint is unsigned");
        assertEquals("-500", profile.get("rank_no").get("min"));
        assertEquals("20000000000000", profile.get("visits").get("max"));
        assertEquals("1950-01-02", profile.get("born").get("min"));
        assertEquals("00:00:01.123456", profile.get("wakes").get("min"), "time keeps microseconds");
        assertEquals("2024-01-01T00:01:00.123456Z", profile.get("created_at").get("min"),
            "datetime2 keeps microseconds");
        assertEquals("2024-01-01T00:01:00Z", profile.get("small_at").get("min"));
        assertEquals("2024-01-01T00:01:00.500Z", profile.get("seen_at").get("min"), "datetimeoffset in UTC");
        assertEquals("timestamp", profile.get("seen_at").get("shape"));

        assertEquals("text", profile.get("token").get("shape"));
        assertEquals("36", profile.get("token").get("max_length"));
        assertEquals("2000", profile.get("doc").get("nulls"));
        assertEquals("binary", profile.get("photo").get("shape"));
        assertEquals("10000", profile.get("place").get("nulls"));
        assertTrue(profile.get("place").get("top").contains("\"POINT (-0.125 51.5)\""), profile.get("place").get("top"));
        assertTrue(profile.get("org").get("top").contains("\"/3/\""), profile.get("org").get("top"));
        assertEquals("7", profile.get("extra").get("distinct"));
    }

    @Test
    void aSqlVariantHoldingBytesIsReadAsHex() throws Exception {
        Map<String, String> v = profile(read(assets.get("Ops.variants"), 0)).get("v");
        assertEquals("text", v.get("shape"));
        assertEquals("2", v.get("nulls"));
        String top = v.get("top");
        assertTrue(top.contains("\"0xCAFE\"") && top.contains("\"12\"") && top.contains("\"text\""), top);
        assertTrue(!top.contains("[B@"), top);
    }

    @Test
    void readsRunAtTheIsolationLevelChosen() throws Exception {
        AssetInfo view = assets.get("Ops.isolation_level");
        // sys.dm_exec_sessions numbers them 1 read uncommitted, 2 read committed, 5 snapshot
        Map<String, String> levels = Map.of("", "1", "read-uncommitted", "1", "read-committed", "2", "snapshot", "5");
        for (var e : levels.entrySet()) {
            Map<String, String> settings = new HashMap<>(source.settings());
            settings.put("isolation", e.getKey());
            SourceConfig s = new SourceConfig("lab", "sqlserver", settings, DB.getPassword());
            assertTrue(CONNECTOR.validate(s).isEmpty());
            assertEquals(e.getValue(), profile(read(s, view, 0)).get("level").get("min"), "isolation " + e.getKey());
        }
    }

    @Test
    void aReadStoppedEarlyEndsAtOnce() throws Exception {
        // 20 million rows: closing the result without cancelling the query first reads them all
        Map<String, String> input = Map.of("url", source.setting("url"), "user", DB.getUsername(), "password",
            DB.getPassword(), "query", "SELECT TOP (20000000) a.object_id, a.column_id, b.name "
                + "FROM sys.all_columns a CROSS JOIN sys.all_columns b",
            "label", "a big join", "asset-urn", URN, "batch-size", "1024");
        RunResult r = new Runner(64L << 20, 2).run(new Pipeline("stop", List.of(
            new NodeSpec("read", new JdbcTableInput(), input),
            new NodeSpec("give-up", new GiveUpAfterOneBatch(), Map.of()))), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("gave up"), r.error());
        assertTrue(r.duration().compareTo(Duration.ofSeconds(5)) < 0, "took " + r.duration());
    }

    @Test
    void readsATableWhoseNameNeedsQuoting() throws Exception {
        List<MetadataEvent> events = read(assets.get("Ops.odd]name"), 0);
        assertEquals("2", rowCount(events).attributes().get("rows"));
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        MetadataEvent rows = rowCount(read(assets.get("crm.customers"), 1000));
        assertEquals("1000", rows.attributes().get("rows"));
        assertEquals("false", rows.attributes().get("complete"));
    }

    @Test
    void aMissingTableFailsWithAClearMessage() throws Exception {
        AssetInfo nope = new AssetInfo("crm.nope", "table", "crm", "nope", null, List.of(), List.of(), false, true,
            Map.of("schema", "crm", "table", "nope"));
        RunResult r = run(nope, 0, new CopyOnWriteArrayList<>());
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Cannot read crm.nope") && r.error().contains("Invalid object name"), r.error());
    }

    @Test
    void aSelfSignedCertificateIsTrustedOnlyWhenTheSettingSaysSo() throws Exception {
        // the container's certificate is self-signed, as the reference lab's is
        String encrypted = "jdbc:sqlserver://" + DB.getHost() + ":" + DB.getMappedPort(1433)
            + ";databaseName=sales;encrypt=true";
        SourceConfig strict = new SourceConfig("lab", "sqlserver",
            Map.of("url", encrypted, "username", DB.getUsername()), DB.getPassword());
        ConnectorException e = assertThrows(ConnectorException.class, () -> CONNECTOR.test(strict));
        assertTrue(e.getMessage().contains("trust_server_certificate=true"), e.getMessage());

        SourceConfig trusting = new SourceConfig("lab", "sqlserver",
            Map.of("url", encrypted, "username", DB.getUsername(), "trust_server_certificate", "true"),
            DB.getPassword());
        CONNECTOR.test(trusting);
        assertTrue(CONNECTOR.catalog(trusting).stream().anyMatch(a -> a.path().equals("crm.customers")));
        assertEquals("2", rowCount(read(trusting, assets.get("Ops.odd]name"), 0)).attributes().get("rows"));
    }

    @Test
    void aWrongPasswordSaysWhatToCheck() {
        SourceConfig wrong = new SourceConfig("lab", "sqlserver", source.settings(), "not-the-password");
        ConnectorException e = assertThrows(ConnectorException.class, () -> CONNECTOR.catalog(wrong));
        assertTrue(e.getMessage().startsWith("Cannot read the catalogue of lab: Login failed"), e.getMessage());
        assertTrue(e.getMessage().contains("Check the user name and password"), e.getMessage());
        assertTrue(!e.getMessage().contains("not-the-password"));
    }

    private static List<MetadataEvent> read(AssetInfo asset, long rowLimit) throws InterruptedException {
        return read(source, asset, rowLimit);
    }

    private static List<MetadataEvent> read(SourceConfig from, AssetInfo asset, long rowLimit)
        throws InterruptedException {
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        RunResult r = run(from, asset, rowLimit, events);
        assertTrue(r.succeeded(), r.error());
        return events;
    }

    private static RunResult run(AssetInfo asset, long rowLimit, List<MetadataEvent> events)
        throws InterruptedException {
        return run(source, asset, rowLimit, events);
    }

    private static RunResult run(SourceConfig from, AssetInfo asset, long rowLimit, List<MetadataEvent> events)
        throws InterruptedException {
        NodeSpec reader = CONNECTOR.reader(from, asset, new Connector.ReadOptions(URN, rowLimit, 4096));
        Pipeline p = new Pipeline("ingest", List.of(reader,
            new NodeSpec("profile", new ProfileNode(), Map.of("asset-urn", URN))));
        return new Runner(128L << 20, 2).run(p, events::add);
    }

    private static Map<String, Map<String, String>> profile(List<MetadataEvent> events) {
        Map<String, Map<String, String>> profile = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> profile.put(e.attributes().get("field"), e.attributes()));
        return profile;
    }

    private static List<String> columnNames(AssetInfo asset) {
        return asset.fields().stream().map(AssetInfo.FieldInfo::name).toList();
    }

    /** A node that takes one batch and fails, as a profiler or sink might part way through a table. */
    private static final class GiveUpAfterOneBatch implements Node {

        @Override
        public String type() {
            return "give-up";
        }

        @Override
        public void init(NodeContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try (VectorSchemaRoot batch = context.input().take(context.allocator())) {
                throw new NodeException("gave up after " + (batch == null ? 0 : batch.getRowCount()) + " rows");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        }
    }

    private static MetadataEvent rowCount(List<MetadataEvent> events) {
        return events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst().orElseThrow();
    }
}
