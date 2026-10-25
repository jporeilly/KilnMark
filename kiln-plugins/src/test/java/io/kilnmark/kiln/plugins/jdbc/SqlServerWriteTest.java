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

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.Rows;

/**
 * Writing to SQL Server in every mode, reading back with the connector's own reader. Tagged
 * {@code sqlserver} (the server needs about 2 GB of memory): {@code ./gradlew :kiln-plugins:heavyTest}.
 */
@Tag("sqlserver")
@Testcontainers
class SqlServerWriteTest {

    @Container
    static final MSSQLServerContainer DB = new MSSQLServerContainer(
        DockerImageName.parse("mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04"))
        .acceptLicense()
        .withEnv("MSSQL_MEMORY_LIMIT_MB", "2048");

    static final SqlServerConnector CONNECTOR = new SqlServerConnector();
    static final String LAB_PASSWORD = "Lab-pw-2026!x";
    static String url;
    static SourceConfig source;
    static SourceConfig lab;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE sales");
            s.execute("CREATE LOGIN lab WITH PASSWORD = '" + LAB_PASSWORD + "', CHECK_POLICY = OFF");
        }
        url = DB.getJdbcUrl() + ";databaseName=sales";
        sql("CREATE SCHEMA billing", "CREATE ROLE reader_role", "CREATE USER lab FOR LOGIN lab");
        source = new SourceConfig("warehouse", "sqlserver", Map.of("url", url, "username", DB.getUsername()),
            DB.getPassword());
        lab = new SourceConfig("warehouse", "sqlserver", Map.of("url", url, "username", "lab"), LAB_PASSWORD);
    }

    static void sql(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
             Statement s = c.createStatement()) {
            for (String st : statements) {
                s.execute(st);
            }
        }
    }

    static List<List<Object>> query(String select) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, DB.getUsername(), DB.getPassword());
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
                           int batch, int failAt) throws Exception {
        return Rows.run(Rows.source(schema, rows, batch, failAt), CONNECTOR.writer(s, options), Rows.events());
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

    @Test
    void createWritesEveryKindWithAndWithoutBulkCopy() throws Exception {
        List<Object[]> rows = Rows.allKinds(3000);
        for (boolean bulk : List.of(true, false)) {
            String table = "billing.all_kinds_" + bulk;
            RunResult r = write(source, options(table, "create", List.of(), Map.of("bulk_copy", bulk)),
                Rows.ALL_KINDS, rows, 700, -1);
            assertTrue(r.succeeded(), r.error());
            Rows.Collected back = read(table);
            assertEquals(List.of("int64", "int16", "int16", "int32", "float32", "float64", "decimal", "decimal",
                "decimal", "boolean", "text", "binary", "date", "time", "timestamp", "timestamp_tz"),
                back.columns.stream().map(c -> c.kindName()).toList());
            back.rows.sort(java.util.Comparator.comparing(row -> (Long) row.get(0)));
            assertEquals(rows.size(), back.rows.size());
            for (int i = 0; i < rows.size(); i++) {
                for (int c = 0; c < rows.get(i).length; c++) {
                    Rows.assertSameValue(rows.get(i)[c], back.rows.get(i).get(c), table + " row " + i + " column " + c);
                }
            }
        }
        assertEquals("nvarchar", query("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = "
            + "'all_kinds_true' AND COLUMN_NAME = 'name'").get(0).get(0));
        assertEquals(-1, query("SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = "
            + "'all_kinds_true' AND COLUMN_NAME = 'name'").get(0).get(0), "nvarchar(max)");
    }

    @Test
    void appendChecksColumnsAndTextLengths() throws Exception {
        sql("CREATE TABLE billing.ledger (customer_id bigint NOT NULL, total decimal(10,2), note nvarchar(5))");
        RunResult ok = write(source, options("billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 10, "short"), 4, -1);
        assertTrue(ok.succeeded(), ok.error());
        RunResult tooLong = write(source, options("billing.ledger", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 2, "too long"), 4, -1);
        assertTrue(tooLong.error().contains("Row 1: the value in column note is 8 characters long, but the column "
            + "holds at most 5"), tooLong.error());
        assertEquals(List.of(List.of(10)), query("SELECT COUNT(*) FROM billing.ledger"));
        Schema wrong = new Schema(List.of(field("customer_id", Rows.TEXT)));
        RunResult bad = write(source, options("billing.ledger", "append", List.of(), Map.of()), wrong,
            Rows.list(new Object[] {"x"}), 4, -1);
        assertTrue(bad.error().contains("the input's column customer_id is text but the table's column customer_id "
            + "is bigint"), bad.error());
    }

    @Test
    void upsertMergesByKey() throws Exception {
        sql("CREATE TABLE billing.spend (customer_id bigint PRIMARY KEY, total decimal(12,2), note nvarchar(50))");
        assertTrue(write(source, options("billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND,
            Rows.spend(0, 100, "first"), 30, -1).succeeded());
        List<Object[]> second = Rows.spend(50, 150, "second");
        second.add(new Object[] {149L, new BigDecimal("9.99"), "last wins"});
        RunResult r = write(source, options("billing.spend", "upsert", List.of("customer_id"), Map.of()), SPEND, second,
            30, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of("first", 50), List.of("last wins", 1), List.of("second", 99)),
            query("SELECT note, COUNT(*) FROM billing.spend GROUP BY note ORDER BY note"));
    }

    @Test
    void replaceSwapsWithSpRenameKeepingGrants() throws Exception {
        sql("CREATE TABLE billing.report (customer_id bigint, total decimal(12,2), note nvarchar(50))",
            "INSERT INTO billing.report VALUES (1, 1, 'old'), (2, 2, 'old')",
            "GRANT SELECT ON billing.report TO reader_role");
        RunResult r = write(source, options("billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "new"), 100, -1);
        assertTrue(r.succeeded(), r.error());
        assertEquals(List.of(List.of(500)), query("SELECT COUNT(*) FROM billing.report WHERE note = 'new'"));
        assertEquals(List.of(List.of(1)), query("SELECT COUNT(*) FROM sys.database_permissions p JOIN "
            + "sys.database_principals pr ON pr.principal_id = p.grantee_principal_id WHERE p.major_id = "
            + "OBJECT_ID('billing.report') AND pr.name = 'reader_role' AND p.permission_name = 'SELECT'"));
        RunResult failed = write(source, options("billing.report", "replace", List.of(), Map.of()), SPEND,
            Rows.spend(0, 500, "failed"), 100, 2);
        assertFalse(failed.succeeded());
        assertEquals(List.of(List.of(500)), query("SELECT COUNT(*) FROM billing.report WHERE note = 'new'"));
        assertEquals(List.of(), query("SELECT name FROM sys.tables WHERE name LIKE 'kiln[_]%'"));
    }

    @Test
    void aLoginWithoutRightsGetsAPlainMessage() throws Exception {
        sql("CREATE TABLE billing.customer_spend (customer_id bigint, total decimal(12,2), note nvarchar(50))",
            "GRANT SELECT ON billing.customer_spend TO lab");
        RunResult append = write(lab, options("billing.customer_spend", "append", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(append.error().contains("the login lab cannot write to billing.customer_spend in warehouse: ask a "
            + "database administrator to grant INSERT and SELECT on billing.customer_spend"), append.error());
        RunResult create = write(lab, options("billing.lab_new", "create", List.of(), Map.of()), SPEND,
            Rows.spend(0, 1, "x"), 10, -1);
        assertTrue(create.error().contains("ask a database administrator to grant CREATE TABLE in the database and "
            + "ALTER on the schema billing"), create.error());
    }
}
