package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * MySQL (kind {@code mysql}), and MariaDB through {@link MariaDbConnector} (kind {@code mariadb}),
 * both through MariaDB Connector/J. The two share all their code; the kind sets the default port,
 * the help text, the letter tile and which server versions are accepted (MySQL 8.0.19 and later,
 * MariaDB 10.6 and later; a server of the other product is refused with a hint to add it as that
 * kind).
 *
 * <p>Settings: {@code host}, {@code port}, {@code database} (optional: when empty, every database
 * the login can see is a schema; when set, the source keeps to that one), {@code username}, the
 * password as the source's secret, {@code tls_mode} ({@code preferred}, the default, encrypts when
 * the server offers TLS; {@code disabled}, {@code required}, {@code verify-ca},
 * {@code verify-full}), {@code server_ca} (the PEM certificate of the authority that signed the
 * server's certificate) and {@code tinyint1_as_boolean} (default {@code true}). No driver property
 * can be given by people: the connector sets them all, so a source cannot make the driver read
 * local files, follow a server's redirect to another host, or run statements of its own.
 *
 * <p>Names are quoted {@code `like this`}, a backtick doubled. Types are mapped in
 * {@link MySqlTypes}; the catalogue comes from {@code information_schema} ({@link MySqlCatalog}).
 * Reads stream (the driver fetches {@code batch-size} rows at a time) in a read-only transaction
 * at the server's isolation level (InnoDB's consistent snapshot by default), with session time
 * zone {@code +00:00}.
 */
public class MySqlConnector extends JdbcConnector {

    /** Which product a source of this kind is. */
    enum Product {
        MYSQL("mysql", "MySQL", "My", 8, 0, 19),
        MARIADB("mariadb", "MariaDB", "Ma", 10, 6, 0);

        final String kind;
        final String displayName;
        final String monogram;
        final int[] minimum;

        Product(String kind, String displayName, String monogram, int major, int minor, int patch) {
            this.kind = kind;
            this.displayName = displayName;
            this.monogram = monogram;
            this.minimum = new int[] {major, minor, patch};
        }

        String minimumText() {
            return minimum[0] + "." + minimum[1] + (minimum[2] > 0 ? "." + minimum[2] : "");
        }
    }

    /** The TLS modes, as sources write them, and the driver's {@code sslMode} for each. */
    static final Map<String, String> TLS_MODES = tlsModes();

    /** The default port of both products. */
    static final int DEFAULT_PORT = 3306;

    /** A host name, an IPv4 address or an IPv6 address (bracketed or not): nothing a URL could misread. */
    private static final Pattern HOST = Pattern.compile("\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9_]([A-Za-z0-9._-]{0,251}"
        + "[A-Za-z0-9_])?|[0-9A-Fa-f:.]+");

