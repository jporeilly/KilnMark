package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

/** SQL Server without a server: settings, SQL, and how the driver's types map. */
class SqlServerConnectorTest {

    private final SqlServerConnector sql = new SqlServerConnector();

    @Test
    void theRegistryFindsSqlServerThroughServiceLoader() {
        Connector c = Connectors.load().get("sqlserver").orElseThrow();
        assertEquals("SQL Server", c.displayName());
        assertEquals(List.of("url", "username", "schemas", "trust_server_certificate", "isolation"),
            c.settings().stream().map(Connector.Setting::key).toList());
        assertEquals("For example jdbc:sqlserver://db.example.com:1433;databaseName=sales;encrypt=true",
            c.settings().get(0).hint());
        assertTrue(!c.settings().get(0).hint().contains("trustServerCertificate"), "people copy the example");
        assertEquals("Trust the server certificate", c.settings().get(3).label());
        assertEquals("Only for test servers with a self-signed certificate. Off by default. With it off, the "
            + "certificate must be valid", c.settings().get(3).hint());
        assertTrue(c.settings().get(4).hint().startsWith("How reads lock: read-uncommitted"));
    }

    @Test
    void trustingTheCertificateIsADriverPropertyNotAnEditOfTheUrl() {
        String url = "jdbc:sqlserver://db:1433;databaseName=sales;encrypt=true";
        AssetInfo t = asset(field("id", 1, "int"));
        Connector.ReadOptions read = new Connector.ReadOptions("urn:kilnmark:x:crm.sites", 0, 100);

        SourceConfig on = trusting(url, "true");
        assertTrue(sql.validate(on).isEmpty(), sql.validate(on).toString());
        assertEquals("true", sql.driverSettings(on).getProperty("trustServerCertificate"));
        assertEquals(1, sql.driverSettings(on).size());
        Map<String, String> node = sql.reader(on, t, read).settings();
        assertEquals(url, node.get("url"), "the URL is passed on as written");
        assertEquals("true", node.get(JdbcTableInput.DRIVER_PREFIX + "trustServerCertificate"));
        assertEquals("true", sql.driverSettings(trusting(url, "TRUE")).getProperty("trustServerCertificate"));

        // off, or not set: nothing is added, so the driver checks the certificate as before
        for (SourceConfig off : List.of(trusting(url, "false"), source(url))) {
            assertTrue(sql.validate(off).isEmpty());
            assertTrue(sql.driverSettings(off).isEmpty());
            assertTrue(sql.reader(off, t, read).settings().keySet().stream()
                .noneMatch(k -> k.startsWith(JdbcTableInput.DRIVER_PREFIX)));
        }
        assertTrue(new PostgresConnector().reader(new SourceConfig("x", "postgresql",
            Map.of("url", "jdbc:postgresql://db/sales"), null), t, read).settings().keySet().stream()
            .noneMatch(k -> k.startsWith(JdbcTableInput.DRIVER_PREFIX)), "PostgreSQL reads as before");

        assertEquals(List.of("trust_server_certificate: true or false, not 'yes'"),
            sql.validate(trusting(url, "yes")));
        assertEquals(List.of("trust_server_certificate: the URL sets trustServerCertificate too. Take it out of the "
            + "URL and use this setting alone"), sql.validate(trusting(url + ";TrustServerCertificate=false", "true")));
        assertTrue(sql.validate(source(url + ";trustServerCertificate=true")).isEmpty(),
            "URLs that already trust the certificate keep working");

        // trusting any certificate could send the password to another server: it must be entered again
        assertTrue(sql.secretScope().contains("trust_server_certificate"));
    }

    @Test
    void theReaderPassesDriverPropertiesUserAndPassword() {
        java.util.Properties p = JdbcTableInput.connectionProperties(Map.of("url", "jdbc:sqlserver://db",
            "user", "kiln", "password", "pa;ss", "driver.trustServerCertificate", "true", "query", "SELECT 1",
            "driver.", "ignored"));
        assertEquals("kiln", p.getProperty("user"));
        assertEquals("pa;ss", p.getProperty("password"));
        assertEquals("true", p.getProperty("trustServerCertificate"));
        assertEquals(3, p.size());
        assertTrue(JdbcTableInput.connectionProperties(Map.of("url", "jdbc:sqlserver://db")).isEmpty());
    }

