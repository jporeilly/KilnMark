package io.kilnmark.kiln.plugins.jdbc;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.jdbc.WritePlan.TargetColumn;
import io.kilnmark.kiln.plugins.write.Rows;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/** The SQL the database writers run, their type mapping and their checks, without a database. */
class WriteSqlTest {

    static final TableName T = new TableName("billing", "customer_spend");
    static final TableName ODD = new TableName("zq\"x]", "jyq]\"k");

    static final List<WriteColumn> ALL = WriteColumn.of(new Schema(List.of(
        field("ok", Rows.BOOL), field("tiny", Rows.INT8), field("small", Rows.INT16), field("n", Rows.INT32),
        field("id", Rows.INT64), field("r", Rows.FLOAT32), field("d", Rows.FLOAT64), field("amount", Rows.decimal(12, 2)),
        field("name", Rows.TEXT), field("raw", Rows.BINARY), field("born", Rows.DATE), field("at", Rows.TIME),
        field("local", Rows.TIMESTAMP), field("utc", Rows.TIMESTAMP_TZ),
        field("u64", new org.apache.arrow.vector.types.pojo.ArrowType.Int(64, false)),
        field("tags", new org.apache.arrow.vector.types.pojo.ArrowType.List()))));

    @Test
    void identifiersAreQuotedSoAnyNameIsSafe() {
        assertEquals("\"zq\"\"x]\".\"jyq]\"\"k\"", WriteSql.POSTGRESQL.table(ODD));
        assertEquals("[zq\"x]]].[jyq]]\"k]", WriteSql.SQL_SERVER.table(ODD));
        assertEquals("INSERT INTO \"billing\".\"customer_spend\" (\"a\", \"b\"\"c\") VALUES (?, ?)",
            WriteSql.POSTGRESQL.insert(T, List.of("a", "b\"c")));
        assertEquals("INSERT INTO [billing].[customer_spend] ([a], [b]]c]) VALUES (?, ?)",
            WriteSql.SQL_SERVER.insert(T, List.of("a", "b]c")));
    }

    @Test
    void createdTablesMapEveryKind() {
        assertEquals("CREATE TABLE \"billing\".\"customer_spend\" (\"ok\" boolean, \"tiny\" smallint, \"small\" smallint, "
            + "\"n\" integer, \"id\" bigint, \"r\" real, \"d\" double precision, \"amount\" numeric(12,2), \"name\" text, "
            + "\"raw\" bytea, \"born\" date, \"at\" time, \"local\" timestamp, \"utc\" timestamptz, "
            + "\"u64\" numeric(20,0), \"tags\" text)", WriteSql.POSTGRESQL.createTable(T, ALL, 0));
        assertEquals("CREATE TABLE [billing].[customer_spend] ([ok] bit, [tiny] smallint, [small] smallint, [n] int, "
            + "[id] bigint, [r] real, [d] float, [amount] decimal(12,2), [name] nvarchar(max), [raw] varbinary(max), "
            + "[born] date, [at] time(6), [local] datetime2(6), [utc] datetimeoffset(6), [u64] decimal(20,0), "
            + "[tags] nvarchar(max))", WriteSql.SQL_SERVER.createTable(T, ALL, 0));
        assertEquals("nvarchar(200)", WriteSql.SQL_SERVER.columnType(ALL.get(8), 200));
        assertEquals("varchar(200)", WriteSql.POSTGRESQL.columnType(ALL.get(8), 200));
    }

    @Test
    void upsertsUseOnConflictAndMergeWithHoldlock() {
        assertEquals("INSERT INTO \"billing\".\"customer_spend\" (\"customer_id\", \"total\", \"seen\") VALUES (?, ?, ?) "
            + "ON CONFLICT (\"customer_id\") DO UPDATE SET \"total\" = EXCLUDED.\"total\", \"seen\" = EXCLUDED.\"seen\"",
            WriteSql.POSTGRESQL.upsert(T, List.of("customer_id", "total", "seen"), List.of("customer_id")));
        assertEquals("INSERT INTO \"billing\".\"customer_spend\" (\"a\", \"b\") VALUES (?, ?) ON CONFLICT (\"a\", \"b\") "
            + "DO NOTHING", WriteSql.POSTGRESQL.upsert(T, List.of("a", "b"), List.of("a", "b")));
        assertEquals("MERGE INTO [billing].[customer_spend] WITH (HOLDLOCK) AS [target] USING (VALUES (?, ?)) AS [source] "
            + "([customer_id], [total]) ON [target].[customer_id] = [source].[customer_id] WHEN MATCHED THEN UPDATE SET "
            + "[total] = [source].[total] WHEN NOT MATCHED THEN INSERT ([customer_id], [total]) VALUES "
            + "([source].[customer_id], [source].[total]);",
            WriteSql.SQL_SERVER.upsert(T, List.of("customer_id", "total"), List.of("customer_id")));
        assertEquals("COPY \"billing\".\"customer_spend\" (\"a\") FROM STDIN WITH (FORMAT csv)",
            ((WriteSql.Postgres) WriteSql.POSTGRESQL).copy(T, List.of("a")));
    }

