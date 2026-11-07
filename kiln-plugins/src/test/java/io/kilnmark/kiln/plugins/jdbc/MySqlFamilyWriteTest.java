package io.kilnmark.kiln.plugins.jdbc;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * Writing to MySQL and MariaDB in every mode, reading back with the connector's own reader, with
 * the refusals and the rights checks. Run for each product by {@link MySqlWriteTest} and
 * {@link MariaDbWriteTest}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MySqlFamilyWriteTest {

    static final String LAB_PASSWORD = "Lab-pw-2026!x";

    /** The server to test against. */
    abstract MySqlServers.Server server();

    MySqlServers.Server db;
    SourceConfig source;
    SourceConfig lab;

    @BeforeAll
    void setUp() throws Exception {
        db = server();
        // kt_other first: its tables refer to kt_billing's
        db.sql("DROP DATABASE IF EXISTS kt_other", "DROP DATABASE IF EXISTS kt_billing",
            "CREATE DATABASE kt_billing CHARACTER SET utf8mb4", "CREATE DATABASE kt_other CHARACTER SET utf8mb4",
            "DROP USER IF EXISTS 'kt_lab'@'%'", "DROP USER IF EXISTS 'kt_reader'@'%'",
            "CREATE USER 'kt_lab'@'%' IDENTIFIED BY '" + LAB_PASSWORD + "'",
            "CREATE USER 'kt_reader'@'%' IDENTIFIED BY '" + LAB_PASSWORD + "'");
        source = db.source("warehouse", Map.of());
        lab = db.source("warehouse", "kt_lab", LAB_PASSWORD, Map.of());
    }

    List<List<Object>> query(String select) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select)) {
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    Object o = rs.getObject(i);
                    row.add(o instanceof Number n && !(o instanceof BigDecimal) ? n.longValue() : o);
                }
                out.add(row);
            }
            return out;
        }
    }

    static Connector.WriteOptions options(String table, String mode, List<String> key, Map<String, Object> settings) {
        return new Connector.WriteOptions(table, mode, key, null, settings, "urn:kilnmark:warehouse:" + table);
    }

    RunResult write(SourceConfig s, Connector.WriteOptions options, Schema schema, List<Object[]> rows, int batch,
                    int failAt, List<MetadataEvent> events) throws Exception {
        return Rows.run(Rows.source(schema, rows, batch, failAt), db.connector().writer(s, options), events);
    }

    RunResult write(SourceConfig s, Connector.WriteOptions options, Schema schema, List<Object[]> rows, int batch,
                    int failAt) throws Exception {
        return write(s, options, schema, rows, batch, failAt, Rows.events());
    }

    Rows.Collected read(String table) throws Exception {
        Rows.Collected c = new Rows.Collected();
        NodeSpec reader = db.connector().reader(source, db.connector().asset(source, table),
            new Connector.ReadOptions("urn:kilnmark:warehouse:" + table, 0, 1000));
        RunResult r = Rows.run(reader, new NodeSpec("collect", Rows.collector(c), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        return c;
    }

    List<String> leftovers() throws SQLException {
        return query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'kt_billing' "
            + "AND TABLE_NAME LIKE 'kiln%'").stream().map(r -> (String) r.get(0)).toList();
    }

    static final Schema SPEND = Rows.SPEND;

    @Test
    void createWritesEveryKindWithAndWithoutBulkLoad() throws Exception {
        List<Object[]> rows = Rows.allKinds(3000);
        for (boolean bulk : List.of(true, false)) {
            String table = "kt_billing.all_kinds_" + bulk;
            List<MetadataEvent> events = Rows.events();
            RunResult r = write(source, options(table, "create", List.of(), Map.of("bulk_load", bulk)),
                Rows.ALL_KINDS, rows, 700, -1, events);
            assertTrue(r.succeeded(), r.error());
            Rows.Collected back = read(table);
            // TIME reads back as MySQL's text; a timestamp with a zone was written to DATETIME(6) in UTC
            assertEquals(List.of("int64", "int16", "int16", "int32", "float32", "float64", "decimal", "decimal",
                "decimal", "boolean", "text", "binary", "date", "text", "timestamp", "timestamp"),
                back.columns.stream().map(c -> c.kindName()).toList(), table);
            back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
            assertEquals(rows.size(), back.rows.size());
            for (int i = 0; i < rows.size(); i++) {
                for (int c = 0; c < rows.get(i).length; c++) {
                    Object want = rows.get(i)[c];
                    if (want instanceof LocalTime t) {
                        want = MySqlWriteSql.temporal(t);
                    } else if (want instanceof Instant t) {
                        want = LocalDateTime.ofInstant(t, ZoneOffset.UTC);
                    }
                    Rows.assertSameValue(want, back.rows.get(i).get(c), table + " row " + i + " column " + c);
                }
            }
            MetadataEvent count = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
                .orElseThrow();
            assertEquals("3000", count.attributes().get("rows"));
            assertEquals("true", count.attributes().get("written"));
            assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.PROFILE), "profiled as written");
            MetadataEvent schema = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.SCHEMA).findFirst()
                .orElseThrow();
            assertTrue(schema.attributes().get("columns").contains("\"type\":\"longtext\""), schema.attributes().toString());
        }
        assertEquals(List.of(List.of("longtext")), query("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE "
            + "TABLE_SCHEMA = 'kt_billing' AND TABLE_NAME = 'all_kinds_true' AND COLUMN_NAME = 'name'"));
        assertEquals(List.of(), leftovers());
    }

    @Test
    void bulkLoadFallsBackToInsertsWhenTheServerRefusesLocalFiles() throws Exception {
        db.sql("SET GLOBAL local_infile = 0");
        try {
            RunResult r = write(source, options("kt_billing.no_local", "create", List.of(), Map.of()), SPEND,
                Rows.spend(0, 1200, "x"), 500, -1);
            assertTrue(r.succeeded(), r.error());
            assertEquals(List.of(List.of(1200L)), query("SELECT COUNT(*) FROM kt_billing.no_local"));
        } finally {
            db.sql("SET GLOBAL local_infile = 1");
        }
    }

    @Test
    void profilingFollowsTheSourceAndNeverFailsTheWrite() throws Exception {
        List<MetadataEvent> off = Rows.events();
        assertTrue(write(source, options("kt_billing.no_profile", "create", List.of(), Map.of("profile_output", false)),
            SPEND, Rows.spend(0, 10, "x"), 4, -1, off).succeeded());
        assertEquals(List.of(MetadataEvent.Kind.SCHEMA, MetadataEvent.Kind.ROW_COUNT),
            off.stream().map(MetadataEvent::kind).toList());

        List<MetadataEvent> broken = Rows.events();
        Connector.WriteOptions bad = new Connector.WriteOptions("kt_billing.bad_profile", "create", List.of(), null,
            Map.of(), "urn:kilnmark:warehouse:kt_billing.bad_profile", Map.of("sample-size", "5", "sample-mode",
            "no-such-mode"));
        RunResult r = write(source, bad, SPEND, Rows.spend(0, 10, "x"), 4, -1, broken);
        assertTrue(r.succeeded(), r.error());
        Map<String, String> count = broken.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
            .orElseThrow().attributes();
        assertEquals("10", count.get("rows"));
        assertEquals("false", count.get("profiled"));
        assertTrue(count.get("profile_error").startsWith("Profiling what was written stopped"), count.toString());
        assertEquals(List.of(List.of(10L)), query("SELECT COUNT(*) FROM kt_billing.bad_profile"));
    }

    @Test
    void createRefusesAnExistingTableAndNeverLeavesAHalfFilledOne() throws Exception {
        db.sql("CREATE TABLE kt_billing.taken (x INT)");
        RunResult taken = write(source, options("kt_billing.taken", "create", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(taken.error().contains("kt_billing.taken already exists"), taken.error());
        for (boolean bulk : List.of(true, false)) {
            RunResult failed = write(source, options("kt_billing.half_" + bulk, "create", List.of(),
                Map.of("bulk_load", bulk, "batch_size", 10)), SPEND, Rows.spend(0, 100, "x"), 20, 3);
            assertFalse(failed.succeeded());
            assertEquals(List.of(), query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = "
                + "'kt_billing' AND TABLE_NAME = 'half_" + bulk + "'"), "the target never appears");
        }
        assertEquals(List.of(), leftovers());
    }

    @Test
    void valuesMySqlCannotHoldAreRefusedPlainly() throws Exception {
        Schema floats = new Schema(List.of(field("x", Rows.FLOAT64)));
        RunResult nan = write(source, options("kt_billing.nan", "create", List.of(), Map.of()), floats,
            Rows.list(new Object[] {Double.NaN}), 4, -1);
        assertTrue(nan.error().contains("Row 1, column x: MySQL cannot store NaN or infinity"), nan.error());
        db.sql("CREATE TABLE kt_billing.narrow (customer_id BIGINT NOT NULL, total DECIMAL(4,2), note VARCHAR(20))");
        RunResult tooBig = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()),
            new Schema(List.of(field("customer_id", Rows.INT64), field("total", Rows.decimal(4, 2)))),
            Rows.list(new Object[] {1L, new BigDecimal("99.99")}, new Object[] {2L, null}), 4, -1);
        assertTrue(tooBig.succeeded(), tooBig.error());
        RunResult strict = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()),
            new Schema(List.of(field("customer_id", Rows.TEXT))), Rows.list(new Object[] {"x"}), 4, -1);
        assertTrue(strict.error().contains("the input's column customer_id is text but the table's column customer_id "
            + "is bigint"), strict.error());
    }

    @Test
    void appendChecksColumnsAndTextLengths() throws Exception {
        db.sql("CREATE TABLE kt_billing.ledger (customer_id BIGINT NOT NULL, total DECIMAL(12,2), note VARCHAR(5), "
            + "id INT AUTO_INCREMENT PRIMARY KEY)");
        RunResult ok = write(source, options("kt_billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 10, "short"), 4, -1);
        assertTrue(ok.succeeded(), ok.error());
        RunResult tooLong = write(source, options("kt_billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 2, "too long"), 4, -1);
        assertTrue(tooLong.error().contains("Row 1: the value in column note is 8 characters long, but the column "
            + "holds at most 5"), tooLong.error());
        assertEquals(List.of(List.of(10L)), query("SELECT COUNT(*) FROM kt_billing.ledger"));
    }

    @Test
    void upsertUpdatesByKeyAndNeedsAUniqueIndex() throws Exception {
        db.sql("CREATE TABLE kt_billing.spend (customer_id BIGINT PRIMARY KEY, total DECIMAL(12,2), note VARCHAR(50))");
        assertTrue(write(source, options("kt_billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            Rows.spend(0, 100, "first"), 30, -1).succeeded());
        List<Object[]> second = Rows.spend(50, 150, "second");
        second.add(new Object[] {149L, new BigDecimal("9.99"), "last wins"});
        RunResult r = write(source, options("kt_billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            second, 30, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of("first", 50L), List.of("last wins", 1L), List.of("second", 99L)),
            query("SELECT note, COUNT(*) FROM kt_billing.spend GROUP BY note ORDER BY note"));

        db.sql("CREATE TABLE kt_billing.no_key (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50), "
            + "UNIQUE KEY prefix_only (note(3)))");
        RunResult refused = write(source, options("kt_billing.no_key", "upsert", List.of("customer_id"), Map.of()),
            SPEND, Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(refused.error().contains("Cannot upsert into kt_billing.no_key by (customer_id)")
            && refused.error().contains("ADD UNIQUE (`customer_id`)"), refused.error());
    }

    @Test
    void replaceSwapsAtomicallyKeepingGrantsByName() throws Exception {
        db.sql("CREATE TABLE kt_billing.report (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50))",
            "INSERT INTO kt_billing.report VALUES (1, 1, 'old'), (2, 2, 'old')",
            "GRANT SELECT ON kt_billing.report TO 'kt_reader'@'%'");
        RunResult r = write(source, options("kt_billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "new"), 100, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(500L)), query("SELECT COUNT(*) FROM kt_billing.report WHERE note = 'new'"));
        SourceConfig reader = db.source("warehouse", "kt_reader", LAB_PASSWORD, Map.of());
        try (Connection c = db.connector().connect(reader); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM kt_billing.report")) {
            assertTrue(rs.next());
            assertEquals(500, rs.getInt(1), "the grant on the name still lets kt_reader read the new table");
        }
        RunResult failed = write(source, options("kt_billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "failed"), 100, 2);
        assertFalse(failed.succeeded());
        assertEquals(List.of(List.of(500L)), query("SELECT COUNT(*) FROM kt_billing.report WHERE note = 'new'"));
        assertEquals(List.of(), leftovers());

        RunResult fresh = write(source, options("kt_billing.fresh", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 5, "x"), 100, -1);
        assertTrue(fresh.succeeded(), fresh.error());
        assertEquals(List.of(List.of(5L)), query("SELECT COUNT(*) FROM kt_billing.fresh"));
    }

    @Test
    void replaceRefusesWhatTheSwapWouldLoseSilently() throws Exception {
        db.sql("CREATE TABLE kt_billing.parent (id BIGINT PRIMARY KEY)",
            "CREATE TABLE kt_other.child (id BIGINT, parent_id BIGINT, CONSTRAINT fk_parent FOREIGN KEY (parent_id) "
                + "REFERENCES kt_billing.parent (id))",
            "CREATE TABLE kt_billing.audited (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50))",
            "CREATE TRIGGER kt_billing.stamp BEFORE INSERT ON kt_billing.audited FOR EACH ROW SET NEW.note = 'x'",
            "CREATE VIEW kt_billing.a_view AS SELECT 1 AS customer_id");
        RunResult fk = write(source, options("kt_billing.parent", "replace", List.of(), Map.of()),
            new Schema(List.of(field("id", Rows.INT64))), Rows.list(new Object[] {1L}), 4, -1);
        assertTrue(fk.error().contains("Cannot replace kt_billing.parent: the foreign key fk_parent on kt_other.child "
            + "refers to it"), fk.error());
        RunResult trigger = write(source, options("kt_billing.audited", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(trigger.error().contains("the trigger stamp is on it"), trigger.error());
        RunResult view = write(source, options("kt_billing.a_view", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(view.error().contains("it is a view, not a table"), view.error());
        assertEquals(List.of(), leftovers());
    }

    @Test
    void aLoginWithoutRightsGetsAPlainMessage() throws Exception {
        db.sql("CREATE TABLE kt_billing.customer_spend (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50))",
            "GRANT SELECT ON kt_billing.customer_spend TO 'kt_lab'@'%'");
        RunResult append = write(lab, options("kt_billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(append.error().contains("the login kt_lab cannot write to kt_billing.customer_spend in warehouse: "
            + "ask a database administrator to grant INSERT on kt_billing.customer_spend"), append.error());
        RunResult create = write(lab, options("kt_billing.lab_new", "create", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(create.error().contains("grant CREATE, INSERT, ALTER and DROP in the database kt_billing"),
            create.error());

        db.sql("GRANT INSERT ON kt_billing.customer_spend TO 'kt_lab'@'%'");
        RunResult allowed = write(lab, options("kt_billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 3, "x"), 10, -1);
        assertTrue(allowed.succeeded(), allowed.error());
    }

    @Test
    void aSourceKeepingToOneDatabaseWritesOnlyThere() {
        SourceConfig one = db.source("warehouse", Map.of("database", "kt_billing"));
        ConnectorException e = assertThrows(ConnectorException.class, () -> db.connector().writer(one,
            options("kt_other.t", "create", List.of(), Map.of())));
        assertTrue(e.getMessage().startsWith("table: this source keeps to the database kt_billing"), e.getMessage());
    }

    @Test
    void commitEveryKeepsWhatWasCommittedAndSaysSo() throws Exception {
        db.sql("CREATE TABLE kt_billing.parts (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50))");
        RunResult r = write(source, options("kt_billing.parts", "append", List.of(), Map.of("commit_every", 20)), SPEND,
            Rows.spend(0, 100, "x"), 20, 3);
        assertFalse(r.succeeded());
        // what was committed stays, in whole commits; batches still queued when the source failed are dropped
        long kept = ((Number) query("SELECT COUNT(*) FROM kt_billing.parts").get(0).get(0)).longValue();
        assertTrue(kept >= 20 && kept <= 60 && kept % 20 == 0, "kept " + kept + ": " + r.error());

        db.sql("CREATE TABLE kt_billing.measures (x DOUBLE)");
        Schema floats = new Schema(List.of(field("x", Rows.FLOAT64)));
        RunResult own = write(source, options("kt_billing.measures", "append", List.of(), Map.of("commit_every", 2)),
            floats, Rows.list(new Object[] {1.0}, new Object[] {2.0}, new Object[] {Double.NaN}), 2, -1);
        assertFalse(own.succeeded());
        assertTrue(own.error().contains("2 rows were already committed, because commit_every is set; they stay in "
            + "kt_billing.measures"), own.error());
        assertEquals(List.of(List.of(2L)), query("SELECT COUNT(*) FROM kt_billing.measures"));
    }

    @Test
    void replacePutsTheOldTableBackWhenAKeyItCouldNotSeeRefusesTheDrop() throws Exception {
        db.sql("DROP USER IF EXISTS 'kt_writer'@'%'",
            "CREATE USER 'kt_writer'@'%' IDENTIFIED BY '" + LAB_PASSWORD + "'",
            "GRANT ALL ON kt_billing.* TO 'kt_writer'@'%'",
            "CREATE TABLE kt_billing.hub (id BIGINT PRIMARY KEY)",
            "INSERT INTO kt_billing.hub VALUES (1), (2)",
            "CREATE TABLE kt_other.spoke (id BIGINT, hub_id BIGINT, CONSTRAINT fk_hub FOREIGN KEY (hub_id) "
                + "REFERENCES kt_billing.hub (id))",
            "INSERT INTO kt_other.spoke VALUES (10, 1)");
        SourceConfig writer = db.source("warehouse", "kt_writer", LAB_PASSWORD, Map.of());
        Schema ids = new Schema(List.of(field("id", Rows.INT64)));
        // kt_writer has no rights in kt_other, so information_schema hides the key from it
        RunResult r = write(writer, options("kt_billing.hub", "replace", List.of(), Map.of()), ids,
            Rows.list(new Object[] {1L}, new Object[] {2L}, new Object[] {3L}), 4, -1);
        assertFalse(r.succeeded(), "the run must not succeed with the key pointing at a stale table");
        assertTrue(r.error().contains("kt_billing.hub"), r.error());
        assertEquals(List.of(List.of(2L)), query("SELECT COUNT(*) FROM kt_billing.hub"), r.error());
        assertEquals(List.of(List.of("hub")), query("SELECT REFERENCED_TABLE_NAME FROM "
            + "information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = 'kt_other' "
            + "AND CONSTRAINT_NAME = 'fk_hub'"), "the key still refers to the table, under its own name");
        assertEquals(List.of(), leftovers());

        // with PROCESS, InnoDB's own list shows the key before anything is written
        db.sql("GRANT PROCESS ON *.* TO 'kt_writer'@'%'");
        RunResult seen = write(writer, options("kt_billing.hub", "replace", List.of(), Map.of()), ids,
            Rows.list(new Object[] {1L}), 4, -1);
        assertTrue(seen.error().contains("Cannot replace kt_billing.hub: the foreign key fk_hub on kt_other.spoke "
            + "refers to it"), seen.error());
        assertEquals(List.of(), leftovers());
    }

    @Test
    void upsertRefusesAnotherUniqueIndexThatCouldMatchAnotherRow() throws Exception {
        db.sql("CREATE TABLE kt_billing.accounts (customer_id BIGINT PRIMARY KEY, total DECIMAL(12,2), "
                + "note VARCHAR(50), UNIQUE KEY uq_note (note))",
            "INSERT INTO kt_billing.accounts VALUES (1, 1, 'alpha')");
        List<Object[]> clash = Rows.list(new Object[] {2L, new BigDecimal("2.00"), "alpha"});
        RunResult refused = write(source, options("kt_billing.accounts", "upsert", List.of("customer_id"), Map.of()),
            SPEND, clash, 4, -1);
        assertTrue(refused.error().contains("the unique index uq_note does not hold every key column"),
            refused.error());
        assertTrue(refused.error().contains("allow_other_unique_keys: true"), refused.error());
        assertEquals(List.of(List.of(1L, new BigDecimal("1.00"))),
            query("SELECT customer_id, total FROM kt_billing.accounts"));

        RunResult allowed = write(source, options("kt_billing.accounts", "upsert", List.of("customer_id"),
            Map.of("allow_other_unique_keys", true)), SPEND, clash, 4, -1);
        assertTrue(allowed.succeeded(), allowed.error());
        assertEquals(List.of(List.of(1L, new BigDecimal("2.00"))),
            query("SELECT customer_id, total FROM kt_billing.accounts"), "as documented: the row uq_note matched");

        db.sql("CREATE TABLE kt_billing.wide (customer_id BIGINT PRIMARY KEY, total DECIMAL(12,2), note VARCHAR(50), "
            + "UNIQUE KEY uq_both (note, customer_id))");
        RunResult wide = write(source, options("kt_billing.wide", "upsert", List.of("customer_id"), Map.of()), SPEND,
            Rows.spend(0, 5, "x"), 4, -1);
        assertTrue(wide.succeeded(), "an index holding the key and more matches only the key's row: " + wide.error());
    }

    @Test
    void theServersOwnSqlModeNeverChangesWhatIsWritten() throws Exception {
        String before = (String) query("SELECT @@GLOBAL.sql_mode").get(0).get(0);
        String odd = db.mariadb() ? "EMPTY_STRING_IS_NULL,NO_BACKSLASH_ESCAPES,ANSI_QUOTES"
            : "TIME_TRUNCATE_FRACTIONAL,NO_BACKSLASH_ESCAPES,ANSI_QUOTES";
        db.sql("CREATE TABLE kt_billing.modes (customer_id BIGINT, note VARCHAR(20))");
        db.sql("SET GLOBAL sql_mode = '" + odd + "'");
        try {
            Schema notes = new Schema(List.of(field("customer_id", Rows.INT64), field("note", Rows.TEXT)));
            RunResult r = write(source, options("kt_billing.modes", "append", List.of(), Map.of()), notes,
                Rows.list(new Object[] {1L, ""}, new Object[] {2L, "a\\b"}), 4, -1);
            assertTrue(r.succeeded(), r.error());
        } finally {
            db.sql("SET GLOBAL sql_mode = '" + before + "'");
        }
        assertEquals(List.of(List.of(1L, "", 0L), List.of(2L, "a\\b", 0L)),
            query("SELECT customer_id, note, note IS NULL FROM kt_billing.modes ORDER BY customer_id"));
    }

    @Test
    void fractionsOfASecondAreNeverRoundedAway() throws Exception {
        db.sql("CREATE TABLE kt_billing.stamps (id BIGINT, at DATETIME, took TIME, at3 DATETIME(3))");
        Schema stamps = new Schema(List.of(field("id", Rows.INT64), field("at", Rows.TIMESTAMP),
            field("took", Rows.TIME), field("at3", Rows.TIMESTAMP)));
        RunResult ok = write(source, options("kt_billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {1L, LocalDateTime.parse("2025-01-31T09:30:00"), LocalTime.parse("09:30:00"),
                LocalDateTime.parse("2025-01-31T09:30:00.123")}), 4, -1);
        assertTrue(ok.succeeded(), ok.error());
        RunResult at = write(source, options("kt_billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {2L, LocalDateTime.parse("2025-01-31T09:30:00.999999"), null, null}), 4, -1);
        assertTrue(at.error().contains("Row 1: the value in column at has more digits of a "
            + "second than the column keeps (0)"), at.error());
        RunResult took = write(source, options("kt_billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {3L, null, LocalTime.parse("09:30:00.5"), null}), 4, -1);
        assertTrue(took.error().contains("Row 1, column took: the value 09:30:00.5")
            && took.error().contains("keeps (0)"), took.error());
        RunResult at3 = write(source, options("kt_billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {4L, null, null, LocalDateTime.parse("2025-01-31T09:30:00.1234")}), 4, -1);
        assertTrue(at3.error().contains("keeps (3)"), at3.error());
        assertEquals(List.of(List.of(1L)), query("SELECT COUNT(*) FROM kt_billing.stamps"));
    }

    @Test
    void timeAndYearColumnsTakeOnlyWhatTheyHold() throws Exception {
        db.sql("CREATE TABLE kt_billing.shifts (id BIGINT, took TIME, season YEAR)");
        RunResult stamp = write(source, options("kt_billing.shifts", "append", List.of(), Map.of()),
            new Schema(List.of(field("id", Rows.INT64), field("took", Rows.TIMESTAMP))),
            Rows.list(new Object[] {1L, LocalDateTime.parse("2025-01-31T09:30:00")}), 4, -1);
        assertTrue(stamp.error().contains("the table's column took is time, which takes only times or text"),
            stamp.error());
        RunResult number = write(source, options("kt_billing.shifts", "append", List.of(), Map.of()),
            new Schema(List.of(field("id", Rows.INT64), field("took", Rows.INT64))),
            Rows.list(new Object[] {1L, 5L}), 4, -1);
        assertTrue(number.error().contains("which takes only times or text"), number.error());
        Schema seasons = new Schema(List.of(field("id", Rows.INT64), field("took", Rows.TEXT),
            field("season", Rows.INT16)));
        RunResult years = write(source, options("kt_billing.shifts", "append", List.of(), Map.of()), seasons,
            Rows.list(new Object[] {1L, "10:15:00", 1999L}, new Object[] {2L, "10:15:00", 1800L}), 4, -1);
        assertTrue(years.error().contains("Row 2, column season: the year is outside what a YEAR column holds "
            + "(1901 to 2155)"), years.error());
        assertEquals(List.of(List.of(0L)), query("SELECT COUNT(*) FROM kt_billing.shifts"));
        RunResult fine = write(source, options("kt_billing.shifts", "append", List.of(), Map.of()), seasons,
            Rows.list(new Object[] {1L, "10:15:00", 2024L}), 4, -1);
        assertTrue(fine.succeeded(), fine.error());
        assertEquals(List.of(List.of("10:15:00", 2024L)), query("SELECT CAST(took AS CHAR), season + 0 FROM "
            + "kt_billing.shifts"));
    }

    @Test
    void replaceRefusesOtherEnginesAndTriggersARoleMightHide() throws Exception {
        db.sql("CREATE TABLE kt_billing.in_memory (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50)) "
            + "ENGINE=MEMORY");
        RunResult memory = write(source, options("kt_billing.in_memory", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(memory.error().contains("it uses the MEMORY storage engine"), memory.error());

        db.sql("DROP USER IF EXISTS 'kt_roled'@'%'", "DROP ROLE IF EXISTS kt_role", "CREATE ROLE kt_role",
            "GRANT ALL ON kt_billing.* TO kt_role",
            "CREATE USER 'kt_roled'@'%' IDENTIFIED BY '" + LAB_PASSWORD + "'",
            "GRANT kt_role TO 'kt_roled'@'%'",
            db.mariadb() ? "SET DEFAULT ROLE kt_role FOR 'kt_roled'@'%'" : "SET DEFAULT ROLE kt_role TO 'kt_roled'@'%'",
            "CREATE TABLE kt_billing.by_role (customer_id BIGINT, total DECIMAL(12,2), note VARCHAR(50))");
        SourceConfig roled = db.source("warehouse", "kt_roled", LAB_PASSWORD, Map.of());
        RunResult role = write(roled, options("kt_billing.by_role", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(role.error().contains("the login cannot be shown to see whether triggers are on it"),
            role.error());
        assertEquals(List.of(), leftovers());
    }

    @Test
    void bulkLoadKeepsBackslashesQuotesAndTheWordNull() throws Exception {
        Schema notes = new Schema(List.of(field("customer_id", Rows.INT64), field("note", Rows.TEXT)));
        List<Object[]> rows = Rows.list(new Object[] {1L, "a\\b"}, new Object[] {2L, "NULL"},
            new Object[] {3L, "\\N"}, new Object[] {4L, null}, new Object[] {5L, "say \"hi\", ok"});
        RunResult r = write(source, options("kt_billing.loaded", "create", List.of(), Map.of("bulk_load", true)), notes,
            rows, 10, -1);
        assertTrue(r.succeeded(), r.error());
        List<List<Object>> back = query("SELECT customer_id, note, note IS NULL FROM kt_billing.loaded "
            + "ORDER BY customer_id");
        assertEquals(List.of(List.of(1L, "a\\b", 0L), List.of(2L, "NULL", 0L), List.of(3L, "\\N", 0L),
            java.util.Arrays.asList(4L, null, 1L), List.of(5L, "say \"hi\", ok", 0L)), back);
    }
}
