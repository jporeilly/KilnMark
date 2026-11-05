package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/** The MySQL and MariaDB connectors without a server: settings, types, quoting and the SQL they write. */
class MySqlConnectorTest {

    private static final MySqlConnector MYSQL = new MySqlConnector();
    private static final MySqlConnector MARIADB = new MariaDbConnector();

    /** A certificate: the bytes need only be long enough base64; nothing parses them here. */
    private static final String CERT = "-----BEGIN CERTIFICATE-----\n"
        + "MIIBszCCAVmgAwIBAgIUQ2k0bXl0ZXN0Y2VydGlmaWNhdGVhYmNkZWYwCgYIKoZIzj0EAwIwEjEQMA4GA1UEAwwHa2lsbi1j\n"
        + "YTAeFw0yNjEwMjUwMDAwMDBaFw0yNzEwMjUwMDAwMDBaMBIxEDAOBgNVBAMMB2tpbG4tY2EwWTATBgcqhkjOPQIBBggqhkjO\n"
        + "-----END CERTIFICATE-----\n";

    private static SourceConfig source(MySqlConnector c, Map<String, String> settings) {
        Map<String, String> s = new HashMap<>(Map.of("host", "db.example.com", "username", "kilnmark_reader"));
        s.putAll(settings);
        s.values().removeIf(v -> v == null || v.isEmpty());
        return new SourceConfig("shop", c.kind(), s, "secret");
    }

    // ------------------------------------------------------------------ the catalogue entry

    @Test
    void twoKindsShareOneConnector() {
        assertEquals("mysql", MYSQL.kind());
        assertEquals("mariadb", MARIADB.kind());
        assertEquals("MySQL", MYSQL.displayName());
        assertEquals("MariaDB", MARIADB.displayName());
        assertEquals("My", MYSQL.monogram());
        assertEquals("Ma", MARIADB.monogram());
        assertEquals("databases", MARIADB.category());
        assertEquals("relational", MARIADB.family());
        assertEquals(Set.of(Connector.Capability.DISCOVER, Connector.Capability.READ, Connector.Capability.WRITE),
            MYSQL.capabilities());
        assertEquals(null, MYSQL.driver(), "bundled");
        assertEquals("Databases", MYSQL.containerKind().label());
        assertEquals("schemas", MYSQL.containerKind().setting());
        assertTrue(Connectors.load().get("mysql").isPresent());
        assertTrue(Connectors.load().get("mariadb").orElseThrow() instanceof MariaDbConnector);
    }

    @Test
    void settingsInFormOrder() {
        assertEquals(List.of("host", "port", "database", "username", "tls_mode", "server_ca", "tinyint1_as_boolean",
            "schemas"), MYSQL.settings().stream().map(Connector.Setting::key).toList());
        assertTrue(MARIADB.settings().get(0).hint().contains("MariaDB"));
        assertTrue(MYSQL.settings().get(0).hint().contains("MySQL"));
        assertTrue(MYSQL.settings().get(0).required());
        assertFalse(MYSQL.settings().get(1).required());
        assertTrue(MYSQL.settings().get(7).multiple());
        assertEquals("db.example.com", MYSQL.example("host"));
        assertEquals("3306", MARIADB.example("port"));
    }

    @Test
    void writeOptionsAreDeclaredWithRealKinds() {
        Map<String, SettingSpec> o = new HashMap<>();
        MYSQL.writeOptions().forEach(s -> o.put(s.key(), s));
        assertEquals(Set.of("commit_every", "batch_size", "text_length", "lock_timeout", "bulk_load",
            "allow_other_unique_keys", "profile_output", "keep_values"), o.keySet());
        for (String flag : List.of("bulk_load", "profile_output", "keep_values")) {
            assertEquals(SettingSpec.Kind.BOOLEAN, o.get(flag).kind(), flag);
            assertEquals(true, o.get(flag).defaultValue(), flag);
        }
        assertEquals(SettingSpec.Kind.BOOLEAN, o.get("allow_other_unique_keys").kind());
        assertEquals(false, o.get("allow_other_unique_keys").defaultValue());
        for (String number : List.of("commit_every", "batch_size", "text_length", "lock_timeout")) {
            assertEquals(SettingSpec.Kind.NUMBER, o.get(number).kind(), number);
        }
        assertEquals(30L, o.get("lock_timeout").defaultValue());
        assertFalse(o.containsKey("keep_grants"), "MySQL keeps grants by name: nothing to choose");
    }

    // ------------------------------------------------------------------ validation and addresses

    @Test
    void aGoodSourceValidates() {
        assertEquals(List.of(), MYSQL.validate(source(MYSQL, Map.of())));
        assertEquals(List.of(), MYSQL.validate(source(MYSQL, Map.of("port", "3307", "database", "shop",
            "tls_mode", "verify-full", "server_ca", CERT, "tinyint1_as_boolean", "FALSE", "schemas", "shop"))));
        assertEquals(List.of(), MARIADB.validate(source(MARIADB, Map.of("host", "fd00::5", "tls_mode", "disabled"))));
        assertEquals(List.of(), MARIADB.validate(source(MARIADB, Map.of("host", "[fd00::5]"))));
    }