    @Test
    void replaceSwapsNamesThenDropsTheOldTable() {
        assertEquals(List.of(
                new WriteSql.Sql("ALTER TABLE \"billing\".\"customer_spend\" RENAME TO \"kiln_old_1\""),
                new WriteSql.Sql("ALTER TABLE \"billing\".\"kiln_new_1\" RENAME TO \"customer_spend\""),
                new WriteSql.Sql("DROP TABLE \"billing\".\"kiln_old_1\"")),
            WriteSql.POSTGRESQL.swap(T, "kiln_new_1", "kiln_old_1"));
        assertEquals(List.of(
                new WriteSql.Sql("EXEC sp_rename @objname = ?, @newname = ?", List.of("[billing].[customer_spend]",
                    "kiln_old_1")),
                new WriteSql.Sql("EXEC sp_rename @objname = ?, @newname = ?", List.of("[billing].[kiln_new_1]",
                    "customer_spend")),
                new WriteSql.Sql("DROP TABLE [billing].[kiln_old_1]")),
            WriteSql.SQL_SERVER.swap(T, "kiln_new_1", "kiln_old_1"));
    }

    @Test
    void upsertKeysIgnoreIncludedColumnsAndReplaceHandsTheTableBack() {
        assertTrue(WriteSql.POSTGRESQL.uniqueKeys(T).text().contains("(i.indkey::int2[])[0:i.indnkeyatts - 1]"));
        assertEquals("ALTER TABLE \"billing\".\"kiln_new_1\" OWNER TO \"Old \"\"Owner\"",
            WriteSql.POSTGRESQL.setOwner(new TableName("billing", "kiln_new_1"), "Old \"Owner"));
        assertEquals("GRANT ALL PRIVILEGES ON \"billing\".\"t\" TO \"o\"",
            WriteSql.POSTGRESQL.grantOwnerRights(new TableName("billing", "t"), "o"));
        assertEquals("GRANT CONTROL ON [billing].[t] TO [o]]x]",
            WriteSql.SQL_SERVER.grantOwnerRights(new TableName("billing", "t"), "o]x"));
        assertEquals(new WriteSql.Sql("ALTER TABLE \"billing\".\"kiln_new_1\" RENAME TO \"t\""),
            WriteSql.POSTGRESQL.rename(new TableName("billing", "kiln_new_1"), "t"));
        assertEquals(List.of("SET LOCAL lock_timeout = 30000"), WriteSql.POSTGRESQL.lockTimeout(30_000));
        assertEquals(List.of("SET LOCK_TIMEOUT 30000"), WriteSql.SQL_SERVER.lockTimeout(30_000));
        assertTrue(WriteSql.POSTGRESQL.privileges("append", T, true).get(0).query().text()
            .contains("has_any_column_privilege"), "column grants count");
    }

    @Test
    void databaseWritersDeclareTheirSettingsAndTheAssetPathIsTheTable() throws Exception {
        assertEquals(List.of("commit_every", "batch_size", "text_length", "keep_grants", "allow_dropping_row_security",
            "lock_timeout", "profile_output", "keep_values"),
            new PostgresConnector().writeOptions().stream().map(io.kilnmark.kiln.SettingSpec::key).toList());
        assertTrue(new SqlServerConnector().writeOptions().stream().anyMatch(o -> o.key().equals("bulk_copy")));
        assertEquals("billing.t", new PostgresConnector().writtenAssetPath(PG, write("billing.t", "create", List.of(),
            null)));
        String blockers = WriteSql.POSTGRESQL.blockers(T).text();
        assertTrue(blockers.contains("pg_publication_rel") && blockers.contains("typarray")
            && blockers.contains("s.relkind = 'S'"), blockers);
        assertEquals(1, WriteSql.POSTGRESQL.blockers(T).params().size());
        assertTrue(WriteSql.SQL_SERVER.blockers(T).text().contains("is_published"));
        assertEquals("INSERT on billing.customer_spend", WriteSql.POSTGRESQL.needs("append", T, true));
    }