    @Test
    void aPasswordInTheUrlIsRefused() {
        for (String url : new String[] {"jdbc:sqlserver://db:1433;databaseName=sales;password=hunter2",
            "jdbc:sqlserver://db:1433;user=kiln;Password = hunter2;encrypt=true",
            "jdbc:sqlserver://;serverName=db;PASSWORD=hunter2"}) {
            List<String> problems = sql.validate(source(url));
            assertEquals(1, problems.size(), url);
            assertTrue(problems.get(0).startsWith("url: take the password out of the URL"), problems.get(0));
            assertTrue(!problems.get(0).contains("hunter2"));
        }
        PostgresConnector pg = new PostgresConnector();
        for (String url : new String[] {"jdbc:postgresql://db/sales?password=hunter2",
            "jdbc:postgresql://db/sales?user=kiln&password=hunter2"}) {
            assertTrue(pg.validate(new SourceConfig("x", "postgresql", Map.of("url", url), null)).get(0)
                .startsWith("url: take the password out of the URL"), url);
        }
        assertTrue(pg.validate(new SourceConfig("x", "postgresql",
            Map.of("url", "jdbc:postgresql://db/sales?user=kiln&application_name=password_app&ssl=true"), null)).isEmpty());
        assertTrue(sql.validate(source("jdbc:sqlserver://db;databaseName=passwords;encrypt=true")).isEmpty());
    }

    @Test
    void isolationIsOneOfThreeAndDefaultsToReadUncommitted() {
        assertTrue(sql.validate(source("jdbc:sqlserver://db", "snapshot")).isEmpty());
        assertTrue(sql.validate(source("jdbc:sqlserver://db", "READ-COMMITTED")).isEmpty());
        List<String> problems = sql.validate(source("jdbc:sqlserver://db", "serializable"));
        assertEquals(List.of("isolation: one of read-uncommitted, snapshot, read-committed, or leave it empty for "
            + "read-uncommitted"), problems);

        AssetInfo t = asset(field("id", 1, "int"));
        Connector.ReadOptions read = new Connector.ReadOptions("urn:kilnmark:x:crm.sites", 0, 100);
        assertEquals("1", sql.reader(source("jdbc:sqlserver://db"), t, read).settings()
            .get(JdbcTableInput.ISOLATION));
        assertEquals("4096", sql.reader(source("jdbc:sqlserver://db", "snapshot"), t, read).settings()
            .get(JdbcTableInput.ISOLATION));
        assertEquals("2", sql.reader(source("jdbc:sqlserver://db", "read-committed"), t, read).settings()
            .get(JdbcTableInput.ISOLATION));
        assertTrue(!new PostgresConnector().reader(new SourceConfig("x", "postgresql",
            Map.of("url", "jdbc:postgresql://db/sales"), null), t, read).settings().containsKey(JdbcTableInput.ISOLATION),
            "PostgreSQL reads as before");
    }

    @Test
    void schemaPatternsEscapeLikeWildcardsWithBrackets() {
        assertEquals("a[_]b", sql.schemaPattern(null, "a_b"));
        assertEquals("s[[]1]", sql.schemaPattern(null, "s[1]"));
        assertEquals("50[%] off", sql.schemaPattern(null, "50% off"));
        assertEquals("dbo", sql.schemaPattern(null, "dbo"));
    }

    @Test
    void validationAcceptsSqlServerUrlsOnly() {
        assertTrue(sql.validate(source("jdbc:sqlserver://db:1433;databaseName=sales;encrypt=true")).isEmpty());
        assertTrue(sql.validate(source("jdbc:sqlserver://;serverName=db;databaseName=sales")).isEmpty());
        for (String bad : new String[] {"jdbc:postgresql://db/sales", "jdbc:jtds:sqlserver://db/sales", ""}) {
            List<String> problems = sql.validate(source(bad));
            assertEquals(1, problems.size(), bad);
            assertTrue(problems.get(0).startsWith("url: a SQL Server JDBC URL, for example jdbc:sqlserver://"),
                problems.get(0));
        }
    }

    @Test
    void identifiersAreBracketedWithClosingBracketsDoubled() {
        assertEquals("[customers]", sql.quote("customers"));
        assertEquals("[Order Lines]", sql.quote("Order Lines"));
        assertEquals("[odd]]name]", sql.quote("odd]name"));
        assertEquals("[[x]", sql.quote("[x"));
    }

    @Test
    void aRowLimitIsTop() {
        assertEquals("SELECT * FROM [crm].[customers]", sql.selectSql("crm", "customers", 0));
        assertEquals("SELECT TOP (1000) * FROM [crm].[customers]", sql.selectSql("crm", "customers", 1000));
        assertEquals("SELECT TOP (5) * FROM [Sales Ops].[odd]]name]", sql.selectSql("Sales Ops", "odd]name", 5));
    }

