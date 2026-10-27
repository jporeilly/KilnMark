package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;

/** The Oracle connector without a server: settings, addresses, type mapping and the SQL it writes. */
class OracleConnectorTest {

    private static final OracleConnector ORACLE = new OracleConnector();

    private static SourceConfig source(Map<String, String> more) {
        Map<String, String> s = new HashMap<>(Map.of("host", "db.example.com", "service_name", "sales.example.com",
            "username", "KILNMARK_READER"));
        s.putAll(more);
        return new SourceConfig("sales", "oracle", s, "pw");
    }

    @Test
    void itIsRegisteredAndNeedsTheCustomersDriver() {
        Connector c = Connectors.load().get("oracle").orElseThrow();
        assertEquals("Oracle", c.displayName());
        Connector.Driver d = c.driver();
        assertEquals("com.oracle.database.jdbc:ojdbc11", d.coordinates());
        assertEquals("ojdbc11-23.26.3.0.0.jar", d.fileName());
        assertEquals("oracle.jdbc.OracleDriver", d.driverClass());
        assertTrue(d.licence().contains("Free Use Terms"));
        assertEquals(false, d.bundleable());
        assertEquals(java.util.Set.of(Connector.Capability.DISCOVER, Connector.Capability.READ,
            Connector.Capability.WRITE), c.capabilities());
    }

    @Test
    void withoutTheDriverAConnectionTestSaysWhoInstallsIt() {
        ConnectorException e = assertThrows(ConnectorException.class,
            () -> ORACLE.test(source(Map.of()), Duration.ofSeconds(5)));
        assertTrue(e.getMessage().startsWith("Cannot connect to sales: This kind of source needs its driver, "
            + "com.oracle.database.jdbc:ojdbc11 23.26.3.0.0"), e.getMessage());
        assertTrue(e.getMessage().contains("Settings, Drivers"), e.getMessage());
    }

    @Test
    void settingsAreCheckedWithoutConnecting() {
        assertEquals(List.of(), ORACLE.validate(source(Map.of())));
        assertEquals(List.of(), ORACLE.validate(source(Map.of("port", "1522", "encryption", "TLS"))));
        List<String> problems = ORACLE.validate(new SourceConfig("x", "oracle", Map.of("host", "db example",
            "port", "70000", "service_name", "bad/name", "username", "a\"b"), null));
        assertEquals(4, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("host:"));
        assertTrue(problems.get(1).startsWith("port:"));
        assertTrue(problems.get(2).startsWith("service_name:"));
        assertTrue(problems.get(3).startsWith("username:"));
        assertTrue(ORACLE.validate(source(Map.of("encryption", "maybe"))).get(0).startsWith("encryption:"));
        assertEquals(Map.of("host", "db.example.com", "port", "1522", "service_name", "FREEPDB1"),
            ORACLE.addressSettings("db.example.com", 1522, "FREEPDB1"));
    }

    @Test
    void theAddressIsBuiltNeverGiven() {
        assertEquals("jdbc:oracle:thin:@//db.example.com:1521/sales.example.com", ORACLE.jdbcUrl(source(Map.of())));
        assertEquals("jdbc:oracle:thin:@tcps://db.example.com:2484/sales.example.com",
            ORACLE.jdbcUrl(source(Map.of("encryption", "tls", "port", "2484"))));
        assertEquals("jdbc:oracle:thin:@//[2001:db8::1]:1521/s", OracleConnector.url("2001:db8::1", null, "s", false));
        assertEquals(List.of("db.example.com"), ORACLE.hosts(source(Map.of())));
    }