    @Test
    void validationNamesTheSettingAtFault() {
        Map<String, String> bad = new HashMap<>();
        bad.put("host", "db.example.com/x?allowLoadLocalInfile=true");
        bad.put("port", "0");
        bad.put("tls_mode", "sometimes");
        bad.put("server_ca", "not a certificate");
        bad.put("tinyint1_as_boolean", "yes");
        bad.put("username", "");
        bad.put(JdbcConnector.TABLE_SAMPLE_ABOVE, "10");
        List<String> problems = MYSQL.validate(source(MYSQL, bad));
        List<String> starts = problems.stream().map(p -> p.substring(0, p.indexOf(':'))).toList();
        assertEquals(List.of("host", "port", "username", "tls_mode", "server_ca", "tinyint1_as_boolean",
            JdbcConnector.TABLE_SAMPLE_ABOVE), starts, problems.toString());
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("port", "x"))).get(0).startsWith("port: a number"));
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("host", ""))).get(0).startsWith("host:"));
    }

    @Test
    void aDatabaseKeepsTheSourceToIt() {
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("database", "shop", "schemas", "shop, stock"))).get(0)
            .startsWith("schemas: this source keeps to the database shop, so it cannot scan stock"));
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("database", "mysql"))).get(0)
            .startsWith("database: mysql is the server's own"));
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("database", "a".repeat(65)))).get(0).startsWith("database:"));
    }

    @Test
    void aCertificateOnlyGoesWithVerification() {
        assertTrue(MYSQL.validate(source(MYSQL, Map.of("server_ca", CERT))).get(0).startsWith("server_ca: only verify"),
            "preferred, the default, never checks a certificate");
        assertEquals(List.of(), MYSQL.validate(source(MYSQL, Map.of("server_ca", CERT, "tls_mode", "verify-ca"))));
    }

    @Test
    void aCertificatePastedOnOneLineIsRestored() {
        String oneLine = CERT.replace("\n", " ");
        String pem = MySqlConnector.pem("  " + oneLine + "  ");
        assertEquals(CERT.replace("\n", "").replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----",
            ""), pem.replace("\n", "").replace("-----BEGIN CERTIFICATE-----", "").replace("-----END CERTIFICATE-----", ""));
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n") && pem.endsWith("\n-----END CERTIFICATE-----\n"));
        assertTrue(pem.lines().allMatch(l -> l.length() <= 64));
        assertNull(MySqlConnector.pem("-----BEGIN CERTIFICATE----- abc -----END CERTIFICATE-----"), "too short");
        assertNull(MySqlConnector.pem("hello"));
        assertNull(MySqlConnector.pem(null));
    }

    @Test
    void addressHostsAndWhereTheSecretGoes() {
        assertEquals(Set.of("host"), MYSQL.addressKeys());
        assertEquals(List.of("db.example.com"), MYSQL.hosts(source(MYSQL, Map.of())));
        assertEquals(List.of("fd00::5"), MYSQL.hosts(source(MYSQL, Map.of("host", "[fd00::5]"))));
        assertEquals(Map.of("host", "10.0.4.12", "port", "3307", "database", "shop"),
            MYSQL.addressSettings("10.0.4.12", 3307, "shop"));
        assertEquals(Map.of("host", "db"), MARIADB.addressSettings("db", null, null));
        assertEquals(Set.of("host", "port", "username", "tls_mode"), MYSQL.secretScope());
        assertEquals("jdbc:mariadb://db.example.com:3306/", MYSQL.jdbcUrl(source(MYSQL, Map.of())));
        assertEquals("jdbc:mariadb://[fd00::5]:3307/", MYSQL.jdbcUrl(source(MYSQL, Map.of("host", "fd00::5",
            "port", "3307"))));
    }

    @Test
    void theReaderGetsTlsAndTypeSettingsButNoDriverProperties() {
        AssetInfo orders = new AssetInfo("shop.orders", "table", "shop", "orders", null, List.of(), List.of(), false,
            true, Map.of("schema", "shop", "table", "orders"));
        Map<String, String> s = MYSQL.reader(source(MYSQL, Map.of("tls_mode", "verify-ca", "server_ca", CERT,
            "tinyint1_as_boolean", "false")), orders, new Connector.ReadOptions("urn:kilnmark:shop:shop.orders", 0,
            100)).settings();
        assertEquals("jdbc:mariadb://db.example.com:3306/", s.get("url"));
        assertEquals("verify-ca", s.get(MySqlConnector.READ_TLS));
        assertTrue(s.get(MySqlConnector.READ_CA).startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertEquals("false", s.get(MySqlConnector.READ_TINYINT));
        assertEquals("SELECT * FROM `shop`.`orders`", s.get("query"));
        assertEquals("preferred", MYSQL.reader(source(MYSQL, Map.of()), orders,
            new Connector.ReadOptions("urn:kilnmark:shop:shop.orders", 0, 100)).settings().get(MySqlConnector.READ_TLS));
    }

    @Test
    void serversOfTheOtherProductOrTooOldAreRefused() throws SQLException {
        MySqlConnector.checkServer(MySqlConnector.Product.MYSQL, "8.4.10");
        MySqlConnector.checkServer(MySqlConnector.Product.MYSQL, "8.0.19");
        MySqlConnector.checkServer(MySqlConnector.Product.MYSQL, "9.5.0-commercial");
        MySqlConnector.checkServer(MySqlConnector.Product.MARIADB, "11.4.12-MariaDB-ubu2404");
        MySqlConnector.checkServer(MySqlConnector.Product.MARIADB, "10.6.0-MariaDB");
        assertTrue(assertThrows(SQLException.class, () -> MySqlConnector.checkServer(MySqlConnector.Product.MYSQL,
            "11.4.12-MariaDB-ubu2404")).getMessage().contains("This server is MariaDB 11.4.12, not MySQL. Add it as a "
            + "MariaDB source"));
        assertTrue(assertThrows(SQLException.class, () -> MySqlConnector.checkServer(MySqlConnector.Product.MARIADB,
            "8.4.10")).getMessage().contains("Add it as a MySQL source"));
        assertTrue(assertThrows(SQLException.class, () -> MySqlConnector.checkServer(MySqlConnector.Product.MYSQL,
            "8.0.18")).getMessage().contains("supports MySQL 8.0.19 and later"));
        assertTrue(assertThrows(SQLException.class, () -> MySqlConnector.checkServer(MySqlConnector.Product.MYSQL,
            "5.7.44-log")).getMessage().contains("supports MySQL 8.0.19 and later"));
        assertTrue(assertThrows(SQLException.class, () -> MySqlConnector.checkServer(MySqlConnector.Product.MARIADB,
            "10.5.27-MariaDB")).getMessage().contains("supports MariaDB 10.6 and later"));
    }

    @Test
    void messagesSayWhatToDo() {
        assertTrue(MYSQL.explain(new SQLException("Access denied for user 'x'@'y'", "28000", 1045))
            .endsWith("Check the user name and password."));
        assertTrue(MYSQL.explain(new SQLException("RSA public key is not available client side")).contains("tls_mode"));
        assertTrue(MYSQL.explain(new SQLException("Host 'k' is not allowed to connect", "HY000", 1130))
            .contains("for this host"));
        assertTrue(MYSQL.explain(new SQLException("Socket fail to connect", "08000", 0)).contains("firewall"));
        assertEquals("Something else", MYSQL.explain(new SQLException("Something else", "HY000", 1)));
    }

    // ------------------------------------------------------------------ quoting and the dialect

    @Test
    void namesAreQuotedWithBackticksDoubled() {
        SqlDialect d = MYSQL.dialect();
        assertEquals("`orders`", d.quote("orders"));
        assertEquals("`odd``name`", d.quote("odd`name"));
        assertEquals("```; DROP TABLE x; --`", d.quote("`; DROP TABLE x; --"));
        assertEquals("`a\"b'c\\d`", d.quote("a\"b'c\\d"));
        assertEquals("`shop`.`my table`", d.table("shop", "my table"));
        assertEquals("SELECT * FROM `kt odd``db`.`odd``name with space` LIMIT 5",
            MYSQL.selectSql("kt odd`db", "odd`name with space", 5));
    }

    @Test
    void literalsMeanTheSameWhateverTheSqlMode() {
        SqlDialect d = MYSQL.dialect();
        assertEquals("_utf8mb4 X'616263'", d.literal("abc"));
        assertEquals("_utf8mb4 X'27205C27'", d.literal("' \\'"), "quotes and backslashes are bytes, never syntax");
        assertEquals("_utf8mb4 X''", d.literal(""));
        assertEquals("_utf8mb4 X'C3A9'", d.literal("é"));
        assertEquals("CAST(t.`x` AS CHAR)", d.text("t.`x`"));
        assertNull(d.regex("x", "a+"), "checked by Kiln");
        assertEquals(List.of("SET TRANSACTION READ ONLY"), d.readOnly());
        assertTrue(d.enforcesReadOnly());
        assertEquals(List.of("SET SESSION max_execution_time = 1500"), SqlDialect.MYSQL.timeout(1500));
        assertEquals(List.of("SET SESSION max_statement_time = 1.500"), SqlDialect.MARIADB.timeout(1500));
        assertEquals(SqlDialect.MYSQL, SqlDialect.forKind("mysql"));
        assertEquals(SqlDialect.MARIADB, SqlDialect.forKind("MariaDB"));
        assertEquals(SqlDialect.MARIADB, MARIADB.dialect());
    }

    @Test
    void spatialColumnsAreReadAsTextAndLargeTablesSampled() {
        AssetInfo places = new AssetInfo("shop.places", "table", "shop", "places", null, List.of(
            new AssetInfo.FieldInfo("id", 1, "int", "int32", false, null),
            new AssetInfo.FieldInfo("spot", 2, "point", "text", true, null),
            new AssetInfo.FieldInfo("area", 3, "geometry", "text", true, null)), List.of(), false, true,
            Map.of("schema", "shop", "table", "places"));
        assertEquals("SELECT `id`, ST_AsText(`spot`, 'axis-order=long-lat') AS `spot`, ST_AsText(`area`, "
            + "'axis-order=long-lat') AS `area` FROM `shop`.`places` LIMIT 10", MYSQL.selectSql(places, 10));
        assertEquals("SELECT `id`, ST_AsText(`spot`) AS `spot`, ST_AsText(`area`) AS `area` FROM `shop`.`places`",
            MARIADB.selectSql(places, 0));
        assertEquals("SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = _utf8mb4 X'73686F70' "
            + "AND TABLE_NAME = _utf8mb4 X'706C61636573'", MYSQL.estimateRowsSql(places));
        assertEquals("WHERE RAND(7) * 100 < 2.5", MYSQL.tableSampleClause("2.5"));
        Map<String, String> s = MYSQL.reader(source(MYSQL, Map.of(JdbcConnector.TABLE_SAMPLE_ABOVE, "10000")), places,
            new Connector.ReadOptions("urn:kilnmark:shop:shop.places", 0, 100)).settings();
        assertTrue(s.get(JdbcTableInput.SAMPLE_QUERY).endsWith("FROM `shop`.`places` WHERE RAND(7) * 100 < {percent}"),
            s.get(JdbcTableInput.SAMPLE_QUERY));
    }

    // ------------------------------------------------------------------ types

    private static MySqlTypes.Mapped catalog(String dataType, String columnType, Long precision, Long scale,
                                             boolean tinyint) {
        return MySqlTypes.map(MySqlTypes.fromCatalog(dataType, columnType, precision, scale), tinyint);
    }

    private static Kind kind(String dataType, String columnType) {
        return catalog(dataType, columnType, null, null, true).kind();
    }

    @Test
    void integersByWidthAndSign() {
        assertEquals(Kind.BOOLEAN, kind("tinyint", "tinyint(1)"));
        assertEquals(Kind.INT16, catalog("tinyint", "tinyint(1)", 3L, 0L, false).kind(), "unless the source says no");
        assertEquals(Kind.INT16, kind("tinyint", "tinyint"));
        assertEquals(Kind.INT16, kind("tinyint", "tinyint(4)"));
        assertEquals(Kind.INT16, kind("tinyint", "tinyint(3) unsigned"));
        assertEquals(Kind.INT16, kind("smallint", "smallint"));
        assertEquals(Kind.INT32, kind("smallint", "smallint unsigned"));
        assertEquals(Kind.INT32, kind("mediumint", "mediumint(8) unsigned"));
        assertEquals(Kind.INT32, kind("int", "int(11)"));
        assertEquals(Kind.INT64, kind("int", "int unsigned"));
        assertEquals(Kind.INT64, kind("bigint", "bigint(20)"));
        MySqlTypes.Mapped big = catalog("bigint", "bigint unsigned", 20L, 0L, true);
        assertEquals(Kind.DECIMAL, big.kind(), "BIGINT UNSIGNED goes past int64");
        assertEquals(20, big.precision());
        assertEquals(0, big.scale());
    }

    @Test
    void decimalsBitsAndTheRest() {
        MySqlTypes.Mapped price = catalog("decimal", "decimal(10,2)", 10L, 2L, true);
        assertEquals(Kind.DECIMAL, price.kind());
        assertEquals(10, price.precision());
        assertEquals(2, price.scale());
        assertEquals(Kind.TEXT, catalog("decimal", "decimal(65,10)", 65L, 10L, true).kind(), "wider than 38 digits");
        assertEquals(Kind.FLOAT32, kind("float", "float"));
        assertEquals(Kind.FLOAT64, kind("double", "double unsigned"));
        assertEquals(Kind.BOOLEAN, kind("bit", "bit(1)"));
        assertEquals(Kind.INT64, kind("bit", "bit(5)"));
        assertEquals(Kind.INT64, kind("bit", "bit(63)"));
        assertEquals(Kind.DECIMAL, kind("bit", "bit(64)"));
        assertEquals(Kind.INT16, kind("year", "year(4)"));
        assertEquals(Kind.DATE, kind("date", "date"));
        assertEquals(Kind.TEXT, kind("time", "time(6)"), "TIME is a duration, read as text");
        assertEquals(Kind.TIMESTAMP, kind("datetime", "datetime(6)"));
        assertEquals(Kind.TIMESTAMP_TZ, kind("timestamp", "timestamp"));
        for (String t : List.of("char", "varchar", "tinytext", "text", "mediumtext", "longtext", "json", "enum", "set",
            "point", "geometry", "multipolygon", "uuid", "inet6")) {
            assertEquals(Kind.TEXT, kind(t, t), t);
        }
        for (String t : List.of("binary", "varbinary", "tinyblob", "blob", "mediumblob", "longblob")) {
            assertEquals(Kind.BINARY, kind(t, t), t);
        }
    }

    @Test
    void zeroAndImpossibleDatesHaveNoValue() {
        assertEquals(LocalDate.of(2024, 2, 29), MySqlTypes.date("2024-02-29"));
        assertEquals(LocalDate.of(0, 1, 1), MySqlTypes.date("0000-01-01"), "year 0 is a year");
        assertNull(MySqlTypes.date("0000-00-00"));
        assertNull(MySqlTypes.date("2024-00-15"));
        assertNull(MySqlTypes.date("2024-03-00"));
        assertNull(MySqlTypes.date("2024-02-31"), "ALLOW_INVALID_DATES");
        assertNull(MySqlTypes.date("garbage"));
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 123_456_000),
            MySqlTypes.dateTime("2024-01-01 10:00:00.123456"));
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0, 0, 500_000_000), MySqlTypes.dateTime("2024-01-01 10:00:00.5"));
        assertEquals(LocalDateTime.of(2024, 1, 1, 10, 0), MySqlTypes.dateTime("2024-01-01 10:00:00"));
        assertNull(MySqlTypes.dateTime("0000-00-00 00:00:00.000000"));
        assertNull(MySqlTypes.dateTime("2024-01-01 25:00:00"));
        assertEquals(new java.math.BigInteger("18446744073709551615"), MySqlTypes.bits(new byte[] {-1, -1, -1, -1, -1,
            -1, -1, -1}));
        assertEquals(21, MySqlTypes.bits(new byte[] {0x15}).intValue());
    }

    // ------------------------------------------------------------------ writing

    private static final MySqlWriteSql MY = MySqlWriteSql.MYSQL;
    private static final MySqlWriteSql MARIA = MySqlWriteSql.MARIADB;
    private static final TableName T = new TableName("shop", "order`s");

    @Test
    void createChoosesMySqlTypes() {
        List<WriteColumn> cols = List.of(
            new WriteColumn("ok", Kind.BOOLEAN, 0, 0, null), new WriteColumn("n", Kind.INT16, 0, 0, null),
            new WriteColumn("i", Kind.INT32, 0, 0, null), new WriteColumn("b", Kind.INT64, 0, 0, null),
            new WriteColumn("f", Kind.FLOAT32, 0, 0, null), new WriteColumn("d", Kind.FLOAT64, 0, 0, null),
            new WriteColumn("p", Kind.DECIMAL, 12, 2, null), new WriteColumn("t", Kind.TEXT, 0, 0, null),
            new WriteColumn("raw", Kind.BINARY, 0, 0, null), new WriteColumn("day", Kind.DATE, 0, 0, null),
            new WriteColumn("at", Kind.TIME, 0, 0, null), new WriteColumn("ts", Kind.TIMESTAMP, 0, 0, null),
            new WriteColumn("utc", Kind.TIMESTAMP_TZ, 0, 0, null));
        assertEquals("CREATE TABLE `shop`.`order``s` (`ok` tinyint(1), `n` smallint, `i` int, `b` bigint, `f` float, "
            + "`d` double, `p` decimal(12,2), `t` longtext, `raw` longblob, `day` date, `at` time(6), `ts` datetime(6), "
            + "`utc` datetime(6)) DEFAULT CHARACTER SET utf8mb4", MY.createTable(T, cols, 0));
        assertEquals("varchar(200)", MY.columnType(cols.get(7), 200));
        assertEquals(16_383, MY.maxTextLength());
        assertFalse(MY.transactionalDdl());
    }

    @Test
    void upsertIsInsertOnDuplicateKeyUpdate() {
        assertEquals("INSERT INTO `shop`.`order``s` (`id`, `total`, `note`) VALUES (?, ?, ?) AS `kiln_new` ON DUPLICATE "
            + "KEY UPDATE `total` = `kiln_new`.`total`, `note` = `kiln_new`.`note`",
            MY.upsert(T, List.of("id", "total", "note"), List.of("id")));
        assertEquals("INSERT INTO `shop`.`order``s` (`id`, `total`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `total` = "
            + "VALUES(`total`)", MARIA.upsert(T, List.of("id", "total"), List.of("id")));
        assertEquals("INSERT INTO `shop`.`order``s` (`id`) VALUES (?) AS `kiln_new` ON DUPLICATE KEY UPDATE `id` = `id`",
            MY.upsert(T, List.of("id"), List.of("id")), "only the key: the row stays as it is");
    }

    @Test
    void replaceSwapsWithOneRenameTable() {
        assertEquals(List.of(
                new WriteSql.Sql("RENAME TABLE `shop`.`order``s` TO `shop`.`kiln_old_1`, `shop`.`kiln_new_1` TO "
                    + "`shop`.`order``s`"),
                new WriteSql.Sql("DROP TABLE `shop`.`kiln_old_1`")),
            MY.swap(T, "kiln_new_1", "kiln_old_1"));
        assertEquals(new WriteSql.Sql("RENAME TABLE `shop`.`kiln_new_1` TO `shop`.`order``s`"),
            MY.rename(new TableName("shop", "kiln_new_1"), "order`s"));
        assertEquals(List.of("SET SESSION lock_wait_timeout = 30", "SET SESSION innodb_lock_wait_timeout = 30"),
            MY.lockTimeout(30_000));
        assertTrue(MY.lockTimedOut(new SQLException("Lock wait timeout exceeded", "HY000", 1205)));
    }

    @Test
    void everyCatalogueQueryHasAParameterForEachPlaceholder() {
        List<WriteSql.Sql> queries = new java.util.ArrayList<>(List.of(MY.exists(T), MY.requiredColumns(T),
            MY.uniqueKeys(T), MY.blockers(T), MY.rowSecurity(T), MY.owner(T), MY.grants(T), MARIA.blockers(T),
            MY.allUniqueKeys(T), MY.hiddenBlockers(T), MARIA.hiddenBlockers(T)));
        for (String mode : List.of("create", "replace", "append", "upsert")) {
            for (boolean exists : List.of(true, false)) {
                MY.privileges(mode, T, exists).forEach(c -> queries.add(c.query()));
                MARIA.privileges(mode, T, exists).forEach(c -> queries.add(c.query()));
            }
        }
        for (WriteSql.Sql q : queries) {
            long marks = q.text().chars().filter(c -> c == '?').count();
            assertEquals(marks, q.params().size(), q.text());
            assertFalse(q.text().contains("order`s"), "names are parameters, never in the SQL: " + q.text());
        }
        assertTrue(MY.privileges("append", T, true).get(0).query().text().contains("CURRENT_ROLE() <> 'NONE'"));
        assertTrue(MARIA.privileges("append", T, true).get(0).query().text().contains("CURRENT_ROLE() IS NOT NULL"));
    }

    @Test
    void rightsChecksAndMessages() {
        assertEquals(5, MY.privileges("create", T, false).size(), "the database, then CREATE, INSERT, ALTER, DROP");
        assertEquals(7, MY.privileges("replace", T, true).size(), "and ALTER and DROP on the table");
        assertEquals(1, MY.privileges("append", T, true).size());
        assertEquals(2, MY.privileges("upsert", T, true).size());
        assertTrue(MY.privileges("create", T, false).get(0).missing().startsWith("the database shop does not exist"));
        assertEquals("INSERT and UPDATE on shop.order`s", MY.needs("upsert", T, true));
        assertTrue(MY.needs("replace", T, true).contains("ALTER and DROP on shop.order`s"));
        assertTrue(MY.permissionDenied(new SQLException("INSERT command denied", "42000", 1142)));
        assertTrue(MY.permissionDenied(new SQLException("UPDATE command denied for column", "42000", 1143)));
        assertFalse(MY.permissionDenied(new SQLException("Duplicate entry", "23000", 1062)));
        assertTrue(MY.keyIndexAdvice(T, List.of("id")).contains("ALTER TABLE `shop`.`order``s` ADD UNIQUE (`id`)"));
    }

    @Test
    void namesMySqlCannotHoldAreRefused() {
        assertNull(MY.identifierProblem("order lines ü"));
        assertTrue(MY.identifierProblem("a".repeat(65)).contains("64"));
        assertNull(MY.identifierProblem("a".repeat(64)));
        assertTrue(MY.identifierProblem("trailing ").contains("ends with a space"));
        assertTrue(MY.identifierProblem("smile 😀").contains("cannot hold"));
        assertTrue(MY.identifierProblem("zero\0").contains("zero character"));
        assertTrue(MY.sameName("Total", "total"));
    }

    @Test
    void valuesAreSentAsMySqlReadsThem() {
        assertEquals("2025-01-31", MySqlWriteSql.temporal(LocalDate.of(2025, 1, 31)));
        assertEquals("0099-01-31", MySqlWriteSql.temporal(LocalDate.of(99, 1, 31)));
        assertEquals("09:30:00.000000", MySqlWriteSql.temporal(LocalTime.of(9, 30)));
        assertEquals("2025-01-31 09:30:00.123456", MySqlWriteSql.temporal(LocalDateTime.of(2025, 1, 31, 9, 30, 0,
            123_456_789)));
        assertEquals("2024-06-01 00:00:00.000000", MySqlWriteSql.temporal(Instant.parse("2024-06-01T01:00:00+01:00")),
            "an instant in UTC");
        assertTrue(MY.valueProblem(Kind.FLOAT64, Double.NaN).contains("NaN"));
        assertTrue(MY.valueProblem(Kind.FLOAT32, Float.POSITIVE_INFINITY).contains("Infinity"));
        assertTrue(MY.valueProblem(Kind.DATE, LocalDate.of(-44, 3, 15)).contains("year -44"));
        assertTrue(MY.valueProblem(Kind.TIMESTAMP, LocalDateTime.of(10000, 1, 1, 0, 0)).contains("10000"));
        assertNull(MY.valueProblem(Kind.TEXT, "zero\0 is fine in MySQL"));
        assertNull(MY.valueProblem(Kind.INT64, null));
        assertEquals(1000, MY.defaultBatchSize("upsert"));
        assertEquals(5000, MY.defaultBatchSize("create"));
    }

    @Test
    void loadDataStreamsQuotedFieldsWithoutEscapes() {
        assertEquals("LOAD DATA LOCAL INFILE 'kilnmark-rows' INTO TABLE `shop`.`order``s` CHARACTER SET utf8mb4 FIELDS "
            + "TERMINATED BY ',' OPTIONALLY ENCLOSED BY '\"' ESCAPED BY '' LINES TERMINATED BY '\n' (`id`, @kiln_2, "
            + "`note`) SET `raw` = UNHEX(@kiln_2)",
            MY.loadData(T, List.of("id", "raw", "note"), List.of(Kind.INT64, Kind.BINARY, Kind.TEXT)));
        assertEquals("NULL", MY.loadField(Kind.TEXT, null));
        assertEquals("\"NULL\"", MY.loadField(Kind.TEXT, "NULL"), "the word, quoted, is text");
        assertEquals("\"\"", MY.loadField(Kind.TEXT, ""));
        assertEquals("\"say \"\"hi\"\", then\nC:\\temp\\\"", MY.loadField(Kind.TEXT, "say \"hi\", then\nC:\\temp\\"));
        assertEquals("CAFE00", MY.loadField(Kind.BINARY, new byte[] {(byte) 0xCA, (byte) 0xFE, 0}));
        assertEquals("1", MY.loadField(Kind.BOOLEAN, true));
        assertEquals("0.000000012", MY.loadField(Kind.DECIMAL, new BigDecimal("1.2E-8")));
        assertEquals("-5", MY.loadField(Kind.INT64, -5L));
        assertEquals("2025-01-31 09:30:00.000000", MY.loadField(Kind.TIMESTAMP_TZ,
            Instant.parse("2025-01-31T09:30:00Z")));
    }

    @Test
    void theWriterChecksItsTargetFirst() throws ConnectorException {
        SourceConfig s = source(MYSQL, Map.of());
        Connector.WriteOptions to = new Connector.WriteOptions("shop.orders", "create", List.of(), null, Map.of(),
            "urn:kilnmark:shop:shop.orders");
        assertEquals("shop.orders", MYSQL.writtenAssetPath(s, to));
        SourceConfig one = source(MYSQL, Map.of("database", "stock"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writtenAssetPath(one, to)).getMessage()
            .startsWith("table: this source keeps to the database stock"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writer(s, new Connector.WriteOptions(
            "shop." + "x".repeat(70), "create", List.of(), null, Map.of(), "urn:kilnmark:shop:x"))).getMessage()
            .startsWith("table: the name"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writer(s, new Connector.WriteOptions(
            "shop.orders", "upsert", List.of(), null, Map.of(), "urn:kilnmark:shop:shop.orders"))).getMessage()
            .startsWith("key:"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writer(s, new Connector.WriteOptions(
            "shop.orders", "create", List.of(), null, Map.of("batch_size", 0), "urn:kilnmark:shop:shop.orders")))
            .getMessage().startsWith("batch_size:"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writer(s, new Connector.WriteOptions(
            "shop.orders", "create", List.of(), null, Map.of("text_length", 20_000), "urn:kilnmark:shop:shop.orders")))
            .getMessage().startsWith("text_length:"));
        assertTrue(assertThrows(ConnectorException.class, () -> MYSQL.writer(s, new Connector.WriteOptions(
            "shop.orders", "create", List.of(), null, Map.of("bulk_load", "maybe"), "urn:kilnmark:shop:shop.orders")))
            .getMessage().startsWith("bulk_load:"));
    }

    @Test
    void theWriterKeepsToItsOwnRulesWhateverTheServerSays() {
        assertEquals("STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION",
            MySqlWriteSql.SQL_MODE);
        for (int code : List.of(1451, 1217, 3730)) {
            assertTrue(MY.foreignKeyRefused(new SQLException("refused", "23000", code)), Integer.toString(code));
        }
        assertFalse(MY.foreignKeyRefused(new SQLException("lock", "HY000", 1205)));
        TableName t = new TableName("shop", "orders");
        assertEquals("RENAME TABLE `shop`.`orders` TO `shop`.`kiln_new_1`, `shop`.`kiln_old_2` TO `shop`.`orders`",
            MY.swapBack(t, "kiln_new_1", "kiln_old_2").text());
        assertTrue(MY.hiddenBlockers(t).text().contains("information_schema.INNODB_FOREIGN "));
        assertTrue(MySqlWriteSql.MARIADB.hiddenBlockers(t).text().contains("information_schema.INNODB_SYS_FOREIGN "));
        assertTrue(MY.allUniqueKeys(t).text().contains("SUB_PART IS NULL"));
        assertNull(WriteSql.Postgres.INSTANCE.allUniqueKeys(t),
            "PostgreSQL's ON CONFLICT names its index: other unique indexes fail the row instead");
        assertEquals(new TableName("shop", "orders"), MySqlConnector.asStored(true, new TableName("Shop", "Orders")));
        assertEquals(new TableName("Shop", "Orders"), MySqlConnector.asStored(false, new TableName("Shop", "Orders")));
    }

    @Test
    void timeYearAndFractionalSecondsAreCheckedAgainstTheColumn() {
        WritePlan.TargetColumn datetime0 = new WritePlan.TargetColumn("at", "datetime", Kind.TIMESTAMP, 0, 0, 0);
        WritePlan.TargetColumn datetime6 = new WritePlan.TargetColumn("at", "datetime", Kind.TIMESTAMP, 0, 6, 0);
        WritePlan.TargetColumn timestamp3 = new WritePlan.TargetColumn("at", "timestamp", Kind.TIMESTAMP_TZ, 0, 3, 0);
        assertEquals(0, MY.fractionDigits(datetime0));
        assertEquals(-1, MY.fractionDigits(datetime6));
        assertEquals(3, MY.fractionDigits(timestamp3));
        WritePlan.TargetColumn time0 = new WritePlan.TargetColumn("took", "time", Kind.TEXT, 0, 0, 0);
        WritePlan.TargetColumn time3 = new WritePlan.TargetColumn("took", "time", Kind.TEXT, 0, 3, 0);
        assertNull(MY.targetValueProblem(time0, "09:30:00"));
        assertNull(MY.targetValueProblem(time0, "09:30:00.000"));
        assertTrue(MY.targetValueProblem(time0, "09:30:00.5").contains("more digits of a second than the column keeps (0)"));
        assertNull(MY.targetValueProblem(time3, "-838:59:59.123"));
        assertTrue(MY.targetValueProblem(time3, "838:59:59.1234").contains("keeps (3)"));
        WritePlan.TargetColumn year = new WritePlan.TargetColumn("season", "year", Kind.INT16, 0, 0, 0);
        assertNull(MY.targetValueProblem(year, 1901L));
        assertNull(MY.targetValueProblem(year, 2155L));
        assertTrue(MY.targetValueProblem(year, 1900L).contains("1901 to 2155"));
        assertTrue(MY.targetValueProblem(year, 99L).contains("1901 to 2155"));
        WriteColumn stamp = new WriteColumn("took", Kind.TIMESTAMP, 0, 0, null);
        WriteColumn number = new WriteColumn("took", Kind.INT64, 0, 0, null);
        WriteColumn time = new WriteColumn("took", Kind.TIME, 0, 0, null);
        WriteColumn text = new WriteColumn("took", Kind.TEXT, 0, 0, null);
        assertTrue(MY.targetMisfit(stamp, time0).contains("which takes only times or text"));
        assertTrue(MY.targetMisfit(number, time0).contains("which takes only times or text"));
        assertNull(MY.targetMisfit(time, time0));
        assertNull(MY.targetMisfit(text, time0));
        WriteSql pg = WriteSql.Postgres.INSTANCE;
        assertEquals(3, pg.fractionDigits(new WritePlan.TargetColumn("at", "timestamp", Kind.TIMESTAMP, 0, 3, 0)));
        assertEquals(0, pg.fractionDigits(new WritePlan.TargetColumn("at", "time", Kind.TIME, 0, 0, 0)));
        assertEquals(-1, pg.fractionDigits(new WritePlan.TargetColumn("at", "timestamptz", Kind.TIMESTAMP_TZ, 0, 6, 0)));
    }

    @Test
    void replaceRefusesOtherEnginesAndTriggersItCannotSee() {
        String blockers = MY.blockers(new TableName("shop", "orders")).text();
        assertTrue(blockers.contains("NOT IN ('INNODB', 'ARIA', 'MYISAM')"), blockers);
        assertTrue(blockers.contains("one a role gives cannot be checked"), blockers);
        assertFalse(blockers.contains("CURRENT_ROLE"), "a role active no longer lets an unseen trigger through");
        long marks = blockers.chars().filter(c -> c == '?').count();
        assertEquals(marks, MY.blockers(new TableName("shop", "orders")).params().size());
    }
}
