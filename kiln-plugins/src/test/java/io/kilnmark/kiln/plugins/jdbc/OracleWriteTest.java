package io.kilnmark.kiln.plugins.jdbc;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.TextForm;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * Writing to Oracle Database Free in every mode, reading back with the connector's own reader,
 * with the refusals (nothing rounded, nothing made null silently) and the rights checks. Tagged
 * {@code oracle}: {@code ./gradlew :kiln-plugins:oracleTest}.
 */
@Tag("oracle")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleWriteTest {

    static final String PASSWORD = "Kt-pw-2026";

    OracleServers.Server db;
    SourceConfig source;
    SourceConfig lab;

    @BeforeAll
    void setUp() throws Exception {
        db = OracleServers.oracle();
        db.quietly("DROP USER kt_other CASCADE", "DROP USER kt_billing CASCADE", "DROP USER kt_lab CASCADE",
            "DROP USER kt_peek CASCADE");
        db.sql("CREATE USER kt_billing IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
            "GRANT CREATE SESSION, CREATE TABLE TO kt_billing",
            "CREATE USER kt_other IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON USERS",
            "CREATE USER kt_lab IDENTIFIED BY \"" + PASSWORD + "\"", "GRANT CREATE SESSION TO kt_lab",
            "CREATE USER kt_peek IDENTIFIED BY \"" + PASSWORD + "\"", "GRANT CREATE SESSION TO kt_peek");
        // the table owner writes to its own schema, as a pipeline's login usually does
        source = db.source("warehouse", "KT_BILLING", PASSWORD, Map.of());
        lab = db.source("warehouse", "KT_LAB", PASSWORD, Map.of());
    }

    List<List<Object>> query(String select) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(select)) {
            List<List<Object>> out = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    Object o = rs.getObject(i);
                    row.add(o instanceof BigDecimal d && d.scale() <= 0 ? d.longValueExact() : o);
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
        StepSpec reader = db.connector().reader(source, db.connector().asset(source, table),
            new Connector.ReadOptions("urn:kilnmark:warehouse:" + table, 0, 1000));
        RunResult r = Rows.run(reader, new StepSpec("collect", Rows.collector(c), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        return c;
    }

    List<String> leftovers() throws SQLException {
        return query("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = 'KT_BILLING' AND TABLE_NAME LIKE 'kiln%'")
            .stream().map(r -> (String) r.get(0)).toList();
    }

    static final Schema SPEND = Rows.SPEND;

    @Test
    void createWritesEveryKindAndNamesItAsOracleWould() throws Exception {
        List<Object[]> rows = Rows.allKinds(2000);
        List<MetadataEvent> events = Rows.events();
        RunResult r = write(source, options("kt_billing.all_kinds", "create", List.of(),
            Map.of("empty_text_as_null", true)), Rows.ALL_KINDS, rows, 700, -1, events);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of("ALL_KINDS")), query("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = "
            + "'KT_BILLING' AND TABLE_NAME = 'ALL_KINDS'"), "a plain lower-case name is stored in upper case");
        assertEquals("KT_BILLING.ALL_KINDS", db.connector().writtenAssetPath(source,
            options("kt_billing.all_kinds", "append", List.of(), Map.of())));
        Rows.Collected back = read("KT_BILLING.ALL_KINDS");
        assertEquals(List.of("ID", "SMALL", "TINY", "COUNT", "RATIO", "SCORE", "PRICE", "BALANCE", "HUGE", "OK",
            "NAME", "RAW", "BORN", "AT", "LOCAL", "UTC"), back.columns.stream().map(c -> c.name()).toList());
        back.rows.sort(java.util.Comparator.comparing(row -> ((Number) row.get(0)).longValue()));
        assertEquals(rows.size(), back.rows.size());
        for (int i = 0; i < rows.size(); i++) {
            for (int c = 0; c < rows.get(i).length; c++) {
                Object want = rows.get(i)[c];
                Object got = back.rows.get(i).get(c);
                if (want instanceof LocalTime t) {
                    want = TextForm.time(t); // kept as text: Oracle has no time of day
                } else if (want instanceof LocalDate d) {
                    want = d.atStartOfDay(); // DATE reads back as a timestamp
                } else if ("".equals(want)) {
                    want = null; // allowed by empty_text_as_null
                }
                if (want instanceof Long n && got instanceof BigDecimal d) {
                    got = d.longValueExact();
                }
                Rows.assertSameValue(want, got, "row " + i + " column " + c);
            }
        }
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.PROFILE), "profiled as written");
        assertEquals(List.of(), leftovers());
    }

    @Test
    void emptyTextIsRefusedUnlessAllowed() throws Exception {
        RunResult r = write(source, options("kt_billing.empties", "create", List.of(), Map.of()), SPEND,
            Rows.list(new Object[] {1L, BigDecimal.ONE, "x"}, new Object[] {2L, BigDecimal.ONE, ""}), 4, -1);
        assertTrue(r.error().contains("Row 2, column NOTE: it is empty text, which Oracle stores as null"), r.error());
        assertEquals(List.of(), query("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = 'KT_BILLING' "
            + "AND TABLE_NAME = 'EMPTIES'"), "the target never appears");
        assertEquals(List.of(), leftovers());
    }

    @Test
    void nothingIsRoundedOnTheWayIn() throws Exception {
        db.sql("CREATE TABLE kt_billing.narrow (customer_id NUMBER(19) NOT NULL, total NUMBER(6,2), "
            + "at_date DATE, at_ms TIMESTAMP(3))");
        Schema s = new Schema(List.of(field("customer_id", Rows.INT64), field("total", Rows.decimal(12, 4)),
            field("at_date", Rows.TIMESTAMP), field("at_ms", Rows.TIMESTAMP)));
        LocalDateTime whole = LocalDateTime.of(2024, 1, 1, 10, 0, 0);
        RunResult ok = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()), s,
            Rows.list(new Object[] {1L, new BigDecimal("1234.5000"), whole, whole.plusNanos(123_000_000)}), 4, -1);
        assertTrue(ok.succeeded(), ok.error());
        RunResult decimals = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()), s,
            Rows.list(new Object[] {2L, new BigDecimal("1.2345"), whole, whole}), 4, -1);
        assertTrue(decimals.error().contains("the value 1.2345 in column TOTAL") && decimals.error().contains(
            "never rounded"), decimals.error());
        RunResult seconds = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()), s,
            Rows.list(new Object[] {3L, BigDecimal.ONE, whole.plusNanos(500_000_000), whole}), 4, -1);
        assertTrue(seconds.error().contains("column AT_DATE has more digits of a second than the column keeps (0)"),
            seconds.error());
        RunResult millis = write(source, options("kt_billing.narrow", "append", List.of(), Map.of()), s,
            Rows.list(new Object[] {4L, BigDecimal.ONE, whole, whole.plusNanos(123_400_000)}), 4, -1);
        assertTrue(millis.error().contains("column AT_MS has more digits of a second than the column keeps (3)"),
            millis.error());
        assertEquals(List.of(List.of(1L)), query("SELECT COUNT(*) FROM kt_billing.narrow"));
    }

    @Test
    void appendMatchesLowerCaseInputToUpperCaseColumnsAndChecksLengths() throws Exception {
        db.sql("CREATE TABLE kt_billing.ledger (customer_id NUMBER(19) NOT NULL, total NUMBER(12,2), "
            + "note VARCHAR2(5 CHAR), id NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)");
        RunResult ok = write(source, options("kt_billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 10, "short"), 4, -1);
        assertTrue(ok.succeeded(), ok.error());
        RunResult tooLong = write(source, options("kt_billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 2, "too long"), 4, -1);
        assertTrue(tooLong.error().contains("Row 1: the value in column NOTE is 8 characters long, but the column "
            + "holds at most 5"), tooLong.error());
        assertEquals(List.of(List.of(10L)), query("SELECT COUNT(*) FROM kt_billing.ledger"));
    }

    @Test
    void upsertMergesByKeyAndNeedsAUniqueIndex() throws Exception {
        db.sql("CREATE TABLE kt_billing.spend (customer_id NUMBER(19) PRIMARY KEY, total NUMBER(12,2), "
            + "note CLOB)");
        assertTrue(write(source, options("kt_billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            Rows.spend(0, 100, "first"), 30, -1).succeeded());
        List<Object[]> second = Rows.spend(50, 150, "second");
        second.add(new Object[] {149L, new BigDecimal("9.99"), "x".repeat(40_000)});
        RunResult r = write(source, options("kt_billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            second, 30, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of("first", 50L), List.of("second", 99L), List.of("xxxxxx", 1L)),
            query("SELECT DBMS_LOB.SUBSTR(note, 6, 1), COUNT(*) FROM kt_billing.spend GROUP BY DBMS_LOB.SUBSTR(note, 6, 1) "
                + "ORDER BY 1"));
        assertEquals(List.of(List.of(40_000L)), query("SELECT DBMS_LOB.GETLENGTH(note) FROM kt_billing.spend "
            + "WHERE customer_id = 149"), "a long text reaches a CLOB through MERGE");

        db.sql("CREATE TABLE kt_billing.no_key (customer_id NUMBER(19), total NUMBER(12,2), note VARCHAR2(50))",
            "CREATE UNIQUE INDEX kt_billing.by_upper ON kt_billing.no_key (UPPER(note))");
        RunResult refused = write(source, options("kt_billing.no_key", "upsert", List.of("customer_id"), Map.of()),
            SPEND, Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(refused.error().contains("Cannot upsert into KT_BILLING.NO_KEY by (CUSTOMER_ID)")
            && refused.error().contains("ADD UNIQUE (\"CUSTOMER_ID\")"), refused.error());
    }

    @Test
    void replaceSwapsTheTableAndRepeatsItsGrants() throws Exception {
        db.sql("CREATE TABLE kt_billing.report (customer_id NUMBER(19), total NUMBER(12,2), note VARCHAR2(50))",
            "INSERT INTO kt_billing.report VALUES (1, 1, 'old')",
            "GRANT SELECT ON kt_billing.report TO kt_peek");
        RunResult r = write(source, options("kt_billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "new"), 100, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(500L)), query("SELECT COUNT(*) FROM kt_billing.report "
            + "WHERE DBMS_LOB.SUBSTR(note, 10) = 'new'"));
        SourceConfig peek = db.source("warehouse", "KT_PEEK", PASSWORD, Map.of());
        try (Connection c = db.connector().connect(peek); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM kt_billing.report")) {
            assertTrue(rs.next());
            assertEquals(500, rs.getInt(1), "the grant was repeated on the new table");
        }
        RunResult failed = write(source, options("kt_billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "failed"), 100, 2);
        assertFalse(failed.succeeded());
        assertEquals(List.of(List.of(500L)), query("SELECT COUNT(*) FROM kt_billing.report "
            + "WHERE DBMS_LOB.SUBSTR(note, 10) = 'new'"));
        assertEquals(List.of(), leftovers());
        assertEquals(List.of(List.of(0L)), query("SELECT COUNT(*) FROM ALL_TABLES WHERE OWNER = 'KT_BILLING' "
            + "AND TABLE_NAME LIKE 'BIN$%'"), "the old table is purged, not left in the recycle bin");
    }

    @Test
    void replaceRefusesWhatTheSwapWouldLoseSilently() throws Exception {
        db.sql("CREATE TABLE kt_billing.parent (id NUMBER(19) PRIMARY KEY)",
            "GRANT REFERENCES ON kt_billing.parent TO kt_other",
            "CREATE TABLE kt_other.child (id NUMBER(19), parent_id NUMBER(19), CONSTRAINT fk_parent FOREIGN KEY "
                + "(parent_id) REFERENCES kt_billing.parent (id))",
            "CREATE TABLE kt_billing.audited (customer_id NUMBER(19), total NUMBER(12,2), note VARCHAR2(50))",
            "CREATE TRIGGER kt_billing.stamp BEFORE INSERT ON kt_billing.audited FOR EACH ROW BEGIN :NEW.note := 'x'; "
                + "END;",
            "CREATE VIEW kt_billing.a_view AS SELECT 1 AS customer_id FROM DUAL");
        // a foreign key the login cannot see: the drop fails, the names go back, nothing is lost
        RunResult unseen = write(source, options("kt_billing.parent", "replace", List.of(), Map.of()),
            new Schema(List.of(field("id", Rows.INT64))), Rows.list(new Object[] {1L}), 4, -1);
        assertTrue(unseen.error().contains("ORA-02449"), unseen.error());
        assertEquals(List.of(List.of("PARENT")), query("SELECT TABLE_NAME FROM ALL_TABLES WHERE OWNER = 'KT_BILLING' "
            + "AND TABLE_NAME = 'PARENT'"));
        assertEquals(List.of(List.of(1L)), query("SELECT COUNT(*) FROM ALL_CONSTRAINTS WHERE OWNER = 'KT_OTHER' "
            + "AND CONSTRAINT_NAME = 'FK_PARENT' AND R_OWNER = 'KT_BILLING'"));
        assertEquals(List.of(), leftovers());
        // one it can see is named before anything is written
        db.sql("GRANT SELECT ON kt_other.child TO kt_billing");
        RunResult fk = write(source, options("kt_billing.parent", "replace", List.of(), Map.of()),
            new Schema(List.of(field("id", Rows.INT64))), Rows.list(new Object[] {1L}), 4, -1);
        assertTrue(fk.error().contains("Cannot replace KT_BILLING.PARENT: the foreign key FK_PARENT on KT_OTHER.CHILD "
            + "refers to it"), fk.error());
        RunResult trigger = write(source, options("kt_billing.audited", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(trigger.error().contains("the trigger STAMP is on it"), trigger.error());
        RunResult view = write(source, options("kt_billing.a_view", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 4, -1);
        assertTrue(view.error().contains("it is a view, not a table"), view.error());
        assertEquals(List.of(), leftovers());
    }

    @Test
    void aLoginWithoutRightsGetsAPlainMessageBeforeAnythingIsWritten() throws Exception {
        db.sql("CREATE TABLE kt_billing.customer_spend (customer_id NUMBER(19), total NUMBER(12,2), note VARCHAR2(50))",
            "GRANT SELECT ON kt_billing.customer_spend TO kt_lab");
        RunResult append = write(lab, options("kt_billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(append.error().contains("the login KT_LAB cannot write to KT_BILLING.CUSTOMER_SPEND in warehouse: "
            + "ask a database administrator to grant INSERT on KT_BILLING.CUSTOMER_SPEND"), append.error());
        RunResult create = write(lab, options("kt_billing.lab_new", "create", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(create.error().contains("CREATE ANY TABLE, INSERT ANY TABLE, ALTER ANY TABLE and DROP ANY TABLE"),
            create.error());

        db.sql("GRANT INSERT ON kt_billing.customer_spend TO kt_lab");
        RunResult allowed = write(lab, options("kt_billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 3, "x"), 10, -1);
        assertTrue(allowed.succeeded(), allowed.error());
    }

    @Test
    void commitEveryKeepsWhatWasCommittedAndSaysSo() throws Exception {
        db.sql("CREATE TABLE kt_billing.parts (customer_id NUMBER(19), total NUMBER(12,2), note VARCHAR2(50))");
        RunResult r = write(source, options("kt_billing.parts", "append", List.of(), Map.of("commit_every", 20)), SPEND,
            Rows.spend(0, 100, "x"), 20, 3);
        assertFalse(r.succeeded());
        long kept = ((Number) query("SELECT COUNT(*) FROM kt_billing.parts").get(0).get(0)).longValue();
        assertTrue(kept >= 20 && kept <= 60 && kept % 20 == 0, "kept " + kept + ": " + r.error());
    }

    @Test
    void timestampsWithAZoneAreKeptAsInstants() throws Exception {
        Schema s = new Schema(List.of(field("id", Rows.INT64), field("at", Rows.TIMESTAMP_TZ)));
        Instant at = Instant.parse("2024-03-31T01:30:00.123456Z");
        assertTrue(write(source, options("kt_billing.instants", "create", List.of(), Map.of()), s,
            Rows.list(new Object[] {1L, at}), 4, -1).succeeded());
        Rows.Collected back = read("KT_BILLING.INSTANTS");
        assertEquals(at, back.rows.get(0).get(1));
    }
}