    @Test
    void twoInputColumnsCannotGoToOneTableColumn() {
        List<WriteColumn> in = WriteColumn.of(new Schema(List.of(field("name", Rows.TEXT), field("Name", Rows.TEXT))));
        WritePlan.Plan plan = WritePlan.plan(in, List.of(col("name", "text", Kind.TEXT, 0, 0, 0)), Set.of(), List.of());
        assertEquals(List.of("the input's columns name and Name would both go to the table's column name"),
            plan.problems());
    }

    @Test
    void catalogueQueriesTakeTheNameAsAParameter() {
        WriteSql.Sql exists = WriteSql.POSTGRESQL.exists(ODD);
        assertEquals(List.of("\"zq\"\"x]\".\"jyq]\"\"k\""), exists.params());
        assertTrue(!exists.text().contains("zq"));
        for (WriteSql sql : List.of(WriteSql.POSTGRESQL, WriteSql.SQL_SERVER)) {
            List<WriteSql.Sql> queries = new java.util.ArrayList<>(List.of(sql.exists(ODD), sql.requiredColumns(ODD),
                sql.blockers(ODD), sql.grants(ODD), sql.rowSecurity(ODD), sql.owner(ODD)));
            if (sql.copyRefused(ODD) != null) {
                queries.add(sql.copyRefused(ODD));
            }
            for (WriteSql.Sql q : queries) {
                assertTrue(!q.text().contains("zq") && !q.text().contains("jyq"), q.text());
            }
            for (String mode : List.of("create", "append", "replace", "upsert")) {
                for (WriteSql.Check c : sql.privileges(mode, ODD, true)) {
                    assertTrue(!c.query().text().contains("zq") && !c.query().text().contains("jyq"),
                        c.query().text());
                }
            }
        }
    }

    @Test
    void postgresCopyEncodesValuesAsCsv() {
        assertEquals("\"plain\"", copy(Kind.TEXT, "plain"));
        assertEquals("\"\"", copy(Kind.TEXT, ""), "an empty string is quoted, unlike a null");
        assertEquals("", copy(Kind.TEXT, null));
        assertEquals("\"a \"\"b\"\", c\nd\\e\"", copy(Kind.TEXT, "a \"b\", c\nd\\e"));
        assertThrows(IllegalArgumentException.class, () -> copy(Kind.TEXT, "a\0b"));
        assertEquals("t", copy(Kind.BOOLEAN, true));
        assertEquals("-12", copy(Kind.INT64, -12L));
        assertEquals("NaN", copy(Kind.FLOAT64, Double.NaN));
        assertEquals("-Infinity", copy(Kind.FLOAT32, Float.NEGATIVE_INFINITY));
        assertEquals("12345678901234567890.0000000001", copy(Kind.DECIMAL, new BigDecimal("12345678901234567890.0000000001")));
        assertEquals("0.00000001", copy(Kind.DECIMAL, new BigDecimal("1E-8")));
        assertEquals("\\xcafe00", copy(Kind.BINARY, new byte[] {(byte) 0xCA, (byte) 0xFE, 0}));
        assertEquals("2025-01-31", copy(Kind.DATE, LocalDate.of(2025, 1, 31)));
        assertEquals("0044-03-15 BC", copy(Kind.DATE, LocalDate.of(-43, 3, 15)));
        assertEquals("09:30:00.000001", copy(Kind.TIME, LocalTime.of(9, 30, 0, 1000)));
        assertEquals("2025-01-31 09:30:00.5", copy(Kind.TIMESTAMP, LocalDateTime.of(2025, 1, 31, 9, 30, 0, 500_000_000)));
        assertEquals("2025-01-31 09:30:00+00", copy(Kind.TIMESTAMP_TZ, Instant.parse("2025-01-31T09:30:00Z")));
    }

    private static String copy(Kind kind, Object v) {
        StringBuilder b = new StringBuilder();
        WriteSql.Postgres.copyValue(b, kind, v);
        return b.toString();
    }

