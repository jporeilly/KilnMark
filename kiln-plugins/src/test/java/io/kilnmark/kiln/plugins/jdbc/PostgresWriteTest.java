package io.kilnmark.kiln.plugins.jdbc;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * Writing to PostgreSQL in every mode, reading back with the connector's own reader. Needs Docker;
 * CI runs it.
 */
@Testcontainers
class PostgresWriteTest {

    @Container
    static final PostgreSQLContainer PG = new PostgreSQLContainer(
        DockerImageName.parse("docker.io/library/postgres:18").asCompatibleSubstituteFor("postgres"));

    static final PostgresConnector CONNECTOR = new PostgresConnector();
    static SourceConfig source;
    static SourceConfig lab;

    @BeforeAll
    static void setUp() throws Exception {
        source = new SourceConfig("warehouse", "postgresql", Map.of("url", PG.getJdbcUrl(), "username",
            PG.getUsername()), PG.getPassword());
        lab = new SourceConfig("warehouse", "postgresql", Map.of("url", PG.getJdbcUrl(), "username", "lab"), "lab-pw");
        sql("CREATE SCHEMA billing", "CREATE SCHEMA locked", "CREATE ROLE reader_role",
            "CREATE ROLE lab LOGIN PASSWORD 'lab-pw'", "GRANT USAGE ON SCHEMA billing TO lab");
    }