    @Test
    void spatialAndHierarchyColumnsAreReadAsText() {
        AssetInfo plain = asset(field("id", 1, "int"), field("name", 2, "nvarchar"));
        assertEquals("SELECT * FROM [crm].[sites]", sql.selectSql(plain, 0));

        AssetInfo spatial = asset(field("id", 1, "int identity"), field("place", 2, "geography"),
            field("shape", 3, "geometry"), field("org]path", 4, "hierarchyid"));
        assertEquals("SELECT TOP (10) [id], [place].ToString() AS [place], [shape].ToString() AS [shape], "
            + "[org]]path].ToString() AS [org]]path] FROM [crm].[sites]", sql.selectSql(spatial, 10));
    }

    @Test
    void typesMapAsTheMicrosoftDriverReportsThem() {
        // jdbc type, type name, precision, scale: as mssql-jdbc reports them in results and catalogue
        assertKind(JdbcColumn.Kind.BOOLEAN, Types.BIT, "bit", 1, 0);
        assertKind(JdbcColumn.Kind.INT16, Types.TINYINT, "tinyint", 3, 0);
        assertKind(JdbcColumn.Kind.INT16, Types.SMALLINT, "smallint", 5, 0);
        assertKind(JdbcColumn.Kind.INT32, Types.INTEGER, "int", 10, 0);
        assertKind(JdbcColumn.Kind.INT32, Types.INTEGER, "int identity", 10, 0);
        assertKind(JdbcColumn.Kind.INT64, Types.BIGINT, "bigint", 19, 0);
        assertKind(JdbcColumn.Kind.FLOAT32, Types.REAL, "real", 24, 0);
        assertKind(JdbcColumn.Kind.FLOAT64, Types.DOUBLE, "float", 53, 0);
        for (String text : new String[] {"char", "varchar", "text"}) {
            assertKind(JdbcColumn.Kind.TEXT, text.equals("text") ? Types.LONGVARCHAR : Types.VARCHAR, text, 10, 0);
        }
        assertKind(JdbcColumn.Kind.TEXT, Types.NCHAR, "nchar", 3, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.NVARCHAR, "nvarchar", 1073741823, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.LONGNVARCHAR, "ntext", 1073741823, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.LONGNVARCHAR, "xml", 1073741823, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.CHAR, "uniqueidentifier", 36, 0);
        assertKind(JdbcColumn.Kind.DATE, Types.DATE, "date", 10, 0);
        assertKind(JdbcColumn.Kind.TIME, Types.TIME, "time", 16, 7);
        assertKind(JdbcColumn.Kind.TIMESTAMP, Types.TIMESTAMP, "datetime", 23, 3);
        assertKind(JdbcColumn.Kind.TIMESTAMP, Types.TIMESTAMP, "datetime2", 27, 7);
        assertKind(JdbcColumn.Kind.TIMESTAMP, Types.TIMESTAMP, "smalldatetime", 16, 0);
        assertKind(JdbcColumn.Kind.TIMESTAMP_TZ, -155, "datetimeoffset", 34, 7);
        assertKind(JdbcColumn.Kind.BINARY, Types.BINARY, "binary", 4, 0);
        assertKind(JdbcColumn.Kind.BINARY, Types.VARBINARY, "varbinary", 2147483647, 0);
        assertKind(JdbcColumn.Kind.BINARY, Types.LONGVARBINARY, "image", 2147483647, 0);
        assertKind(JdbcColumn.Kind.BINARY, Types.BINARY, "timestamp", 8, 0); // rowversion
        // reported as VARBINARY in the catalogue but as their own codes in results: text either way
        assertKind(JdbcColumn.Kind.TEXT, Types.VARBINARY, "geography", 2147483647, 0);
        assertKind(JdbcColumn.Kind.TEXT, -158, "geography", 2147483647, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.VARBINARY, "geometry", 2147483647, 0);
        assertKind(JdbcColumn.Kind.TEXT, -157, "geometry", 2147483647, 0);
        assertKind(JdbcColumn.Kind.TEXT, Types.VARBINARY, "hierarchyid", 892, 0);
        assertKind(JdbcColumn.Kind.TEXT, -150, "sql_variant", 2147483647, 0);
        assertKind(JdbcColumn.Kind.TEXT, -156, "sql_variant", 0, 0);

        JdbcColumn offset = JdbcColumn.map("seen_at", -155, "datetimeoffset", 34, 7);
        assertEquals(new ArrowType.Timestamp(TimeUnit.MICROSECOND, "UTC"), offset.arrowType());
    }