    @Test
    void typesMapWithoutRounding() {
        assertEquals(Kind.INT16, OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 4, 0)).kind());
        assertEquals(Kind.INT32, OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 9, 0)).kind());
        assertEquals(Kind.INT64, OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 18, 0)).kind());
        OracleTypes.Mapped wide = OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 30, 0));
        assertEquals(Kind.DECIMAL, wide.kind());
        assertEquals(30, wide.precision());
        OracleTypes.Mapped hundreds = OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 5, -2));
        assertEquals(List.of(Kind.DECIMAL, 7, 0), List.of(hundreds.kind(), hundreds.precision(), hundreds.scale()));
        OracleTypes.Mapped tiny = OracleTypes.map(OracleTypes.fromCatalog("NUMBER", 2, 5));
        assertEquals(List.of(Kind.DECIMAL, 5, 5), List.of(tiny.kind(), tiny.precision(), tiny.scale()));
        assertEquals(Kind.TEXT, OracleTypes.map(OracleTypes.fromCatalog("NUMBER", null, null)).kind());
        assertEquals(Kind.DECIMAL, OracleTypes.map(OracleTypes.fromCatalog("NUMBER", null, 0)).kind());
        assertEquals(Kind.FLOAT64, OracleTypes.map(OracleTypes.fromCatalog("FLOAT", 126, null)).kind());
        assertEquals(Kind.TIMESTAMP, OracleTypes.map(OracleTypes.fromCatalog("DATE", null, null)).kind());
        assertEquals(Kind.TIMESTAMP_TZ, OracleTypes.map(OracleTypes.fromCatalog("TIMESTAMP(6) WITH LOCAL TIME ZONE",
            null, 6)).kind());
        assertEquals(Kind.BINARY, OracleTypes.map(OracleTypes.fromCatalog("BLOB", null, null)).kind());
        assertEquals(OracleTypes.Read.NULL, OracleTypes.map(OracleTypes.fromCatalog("BFILE", null, null)).read());
        assertEquals("NUMBER(10,2)", OracleCatalog.describe("NUMBER", null, 10, 2, null, null, null));
        assertEquals("VARCHAR2(40 CHAR)", OracleCatalog.describe("VARCHAR2", null, null, null, 40, "C", 160));
        assertEquals("MDSYS.SDO_GEOMETRY", OracleCatalog.describe("SDO_GEOMETRY", "MDSYS", null, null, null, null, 1));
    }

    @Test
    void namesWrittenInLowerCaseAreStoredInUpperCase() {
        assertEquals("ORDERS", OracleWriteSql.stored("orders"));
        assertEquals("Orders", OracleWriteSql.stored("Orders"));
        assertEquals("odd name", OracleWriteSql.stored("odd name"));
        assertEquals("ORDER$2#", OracleWriteSql.stored("order$2#"));
    }

    @Test
    void theWriterRefusesWhatOracleWouldChangeSilently() {
        OracleWriteSql strict = new OracleWriteSql(true, false);
        assertTrue(strict.valueProblem(Kind.TEXT, "").contains("empty text, which Oracle stores as null"));
        assertNull(new OracleWriteSql(true, true).valueProblem(Kind.TEXT, ""));
        assertNull(strict.valueProblem(Kind.TEXT, "x"));
        assertTrue(strict.valueProblem(Kind.DECIMAL, new BigDecimal("1234567890123456789012345678901234567890"))
            .contains("more than 38 significant digits"));
        assertNull(strict.valueProblem(Kind.DECIMAL, new BigDecimal("12345678901234567890123456789012345678e5")));
        assertEquals(0, strict.fractionDigits(OracleConnector.target("D", "DATE", null, null, null)));
        assertEquals(3, strict.fractionDigits(OracleConnector.target("T", "TIMESTAMP", null, 3, null)));
        assertEquals(-1, strict.fractionDigits(OracleConnector.target("N", "NUMBER", 10, 2, null)));
        assertEquals("BOOLEAN", strict.columnType(new io.kilnmark.kiln.plugins.write.WriteColumn("b", Kind.BOOLEAN, 0, 0,
            null), 0));
        assertEquals("NUMBER(1)", new OracleWriteSql(false, false).columnType(
            new io.kilnmark.kiln.plugins.write.WriteColumn("b", Kind.BOOLEAN, 0, 0, null), 0));
    }

    @Test
    void upsertIsOneMergeARowAndReplaceSwapsInOneBlock() {
        OracleWriteSql sql = new OracleWriteSql(true, false);
        TableName t = new TableName("SALES", "ORDERS");
        assertEquals("MERGE INTO \"SALES\".\"ORDERS\" \"kiln_target\" USING (SELECT ? AS \"ID\", ? AS \"NOTE\" FROM DUAL) "
            + "\"kiln_source\" ON (\"kiln_target\".\"ID\" = \"kiln_source\".\"ID\") WHEN MATCHED THEN UPDATE SET "
            + "\"kiln_target\".\"NOTE\" = \"kiln_source\".\"NOTE\" WHEN NOT MATCHED THEN INSERT (\"kiln_target\".\"ID\", "
            + "\"kiln_target\".\"NOTE\") VALUES (\"kiln_source\".\"ID\", \"kiln_source\".\"NOTE\")",
            sql.upsert(t, List.of("ID", "NOTE"), List.of("ID")));
        List<WriteSql.Sql> swap = sql.swap(t, "kiln_new_1", "kiln_old_1");
        assertEquals(1, swap.size(), "one block, which puts the names back when anything fails");
        assertEquals("BEGIN EXECUTE IMMEDIATE 'ALTER TABLE \"SALES\".\"ORDERS\" RENAME TO \"kiln_old_1\"'; "
            + "BEGIN EXECUTE IMMEDIATE 'ALTER TABLE \"SALES\".\"kiln_new_1\" RENAME TO \"ORDERS\"'; "
            + "EXCEPTION WHEN OTHERS THEN EXECUTE IMMEDIATE 'ALTER TABLE \"SALES\".\"kiln_old_1\" RENAME TO \"ORDERS\"'; "
            + "RAISE; END; BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"SALES\".\"kiln_old_1\" PURGE'; "
            + "EXCEPTION WHEN OTHERS THEN EXECUTE IMMEDIATE 'ALTER TABLE \"SALES\".\"ORDERS\" RENAME TO \"kiln_new_1\"'; "
            + "EXECUTE IMMEDIATE 'ALTER TABLE \"SALES\".\"kiln_old_1\" RENAME TO \"ORDERS\"'; RAISE; END; END;",
            swap.get(0).text());
    }

    @Test
    void theDialectSpeaksOracle() {
        SqlDialect d = SqlDialect.forKind("oracle");
        assertEquals("SELECT * FROM (SELECT 1 FROM DUAL) limited FETCH FIRST 5 ROWS ONLY", d.limit("SELECT 1 FROM DUAL", 5));
        assertEquals("TRIM(t.\"NOTE\") IS NULL", d.blank("t.\"NOTE\""));
        assertNull(d.regex("x", "a+"));
        assertEquals(List.of("SET TRANSACTION READ ONLY"), d.readOnly());
        assertEquals("SELECT * FROM \"S\".\"T\" FETCH FIRST 10 ROWS ONLY", ORACLE.selectSql("S", "T", 10));
        assertEquals("SAMPLE (2.5) SEED (7)", ORACLE.tableSampleClause("2.5"));
    }
}