    static void sql(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            for (String st : statements) {
                s.execute(st);
            }
        }
    }

    static List<List<Object>> query(String select) throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select)) {
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.add(rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        }
    }

    static Connector.WriteOptions options(String table, String mode, List<String> key, Map<String, Object> settings) {
        return new Connector.WriteOptions(table, mode, key, null, settings, "urn:kilnmark:warehouse:" + table);
    }

    static RunResult write(SourceConfig s, Connector.WriteOptions options, Schema schema, List<Object[]> rows,
                           int batch, int failAt, List<MetadataEvent> events) throws Exception {
        return Rows.run(Rows.source(schema, rows, batch, failAt), CONNECTOR.writer(s, options), events);
    }

    static Rows.Collected read(String table) throws Exception {
        Rows.Collected c = new Rows.Collected();
        StepSpec reader = CONNECTOR.reader(source, CONNECTOR.asset(source, table),
            new Connector.ReadOptions("urn:kilnmark:warehouse:" + table, 0, 1000));
        RunResult r = Rows.run(reader, new StepSpec("collect", Rows.collector(c), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        return c;
    }

    static final Schema SPEND = Rows.SPEND;

    static List<Object[]> spend(int from, int to, String note) {
        return Rows.spend(from, to, note);
    }

    @Test
    void createWritesEveryKindByCopyAndReadsBackTheSame() throws Exception {
        List<Object[]> rows = Rows.allKinds(5000);
        List<MetadataEvent> events = Rows.events();
        RunResult r = write(source, options("billing.all_kinds", "create", List.of(), Map.of()), Rows.ALL_KINDS, rows,
            1000, -1, events);
        assertTrue(r.succeeded(), r.error());

        Rows.Collected back = read("billing.all_kinds");
        assertEquals(List.of("int64", "int16", "int16", "int32", "float32", "float64", "decimal", "decimal", "decimal",
            "boolean", "text", "binary", "date", "time", "timestamp", "timestamp_tz"),
            back.columns.stream().map(c -> c.kindName()).toList());
        assertEquals(rows.size(), back.rows.size());
        back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
        for (int i = 0; i < rows.size(); i++) {
            for (int c = 0; c < rows.get(i).length; c++) {
                Rows.assertSameValue(rows.get(i)[c], back.rows.get(i).get(c), "row " + i + " column " + c);
            }
        }
        assertEquals("timestamp with time zone", query("SELECT data_type FROM information_schema.columns WHERE "
            + "table_schema = 'billing' AND table_name = 'all_kinds' AND column_name = 'utc'").get(0).get(0));
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT
            && "5000".equals(e.attributes().get("rows"))));
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.SCHEMA
            && e.attributes().get("columns").contains("\"type\":\"numeric(38,10)\"")));

        RunResult again = write(source, options("billing.all_kinds", "create", List.of(), Map.of()), Rows.ALL_KINDS, rows,
            1000, -1, Rows.events());
        assertFalse(again.succeeded());
        assertTrue(again.error().contains("billing.all_kinds already exists in warehouse: use mode append"),
            again.error());
    }

    @Test
    void appendChecksColumnsBeforeWritingAndReachesJsonAndUuidColumns() throws Exception {
        sql("CREATE TABLE billing.ledger (id bigint NOT NULL, amount numeric(10,2), doc jsonb, ref uuid, "
            + "loaded_at timestamptz NOT NULL DEFAULT now(), region text NOT NULL DEFAULT 'uk')");
        Schema schema = new Schema(List.of(field("id", Rows.INT32), field("amount", Rows.decimal(8, 2)),
            field("doc", Rows.TEXT), field("ref", Rows.TEXT)));
        List<MetadataEvent> appended = Rows.events();
        RunResult ok = write(source, options("billing.ledger", "append", List.of(), Map.of()), schema, Rows.list(
            new Object[] {1, new BigDecimal("1.50"), "{\"a\": 1}", "1b4e28ba-2fa1-11d2-883f-0016d3cca427"},
            new Object[] {2, null, null, null}), 10, -1, appended);
        assertTrue(ok.succeeded(), ok.error());
        assertEquals(List.of(MetadataEvent.Kind.ROW_COUNT), appended.stream().map(MetadataEvent::kind).toList(),
            "an append reports the rows it added, not the table");
        assertEquals("false", appended.get(0).attributes().get("complete"));
        assertEquals("true", appended.get(0).attributes().get("appended"));
        assertEquals(List.of(List.of(2L)), query("SELECT count(*) FROM billing.ledger"));
        assertEquals("1", query("SELECT doc->>'a' FROM billing.ledger WHERE id = 1").get(0).get(0));

        Schema wrong = new Schema(List.of(field("id", Rows.TEXT), field("amount", Rows.decimal(12, 4)),
            field("colour", Rows.TEXT)));
        RunResult bad = write(source, options("billing.ledger", "append", List.of(), Map.of()), wrong,
            Rows.list(new Object[] {"x", BigDecimal.ONE, "red"}), 10, -1, Rows.events());
        assertFalse(bad.succeeded());
        assertTrue(bad.error().contains("Cannot append to billing.ledger: its columns do not match the input: the "
            + "input's column id is text but the table's column id is int8"), bad.error());
        assertTrue(bad.error().contains("the table has no column colour"), bad.error());
        assertEquals(List.of(List.of(2L)), query("SELECT count(*) FROM billing.ledger"), "nothing was written");

        // a narrower decimal is checked value by value: values that fit go in, others stop the write unrounded
        Schema wide = new Schema(List.of(field("id", Rows.INT64), field("amount", Rows.decimal(14, 4))));
        RunResult fits = write(source, options("billing.ledger", "append", List.of(), Map.of()), wide,
            Rows.list(new Object[] {3L, new BigDecimal("12345678.5000")}), 10, -1, Rows.events());
        assertTrue(fits.succeeded(), fits.error());
        RunResult rounds = write(source, options("billing.ledger", "append", List.of(), Map.of()), wide,
            Rows.list(new Object[] {4L, new BigDecimal("1.0000")}, new Object[] {5L, new BigDecimal("1.2345")}), 10,
            -1, Rows.events());
        assertTrue(rounds.error().contains("Row 2: the value 1.2345 in column amount has more decimal places than the "
            + "column's 2"), rounds.error());
        RunResult large = write(source, options("billing.ledger", "append", List.of(), Map.of()), wide,
            Rows.list(new Object[] {6L, new BigDecimal("123456789.0000")}), 10, -1, Rows.events());
        assertTrue(large.error().contains("Row 1: the value 123456789.0000 in column amount has more digits before the "
            + "point than the column's 8"), large.error());
        assertEquals(List.of(List.of(3L)), query("SELECT count(*) FROM billing.ledger"), "only the row that fits");

        // declared beyond 38 digits, and with a negative scale: still checked, never rounded
        sql("CREATE TABLE billing.wide (id bigint, amount numeric(50,2))",
            "CREATE TABLE billing.hundreds (id bigint, amount numeric(5,-2))");
        RunResult wider = write(source, options("billing.wide", "append", List.of(), Map.of()), wide,
            Rows.list(new Object[] {1L, new BigDecimal("1.2345")}), 10, -1, Rows.events());
        assertTrue(wider.error().contains("Row 1: the value 1.2345 in column amount has more decimal places than the "
            + "column's 2"), wider.error());
        assertEquals(List.of(List.of(0L)), query("SELECT count(*) FROM billing.wide"));
        Schema whole = new Schema(List.of(field("id", Rows.INT64), field("amount", Rows.decimal(10, 0))));
        assertTrue(write(source, options("billing.hundreds", "append", List.of(), Map.of()), whole,
            Rows.list(new Object[] {1L, new BigDecimal("12300")}), 10, -1, Rows.events()).succeeded());
        RunResult notHundreds = write(source, options("billing.hundreds", "append", List.of(), Map.of()), whole,
            Rows.list(new Object[] {2L, new BigDecimal("12345")}), 10, -1, Rows.events());
        assertTrue(notHundreds.error().contains("is not a whole multiple of 100"), notHundreds.error());
        List<List<Object>> stored = query("SELECT amount FROM billing.hundreds");
        assertEquals(1, stored.size());
        assertEquals(0, new BigDecimal("12300").compareTo((BigDecimal) stored.get(0).get(0)), stored.toString());

        RunResult missing = write(source, options("billing.no_such", "append", List.of(), Map.of()), schema,
            Rows.list(new Object[] {1, null, null, null}), 10, -1, Rows.events());
        assertTrue(missing.error().contains("billing.no_such does not exist in warehouse"), missing.error());
    }

    @Test
    void upsertInsertsAndUpdatesByKeyAndNeedsAUniqueIndex() throws Exception {
        sql("CREATE TABLE billing.spend (customer_id bigint PRIMARY KEY, total numeric(12,2), note text)");
        RunResult first = write(source, options("billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            spend(0, 100, "first"), 30, -1, Rows.events());
        assertTrue(first.succeeded(), first.error());
        List<Object[]> second = spend(50, 150, "second");
        second.add(new Object[] {149L, new BigDecimal("9.99"), "last wins"});
        RunResult r = write(source, options("billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND, second,
            30, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(150L)), query("SELECT count(*) FROM billing.spend"));
        assertEquals(List.of(List.of("first", 50L), List.of("last wins", 1L), List.of("second", 99L)),
            query("SELECT note, count(*) FROM billing.spend GROUP BY note ORDER BY note"));

        RunResult nullKey = write(source, options("billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            Rows.list(new Object[] {null, BigDecimal.ONE, "x"}), 30, -1, Rows.events());
        assertTrue(nullKey.error().contains("Row 1 has no value in the key column customer_id"), nullKey.error());

        sql("CREATE TABLE billing.no_key (customer_id bigint, total numeric(12,2), note text)");
        RunResult noIndex = write(source, options("billing.no_key", "upsert", List.of("customer_id"), Map.of()), SPEND,
            spend(0, 1, "x"), 30, -1, Rows.events());
        assertTrue(noIndex.error().contains("unique index or primary key on exactly those columns"), noIndex.error());
        assertTrue(noIndex.error().contains("CREATE UNIQUE INDEX ON \"billing\".\"no_key\" (\"customer_id\")"),
            noIndex.error());
    }

    @Test
    void replaceSwapsInTheNewTableAtTheCommitKeepingGrants() throws Exception {
        sql("CREATE TABLE billing.report (customer_id bigint, total numeric(12,2), note text)",
            "INSERT INTO billing.report SELECT n, n, 'old' FROM generate_series(1, 10) n",
            "GRANT SELECT ON billing.report TO reader_role");
        CountDownLatch firstBatchSent = new CountDownLatch(1);
        CountDownLatch carryOn = new CountDownLatch(1);
        Step slow = slowSource(SPEND, spend(0, 2000, "new"), 500, firstBatchSent, carryOn);
        java.util.concurrent.atomic.AtomicReference<Throwable> seen = new java.util.concurrent.atomic.AtomicReference<>();
        Thread checker = Thread.ofVirtual().start(() -> {
            try {
                assertTrue(firstBatchSent.await(60, TimeUnit.SECONDS));
                Thread.sleep(500); // the writer has created and started filling the new table
                // meanwhile readers see the old table, unlocked
                assertEquals(List.of(List.of(10L)), query("SELECT count(*) FROM billing.report WHERE note = 'old'"));
            } catch (Throwable e) {
                seen.set(e);
            } finally {
                carryOn.countDown();
            }
        });
        RunResult r = Rows.run(slow, CONNECTOR.writer(source, options("billing.report", "replace", List.of(),
            Map.of())), Rows.events());
        checker.join();
        if (seen.get() != null) {
            throw new AssertionError("while the replace was running", seen.get());
        }
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(2000L)), query("SELECT count(*) FROM billing.report WHERE note = 'new'"));
        assertEquals(List.of(List.of(true)), query("SELECT has_table_privilege('reader_role', 'billing.report', "
            + "'SELECT')"));
        assertEquals(List.of(), query("SELECT relname FROM pg_class WHERE relname LIKE 'kiln_%'"));

        // a failure leaves the old table as it was, and no new table behind
        RunResult failed = write(source, options("billing.report", "replace", List.of(), Map.of("commit_every", 100)),
            SPEND, spend(0, 5000, "failed"), 500, 3, Rows.events());
        assertFalse(failed.succeeded());
        assertEquals(List.of(List.of(2000L)), query("SELECT count(*) FROM billing.report WHERE note = 'new'"));
        assertEquals(List.of(), query("SELECT relname FROM pg_class WHERE relname LIKE 'kiln_%'"));

        sql("CREATE VIEW billing.report_view AS SELECT * FROM billing.report");
        RunResult blocked = write(source, options("billing.report", "replace", List.of(), Map.of()), SPEND,
            spend(0, 1, "x"), 10, -1, Rows.events());
        assertTrue(blocked.error().contains("Cannot replace billing.report: the view billing.report_view depends on "
            + "it"), blocked.error());
    }

    @Test
    void replaceRefusesWhatDroppingTheOldTableWouldBreak() throws Exception {
        sql("CREATE TABLE billing.parted (customer_id bigint, total numeric(12,2), note text) PARTITION BY RANGE "
                + "(customer_id)",
            "CREATE TABLE billing.part1 PARTITION OF billing.parted FOR VALUES FROM (0) TO (1000)",
            "CREATE TABLE billing.base (customer_id bigint, total numeric(12,2), note text)",
            "CREATE TABLE billing.child () INHERITS (billing.base)",
            "CREATE TABLE billing.fn_t (customer_id bigint, total numeric(12,2), note text)",
            "CREATE FUNCTION billing.total_spend() RETURNS numeric LANGUAGE sql BEGIN ATOMIC "
                + "SELECT sum(total) FROM billing.fn_t; END",
            "CREATE TABLE billing.typed_src (customer_id bigint, total numeric(12,2), note text)",
            "CREATE TABLE billing.typed_use (row billing.typed_src)",
            "CREATE VIEW billing.a_view AS SELECT 1 AS one",
            "CREATE TABLE billing.pubbed (customer_id bigint, total numeric(12,2), note text)",
            "CREATE PUBLICATION spend_pub FOR TABLE billing.pubbed",
            "CREATE TABLE billing.arr_src (customer_id bigint, total numeric(12,2), note text)",
            "CREATE FUNCTION billing.count_rows(rows billing.arr_src[]) RETURNS int LANGUAGE sql "
                + "AS 'SELECT cardinality(rows)'",
            "CREATE TABLE billing.seq_src (customer_id bigserial, total numeric(12,2), note text)",
            "CREATE TABLE billing.seq_user (id bigint DEFAULT nextval('billing.seq_src_customer_id_seq'))",
            "CREATE SEQUENCE billing.a_sequence");
        Map<String, String> refusals = new java.util.LinkedHashMap<>();
        refusals.put("billing.part1", "it is a partition of billing.parted");
        refusals.put("billing.parted", "it is a partitioned table, not a table");
        refusals.put("billing.base", "the table billing.child inherits from it");
        refusals.put("billing.child", "it inherits from billing.base");
        refusals.put("billing.fn_t", "the function billing.total_spend() depends on it");
        refusals.put("billing.typed_src", "depends on it");
        refusals.put("billing.a_view", "it is a view, not a table");
        refusals.put("billing.pubbed", "the publication spend_pub replicates it");
        refusals.put("billing.arr_src", "the function billing.count_rows(billing.arr_src[]) depends on it");
        refusals.put("billing.seq_src", "billing.seq_user");
        refusals.put("billing.a_sequence", "it is a sequence, not a table");
        for (var e : refusals.entrySet()) {
            RunResult r = write(source, options(e.getKey(), "replace", List.of(), Map.of()), SPEND, spend(0, 1, "x"), 10,
                -1, Rows.events());
            assertFalse(r.succeeded(), e.getKey());
            assertTrue(r.error().contains("Cannot replace " + e.getKey() + ": ") && r.error().contains(e.getValue()),
                r.error());
        }
        assertEquals(List.of(), query("SELECT relname FROM pg_class WHERE relname LIKE 'kiln_%'"));
    }

    @Test
    void replaceOfATableWithRowSecurityNeedsAnExplicitYes() throws Exception {
        sql("CREATE TABLE billing.secure (customer_id bigint, total numeric(12,2), note text)",
            "ALTER TABLE billing.secure ENABLE ROW LEVEL SECURITY");
        RunResult refused = write(source, options("billing.secure", "replace", List.of(), Map.of()), SPEND,
            spend(0, 5, "x"), 10, -1, Rows.events());
        assertTrue(refused.error().contains("it has row-level security") && refused.error()
            .contains("allow_dropping_row_security: true"), refused.error());
        RunResult allowed = write(source, options("billing.secure", "replace", List.of(),
            Map.of("allow_dropping_row_security", true)), SPEND, spend(0, 5, "x"), 10, -1, Rows.events());
        assertTrue(allowed.succeeded(), allowed.error());
        assertEquals(List.of(List.of(5L)), query("SELECT count(*) FROM billing.secure"));
    }

    @Test
    void appendToATableWithRowSecurityInsertsRowByRow() throws Exception {
        sql("CREATE TABLE billing.rls_append (customer_id bigint, total numeric(12,2), note text)",
            "ALTER TABLE billing.rls_append ENABLE ROW LEVEL SECURITY",
            "CREATE POLICY everyone ON billing.rls_append USING (true) WITH CHECK (note <> 'refused')",
            "GRANT INSERT, SELECT ON billing.rls_append TO lab");
        RunResult r = write(lab, options("billing.rls_append", "append", List.of(), Map.of()), SPEND,
            spend(0, 50, "ok"), 20, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(50L)), query("SELECT count(*) FROM billing.rls_append"));
        RunResult checked = write(lab, options("billing.rls_append", "append", List.of(), Map.of()), SPEND,
            spend(100, 101, "refused"), 20, -1, Rows.events());
        assertFalse(checked.succeeded(), "the policy still applies");
        assertTrue(checked.error().contains("A row-level security policy on billing.rls_append refused a row"),
            checked.error());
    }

    @Test
    void aLoginThatMayOnlyInsertCanAppend() throws Exception {
        sql("CREATE TABLE billing.inbox (customer_id bigint, total numeric(12,2), note text, secret text)",
            "GRANT INSERT ON billing.inbox TO lab",
            "CREATE TABLE billing.partly (customer_id bigint, total numeric(12,2), note text, secret text)",
            "GRANT INSERT ON billing.partly TO lab", "GRANT SELECT (customer_id) ON billing.partly TO lab");
        for (String table : List.of("billing.inbox", "billing.partly")) {
            RunResult r = write(lab, options(table, "append", List.of(), Map.of()), SPEND, spend(0, 25, "in"), 10, -1,
                Rows.events());
            assertTrue(r.succeeded(), table + ": " + r.error());
            assertEquals(List.of(List.of(25L)), query("SELECT count(*) FROM " + table));
        }
    }

    @Test
    void upsertAcceptsAUniqueIndexWithIncludedColumns() throws Exception {
        sql("CREATE TABLE billing.inc (customer_id bigint, total numeric(12,2), note text)",
            "CREATE UNIQUE INDEX ON billing.inc (customer_id) INCLUDE (note)");
        RunResult r = write(source, options("billing.inc", "upsert", List.of("customer_id"), Map.of()), SPEND,
            spend(0, 10, "a"), 10, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());
    }

    @Test
    void replaceGivesTheNewTableToTheOldOwnerAndNeverHalfFillsANewTarget() throws Exception {
        sql("CREATE ROLE owner_role", "GRANT CREATE, USAGE ON SCHEMA billing TO owner_role",
            "CREATE TABLE billing.owned (customer_id bigint, total numeric(12,2), note text)",
            "ALTER TABLE billing.owned OWNER TO owner_role");
        RunResult r = write(source, options("billing.owned", "replace", List.of(), Map.of()), SPEND, spend(0, 10, "n"),
            10, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of("owner_role")), query("SELECT pg_get_userbyid(relowner) FROM pg_class "
            + "WHERE oid = 'billing.owned'::regclass"));

        RunResult failed = write(source, options("billing.brand_new", "replace", List.of(), Map.of("commit_every", 100)),
            SPEND, spend(0, 5000, "x"), 500, 4, Rows.events());
        assertFalse(failed.succeeded());
        assertEquals(List.of(), query("SELECT relname FROM pg_class WHERE relname IN ('brand_new') OR relname LIKE "
            + "'kiln_%'"), "neither a half-filled target nor the new table is left");
    }

    /** A source that sends its first batch, then waits for {@code carryOn}. */
    static Step slowSource(Schema schema, List<Object[]> rows, int batch, CountDownLatch sent, CountDownLatch carryOn) {
        Step inner = Rows.source(schema, rows, batch);
        return new Step() {
            private int calls;

            @Override
            public String type() {
                return "slow";
            }

            @Override
            public void init(StepContext context) throws StepException {
                inner.init(context);
            }

            @Override
            public boolean process(StepContext context) throws StepException {
                if (calls++ == 1) {
                    sent.countDown();
                    try {
                        carryOn.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new StepException("Interrupted", e);
                    }
                }
                return inner.process(context);
            }
        };
    }

    @Test
    void aLoginWithoutRightsGetsAPlainMessage() throws Exception {
        sql("CREATE TABLE billing.customer_spend (customer_id bigint, total numeric(12,2), note text)",
            "GRANT SELECT ON billing.customer_spend TO lab");
        RunResult append = write(lab, options("billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            spend(0, 1, "x"), 10, -1, Rows.events());
        assertFalse(append.succeeded());
        assertTrue(append.error().contains("the login lab cannot write to billing.customer_spend in warehouse: ask a "
            + "database administrator to grant INSERT on billing.customer_spend"), append.error());
        RunResult create = write(lab, options("billing.lab_new", "create", List.of(), Map.of()), SPEND,
            spend(0, 1, "x"), 10, -1, Rows.events());
        assertTrue(create.error().contains("the login lab cannot write to billing.lab_new in warehouse: ask a database "
            + "administrator to grant CREATE on the schema billing"), create.error());
        RunResult noSchema = write(source, options("nowhere.t", "create", List.of(), Map.of()), SPEND,
            spend(0, 1, "x"), 10, -1, Rows.events());
        assertTrue(noSchema.error().contains("nowhere") && noSchema.error().contains("does not exist"),
            noSchema.error());
    }

    @Test
    void commitEveryKeepsWhatWasCommittedAndSaysSo() throws Exception {
        RunResult r = write(source, options("billing.chunked", "create", List.of(), Map.of("commit_every", 1000)),
            SPEND, spend(0, 10_000, "c"), 500, 6, Rows.events());
        assertFalse(r.succeeded());
        // what was committed stays, in whole commits; batches still queued when the source failed are dropped
        long kept = (Long) query("SELECT count(*) FROM billing.chunked").get(0).get(0);
        assertTrue(kept >= 1000 && kept <= 3000 && kept % 1000 == 0, "kept " + kept);

        RunResult own = write(source, options("billing.chunked2", "create", List.of(), Map.of("commit_every", 2)),
            SPEND, Rows.list(new Object[] {1L, BigDecimal.ONE, "a"}, new Object[] {2L, BigDecimal.ONE, "b"},
                new Object[] {3L, BigDecimal.ONE, "zero\0"}), 2, -1, Rows.events());
        assertFalse(own.succeeded());
        assertTrue(own.error().contains("2 rows were already committed, because commit_every is set; they stay in "
            + "billing.chunked2"), own.error());
        assertEquals(List.of(List.of(2L)), query("SELECT count(*) FROM billing.chunked2"));
    }

    @Test
    void textPostgresCannotHoldIsRefusedPlainly() throws Exception {
        RunResult r = write(source, options("billing.zero", "create", List.of(), Map.of()), SPEND,
            Rows.list(new Object[] {1L, BigDecimal.ONE, "ok"}, new Object[] {2L, BigDecimal.ONE, "bad\0text"}), 10, -1,
            Rows.events());
        assertFalse(r.succeeded());
        assertTrue(r.error().contains("Row 2, column note: it holds a zero character"), r.error());
        assertEquals(List.of(), query("SELECT relname FROM pg_class WHERE relname = 'zero'"), "rolled back");
    }

    @Test
    void timestampsWithFewerDigitsOfASecondAreNeverRounded() throws Exception {
        sql("CREATE TABLE billing.stamps (id bigint, at timestamp(3), plain timestamp, took time(0))");
        Schema stamps = new Schema(List.of(Rows.field("id", Rows.INT64), Rows.field("at", Rows.TIMESTAMP),
            Rows.field("plain", Rows.TIMESTAMP), Rows.field("took", Rows.TIME)));
        RunResult ok = write(source, options("billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {1L, java.time.LocalDateTime.parse("2025-01-31T09:30:00.123"),
                java.time.LocalDateTime.parse("2025-01-31T09:30:00.123456"), java.time.LocalTime.parse("09:30:00")}),
            4, -1, Rows.events());
        assertTrue(ok.succeeded(), ok.error());
        RunResult at = write(source, options("billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {2L, java.time.LocalDateTime.parse("2025-01-31T09:30:00.1235"), null, null}), 4, -1,
            Rows.events());
        assertTrue(at.error().contains("in column at has more digits of a second than the column keeps (3)"),
            at.error());
        RunResult took = write(source, options("billing.stamps", "append", List.of(), Map.of()), stamps,
            Rows.list(new Object[] {3L, null, null, java.time.LocalTime.parse("09:30:00.5")}), 4, -1, Rows.events());
        assertTrue(took.error().contains("in column took has more digits of a second than the column keeps (0)"),
            took.error());
        assertEquals(List.of(List.of(1L)), query("SELECT count(*) FROM billing.stamps"));
    }
}