    /** {@code 8.4.10}, {@code 11.4.12-MariaDB-ubu2404}: the version at the start. */
    private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)");

    private static final Pattern PEM = Pattern.compile("-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)"
        + "-----END CERTIFICATE-----");

    /** Settings the reader step receives besides the URL, user and password. */
    static final String READ_TLS = "tls-mode";
    static final String READ_CA = "server-ca";
    static final String READ_TINYINT = "tinyint1-boolean";

    private final Product product;

    /** MySQL. */
    public MySqlConnector() {
        this(Product.MYSQL);
    }

    MySqlConnector(Product product) {
        this.product = product;
    }

    private static Map<String, String> tlsModes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("preferred", "trust");
        m.put("disabled", "disable");
        m.put("required", "trust");
        m.put("verify-ca", "verify-ca");
        m.put("verify-full", "verify-full");
        return java.util.Collections.unmodifiableMap(m);
    }

    @Override
    public String kind() {
        return product.kind;
    }

    @Override
    public String displayName() {
        return product.displayName;
    }

    @Override
    public String monogram() {
        return product.monogram;
    }

    @Override
    public String docsAnchor() {
        return "mysql-and-mariadb";
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:mariadb://";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:mariadb://db.example.com:3306/";
    }

    // ------------------------------------------------------------------ settings

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("host", "Host", true, "The " + product.displayName + " server's name or address", false),
            new Setting("port", "Port", false, "Leave empty for " + DEFAULT_PORT + ", " + product.displayName
                + "'s usual port", false),
            new Setting("database", "Database", false, "Leave empty to see every database as a schema, or give one "
                + "database to keep the source to it", false),
            new Setting("username", "User name", true, "A login with SELECT is enough for scanning; pipelines that "
                + "write to this source need a login with write rights", false),
            new Setting("tls_mode", "TLS", false, "preferred (the default) encrypts when the server offers TLS, "
                + "without checking its certificate; required always encrypts; verify-ca also checks the certificate "
                + "against the server CA certificate; verify-full also checks the host name; disabled never encrypts",
                false),
            new Setting("server_ca", "Server CA certificate", false, "For verify-ca and verify-full: the PEM "
                + "certificate (-----BEGIN CERTIFICATE----- ...) of the authority that signed the server's "
                + "certificate. Leave empty to use the certificates this server trusts", false),
            new Setting("tinyint1_as_boolean", "Read TINYINT(1) as true or false", false, "true (the default) reads "
                + "TINYINT(1) columns, which is how " + product.displayName + " stores BOOLEAN, as true or false; "
                + "false reads them as numbers", false),
            new Setting("schemas", "Databases", false, "Leave empty to scan every database, or choose them from a list",
                true));
    }

    @Override
    public ContainerKind containerKind() {
        return new ContainerKind("schemas", "Databases", "tables and views", ".");
    }

    @Override
    public String example(String settingKey) {
        return switch (settingKey) {
            case "host" -> "db.example.com";
            case "port" -> Integer.toString(DEFAULT_PORT);
            case "database" -> "shop";
            case "username" -> "kilnmark_reader";
            case "tls_mode" -> "preferred";
            case "tinyint1_as_boolean" -> "true";
            case "schemas" -> "sales, stock";
            default -> null;
        };
    }

    @Override
    protected List<String> validateAddress(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String host = source.setting("host");
        if (host == null) {
            problems.add("host: the " + product.displayName + " server's name or address, for example db.example.com");
        } else if (!HOST.matcher(host).matches() || host.length() > 253) {
            problems.add("host: '" + host + "' is not a host name or address, such as db.example.com or 10.0.4.12");
        }
        String port = source.setting("port");
        if (port != null) {
            int p;
            try {
                p = Integer.parseInt(port);
            } catch (NumberFormatException e) {
                p = -1;
            }
            if (p < 1 || p > 65535) {
                problems.add("port: a number from 1 to 65535, not '" + port + "'");
            }
        }
        String database = source.setting("database");
        if (database != null) {
            String problem = nameProblem(database);
            if (problem != null) {
                problems.add("database: " + problem);
            } else if (MySqlCatalog.isSystem(database)) {
                problems.add("database: " + database + " is the server's own; give a database that holds your data");
            } else {
                List<String> others = source.list("schemas").stream().filter(s -> !s.equalsIgnoreCase(database))
                    .toList();
                if (!others.isEmpty()) {
                    problems.add("schemas: this source keeps to the database " + database + ", so it cannot scan "
                        + String.join(", ", others) + ". Leave the databases to scan empty, or the database empty");
                }
            }
        }
        if (source.setting("username") == null) {
            problems.add("username: the login to connect with, for example kilnmark_reader");
        }
        String tls = source.setting("tls_mode");
        if (tls != null && !TLS_MODES.containsKey(tls.toLowerCase(Locale.ROOT))) {
            problems.add("tls_mode: one of " + String.join(", ", TLS_MODES.keySet())
                + ", or leave it empty for preferred");
        }
        String ca = source.setting("server_ca");
        if (ca != null && pem(ca) == null) {
            problems.add("server_ca: a PEM certificate, from -----BEGIN CERTIFICATE----- to -----END CERTIFICATE-----");
        }
        String mode = tls == null ? "preferred" : tls.toLowerCase(Locale.ROOT);
        if (ca != null && TLS_MODES.containsKey(mode) && !mode.startsWith("verify")) {
            problems.add("server_ca: only verify-ca and verify-full check the certificate; set tls_mode to one of "
                + "them, or leave the certificate empty");
        }
        String flag = source.setting("tinyint1_as_boolean");
        if (flag != null && !flag.equalsIgnoreCase("true") && !flag.equalsIgnoreCase("false")) {
            problems.add("tinyint1_as_boolean: true or false, not '" + flag + "'");
        }
        return problems;
    }

    /** Why a database or table name cannot be one, or null. */
    private static String nameProblem(String name) {
        if (name.codePointCount(0, name.length()) > 64) {
            return "'" + name + "' is longer than the 64 characters MySQL names hold";
        }
        if (name.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "a name cannot hold control characters";
        }
        return null;
    }

    /** The host, port, user name and TLS mode decide where the password goes and how safely. */
    @Override
    public Set<String> secretScope() {
        return Set.of("host", "port", "username", "tls_mode");
    }

    @Override
    public Set<String> addressKeys() {
        return Set.of("host");
    }

    @Override
    public List<String> hosts(SourceConfig source) {
        String host = source.setting("host");
        if (host == null) {
            return List.of();
        }
        return List.of(host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host);
    }

    /** Host, port and database are this connector's own settings. */
    @Override
    public Map<String, String> addressSettings(String host, Integer port, String database) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", host);
        if (port != null) {
            out.put("port", Integer.toString(port));
        }
        if (database != null && !database.isBlank()) {
            out.put("database", database);
        }
        return out;
    }

    @Override
    protected String addressUrl(String host, Integer port, String database) {
        return null;
    }

    private static boolean tinyint1AsBoolean(SourceConfig source) {
        return !"false".equalsIgnoreCase(source.setting("tinyint1_as_boolean"));
    }

    private static String tlsMode(SourceConfig source) {
        String tls = source.setting("tls_mode");
        return tls == null ? "preferred" : tls.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ connecting

    /** {@code jdbc:mariadb://host:port/}: no database (names are always qualified) and no properties. */
    @Override
    protected String jdbcUrl(SourceConfig source) {
        String host = source.setting("host");
        String h = host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        String port = source.setting("port");
        return "jdbc:mariadb://" + h + ":" + (port == null ? DEFAULT_PORT : port) + "/";
    }

    /** MariaDB Connector/J's connect and socket timeouts, in milliseconds. */
    @Override
    protected Properties timeouts(Duration timeout) {
        Properties p = new Properties();
        String ms = Long.toString(seconds(timeout) * 1000L);
        p.setProperty("connectTimeout", ms);
        p.setProperty("socketTimeout", ms);
        return p;
    }

    /** Batched inserts rewritten as multi-row INSERTs; for bulk loads, LOCAL INFILE (always from a stream). */
    @Override
    protected Properties writeProperties(boolean bulk) {
        Properties p = new Properties();
        p.setProperty("rewriteBatchedStatements", "true");
        if (bulk) {
            p.setProperty("allowLocalInfile", "true");
        }
        return p;
    }

    @Override
    protected Connection open(SourceConfig source, Properties p) throws SQLException {
        return connect(product, jdbcUrl(source), p, tlsMode(source), pem(source.setting("server_ca")));
    }

    /**
     * Opens a connection with the driver properties every connection gets: TLS as {@code tls},
     * no redirects to other hosts, MySQL's own types for TINYINT(1), YEAR and BIT, databases as
     * schemas in metadata, and session time zone {@code +00:00}. {@code preferred} falls back to an
     * unencrypted connection only when the server offers no TLS at all. The server must be
     * {@code product} at its minimum version or later.
     */
    static Connection connect(Product product, String url, Properties given, String tls, String pem)
        throws SQLException {
        Properties p = new Properties();
        p.putAll(given);
        p.setProperty("sslMode", TLS_MODES.getOrDefault(tls, "trust"));
        if (pem != null) {
            p.setProperty("serverSslCert", pem);
        }
        p.setProperty("permitRedirect", "false");
        p.setProperty("allowMultiQueries", "false");
        p.setProperty("allowPublicKeyRetrieval", "false");
        p.setProperty("tinyInt1isBit", "false");
        p.setProperty("transformedBitIsBoolean", "false");
        p.setProperty("yearIsDateType", "false");
        p.setProperty("useCatalogTerm", "Schema");
        p.setProperty("sessionVariables", "time_zone='+00:00'");
        if (!given.containsKey("allowLocalInfile")) {
            p.setProperty("allowLocalInfile", "false");
        }
        Connection c;
        try {
            c = DriverManager.getConnection(url, p);
        } catch (SQLException e) {
            if (!"preferred".equals(tls) || e.getMessage() == null || !e.getMessage().contains("ssl not enabled")) {
                throw e;
            }
            p.setProperty("sslMode", "disable");
            c = DriverManager.getConnection(url, p);
        }
        try {
            checkServer(product, c.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    /**
     * Refuses a server of the other product, or older than this one's minimum.
     *
     * @param version as the server reports it, for example {@code 8.4.10} or {@code 11.4.12-MariaDB-ubu2404}
     */
    static void checkServer(Product product, String version) throws SQLException {
        String v = version == null ? "" : version;
        boolean mariadb = v.toLowerCase(Locale.ROOT).contains("mariadb");
        if (mariadb != (product == Product.MARIADB)) {
            String actual = mariadb ? "MariaDB" : "MySQL";
            throw new SQLException("This server is " + actual + " " + v.replaceAll("-.*", "") + ", not "
                + product.displayName + ". Add it as a " + actual + " source instead", "08004");
        }
        Matcher m = VERSION.matcher(v);
        if (m.find()) {
            int[] got = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            for (int i = 0; i < 3; i++) {
                if (got[i] != product.minimum[i]) {
                    if (got[i] < product.minimum[i]) {
                        throw new SQLException("This server is " + product.displayName + " " + m.group() + "; Kilnmark "
                            + "supports " + product.displayName + " " + product.minimumText() + " and later", "08004");
                    }
                    break;
                }
            }
        }
    }

    /**
     * The certificate in {@code text}, with its lines restored, or null when there is none. A
     * certificate pasted into a one-line field loses its line breaks; the base64 between the
     * markers is all that matters.
     */
    static String pem(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PEM.matcher(text);
        if (!m.find()) {
            return null;
        }
        String body = m.group(1).replaceAll("\\s", "");
        try {
            if (Base64.getDecoder().decode(body).length < 100) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        StringBuilder b = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            b.append(body, i, Math.min(body.length(), i + 64)).append('\n');
        }
        return b.append("-----END CERTIFICATE-----\n").toString();
    }

    @Override
    protected Map<String, String> readSettings(SourceConfig source) {
        Map<String, String> s = new HashMap<>();
        s.put(READ_TLS, tlsMode(source));
        String pem = pem(source.setting("server_ca"));
        if (pem != null) {
            s.put(READ_CA, pem);
        }
        s.put(READ_TINYINT, Boolean.toString(tinyint1AsBoolean(source)));
        return s;
    }

    @Override
    protected JdbcTableInput tableInput() {
        return new JdbcTableInput(new Reading(product));
    }

    /** How the reader step connects and maps columns. */
    private record Reading(Product product) implements JdbcTableInput.Reading {

        @Override
        public Connection open(Map<String, String> settings) throws SQLException {
            Properties p = new Properties();
            if (settings.get("user") != null) {
                p.setProperty("user", settings.get("user"));
            }
            if (settings.get("password") != null) {
                p.setProperty("password", settings.get("password"));
            }
            return connect(product, settings.get("url"), p, settings.getOrDefault(READ_TLS, "preferred"),
                settings.get(READ_CA));
        }

        @Override
        public JdbcTableInput.ColumnReader column(ResultSetMetaData md, int i, Map<String, String> settings)
            throws SQLException {
            boolean tinyint = !"false".equals(settings.get(READ_TINYINT));
            return MySqlTypes.reader(md.getColumnLabel(i), MySqlTypes.map(MySqlTypes.fromResult(md, i), tinyint));
        }
    }

    // ------------------------------------------------------------------ the catalogue

    @Override
    protected String quote(String identifier) {
        return dialect().quote(identifier);
    }

    @Override
    public SqlDialect dialect() {
        return product == Product.MARIADB ? SqlDialect.MARIADB : SqlDialect.MYSQL;
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return MySqlCatalog.isSystem(schema);
    }

    @Override
    public List<ContainerInfo> containers(SourceConfig source, Duration timeout) throws ConnectorException {
        checkValid(source);
        AssetFilter patterns = filter(source).allContainers();
        try (Connection c = connect(source, timeout)) {
            return MySqlCatalog.counts(c, source.setting("database"), patterns).entrySet().stream()
                .map(e -> new ContainerInfo(e.getKey(), e.getValue())).toList();
        } catch (SQLException e) {
            throw new ConnectorException("Cannot list the databases of " + source.name() + ": " + explain(e), e);
        }
    }

    @Override
    public List<AssetInfo> assets(SourceConfig source, String container) throws ConnectorException {
        checkValid(source);
        String only = source.setting("database");
        if (only != null && !only.equals(container)) {
            return List.of();
        }
        try (Connection c = connect(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (String[] t : MySqlCatalog.tables(c, container)) {
                out.add(new AssetInfo(container + "." + t[0], t[1], container, t[0], null, List.of(), List.of(),
                    !t[1].equals("table"), true, Map.of("schema", container, "table", t[0])));
            }
            return out;
        } catch (SQLException e) {
            throw new ConnectorException("Cannot list the tables of " + container + " in " + source.name() + ": "
                + explain(e), e);
        }
    }

    @Override
    protected List<JdbcCatalog.Table> readCatalog(Connection connection, AssetFilter filter) throws SQLException {
        return MySqlCatalog.read(connection, MySqlCatalog.wanted(connection, null, filter), filter, true);
    }

    /** The source's database, when it keeps to one, and its TINYINT(1) setting decide what is read. */
    @Override
    protected List<JdbcCatalog.Table> readCatalog(Connection connection, AssetFilter filter, SourceConfig source)
        throws SQLException {
        String only = source.setting("database");
        boolean tinyint = tinyint1AsBoolean(source);
        if (only != null && !filter.containers().isEmpty() && filter.containers().stream()
            .noneMatch(s -> s.equalsIgnoreCase(only))) {
            return List.of();
        }
        List<String> wanted = MySqlCatalog.wanted(connection, only, filter);
        return MySqlCatalog.read(connection, wanted, filter, tinyint);
    }

    @Override
    protected List<JdbcCatalog.ForeignKey> readForeignKeys(Connection connection, List<JdbcCatalog.Table> tables)
        throws SQLException {
        return MySqlCatalog.foreignKeys(connection, tables);
    }

    // ------------------------------------------------------------------ reading

    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return select("*", schema, table, rowLimit);
    }

    /** Spatial columns through {@code ST_AsText}, as well-known text (longitude first on MySQL). */
    @Override
    protected String selectSql(AssetInfo asset, long rowLimit) {
        String schema = asset.hints().get("schema");
        String table = asset.hints().get("table");
        if (asset.fields().stream().noneMatch(MySqlConnector::spatial)) {
            return selectSql(schema, table, rowLimit);
        }
        String columns = asset.fields().stream().map(f -> spatial(f)
            ? "ST_AsText(" + quote(f.name()) + (product == Product.MYSQL ? ", 'axis-order=long-lat'" : "") + ") AS "
                + quote(f.name())
            : quote(f.name())).collect(Collectors.joining(", "));
        return select(columns, schema, table, rowLimit);
    }

    private String select(String columns, String schema, String table, long rowLimit) {
        return "SELECT " + columns + " FROM " + quote(schema) + "." + quote(table)
            + (rowLimit > 0 ? " LIMIT " + rowLimit : "");
    }

    private static boolean spatial(AssetInfo.FieldInfo f) {
        String type = f.sourceType() == null ? "" : f.sourceType().toLowerCase(Locale.ROOT).replaceAll("[ (].*", "");
        return MySqlTypes.SPATIAL.contains(type);
    }

    /** The storage engine's estimate ({@code TABLE_ROWS}), which InnoDB keeps roughly. */
    @Override
    protected String estimateRowsSql(AssetInfo asset) {
        return "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = "
            + dialect().literal(asset.hints().get("schema")) + " AND TABLE_NAME = "
            + dialect().literal(asset.hints().get("table"));
    }

    /**
     * MySQL has no TABLESAMPLE: rows are kept at random with a fixed seed, so the same rows while
     * the table is unchanged. The server still reads the whole table, but only the sample is sent.
     */
    @Override
    protected String tableSampleClause(String percent) {
        return "WHERE RAND(7) * 100 < " + percent;
    }

    // ------------------------------------------------------------------ writing

    @Override
    WriteSql writeSql() {
        return product == Product.MARIADB ? MySqlWriteSql.MARIADB : MySqlWriteSql.MYSQL;
    }

    /**
     * {@code commit_every}, {@code batch_size}, {@code text_length}, {@code lock_timeout} and
     * {@code bulk_load}. There is no {@code keep_grants}: MySQL keeps a table's grants under its
     * name, so a replaced table always has them.
     */
    @Override
    List<SettingSpec> writeOptionsOfOwn() {
        return List.of(
            WriteSettings.number("commit_every", "Commit every N rows", 0,
                "0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A "
                    + "failure then keeps the rows already committed"),
            WriteSettings.number("batch_size", "Rows per batch", 1000,
                "Rows sent to the database at a time (1,000; 5,000 for tables the step creates)"),
            WriteSettings.number("text_length", "Text column length", 0,
                "For create and replace: VARCHAR columns of this length (at most 16,383); 0 (the default) for "
                    + "LONGTEXT"),
            WriteSettings.number("lock_timeout", "Lock timeout (seconds)", 30,
                "For replace: how long the swap waits for others using the old table"),
            WriteSettings.flag("bulk_load", "Bulk load", true,
                "Load tables this step creates with LOAD DATA LOCAL INFILE, sent from memory, when the server allows "
                    + "it (local_infile); otherwise, or when false, with batched inserts"));
    }

    /** {@code database.table}, refused outside the source's database when it keeps to one. */
    @Override
    public String writtenAssetPath(SourceConfig source, WriteOptions write) throws ConnectorException {
        TableName t = TableName.parse(write.target());
        checkDatabase(source, t);
        return t.schema() + "." + t.table();
    }

    @Override
    public io.kilnmark.kiln.Step writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        if (write.target() != null && !write.target().isBlank()) {
            checkDatabase(source, TableName.parse(write.target()));
        }
        return super.writer(source, write);
    }

    private static void checkDatabase(SourceConfig source, TableName t) throws ConnectorException {
        String only = source.setting("database");
        if (only != null && !only.equals(t.schema())) {
            throw new ConnectorException("table: this source keeps to the database " + only + ", so write to "
                + only + "." + t.table() + " rather than " + t);
        }
    }

    /**
     * The table's columns from {@code information_schema.COLUMNS}, which a login sees with any
     * privilege on them, so a login that may only insert can still append.
     */
    @Override
    List<WritePlan.TargetColumn> targetColumns(Connection connection, SourceConfig source, TableName table)
        throws SQLException {
        boolean tinyint = tinyint1AsBoolean(source);
        List<WritePlan.TargetColumn> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, "
            + "NUMERIC_PRECISION, NUMERIC_SCALE, CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS WHERE "
            + "TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table.schema());
            ps.setString(2, table.table());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MySqlTypes.Type type = MySqlTypes.fromCatalog(rs.getString(2), rs.getString(3), longOrNull(rs, 4),
                        longOrNull(rs, 5));
                    MySqlTypes.Mapped m = MySqlTypes.map(type, tinyint);
                    Long chars = longOrNull(rs, 6);
                    boolean varchar = type.name().equals("varchar") || type.name().equals("char");
                    int maxLength = varchar && chars != null && chars > 0 && chars < 1_000_000 ? chars.intValue() : 0;
                    String typeName = type.name() + (type.unsigned() ? " unsigned" : "");
                    out.add(new WritePlan.TargetColumn(rs.getString(1), typeName, m.kind(),
                        m.kind() == JdbcColumn.Kind.DECIMAL ? m.precision() : maxLength, m.scale(), maxLength));
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    // ------------------------------------------------------------------ messages

    @Override
    protected String explain(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint;
        if (e.getErrorCode() == 1045) {
            hint = "Check the user name and password.";
        } else if (e.getErrorCode() == 1130) {
            hint = "The server does not accept this user from Kilnmark's address: ask its administrator to create "
                + "the user for this host (or '%').";
        } else if (e.getErrorCode() == 1044 || e.getErrorCode() == 1049) {
            hint = "Check the database name, and that this login has rights in it.";
        } else if (message.contains("RSA public key")) {
            hint = "The login uses caching_sha2_password, which needs an encrypted connection: set tls_mode to "
                + "preferred or stricter.";
        } else if (message.contains("ssl not enabled")) {
            hint = "The server does not offer TLS: set tls_mode to preferred, or ask its administrator to enable "
                + "TLS.";
        } else if (message.contains("PKIX") || message.contains("certificate") || message.contains("SSL")) {
            hint = "The server's TLS certificate is not trusted, or names another host: give the authority's "
                + "certificate as server_ca, or use verify-ca instead of verify-full.";
        } else if (e.getSQLState() != null && e.getSQLState().startsWith("08") && !"08004".equals(e.getSQLState())) {
            hint = "Check the host and port, that the server accepts TCP connections (bind-address, skip-networking) "
                + "and that no firewall blocks the port.";
        } else {
            return message;
        }
        return message + " " + hint;
    }
}