    @Test
    void moneyKeepsItsFourDecimalPlaces() {
        JdbcColumn money = JdbcColumn.map("m", Types.DECIMAL, "money", 19, 4);
        assertEquals(JdbcColumn.Kind.DECIMAL, money.kind());
        assertEquals(new ArrowType.Decimal(19, 4, 128), money.arrowType());
        assertEquals(new ArrowType.Decimal(10, 4, 128), JdbcColumn.map("s", Types.DECIMAL, "smallmoney", 10, 4).arrowType());
        assertEquals(new ArrowType.Decimal(18, 0, 128), JdbcColumn.map("n", Types.NUMERIC, "numeric", 18, 0).arrowType());
        assertEquals(new ArrowType.Decimal(12, 3, 128), JdbcColumn.map("d", Types.DECIMAL, "decimal", 12, 3).arrowType());
    }

    @Test
    void postgresTypesMapAsBefore() {
        assertKind(JdbcColumn.Kind.TIMESTAMP_TZ, Types.TIMESTAMP, "timestamptz", 35, 6);
        assertKind(JdbcColumn.Kind.FLOAT64, Types.DOUBLE, "money", 17, 17); // pgjdbc reports money as double
        assertKind(JdbcColumn.Kind.TEXT, Types.OTHER, "geometry", 2147483647, 0); // PostGIS
        assertKind(JdbcColumn.Kind.BINARY, Types.BINARY, "bytea", 2147483647, 0);
    }

    @Test
    void systemSchemasAreSkippedWhateverTheirCase() {
        for (String s : new String[] {"sys", "SYS", "INFORMATION_SCHEMA", "information_schema", "guest", "Guest",
            "db_owner", "DB_DataReader", "db_denydatawriter"}) {
            assertTrue(sql.isSystemSchema(s), s);
        }
        for (String s : new String[] {"dbo", "crm", "Sales", "Sales Ops", "db", "public", "pg_catalog", "db_custom"}) {
            assertFalse(sql.isSystemSchema(s), s);
        }
    }

    @Test
    void eachDatabaseSkipsOnlyItsOwnSystemSchemas() {
        PostgresConnector pg = new PostgresConnector();
        for (String s : new String[] {"pg_catalog", "information_schema", "pg_toast", "pg_toast_temp_1", "pg_temp_3",
            "ag_catalog"}) {
            assertTrue(pg.isSystemSchema(s), s);
        }
        for (String s : new String[] {"sys", "guest", "db_owner", "public", "crm", "INFORMATION_SCHEMA"}) {
            assertFalse(pg.isSystemSchema(s), s);
        }
    }

    @Test
    void connectionFailuresSayWhatToDo() {
        assertTrue(sql.explain(new SQLException("Login failed for user 'kiln'.", "S0001", 18456))
            .endsWith("Check the user name and password, and that the server allows SQL Server authentication."));
        assertTrue(sql.explain(new SQLException("Cannot open database \"sales\" requested by the login.", "S0001",
            4060)).contains("Check databaseName in the URL"));
        assertTrue(sql.explain(new SQLException("The driver could not establish a secure connection to SQL Server by "
            + "using Secure Sockets Layer (SSL) encryption. Error: \"PKIX path building failed\"", "08S01", 0))
            .contains("turn on Trust the server certificate (trust_server_certificate=true)"));
        assertTrue(sql.explain(new SQLException("The TCP/IP connection to the host db, port 1433 has failed.", "08S01",
            0)).contains("Check the host and port"));
        assertEquals("Something else.", sql.explain(new SQLException("Something else.", "S0001", 1)));
    }

    private static void assertKind(JdbcColumn.Kind expected, int jdbcType, String typeName, int precision, int scale) {
        assertEquals(expected, JdbcColumn.map("c", jdbcType, typeName, precision, scale).kind(), typeName);
    }

    private static SourceConfig source(String url) {
        return new SourceConfig("x", "sqlserver", Map.of("url", url), null);
    }

    private static SourceConfig trusting(String url, String trust) {
        return new SourceConfig("x", "sqlserver", Map.of("url", url, "trust_server_certificate", trust), null);
    }

    private static SourceConfig source(String url, String isolation) {
        return new SourceConfig("x", "sqlserver", Map.of("url", url, "isolation", isolation), null);
    }

    private static AssetInfo.FieldInfo field(String name, int position, String type) {
        return new AssetInfo.FieldInfo(name, position, type, "text", true, null);
    }

    private static AssetInfo asset(AssetInfo.FieldInfo... fields) {
        return new AssetInfo("crm.sites", "table", "crm", "sites", null, List.of(fields), List.of(), false, true,
            Map.of("schema", "crm", "table", "sites"));
    }
}