    @Test
    void namesTooLongForTheDatabaseAreRefused() {
        assertNull(WriteSql.POSTGRESQL.identifierProblem("a".repeat(63)));
        assertTrue(WriteSql.POSTGRESQL.identifierProblem("a".repeat(64)).contains("63"));
        assertTrue(WriteSql.POSTGRESQL.identifierProblem("é".repeat(32)).contains("64 bytes"));
        assertNull(WriteSql.SQL_SERVER.identifierProblem("a".repeat(128)));
        assertTrue(WriteSql.SQL_SERVER.identifierProblem("a".repeat(129)).contains("128"));
    }

    @Test
    void tableNamesAreSchemaDotTable() throws Exception {
        assertEquals(T, TableName.parse("billing.customer_spend"));
        assertEquals(new TableName("Mixed Case", "Ta-ble"), TableName.parse("Mixed Case.Ta-ble"));
        for (String bad : List.of("", "customer_spend", "a.b.c", ".t", "s.", " s.t", "s.t ", "s.t\u0000")) {
            assertTrue(assertThrows(ConnectorException.class, () -> TableName.parse(bad)).getMessage()
                .startsWith("table: "), bad);
        }
    }

    // ------------------------------------------------------------------ append compatibility

    static TargetColumn col(String name, String type, Kind kind, int precision, int scale, int length) {
        return new TargetColumn(name, type, kind, precision, scale, length);
    }

    @Test
    void appendAcceptsColumnsThatFitAndNamesThoseThatDoNot() {
        List<WriteColumn> in = WriteColumn.of(new Schema(List.of(field("id", Rows.INT32), field("amount",
            Rows.decimal(12, 4)), field("Name", Rows.TEXT), field("seen", Rows.DATE), field("extra", Rows.TEXT),
            field("flag", Rows.BOOL))));
        List<TargetColumn> table = List.of(
            col("id", "bigint", Kind.INT64, 19, 0, 0),
            col("amount", "numeric", Kind.DECIMAL, 10, 2, 0),
            col("name", "varchar", Kind.TEXT, 50, 0, 50),
            col("seen", "timestamptz", Kind.TIMESTAMP_TZ, 0, 0, 0),
            col("flag", "integer", Kind.INT32, 10, 0, 0),
            col("region", "text", Kind.TEXT, 0, 0, 0));
        WritePlan.Plan plan = WritePlan.plan(in, table, Set.of("region", "id"), List.of());
        assertEquals(List.of(
            "the table has no column extra",
            "the input's column flag is boolean but the table's column flag is integer",
            "the table's column region needs a value (it is NOT NULL with no default), but the input has no such column"),
            plan.problems());
        assertEquals(List.of("id", "amount", "name", "seen"), plan.bindings().stream().map(WritePlan.Binding::target)
            .toList(), "Name matches name, ignoring case, as the only such column; amount is checked per value");
        assertEquals(50, plan.bindings().get(2).maxLength());
        assertEquals(List.of(10, 2), List.of(plan.bindings().get(1).precision(), plan.bindings().get(1).scale()));
        String message = WritePlan.message("append", T, plan.problems());
        assertTrue(message.startsWith("Cannot append to billing.customer_spend: its columns do not match the input: "),
            message);
    }

    @Test
    void narrowerDecimalsAreCheckedValueByValueAndNeverRounded() {
        assertEquals(new BigDecimal("1.50"), WritePlan.fitDecimal(new BigDecimal("1.5000"), 10, 2), "trailing zeros go");
        assertEquals(new BigDecimal("12345678.00"), WritePlan.fitDecimal(new BigDecimal("12345678"), 10, 2));
        assertEquals(new BigDecimal("-0.01"), WritePlan.fitDecimal(new BigDecimal("-0.01"), 10, 2));
        assertEquals(new BigDecimal("0.00"), WritePlan.fitDecimal(BigDecimal.ZERO, 2, 2));
        assertNull(WritePlan.fitDecimal(new BigDecimal("1.234"), 10, 2), "would be rounded");
        assertNull(WritePlan.fitDecimal(new BigDecimal("123456789.5"), 10, 2), "nine digits before the point");
        assertTrue(WritePlan.decimalProblem(new BigDecimal("1.234"), 10, 2).contains("decimal places"));
        assertTrue(WritePlan.decimalProblem(new BigDecimal("123456789"), 10, 2).contains("digits before the point"));
    }

