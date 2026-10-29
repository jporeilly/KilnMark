package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.CatalogInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.DriverFolder;
import io.kilnmark.kiln.connect.RelationshipInfo;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileNode;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * Reading Oracle Database Free (pinned in {@link OracleServers}) through the customer's driver in
 * the drivers folder: schemas, every type, keys, comments, sampling, the session's settings,
 * quoting and failures. Tagged {@code oracle}: {@code ./gradlew :kiln-plugins:oracleTest}, which
 * CI's heavy-databases job runs.
 */
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleIngestTest {

    private static final String URN = "urn:kilnmark:sales:KT_SALES.ORDERS";
    static final String PASSWORD = "Kt-pw-2026";

    private OracleServers.Server db;
    private SourceConfig source;
    private Map<String, AssetInfo> assets;
    private CatalogInfo catalog;

    @BeforeAll
    void load() throws Exception {
        db = OracleServers.oracle();
        db.quietly("DROP USER kt_logistics CASCADE", "DROP USER kt_sales CASCADE", "DROP USER kt_reader CASCADE");
        db.sql("CREATE USER kt_sales IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
            "CREATE USER kt_logistics IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
            "CREATE USER kt_reader IDENTIFIED BY \"" + PASSWORD + "\"",
            "GRANT CREATE SESSION TO kt_reader",
            """
            CREATE TABLE kt_sales.customers (
                customer_id NUMBER(10) PRIMARY KEY,
                email       VARCHAR2(200 CHAR),
                CONSTRAINT uk_email UNIQUE (email)
            )""",
            """
            CREATE TABLE kt_sales.orders (
                order_id    NUMBER(18) PRIMARY KEY,
                customer_id NUMBER(10) NOT NULL,
                small       NUMBER(4),
                whole       NUMBER(9),
                wide        NUMBER(30),
                price       NUMBER(10,2),
                hundreds    NUMBER(5,-2),
                tiny_frac   NUMBER(2,5),
                plain       NUMBER,
                star        NUMBER(*,0),
                approx      FLOAT(63),
                ratio       BINARY_FLOAT,
                score       BINARY_DOUBLE,
                due         DATE,
                placed      TIMESTAMP(6),
                changed     TIMESTAMP(6) WITH TIME ZONE,
                seen        TIMESTAMP WITH LOCAL TIME ZONE,
                waited      INTERVAL DAY(2) TO SECOND(6),
                flag        BOOLEAN,
                note        VARCHAR2(100 CHAR),
                code        CHAR(3),
                nnote       NVARCHAR2(20),
                body        CLOB,
                photo       BLOB,
                raw_id      RAW(4),
                doc         JSON,
                CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES kt_sales.customers (customer_id)
            )""",
            "COMMENT ON TABLE kt_sales.orders IS 'Orders as placed'",
            "COMMENT ON COLUMN kt_sales.orders.due IS 'When it should arrive'",
            "INSERT INTO kt_sales.customers VALUES (1, 'ada@example.com')",
            "INSERT INTO kt_sales.customers VALUES (2, NULL)",
            """
            INSERT INTO kt_sales.orders VALUES (1, 1, -9999, 123456789, 123456789012345678901234567890, 12345678.91,
                12300, 0.00012, 1234.5000, 42, 1.5, 2.5, 3.25, TIMESTAMP '2024-02-29 10:11:12',
                TIMESTAMP '2024-01-01 10:00:00.123456', TIMESTAMP '2024-06-01 14:00:00.5 +02:00',
                TIMESTAMP '2024-06-01 12:00:00 +00:00', INTERVAL '2 03:04:05.5' DAY TO SECOND, TRUE, 'héllo ✓', 'abc',
                N'één', 'a long body', HEXTORAW('CAFE'), HEXTORAW('00FF00FF'), '{"lines": [1, 2]}')""",
            // FLOAT(63) keeps 19 digits; the instant is in the hour New York repeats when summer time ends
            "INSERT INTO kt_sales.orders (order_id, customer_id, plain, approx, seen) VALUES (2, 2, 0.000017, "
                + "0.1234567890123456789, TIMESTAMP '2024-11-03 05:30:00.25 +00:00')",
            "CREATE VIEW kt_sales.big_orders AS SELECT order_id, price FROM kt_sales.orders WHERE price > 100",
            """
            CREATE TABLE kt_logistics.shipments (
                shipment_id NUMBER(10) PRIMARY KEY,
                order_id    NUMBER(18) NOT NULL
            )""",
            "GRANT REFERENCES ON kt_sales.orders TO kt_logistics",
            "ALTER TABLE kt_logistics.shipments ADD CONSTRAINT fk_order FOREIGN KEY (order_id) "
                + "REFERENCES kt_sales.orders (order_id)",
            "CREATE TABLE kt_sales.\"odd name\" (\"select\" NUMBER(5), \"Ü\" VARCHAR2(10 CHAR))",
            "INSERT INTO kt_sales.\"odd name\" VALUES (1, 'één')",
            "INSERT INTO kt_sales.\"odd name\" VALUES (2, NULL)",
            "CREATE TABLE kt_sales.numbers (n NUMBER(10) PRIMARY KEY, label VARCHAR2(20))",
            "INSERT INTO kt_sales.numbers SELECT LEVEL, 'n' || MOD(LEVEL, 10) FROM DUAL CONNECT BY LEVEL <= 20000",
            "BEGIN DBMS_STATS.GATHER_TABLE_STATS('KT_SALES', 'NUMBERS'); END;",
            "CREATE VIEW kt_sales.many AS SELECT a.n AS a, b.n AS b FROM kt_sales.numbers a CROSS JOIN kt_sales.numbers b",
            "GRANT SELECT ON kt_sales.customers TO kt_reader");
        source = db.source("sales", Map.of("schemas", "KT_SALES, KT_LOGISTICS"));
        catalog = db.connector().catalogWithRelationships(source);
        assets = catalog.assets().stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    @Test
    void theConnectionTestPassesThroughTheInstalledDriverAlone() throws Exception {
        db.connector().test(source, Duration.ofSeconds(30));
        assertTrue(db.connector().validate(source).isEmpty(), db.connector().validate(source).toString());
        try (Connection c = db.connector().connect(source)) {
            assertNotSame(OracleConnector.class.getClassLoader(), c.getClass().getClassLoader(),
                "the driver is loaded from the drivers folder, in a class loader of its own");
            Map<String, String> session = OracleConnector.session(c);
            assertEquals("+00:00", session.get("time_zone"));
            assertEquals("YYYY-MM-DD HH24:MI:SS", session.get("date_format"));
        }
    }

    @Test
    void withoutItsDriverTheSourceSaysWhoInstallsIt() throws Exception {
        OracleConnector none = new OracleConnector();
        ConnectorException e = assertThrows(ConnectorException.class, () -> none.test(source, Duration.ofSeconds(10)));
        assertTrue(e.getMessage().contains("needs its driver, com.oracle.database.jdbc:ojdbc11 23.26.3.0.0")
            && e.getMessage().contains("Settings, Drivers"), e.getMessage());

        DriverFolder wrong = new DriverFolder(Files.createTempDirectory("kt-wrong").resolve("drivers"));
        wrong.prepare();
        Files.writeString(wrong.file(OracleConnector.DRIVER), "not the driver");
        OracleConnector tampered = (OracleConnector) new OracleConnector()
            .withContext(new ConnectorContext(Map.of(), wrong));
        ConnectorException m = assertThrows(ConnectorException.class,
            () -> tampered.test(source, Duration.ofSeconds(10)));
        assertTrue(m.getMessage().contains("is not the driver Kilnmark expects"), m.getMessage());
    }

    @Test
    void schemasAreUsersAndOraclesOwnAreLeftOut() throws Exception {
        List<String> names = db.connector().containers(db.source("sales", Map.of())).stream()
            .map(Connector.ContainerInfo::name).toList();
        assertTrue(names.containsAll(List.of("KT_SALES", "KT_LOGISTICS")), names.toString());
        assertFalse(names.contains("SYS") || names.contains("SYSTEM") || names.contains("MDSYS"), names.toString());
        Map<String, Long> counts = db.connector().containers(source).stream()
            .collect(Collectors.toMap(Connector.ContainerInfo::name, Connector.ContainerInfo::count));
        assertEquals(6L, counts.get("KT_SALES"), "customers, orders, odd name, numbers and the views big_orders and many");
        SourceConfig lower = db.source("sales", Map.of("schemas", "kt_logistics"));
        assertEquals(List.of("KT_LOGISTICS.SHIPMENTS"), db.connector().catalog(lower).stream().map(AssetInfo::path)
            .toList(), "a schema written in lower case finds the upper-case user");
    }

    @Test
    void catalogHasTypesKeysCommentsAndRowEstimates() {
        AssetInfo orders = assets.get("KT_SALES.ORDERS");
        assertEquals("table", orders.kind());
        assertEquals("KT_SALES", orders.container());
        assertEquals("Orders as placed", orders.description());
        assertEquals(List.of("ORDER_ID"), orders.primaryKey());
        assertFalse(orders.bounded());
        Map<String, AssetInfo.FieldInfo> f = orders.fields().stream()
            .collect(Collectors.toMap(AssetInfo.FieldInfo::name, Function.identity()));
        assertEquals("When it should arrive", f.get("DUE").description());
        Map<String, String> kinds = Map.ofEntries(Map.entry("ORDER_ID", "int64"), Map.entry("SMALL", "int16"),
            Map.entry("WHOLE", "int32"), Map.entry("WIDE", "decimal"), Map.entry("PRICE", "decimal"),
            Map.entry("HUNDREDS", "decimal"), Map.entry("TINY_FRAC", "decimal"), Map.entry("PLAIN", "text"),
            Map.entry("STAR", "decimal"), Map.entry("APPROX", "text"), Map.entry("RATIO", "float32"),
            Map.entry("SCORE", "float64"), Map.entry("DUE", "timestamp"), Map.entry("PLACED", "timestamp"),
            Map.entry("CHANGED", "timestamp_tz"), Map.entry("SEEN", "timestamp_tz"), Map.entry("WAITED", "text"),
            Map.entry("FLAG", "boolean"), Map.entry("NOTE", "text"), Map.entry("CODE", "text"),
            Map.entry("NNOTE", "text"), Map.entry("BODY", "text"), Map.entry("PHOTO", "binary"),
            Map.entry("RAW_ID", "binary"), Map.entry("DOC", "text"));
        kinds.forEach((name, kind) -> assertEquals(kind, f.get(name).kind(), name));
        assertEquals("NUMBER(10,2)", f.get("PRICE").sourceType());
        assertEquals("VARCHAR2(100 CHAR)", f.get("NOTE").sourceType());
        assertEquals("NUMBER", f.get("PLAIN").sourceType());
        assertEquals("uk_email".toUpperCase() + "(EMAIL)", assets.get("KT_SALES.CUSTOMERS").hints().get("unique_keys"));
        assertEquals("20000", assets.get("KT_SALES.NUMBERS").hints().get("row_estimate"));
        AssetInfo view = assets.get("KT_SALES.BIG_ORDERS");
        assertEquals("view", view.kind());
        assertTrue(view.bounded());
    }

    @Test
    void foreignKeysAcrossSchemasAreRelationships() {
        List<RelationshipInfo> r = catalog.relationships().stream()
            .sorted(java.util.Comparator.comparing(RelationshipInfo::name)).toList();
        assertEquals(2, r.size(), r.toString());
        assertEquals("FK_CUSTOMER", r.get(0).name());
        assertEquals("KT_SALES.ORDERS", r.get(0).fromPath());
        assertEquals(List.of("CUSTOMER_ID"), r.get(0).fromFields());
        assertEquals("KT_SALES.CUSTOMERS", r.get(0).toPath());
        assertEquals("FK_ORDER", r.get(1).name());
        assertEquals("KT_LOGISTICS.SHIPMENTS", r.get(1).fromPath());
        assertEquals("KT_SALES.ORDERS", r.get(1).toPath());
    }

    @Test
    void readsEveryTypeAsItsKindWithoutRounding() throws Exception {
        Rows.Collected back = collect(source, assets.get("KT_SALES.ORDERS"));
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < back.columns.size(); i++) {
            at.put(back.columns.get(i).name(), i);
        }
        back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
        List<Object> one = back.rows.get(0);
        Function<String, Object> v = name -> one.get(at.get(name));
        assertEquals(-9999L, v.apply("SMALL"));
        assertEquals(123456789L, v.apply("WHOLE"));
        assertEquals(0, new BigDecimal("123456789012345678901234567890").compareTo((BigDecimal) v.apply("WIDE")));
        assertEquals(0, new BigDecimal("12345678.91").compareTo((BigDecimal) v.apply("PRICE")));
        assertEquals(0, new BigDecimal("12300").compareTo((BigDecimal) v.apply("HUNDREDS")));
        assertEquals(0, new BigDecimal("0.00012").compareTo((BigDecimal) v.apply("TINY_FRAC")));
        assertEquals("1234.5", v.apply("PLAIN"), "a NUMBER without precision is its exact digits");
        assertEquals(0, new BigDecimal("42").compareTo((BigDecimal) v.apply("STAR")));
        assertEquals("1.5", v.apply("APPROX"), "FLOAT(63) read exactly, as text");
        assertEquals(2.5f, v.apply("RATIO"));
        assertEquals(3.25, v.apply("SCORE"));
        assertEquals(LocalDateTime.of(2024, 2, 29, 10, 11, 12), v.apply("DUE"), "a DATE keeps its time of day");
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000), v.apply("PLACED"));
        assertEquals(Instant.parse("2024-06-01T12:00:00.500Z"), v.apply("CHANGED"), "an instant, in UTC");
        assertEquals(Instant.parse("2024-06-01T12:00:00Z"), v.apply("SEEN"));
        assertTrue(((String) v.apply("WAITED")).contains("2 3:4:5.5") || ((String) v.apply("WAITED")).contains("02 03:04:05"),
            (String) v.apply("WAITED"));
        assertEquals(true, v.apply("FLAG"));
        assertEquals("héllo ✓", v.apply("NOTE"));
        assertEquals("abc", v.apply("CODE"));
        assertEquals("één", v.apply("NNOTE"));
        assertEquals("a long body", v.apply("BODY"));
        assertArrayEquals(new byte[] {(byte) 0xCA, (byte) 0xFE}, (byte[]) v.apply("PHOTO"));
        assertArrayEquals(new byte[] {0, (byte) 0xFF, 0, (byte) 0xFF}, (byte[]) v.apply("RAW_ID"));
        assertTrue(((String) v.apply("DOC")).contains("\"lines\""), (String) v.apply("DOC"));
        assertEquals("0.000017", back.rows.get(1).get(at.get("PLAIN")));
        assertEquals("0.1234567890123456789", back.rows.get(1).get(at.get("APPROX")), "every digit FLOAT(63) keeps");
        assertEquals(Instant.parse("2024-11-03T05:30:00.250Z"), back.rows.get(1).get(at.get("SEEN")),
            "the same instant whatever the JVM's time zone (oracleTest runs in America/New_York)");
        assertNull(back.rows.get(1).get(at.get("DUE")));
    }

    @Test
    void namesThatNeedQuotingAreRead() throws Exception {
        AssetInfo odd = assets.get("KT_SALES.odd name");
        assertEquals(List.of("select", "Ü"), odd.fields().stream().map(AssetInfo.FieldInfo::name).toList());
        assertEquals("2", rowCount(profiled(source, odd, 0)).attributes().get("rows"));
        assertEquals("KT_SALES.odd name", db.connector().asset(source, "KT_SALES.odd name").path());
    }

    @Test
    void aLargeTableIsSampledWhenTheSourceAsks() throws Exception {
        SourceConfig sampling = db.source("sales", Map.of("schemas", "KT_SALES",
            JdbcConnector.TABLE_SAMPLE_ABOVE, "2000"));
        MetadataEvent rows = rowCount(profiled(sampling, assets.get("KT_SALES.NUMBERS"), 0));
        assertEquals("true", rows.attributes().get("sampled"), rows.attributes().toString());
        long read = Long.parseLong(rows.attributes().get("rows"));
        assertTrue(read > 500 && read < 6000, "about 2,000 of 20,000 rows: " + read);
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        MetadataEvent rows = rowCount(profiled(source, assets.get("KT_SALES.NUMBERS"), 1000));
        assertEquals("1000", rows.attributes().get("rows"));
        assertEquals("false", rows.attributes().get("complete"));
    }

    @Test
    void aReadStoppedEarlyEndsAtOnce() throws Exception {
        NodeSpec reader = db.connector().reader(source, assets.get("KT_SALES.MANY"),
            new Connector.ReadOptions(URN, 0, 1024));
        RunResult r = new Runner(64L << 20, 2).run(new Pipeline("stop", List.of(reader,
            new NodeSpec("give-up", new GiveUpAfterOneBatch(), Map.of()))), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("gave up"), r.error());
        assertTrue(r.duration().compareTo(Duration.ofSeconds(20)) < 0, "took " + r.duration());
    }

    @Test
    void aMissingTableFailsWithAClearMessage() throws Exception {
        AssetInfo nope = new AssetInfo("KT_SALES.NOPE", "table", "KT_SALES", "NOPE", null, List.of(), List.of(), false,
            true, Map.of("schema", "KT_SALES", "table", "NOPE"));
        RunResult r = run(source, nope, 0, new CopyOnWriteArrayList<>());
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Cannot read KT_SALES.NOPE") && r.error().contains("ORA-00942"), r.error());
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> db.connector().asset(source, "KT_SALES.NOPE"));
        assertTrue(e.getMessage().contains("no table or view KT_SALES.NOPE"), e.getMessage());
    }

    @Test
    void aWrongPasswordSaysWhatToCheck() {
        SourceConfig wrong = new SourceConfig("sales", "oracle", source.settings(), "not-the-password");
        ConnectorException e = assertThrows(ConnectorException.class, () -> db.connector().catalog(wrong));
        assertTrue(e.getMessage().startsWith("Cannot read the catalogue of sales: "), e.getMessage());
        assertTrue(e.getMessage().contains("Check the user name and password"), e.getMessage());
        assertFalse(e.getMessage().contains("not-the-password"));
    }

    @Test
    void aWrongServiceSaysWhatToCheck() {
        SourceConfig wrong = db.source("sales", Map.of("service_name", "NOPE"));
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> db.connector().test(wrong, Duration.ofSeconds(20)));
        assertTrue(e.getMessage().contains("check service_name"), e.getMessage());
    }

    @Test
    void aReaderSeesOnlyWhatItIsGranted() throws Exception {
        SourceConfig reader = db.source("sales", "KT_READER", PASSWORD, Map.of("schemas", "KT_SALES"));
        assertEquals(List.of("KT_SALES.CUSTOMERS"), db.connector().catalog(reader).stream().map(AssetInfo::path)
            .toList());
    }

    @Test
    void readOnlyChecksRunAsTheDialectSays() throws Exception {
        try (Connection c = db.connector().connect(source)) {
            c.setAutoCommit(false);
            c.setReadOnly(true);
            try (Statement s = c.createStatement()) {
                for (String st : SqlDialect.ORACLE.readOnly()) {
                    s.execute(st);
                }
                try (ResultSet rs = s.executeQuery(SqlDialect.ORACLE.limit("SELECT n FROM "
                    + SqlDialect.ORACLE.table("KT_SALES", "NUMBERS") + " WHERE " + SqlDialect.ORACLE.blank("label")
                    + " OR n > 0 ORDER BY n", 3))) {
                    int n = 0;
                    while (rs.next()) {
                        n++;
                    }
                    assertEquals(3, n);
                }
                assertThrows(java.sql.SQLException.class,
                    () -> s.execute("INSERT INTO KT_SALES.NUMBERS VALUES (-1, 'x')"), "a read-only transaction");
            }
            c.rollback();
        }
    }

    private Rows.Collected collect(SourceConfig from, AssetInfo asset) throws Exception {
        Rows.Collected c = new Rows.Collected();
        NodeSpec reader = db.connector().reader(from, asset, new Connector.ReadOptions(URN, 0, 1000));
        RunResult r = Rows.run(reader, new NodeSpec("collect", Rows.collector(c), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        return c;
    }

    private List<MetadataEvent> profiled(SourceConfig from, AssetInfo asset, long rowLimit) throws Exception {
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        RunResult r = run(from, asset, rowLimit, events);
        assertTrue(r.succeeded(), r.error());
        return events;
    }

    private RunResult run(SourceConfig from, AssetInfo asset, long rowLimit, List<MetadataEvent> events)
        throws InterruptedException {
        NodeSpec reader = db.connector().reader(from, asset, new Connector.ReadOptions(URN, rowLimit, 4096));
        Pipeline p = new Pipeline("ingest", List.of(reader,
            new NodeSpec("profile", new ProfileNode(), Map.of("asset-urn", URN))));
        return new Runner(128L << 20, 2).run(p, events::add);
    }

    private static MetadataEvent rowCount(List<MetadataEvent> events) {
        return events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst().orElseThrow();
    }

    /** A node that takes one batch and fails, as a profiler or sink might part way through a table. */
    private static final class GiveUpAfterOneBatch implements io.kilnmark.kiln.Node {

        @Override
        public String type() {
            return "give-up";
        }

        @Override
        public void init(io.kilnmark.kiln.NodeContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(io.kilnmark.kiln.NodeContext context) throws io.kilnmark.kiln.NodeException {
            try (org.apache.arrow.vector.VectorSchemaRoot batch = context.input().take(context.allocator())) {
                throw new io.kilnmark.kiln.NodeException("gave up after " + (batch == null ? 0 : batch.getRowCount())
                    + " rows");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new io.kilnmark.kiln.NodeException("Interrupted", e);
            }
        }
    }
}
