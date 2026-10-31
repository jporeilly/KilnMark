package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.CatalogInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.RelationshipInfo;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileNode;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * The MySQL and MariaDB connectors against a real server: databases as schemas, every type, keys,
 * comments, sampling, streaming, quoting and failures. Run for each product by
 * {@link MySqlIngestTest} and {@link MariaDbIngestTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MySqlFamilyIngestTest {

    private static final String URN = "urn:kilnmark:shop:kt_shop.orders";

    /** The server to test against. */
    abstract MySqlServers.Server server();

    private MySqlServers.Server db;
    private SourceConfig source;
    private Map<String, AssetInfo> assets;
    private CatalogInfo catalog;

    @BeforeAll
    void load() throws Exception {
        db = server();
        db.sql("DROP DATABASE IF EXISTS kt_shop", "DROP DATABASE IF EXISTS kt_stock",
            "DROP DATABASE IF EXISTS `kt odd``db`", "CREATE DATABASE kt_shop CHARACTER SET utf8mb4",
            "CREATE DATABASE kt_stock CHARACTER SET utf8mb4", "CREATE DATABASE `kt odd``db` CHARACTER SET utf8mb4");
        db.sql("SET SESSION sql_mode = ''",
            """
            CREATE TABLE kt_stock.products (
                sku       VARCHAR(20) NOT NULL PRIMARY KEY,
                title     VARCHAR(100) NOT NULL COMMENT 'What the shelf label says',
                UNIQUE KEY uk_title (title)
            ) COMMENT = 'Things we sell'""",
            """
            CREATE TABLE kt_shop.customers (
                customer_id INT UNSIGNED NOT NULL PRIMARY KEY,
                email       VARCHAR(200),
                UNIQUE KEY uk_email (email),
                UNIQUE KEY uk_email_prefix (email(10), customer_id)
            )""",
            """
            CREATE TABLE kt_shop.orders (
                order_id    BIGINT NOT NULL PRIMARY KEY,
                customer_id INT UNSIGNED NOT NULL,
                sku         VARCHAR(20),
                flag        TINYINT(1),
                tiny        TINYINT,
                tiny_u      TINYINT UNSIGNED,
                small       SMALLINT,
                small_u     SMALLINT UNSIGNED,
                medium      MEDIUMINT,
                medium_u    MEDIUMINT UNSIGNED,
                whole       INT,
                whole_u     INT UNSIGNED,
                big         BIGINT,
                big_u       BIGINT UNSIGNED,
                price       DECIMAL(10,2),
                vast        DECIMAL(65,10),
                ratio       FLOAT,
                score       DOUBLE,
                bit1        BIT(1),
                bits        BIT(5),
                bit64       BIT(64),
                made        YEAR,
                due         DATE,
                took        TIME(6),
                placed      DATETIME(6),
                changed     TIMESTAMP(6) NULL,
                doc         JSON,
                size        ENUM('small','medium','large'),
                tags        SET('gift','fragile','express'),
                photo       BLOB,
                note        TEXT,
                code        CHAR(3),
                raw         VARBINARY(8),
                spot        POINT,
                CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES kt_shop.customers (customer_id),
                CONSTRAINT fk_sku FOREIGN KEY (sku) REFERENCES kt_stock.products (sku)
            ) COMMENT = 'Orders as placed'""",
            "INSERT INTO kt_stock.products VALUES ('SKU-1', 'Blue mug'), ('SKU-2', 'Red mug')",
            "INSERT INTO kt_shop.customers VALUES (1, 'ada@example.com'), (4294967295, NULL)",
            """
            INSERT INTO kt_shop.orders VALUES (1, 1, 'SKU-1', 1, -128, 255, -32768, 65535, -8388608, 16777215,
                -2147483648, 4294967295, -9223372036854775808, 18446744073709551615, 12345678.91,
                12345678901234567890123456789012345678901234567890.0123456789, 1.5, 2.25, b'1', b'10101',
                b'1111111111111111111111111111111111111111111111111111111111111111', 2024, '2024-02-29',
                '-838:59:59.000000', '2024-01-01 10:00:00.123456', '2024-06-01 12:00:00.500000',
                '{"lines": [1, 2]}', 'large', 'gift,express', x'CAFE', 'héllo ✓', 'abc', x'00FF',
                ST_GeomFromText('POINT(-0.1 51.5)'))""",
            """
            INSERT INTO kt_shop.orders (order_id, customer_id, flag, due, took, placed, changed, made)
            VALUES (2, 4294967295, 2, '0000-00-00', '100:00:00', '0000-00-00 00:00:00', '0000-00-00 00:00:00', 0)""",
            "INSERT INTO kt_shop.orders (order_id, customer_id, due, took) VALUES (3, 1, '2024-00-15', '23:59:59.999999')",
            "ALTER TABLE kt_shop.orders MODIFY due DATE COMMENT 'When it should arrive'",
            "CREATE VIEW kt_shop.big_orders AS SELECT order_id, price FROM kt_shop.orders WHERE price > 100",
            "CREATE TABLE `kt odd``db`.`odd``name with space` (`select` INT, `ü` VARCHAR(10))",
            "INSERT INTO `kt odd``db`.`odd``name with space` VALUES (1, 'één'), (2, NULL)",
            "CREATE TABLE kt_shop.numbers (n INT NOT NULL PRIMARY KEY, label VARCHAR(20))",
            """
            INSERT INTO kt_shop.numbers
            SELECT a.d + 10 * b.d + 100 * c.d + 1000 * e.d + 10000 * f.d, CONCAT('n', a.d)
            FROM (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                  SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a,
                 (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                  SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b,
                 (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                  SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c,
                 (SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL
                  SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) e,
                 (SELECT 0 d UNION ALL SELECT 1) f""",
            "ANALYZE TABLE kt_shop.numbers",
            // a view of millions of rows, for stopping a read early
            "CREATE VIEW kt_shop.many AS SELECT a.n AS a, b.n AS b FROM kt_shop.numbers a CROSS JOIN kt_shop.numbers b");
        source = db.source("shop", Map.of("schemas", "kt_shop, kt_stock, kt odd`db"));
        catalog = db.connector().catalogWithRelationships(source);
        assets = catalog.assets().stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    @Test
    void theConnectionTestPasses() throws Exception {
        db.connector().test(source, Duration.ofSeconds(20));
        assertTrue(db.connector().validate(source).isEmpty(), db.connector().validate(source).toString());
    }

    @Test
    void databasesAreSchemasAndTheServersOwnAreLeftOut() throws Exception {
        List<String> names = db.connector().containers(db.source("shop", Map.of())).stream()
            .map(Connector.ContainerInfo::name).toList();
        assertTrue(names.containsAll(List.of("kt_shop", "kt_stock", "kt odd`db")), names.toString());
        assertTrue(names.stream().noneMatch(MySqlCatalog::isSystem), names.toString());
        Map<String, Long> counts = db.connector().containers(source).stream()
            .collect(Collectors.toMap(Connector.ContainerInfo::name, Connector.ContainerInfo::count));
        assertEquals(5L, counts.get("kt_shop"), "customers, orders, numbers and the views big_orders and many");

        SourceConfig one = db.source("shop", Map.of("database", "kt_stock"));
        assertEquals(List.of("kt_stock"), db.connector().containers(one).stream().map(Connector.ContainerInfo::name)
            .toList(), "a source keeping to one database lists only it");
        assertEquals(List.of("kt_stock.products"), db.connector().catalog(one).stream().map(AssetInfo::path).toList());
    }

    @Test
    void catalogHasTypesKeysCommentsAndRowEstimates() {
        AssetInfo orders = assets.get("kt_shop.orders");
        assertEquals("table", orders.kind());
        assertEquals("kt_shop", orders.container());
        assertEquals("Orders as placed", orders.description());
        assertEquals(List.of("order_id"), orders.primaryKey());
        assertFalse(orders.bounded());
        assertTrue(orders.hints().containsKey("row_estimate"), orders.hints().toString());
        Map<String, AssetInfo.FieldInfo> f = orders.fields().stream()
            .collect(Collectors.toMap(AssetInfo.FieldInfo::name, Function.identity()));
        assertEquals("When it should arrive", f.get("due").description());
        Map<String, String> kinds = Map.ofEntries(Map.entry("flag", "boolean"), Map.entry("tiny", "int16"),
            Map.entry("tiny_u", "int16"), Map.entry("small", "int16"), Map.entry("small_u", "int32"),
            Map.entry("medium", "int32"), Map.entry("medium_u", "int32"), Map.entry("whole", "int32"),
            Map.entry("whole_u", "int64"), Map.entry("big", "int64"), Map.entry("big_u", "decimal"),
            Map.entry("price", "decimal"), Map.entry("vast", "text"), Map.entry("ratio", "float32"),
            Map.entry("score", "float64"), Map.entry("bit1", "boolean"), Map.entry("bits", "int64"),
            Map.entry("bit64", "decimal"), Map.entry("made", "int16"), Map.entry("due", "date"),
            Map.entry("took", "text"), Map.entry("placed", "timestamp"), Map.entry("changed", "timestamp_tz"),
            Map.entry("doc", "text"), Map.entry("size", "text"), Map.entry("tags", "text"),
            Map.entry("photo", "binary"), Map.entry("note", "text"), Map.entry("code", "text"),
            Map.entry("raw", "binary"), Map.entry("spot", "text"));
        kinds.forEach((name, kind) -> assertEquals(kind, f.get(name).kind(), name));
        assertEquals("bigint unsigned", f.get("big_u").sourceType().replace("(20)", ""));
        assertTrue(f.get("size").sourceType().startsWith("enum("), f.get("size").sourceType());

        AssetInfo customers = assets.get("kt_shop.customers");
        assertEquals("uk_email(email)", customers.hints().get("unique_keys"),
            "a unique index on a prefix of a column is not a unique key of it");
        AssetInfo products = assets.get("kt_stock.products");
        assertEquals("Things we sell", products.description());
        assertEquals("What the shelf label says", products.fields().get(1).description());

        AssetInfo view = assets.get("kt_shop.big_orders");
        assertEquals("view", view.kind());
        assertTrue(view.bounded());
        assertNull(view.description());
        assertFalse(view.hints().containsKey("row_estimate"));
    }

    @Test
    void foreignKeysAcrossDatabasesAreRelationships() {
        List<RelationshipInfo> r = catalog.relationships().stream()
            .sorted(java.util.Comparator.comparing(RelationshipInfo::name)).toList();
        assertEquals(2, r.size(), r.toString());
        assertEquals("kt_shop.orders", r.get(0).fromPath());
        assertEquals(List.of("customer_id"), r.get(0).fromFields());
        assertEquals("kt_shop.customers", r.get(0).toPath());
        assertEquals("fk_sku", r.get(1).name());
        assertEquals("kt_stock.products", r.get(1).toPath());
        assertEquals(List.of("sku"), r.get(1).toFields());
    }

    @Test
    void readsEveryTypeAsItsKind() throws Exception {
        Rows.Collected back = collect(source, assets.get("kt_shop.orders"));
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < back.columns.size(); i++) {
            at.put(back.columns.get(i).name(), i);
        }
        back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
        List<Object> one = back.rows.get(0);
        Function<String, Object> v = name -> one.get(at.get(name));
        assertEquals(true, v.apply("flag"));
        assertEquals(-128L, v.apply("tiny"));
        assertEquals(255L, v.apply("tiny_u"));
        assertEquals(-32768L, v.apply("small"));
        assertEquals(65535L, v.apply("small_u"));
        assertEquals(-8388608L, v.apply("medium"));
        assertEquals(16777215L, v.apply("medium_u"));
        assertEquals(-2147483648L, v.apply("whole"));
        assertEquals(4294967295L, v.apply("whole_u"));
        assertEquals(Long.MIN_VALUE, v.apply("big"));
        assertEquals(new BigDecimal("18446744073709551615"), v.apply("big_u"));
        assertEquals(0, new BigDecimal("12345678.91").compareTo((BigDecimal) v.apply("price")));
        assertEquals("12345678901234567890123456789012345678901234567890.0123456789", v.apply("vast"));
        assertEquals(1.5f, v.apply("ratio"));
        assertEquals(2.25, v.apply("score"));
        assertEquals(true, v.apply("bit1"));
        assertEquals(21L, v.apply("bits"));
        assertEquals(new BigDecimal("18446744073709551615"), v.apply("bit64"));
        assertEquals(2024L, v.apply("made"));
        assertEquals(LocalDate.of(2024, 2, 29), v.apply("due"));
        assertEquals("-838:59:59.000000", v.apply("took"));
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000), v.apply("placed"));
        assertEquals(java.time.Instant.parse("2024-06-01T12:00:00.500Z"), v.apply("changed"),
            "TIMESTAMP is an instant, in UTC whatever the zones");
        assertTrue(((String) v.apply("doc")).contains("\"lines\""), (String) v.apply("doc"));
        assertEquals("large", v.apply("size"));
        assertEquals("gift,express", v.apply("tags"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {(byte) 0xCA, (byte) 0xFE},
            (byte[]) v.apply("photo"));
        assertEquals("héllo ✓", v.apply("note"));
        assertEquals("abc", v.apply("code"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {0, (byte) 0xFF}, (byte[]) v.apply("raw"));
        assertEquals("POINT(-0.1 51.5)", v.apply("spot"), "well-known text, longitude first");

        List<Object> zero = back.rows.get(1);
        assertEquals(true, zero.get(at.get("flag")), "a TINYINT(1) holding 2 is true");
        assertNull(zero.get(at.get("due")), "0000-00-00 has no date");
        assertNull(zero.get(at.get("placed")));
        assertNull(zero.get(at.get("changed")));
        assertNull(zero.get(at.get("made")), "YEAR 0000 is MySQL's zero year");
        assertEquals("100:00:00.000000", zero.get(at.get("took")), "TIME past 24 hours is kept");
        List<Object> three = back.rows.get(2);
        assertNull(three.get(at.get("due")), "a zero month has no date");
        assertEquals("23:59:59.999999", three.get(at.get("took")));
    }

    @Test
    void zeroDatesAreCountedInTheRowCount() throws Exception {
        List<MetadataEvent> events = profiled(source, assets.get("kt_shop.orders"), 0);
        MetadataEvent rows = rowCount(events);
        assertEquals("3", rows.attributes().get("rows"));
        assertEquals("5", rows.attributes().get("zero_dates"), "row 2: due, placed, changed and made; row 3: due");
    }

    @Test
    void tinyint1CanBeReadAsANumber() throws Exception {
        SourceConfig numbers = db.source("shop", Map.of("schemas", "kt_shop", "tinyint1_as_boolean", "false"));
        AssetInfo orders = db.connector().asset(numbers, "kt_shop.orders");
        assertEquals("int16", orders.fields().stream().filter(x -> x.name().equals("flag")).findFirst().orElseThrow()
            .kind());
        Rows.Collected back = collect(numbers, orders);
        back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
        assertEquals(2L, back.rows.get(1).get(3));
    }

    @Test
    void namesThatNeedQuotingAreRead() throws Exception {
        AssetInfo odd = assets.get("kt odd`db.odd`name with space");
        assertEquals(List.of("select", "ü"), odd.fields().stream().map(AssetInfo.FieldInfo::name).toList());
        assertEquals("2", rowCount(profiled(source, odd, 0)).attributes().get("rows"));
        assertEquals("kt odd`db.odd`name with space", db.connector().asset(source, "kt odd`db.odd`name with space")
            .path());
    }

    @Test
    void aLargeTableIsSampledWhenTheSourceAsks() throws Exception {
        SourceConfig sampling = db.source("shop", Map.of("schemas", "kt_shop",
            JdbcConnector.TABLE_SAMPLE_ABOVE, "2000"));
        MetadataEvent rows = rowCount(profiled(sampling, assets.get("kt_shop.numbers"), 0));
        assertEquals("true", rows.attributes().get("sampled"), rows.attributes().toString());
        assertEquals("false", rows.attributes().get("complete"));
        long read = Long.parseLong(rows.attributes().get("rows"));
        assertTrue(read > 500 && read < 6000, "about 2,000 of 20,000 rows: " + read);
    }

    /**
     * A narrowed read: the columns asked for, and the server applies the filter with each value bound.
     * The rows read are what the server itself finds for the same condition (its collation ignores case,
     * so 'N7' finds 'n7' too: the read node then drops what Kilnmark's own comparison does not keep).
     */
    @Test
    void aNarrowedReadSelectsTheColumnsAndBindsTheValues() throws Exception {
        AssetInfo numbers = assets.get("kt_shop.numbers");
        assertTrue(db.connector().narrowsReads(source, numbers));
        io.kilnmark.kiln.connect.ReadFilter filter = new io.kilnmark.kiln.connect.ReadFilter.And(List.of(
            new io.kilnmark.kiln.connect.ReadFilter.Between("n", 100L, 199L, false),
            new io.kilnmark.kiln.connect.ReadFilter.In("label", List.of("n3", "N7", "x' OR '1'='1"), false)));
        NodeSpec reader = db.connector().reader(source, numbers, new Connector.ReadOptions(URN, 0, 4096,
            List.of("n"), filter));
        assertEquals("SELECT `n` FROM `kt_shop`.`numbers` WHERE (`n` BETWEEN ? AND ? AND `label` IN (?, ?, ?))",
            reader.settings().get("query"));
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        RunResult r = new Runner(128L << 20, 2).run(new Pipeline("narrowed", List.of(reader,
            new NodeSpec("profile", new ProfileNode(), Map.of("asset-urn", URN)))), events::add);
        assertTrue(r.succeeded(), r.error());
        long expected;
        try (var c = db.connector().connect(source); var s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM kt_shop.numbers WHERE n BETWEEN 100 AND 199 "
                 + "AND label IN ('n3', 'N7')")) {
            rs.next();
            expected = rs.getLong(1);
        }
        assertEquals(20, expected, "the server ignores case");
        assertEquals(Long.toString(expected), rowCount(events).attributes().get("rows"));
        assertEquals(1, events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE).count(), "only n");
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        MetadataEvent rows = rowCount(profiled(source, assets.get("kt_shop.numbers"), 1000));
        assertEquals("1000", rows.attributes().get("rows"));
        assertEquals("false", rows.attributes().get("complete"));
    }

    @Test
    void aReadStoppedEarlyEndsAtOnce() throws Exception {
        NodeSpec reader = db.connector().reader(source, assets.get("kt_shop.many"),
            new Connector.ReadOptions(URN, 0, 1024));
        RunResult r = new Runner(64L << 20, 2).run(new Pipeline("stop", List.of(reader,
            new NodeSpec("give-up", new GiveUpAfterOneBatch(), Map.of()))), e -> { });
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("gave up"), r.error());
        assertTrue(r.duration().compareTo(Duration.ofSeconds(10)) < 0, "took " + r.duration());
    }

    @Test
    void aMissingTableFailsWithAClearMessage() throws Exception {
        AssetInfo nope = new AssetInfo("kt_shop.nope", "table", "kt_shop", "nope", null, List.of(), List.of(), false,
            true, Map.of("schema", "kt_shop", "table", "nope"));
        RunResult r = run(source, nope, 0, new CopyOnWriteArrayList<>());
        assertEquals(RunResult.Status.FAILED, r.status());
        assertTrue(r.error().contains("Cannot read kt_shop.nope") && r.error().contains("doesn't exist"), r.error());
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> db.connector().asset(source, "kt_shop.nope"));
        assertTrue(e.getMessage().contains("no table or view kt_shop.nope"), e.getMessage());
    }

    @Test
    void aWrongPasswordSaysWhatToCheck() {
        SourceConfig wrong = new SourceConfig("shop", db.kind(), source.settings(), "not-the-password");
        ConnectorException e = assertThrows(ConnectorException.class, () -> db.connector().catalog(wrong));
        assertTrue(e.getMessage().startsWith("Cannot read the catalogue of shop: "), e.getMessage());
        assertTrue(e.getMessage().contains("Check the user name and password"), e.getMessage());
        assertFalse(e.getMessage().contains("not-the-password"));
    }

    @Test
    void theOtherProductIsRefusedWithAHint() {
        MySqlConnector other = db.mariadb() ? new MySqlConnector() : new MariaDbConnector();
        SourceConfig s = new SourceConfig("shop", other.kind(), source.settings(), source.secret());
        ConnectorException e = assertThrows(ConnectorException.class, () -> other.test(s, Duration.ofSeconds(20)));
        assertTrue(e.getMessage().contains("Add it as a " + (db.mariadb() ? "MariaDB" : "MySQL") + " source"),
            e.getMessage());
    }

    @Test
    void tlsIsUsedWhenAskedFor() throws Exception {
        SourceConfig required = db.source("shop", Map.of("schemas", "kt_shop", "tls_mode", "required"));
        db.connector().test(required, Duration.ofSeconds(20));
        try (var c = db.connector().connect(required); var s = c.createStatement();
             var rs = s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            assertTrue(rs.next() && !rs.getString(2).isEmpty(), "encrypted");
        }
        try (var c = db.connector().connect(required); var s = c.createStatement();
             var rs = s.executeQuery("SELECT @@SESSION.time_zone")) {
            assertTrue(rs.next());
            assertEquals("+00:00", rs.getString(1));
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
}