    @Test
    void declaredDecimalsOfAnyPrecisionAndNegativeScalesAreChecked() {
        List<WriteColumn> in = WriteColumn.of(new Schema(List.of(field("amount", Rows.decimal(12, 4)))));
        WritePlan.Plan wide = WritePlan.plan(in, List.of(col("amount", "numeric", Kind.DECIMAL, 50, 2, 0)), Set.of(),
            List.of());
        assertEquals(List.of(50, 2), List.of(wide.bindings().get(0).precision(), wide.bindings().get(0).scale()),
            "numeric(50,2) is declared: checked per value, not taken as unconstrained");
        WritePlan.Plan open = WritePlan.plan(in, List.of(col("amount", "numeric", Kind.DECIMAL, 0, 0, 0)), Set.of(),
            List.of());
        assertEquals(0, open.bindings().get(0).precision(), "plain numeric holds anything");
        assertNull(WritePlan.fitDecimal(new BigDecimal("1.2345"), 50, 2), "never rounded, however wide");
        assertEquals(-2, JdbcTableOutput.scale(2046), "numeric(5,-2) as the driver reports it");
        assertEquals(2, JdbcTableOutput.scale(2));
        assertEquals(new BigDecimal("1.23E+4"), WritePlan.fitDecimal(new BigDecimal("12300"), 5, -2));
        assertNull(WritePlan.fitDecimal(new BigDecimal("12345"), 5, -2));
        assertTrue(WritePlan.decimalProblem(new BigDecimal("12345"), 5, -2).contains("not a whole multiple of 100"));
        assertNull(WritePlan.fitDecimal(new BigDecimal("123450000"), 5, -2), "more than seven digits before the point");
    }

    @Test
    void sqlServerBulkCopyGetsFullIsoDatesAndTimes() {
        assertEquals("2024-01-01T00:00:00.0000000", WriteSql.SqlServer.isoText(Kind.TIMESTAMP,
            LocalDateTime.of(2024, 1, 1, 0, 0)));
        assertEquals("2024-06-01T00:00:00.0000010+00:00", WriteSql.SqlServer.isoText(Kind.TIMESTAMP_TZ,
            Instant.parse("2024-06-01T00:00:00.000001Z")));
        assertEquals("00:00:00.0000000", WriteSql.SqlServer.isoText(Kind.TIME, LocalTime.MIDNIGHT));
        assertEquals("0950-03-01", WriteSql.SqlServer.isoText(Kind.DATE, LocalDate.of(950, 3, 1)));
        assertNull(WriteSql.SqlServer.isoText(Kind.TEXT, "x"));
    }

    @Test
    void sqlServerCatalogueUnionsUseTheDatabaseCollation() {
        String q = WriteSql.SQL_SERVER.blockers(T).text();
        for (String branch : q.split("UNION")) {
            assertTrue(branch.contains("COLLATE DATABASE_DEFAULT"), branch);
        }
        assertTrue(WriteSql.SQL_SERVER.owner(T).text().contains("COLLATE DATABASE_DEFAULT"));
    }

    @Test
    void decimalsAndTextFitWhereNoValueIsLost() {
        WriteColumn d = WriteColumn.of(field("x", Rows.decimal(12, 2)));
        assertNull(WritePlan.misfit(d, col("x", "numeric", Kind.DECIMAL, 0, 0, 0)), "unconstrained numeric");
        assertNull(WritePlan.misfit(d, col("x", "numeric", Kind.DECIMAL, 14, 4, 0)));
        assertNull(WritePlan.misfit(d, col("x", "numeric", Kind.DECIMAL, 10, 1, 0)), "narrower: checked per value");
        assertNull(WritePlan.misfit(WriteColumn.of(field("x", Rows.INT64)), col("x", "numeric", Kind.DECIMAL, 5, 0, 0)));
        assertTrue(WritePlan.misfit(WriteColumn.of(field("x", Rows.FLOAT64)), col("x", "numeric", Kind.DECIMAL, 10, 2,
            0)) != null, "floats are not turned into decimals");
        assertNull(WritePlan.misfit(d, col("x", "text", Kind.TEXT, 0, 0, 0)));
        assertNull(WritePlan.misfit(WriteColumn.of(field("x", Rows.INT64)), col("x", "numeric", Kind.DECIMAL, 19, 0, 0)));
        assertTrue(WritePlan.misfit(WriteColumn.of(field("x", Rows.INT64)), col("x", "integer", Kind.INT32, 10, 0, 0))
            != null);
        assertTrue(WritePlan.misfit(WriteColumn.of(field("x", Rows.TEXT)), col("x", "integer", Kind.INT32, 10, 0, 0))
            != null, "text is never parsed into numbers");
        assertTrue(WritePlan.misfit(WriteColumn.of(field("x", Rows.BINARY)), col("x", "text", Kind.TEXT, 0, 0, 0))
            .contains("bytes are not written as text"));
        assertNull(WritePlan.misfit(WriteColumn.of(field("x", Rows.TIMESTAMP)),
            col("x", "timestamptz", Kind.TIMESTAMP_TZ, 0, 0, 0)));
    }

    @Test
    void valuesAreConvertedToTheTableColumnsKind() {
        assertEquals("12.50", WritePlan.convert(new BigDecimal("12.50"), Kind.DECIMAL, Kind.TEXT));
        assertEquals("2025-01-31 09:30:00Z", WritePlan.convert(Instant.parse("2025-01-31T09:30:00Z"), Kind.TIMESTAMP_TZ,
            Kind.TEXT));
        assertEquals(new BigDecimal(7), WritePlan.convert(7L, Kind.INT32, Kind.DECIMAL));
        assertEquals(0.1, WritePlan.convert(0.1f, Kind.FLOAT32, Kind.FLOAT64));
        assertEquals(Instant.parse("2025-01-31T00:00:00Z"), WritePlan.convert(LocalDate.of(2025, 1, 31), Kind.DATE,
            Kind.TIMESTAMP_TZ));
        assertEquals(LocalDateTime.of(2025, 1, 31, 9, 30), WritePlan.convert(Instant.parse("2025-01-31T09:30:00Z"),
            Kind.TIMESTAMP_TZ, Kind.TIMESTAMP));
        assertNull(WritePlan.convert(null, Kind.INT32, Kind.TEXT));
    }

    // ------------------------------------------------------------------ the connector's checks of a write

    static final SourceConfig PG = new SourceConfig("warehouse", "postgresql",
        Map.of("url", "jdbc:postgresql://db.example.com:5432/sales", "username", "lab"), "secret");

    static Connector.WriteOptions write(String target, String mode, List<String> key, String format) {
        return new Connector.WriteOptions(target, mode, key, format, Map.of(), "urn:kilnmark:warehouse:" + target);
    }

    @Test
    void theConnectorChecksTheWriteBeforeConnecting() throws Exception {
        PostgresConnector pg = new PostgresConnector();
        assertTrue(pg.capabilities().contains(Connector.Capability.WRITE));
        assertTrue(new SqlServerConnector().capabilities().contains(Connector.Capability.WRITE));
        assertEquals(JdbcTableOutput.TYPE, pg.writer(PG, write("billing.t", "append", List.of(), null)).type());
        assertEquals(JdbcTableOutput.TYPE, pg.writer(PG, write("billing.t", "upsert", List.of("id"), null)).type());
        Map<Connector.WriteOptions, String> refused = Map.of(
            write("billing.t", "merge", List.of(), null), "mode: create, append, replace or upsert, not 'merge'",
            write("billing.t", null, List.of(), null), "mode: create, append, replace or upsert",
            write("billing.t", "upsert", List.of(), null), "key: upsert needs",
            write("billing.t", "upsert", List.of("id", "id"), null), "key: each key column once",
            write("billing.t", "append", List.of("id"), null), "key: only mode upsert",
            write("billing.t", "create", List.of(), "csv"), "format: only files",
            write("t", "create", List.of(), null), "table: 't' should be schema.table",
            write("billing." + "x".repeat(64), "create", List.of(), null), "table: the name");
        for (var e : refused.entrySet()) {
            ConnectorException ex = assertThrows(ConnectorException.class, () -> pg.writer(PG, e.getKey()));
            assertTrue(ex.getMessage().startsWith(e.getValue()), ex.getMessage());
        }
        Connector.WriteOptions badOption = new Connector.WriteOptions("billing.t", "create", List.of(), null,
            Map.of("commit_every", "lots"), "urn:kilnmark:warehouse:billing.t");
        assertTrue(assertThrows(ConnectorException.class, () -> pg.writer(PG, badOption)).getMessage()
            .startsWith("commit_every: a whole number"));
        SourceConfig bad = new SourceConfig("warehouse", "postgresql", Map.of("url", "jdbc:mysql://x/y"), null);
        assertTrue(assertThrows(ConnectorException.class, () -> pg.writer(bad, write("billing.t", "create", List.of(),
            null))).getMessage().startsWith("Check the settings of warehouse"));
    }
}
