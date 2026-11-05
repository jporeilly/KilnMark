package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.JdbcDrivers;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * Oracle Database 19c and later (kind {@code oracle}), read and written through Oracle's own JDBC
 * driver, which Kilnmark does not ship: its licence (the Oracle Free Use Terms and Conditions)
 * does not allow it in a product that is sold. The customer installs it ({@link #driver()}); the
 * connector reaches it only through {@link JdbcDrivers} and the standard JDBC interfaces, so no
 * Oracle class is on Kilnmark's class path.
 *
 * <p>Settings: {@code host}, {@code port} (1521), {@code service_name} (the database service, such
 * as {@code FREEPDB1}), {@code username}, the password as the source's secret, {@code encryption}
 * ({@code accepted}, the default, encrypts when the server asks; {@code required} insists on
 * Oracle's native encryption; {@code tls} connects over TCPS, checking the server's certificate
 * against this server's Java trust store and its name against the host) and {@code schemas}. No
 * driver property and no address in Oracle's own syntax can be given by people: the connector
 * builds the address and sets every property, so a source cannot point the driver at local files
 * (wallets, {@code tnsnames.ora}) or another host; the driver's {@code TNS_ADMIN} is an empty
 * private folder ({@link JdbcDrivers#emptyFolder()}), so no {@code ojdbc.properties} of the
 * environment's choosing feeds it either.
 *
 * <p>Each user is a schema; the catalogue comes from the {@code ALL_*} views ({@link OracleCatalog}),
 * types are mapped in {@link OracleTypes}, and writes go through {@link OracleWriteSql}. Sessions
 * use time zone {@code +00:00} and ISO date formats. Names are used as the catalogue spells them;
 * a pipeline target written in lower case ({@code sales.orders}) names the upper-case table
 * ({@code SALES.ORDERS}) unless a table with the lower-case name exists.
 */
public class OracleConnector extends JdbcConnector {

    /** Oracle's JDBC driver for Java 11 and later, pinned: version and the SHA-256 of Maven Central's jar. */
    public static final Driver DRIVER = new Driver("com.oracle.database.jdbc:ojdbc11", "23.26.3.0.0",
        "764cc3f88454d1be117e155d01ed617467b984c9c68f13090dd6083799d2bb8c",
        "Oracle Free Use Terms and Conditions (FUTC)", "https://www.oracle.com/downloads/licenses/oracle-free-license.html",
        false, "oracle.jdbc.OracleDriver");

    static final int DEFAULT_PORT = 1521;
    /** The oldest major version supported. */
    static final int MINIMUM_VERSION = 19;

    static final List<String> ENCRYPTION = List.of("accepted", "required", "tls");

    /** A host name, an IPv4 address or an IPv6 address (bracketed or not). */
    private static final Pattern HOST = Pattern.compile("\\[[0-9A-Fa-f:.]+\\]|[A-Za-z0-9_]([A-Za-z0-9._-]{0,251}"
        + "[A-Za-z0-9_])?|[0-9A-Fa-f:.]+");
    /** A service name: letters, digits and {@code . _ - $ #}, as Oracle Net allows them. */
    private static final Pattern SERVICE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._$#-]{0,127}");

    /** Settings the reader node receives besides the URL, user and password. */
    static final String READ_ENCRYPTION = "oracle-encryption";

    private final JdbcDrivers drivers;

    public OracleConnector() {
        this(JdbcDrivers.NONE);
    }

    OracleConnector(JdbcDrivers drivers) {
        this.drivers = drivers;
    }

    /** With the drivers the customer installed. */
    @Override
    public Connector withContext(ConnectorContext context) {
        return new OracleConnector(context.drivers());
    }

    @Override
    protected JdbcDrivers drivers() {
        return drivers;
    }

    @Override
    public Driver driver() {
        return DRIVER;
    }

    @Override
    public String kind() {
        return "oracle";
    }

    @Override
    public String displayName() {
        return "Oracle";
    }

    /** Encrypted with {@code tls} (TCPS) or {@code required} (Oracle's native encryption, insisted on); not with {@code accepted}. */
    @Override
    public String unencryptedReason(SourceConfig source) {
        String e = encryption(source);
        return e.equals("tls") || e.equals("required") ? null
            : "encryption: " + e + " leaves the connection unencrypted unless the server asks; choose tls or required";
    }

    @Override
    public String monogram() {
        return "Ora";
    }

    @Override
    public String docsAnchor() {
        return "oracle";
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:oracle:thin:@";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:oracle:thin:@//db.example.com:1521/sales.example.com";
    }

    // ------------------------------------------------------------------ settings

    @Override
    public List<Setting> settings() {
        return List.of(
            Setting.text("host", "Host", true, "The Oracle server's name or address"),
            Setting.number("port", "Port", Integer.toString(DEFAULT_PORT), "Leave empty for " + DEFAULT_PORT
                + ", Oracle's usual port"),
            Setting.text("service_name", "Service name", true, "The database service to connect to, for example "
                + "FREEPDB1 or sales.example.com (ask the database's administrator; not a SID)"),
            Setting.text("username", "User name", true, "A login with CREATE SESSION and SELECT on the tables is "
                + "enough for scanning; pipelines that write to this source need a login with write rights"),
            Setting.choice("encryption", "Encryption", ENCRYPTION, "accepted", "accepted (the default) encrypts when "
                + "the server asks for Oracle's native encryption; required always uses it; tls connects over TCPS, "
                + "checking the server's certificate against this server's trusted certificates and its name "
                + "against the host"),
            new Setting("schemas", "Schemas", false, "Leave empty to scan every schema, or choose them from a list",
                true));
    }

    @Override
    public ContainerKind containerKind() {
        return new ContainerKind("schemas", "Schemas", "tables and views", ".");
    }

    @Override
    public String example(String settingKey) {
        return switch (settingKey) {
            case "host" -> "db.example.com";
            case "port" -> Integer.toString(DEFAULT_PORT);
            case "service_name" -> "sales.example.com";
            case "username" -> "KILNMARK_READER";
            case "encryption" -> "accepted";
            case "schemas" -> "SALES, HR";
            default -> null;
        };
    }

    @Override
    protected List<String> validateAddress(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String host = source.setting("host");
        if (host == null) {
            problems.add("host: the Oracle server's name or address, for example db.example.com");
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
        String service = source.setting("service_name");
        if (service == null) {
            problems.add("service_name: the database service, for example FREEPDB1 or sales.example.com");
        } else if (!SERVICE.matcher(service).matches()) {
            problems.add("service_name: letters, digits and . _ - $ # only, such as sales.example.com, not '"
                + service + "'");
        }
        String user = source.setting("username");
        if (user == null) {
            problems.add("username: the login to connect with, for example KILNMARK_READER");
        } else if (user.length() > 128 || user.chars().anyMatch(c -> c < 0x20 || c == '"' || c == 0x7f)) {
            problems.add("username: at most 128 characters, without double quotes or control characters");
        }
        String encryption = source.setting("encryption");
        if (encryption != null && !ENCRYPTION.contains(encryption.toLowerCase(Locale.ROOT))) {
            problems.add("encryption: one of " + String.join(", ", ENCRYPTION) + ", not '" + encryption + "'");
        }
        return problems;
    }

    /** The address, user name and encryption decide where the password goes and how safely. */
    @Override
    public Set<String> secretScope() {
        return Set.of("host", "port", "service_name", "username", "encryption");
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

    /** Host, port and service name are this connector's own settings; the database is the service. */
    @Override
    public Map<String, String> addressSettings(String host, Integer port, String database) {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("host", host);
        if (port != null) {
            out.put("port", Integer.toString(port));
        }
        if (database != null && !database.isBlank()) {
            out.put("service_name", database);
        }
        return out;
    }

    private static String encryption(SourceConfig source) {
        String e = source.setting("encryption");
        return e == null ? "accepted" : e.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ connecting

    /** {@code jdbc:oracle:thin:@//host:port/service}, or {@code @tcps://…} for TLS: Oracle's EZConnect. */
    @Override
    protected String jdbcUrl(SourceConfig source) {
        return url(source.setting("host"), source.setting("port"), source.setting("service_name"),
            encryption(source).equals("tls"));
    }

    static String url(String host, String port, String service, boolean tls) {
        String h = host != null && host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:oracle:thin:@" + (tls ? "tcps://" : "//") + h + ":" + (port == null ? DEFAULT_PORT : port) + "/"
            + service;
    }

    /** The driver's connect and read timeouts, in milliseconds. */
    @Override
    protected Properties timeouts(Duration timeout) {
        Properties p = new Properties();
        String ms = Long.toString(seconds(timeout) * 1000L);
        p.setProperty("oracle.net.CONNECT_TIMEOUT", ms);
        p.setProperty("oracle.jdbc.ReadTimeout", ms);
        return p;
    }

    @Override
    protected Connection open(SourceConfig source, Properties p) throws SQLException {
        return connect(jdbcUrl(source), p, encryption(source));
    }

    /**
     * Opens a connection through the customer's driver with the properties every connection gets:
     * the encryption asked for, time zones as offsets rather than regions, and no file of this
     * server's (wallet, tnsnames.ora) consulted; then sets the session's time zone and date
     * formats, and checks the server is 19c or later.
     */
    Connection connect(String url, Properties given, String encryption) throws SQLException {
        Properties p = new Properties();
        p.putAll(given);
        p.setProperty("oracle.jdbc.timezoneAsRegion", "false");
        java.nio.file.Path empty = drivers.emptyFolder();
        if (empty != null) {
            // where the driver would look for ojdbc.properties and tnsnames.ora: an empty folder of ours
            p.setProperty("oracle.net.tns_admin", empty.toString());
        }
        p.setProperty("v$session.program", "Kilnmark");
        if (encryption.equals("required")) {
            p.setProperty("oracle.net.encryption_client", "REQUIRED");
            p.setProperty("oracle.net.crypto_checksum_client", "REQUIRED");
        } else if (encryption.equals("tls")) {
            p.setProperty("oracle.net.ssl_server_dn_match", "true");
        }
        Connection c = connectUrl(url, p);
        try {
            int major = c.getMetaData().getDatabaseMajorVersion();
            if (major < MINIMUM_VERSION) {
                throw new SQLException("This server is Oracle Database " + major + "; Kilnmark supports Oracle "
                    + MINIMUM_VERSION + "c and later", "08004");
            }
            try (Statement s = c.createStatement()) {
                s.execute("ALTER SESSION SET TIME_ZONE = '+00:00' NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS' "
                    + "NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF6' "
                    + "NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF6 TZH:TZM' NLS_NUMERIC_CHARACTERS = '.,'");
            }
        } catch (SQLException e) {
            c.close();
            throw e;
        }
        return c;
    }

    @Override
    protected Map<String, String> readSettings(SourceConfig source) {
        return Map.of(READ_ENCRYPTION, encryption(source));
    }

    @Override
    protected JdbcTableInput tableInput() {
        return new JdbcTableInput(new Reading(this));
    }

    /** How the reader node connects (through the customer's driver) and maps columns. */
    private record Reading(OracleConnector connector) implements JdbcTableInput.Reading {

        @Override
        public Connection open(Map<String, String> settings) throws SQLException {
            Properties p = new Properties();
            if (settings.get("user") != null) {
                p.setProperty("user", settings.get("user"));
            }
            if (settings.get("password") != null) {
                p.setProperty("password", settings.get("password"));
            }
            return connector.connect(settings.get("url"), p, settings.getOrDefault(READ_ENCRYPTION, "accepted"));
        }

        @Override
        public JdbcTableInput.ColumnReader column(ResultSetMetaData md, int i, Map<String, String> settings)
            throws SQLException {
            return OracleTypes.reader(md.getColumnLabel(i), OracleTypes.map(OracleTypes.fromResult(md, i)));
        }
    }

    // ------------------------------------------------------------------ the catalogue

    @Override
    protected String quote(String identifier) {
        return dialect().quote(identifier);
    }

    @Override
    public SqlDialect dialect() {
        return SqlDialect.ORACLE;
    }

    /** Oracle's own schemas are known from the catalogue ({@code ORACLE_MAINTAINED}), not by name. */
    @Override
    protected boolean isSystemSchema(String schema) {
        return false;
    }

    /**
     * Schemas Oracle itself keeps, refused by Explore and previews even when named: SYS holds the
     * {@code V$} views, whose SQL text quotes other sessions' values.
     */
    @Override
    protected boolean refusedSchema(String schema) {
        return ORACLE_OWN.contains(schema.toUpperCase(Locale.ROOT));
    }

    private static final java.util.Set<String> ORACLE_OWN = java.util.Set.of("SYS", "SYSTEM", "AUDSYS", "SYSBACKUP",
        "SYSDG", "SYSKM", "SYSRAC", "XDB", "MDSYS", "CTXSYS", "ORDSYS", "ORDDATA", "OLAPSYS", "DBSNMP", "OUTLN", "LBACSYS",
        "DVSYS", "DVF", "GSMADMIN_INTERNAL", "GSMCATUSER", "OJVMSYS", "WMSYS", "APPQOSSYS", "DBSFWUSER", "REMOTE_SCHEDULER_AGENT",
        "ANONYMOUS", "GGSYS", "XS$NULL", "DIP", "MDDATA", "SI_INFORMTN_SCHEMA", "ORDPLUGINS", "PUBLIC");

    @Override
    public List<ContainerInfo> containers(SourceConfig source, Duration timeout) throws ConnectorException {
        checkValid(source);
        AssetFilter patterns = filter(source).allContainers();
        try (Connection c = connect(source, timeout)) {
            return OracleCatalog.counts(c, patterns).entrySet().stream()
                .map(e -> new ContainerInfo(e.getKey(), e.getValue())).toList();
        } catch (SQLException e) {
            throw new ConnectorException("Cannot list the schemas of " + source.name() + ": " + explain(e), e);
        }
    }

    @Override
    public List<AssetInfo> assets(SourceConfig source, String container) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (String[] t : OracleCatalog.tables(c, container)) {
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
        return OracleCatalog.read(connection, OracleCatalog.wanted(connection, filter), filter);
    }

    @Override
    protected List<JdbcCatalog.ForeignKey> readForeignKeys(Connection connection, List<JdbcCatalog.Table> tables)
        throws SQLException {
        return OracleCatalog.foreignKeys(connection, tables);
    }

    // ------------------------------------------------------------------ reading

    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return select("*", schema, table, rowLimit);
    }

    /**
     * XMLTYPE columns through {@code XMLSERIALIZE}, as text; BFILE columns (files outside the
     * database) as null; TIMESTAMP WITH LOCAL TIME ZONE as TIMESTAMP WITH TIME ZONE, so the value
     * arrives with its offset and never depends on the JVM's time zone.
     */
    @Override
    protected String selectSql(AssetInfo asset, long rowLimit) {
        String schema = asset.hints().get("schema");
        String table = asset.hints().get("table");
        if (asset.fields().stream().noneMatch(f -> special(f) != null)) {
            return selectSql(schema, table, rowLimit);
        }
        String columns = asset.fields().stream().map(f -> {
            String how = special(f);
            return how == null ? quote(f.name()) : how + " AS " + quote(f.name());
        }).collect(Collectors.joining(", "));
        return select(columns, schema, table, rowLimit);
    }

    private String special(AssetInfo.FieldInfo f) {
        String type = f.sourceType() == null ? "" : OracleTypes.baseName(f.sourceType());
        if (type.equals("XMLTYPE") || type.endsWith(".XMLTYPE")) {
            return "XMLSERIALIZE(CONTENT " + quote(f.name()) + " AS CLOB)";
        }
        if (type.equals("BFILE")) {
            return "CAST(NULL AS VARCHAR2(1))";
        }
        if (type.equals("TIMESTAMP WITH LOCAL TIME ZONE")) {
            return "CAST(" + quote(f.name()) + " AS TIMESTAMP(9) WITH TIME ZONE)";
        }
        return null;
    }

    private String select(String columns, String schema, String table, long rowLimit) {
        return select(columns, schema, table, null, rowLimit);
    }

    private String select(String columns, String schema, String table, String where, long rowLimit) {
        return "SELECT " + columns + " FROM " + quote(schema) + "." + quote(table)
            + (where == null ? "" : " WHERE " + where) + (rowLimit > 0 ? " FETCH FIRST " + rowLimit + " ROWS ONLY" : "");
    }

    @Override
    protected String selectSql(AssetInfo asset, long rowLimit, boolean explicit, String where) {
        String columns = explicit || asset.fields().stream().anyMatch(f -> special(f) != null)
            ? asset.fields().stream().map(f -> {
                String how = special(f);
                return how == null ? quote(f.name()) : how + " AS " + quote(f.name());
            }).collect(Collectors.joining(", "))
            : "*";
        return select(columns, asset.hints().get("schema"), asset.hints().get("table"), where, rowLimit);
    }

    /** Oracle has no boolean column before 23ai, and drivers differ on binding one: left to the read node. */
    @Override
    protected boolean bindsBooleans() {
        return false;
    }

    /** Ordinary tables kept in this database only, for Count exactly (ADR 0045). */
    @Override
    protected String localTableSql() {
        return "SELECT 1 FROM ALL_TABLES t WHERE t.OWNER = ? AND t.TABLE_NAME = ? AND NOT EXISTS "
            + "(SELECT 1 FROM ALL_EXTERNAL_TABLES e WHERE e.OWNER = t.OWNER AND e.TABLE_NAME = t.TABLE_NAME)";
    }

    /** The optimiser's row count from the last statistics; none without statistics, so the table is read in full. */
    @Override
    protected String estimateRowsSql(AssetInfo asset) {
        return "SELECT NUM_ROWS FROM ALL_TABLES WHERE OWNER = " + dialect().literal(asset.hints().get("schema"))
            + " AND TABLE_NAME = " + dialect().literal(asset.hints().get("table"));
    }

    /** {@code SAMPLE (p) SEED (7)}: a row sample, the same rows while the table is unchanged. */
    @Override
    protected String tableSampleClause(String percent) {
        return "SAMPLE (" + percent + ") SEED (7)";
    }

    // ------------------------------------------------------------------ writing

    /** For the capabilities: the actual SQL depends on the server and the node ({@link #writer}). */
    @Override
    WriteSql writeSql() {
        return new OracleWriteSql(false, false);
    }

    @Override
    List<SettingSpec> writeOptionsOfOwn() {
        return List.of(
            WriteSettings.number("commit_every", "Commit every N rows", 0,
                "0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A "
                    + "failure then keeps the rows already committed"),
            WriteSettings.number("batch_size", "Rows per batch", 1000, "Rows sent to the database at a time"),
            WriteSettings.number("text_length", "Text column length", 0,
                "For create and replace: VARCHAR2 columns of this many characters (at most 4,000); 0 (the default) "
                    + "for CLOB"),
            WriteSettings.flag("keep_grants", "Keep grants", true,
                "For replace: repeat the old table's grants on the new one"),
            WriteSettings.flag("allow_dropping_row_security", "Allow dropping row-level security", false,
                "For replace: replace a table with virtual private database policies, which the new table will not "
                    + "have"),
            WriteSettings.number("lock_timeout", "Lock timeout (seconds)", 30,
                "For replace: how long the swap waits for others using the old table"),
            WriteSettings.flag("empty_text_as_null", "Write empty text as null", false,
                "Oracle stores empty text as null. false (the default) refuses empty text, so nothing changes "
                    + "silently; true writes it as null"));
    }

    @Override
    public String writtenAssetPath(SourceConfig source, WriteOptions write) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source)) {
            return resolve(c, TableName.parse(write.target())).toString();
        } catch (SQLException e) {
            throw new ConnectorException("Cannot look up " + write.target() + " in " + source.name() + ": "
                + explain(e), e);
        }
    }

    /**
     * Writes with SQL for this server (BOOLEAN columns on 23ai and later, its character sets) and
     * this node ({@code empty_text_as_null}), to the table as the catalogue names it.
     */
    @Override
    public io.kilnmark.kiln.Node writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        checkValid(source);
        boolean emptyAsNull = new WriteSettings(write.options()).bool("empty_text_as_null", false);
        TableName target;
        boolean booleans;
        Map<String, String> charsets;
        try (Connection c = connect(source)) {
            target = write.target() == null || write.target().isBlank() ? null : resolve(c, TableName.parse(write.target()));
            booleans = c.getMetaData().getDatabaseMajorVersion() >= 23;
            charsets = charsets(c);
        } catch (SQLException e) {
            throw new ConnectorException("Cannot write to " + source.name() + ": " + explain(e), e);
        }
        WriteOptions resolved = target == null ? write : new WriteOptions(target.toString(), write.mode(), write.key(),
            write.format(), write.options(), write.assetUrn(), write.profiling());
        return writer(source, resolved, new OracleWriteSql(booleans, emptyAsNull,
            charsets.getOrDefault("NLS_CHARACTERSET", "AL32UTF8"),
            charsets.getOrDefault("NLS_NCHAR_CHARACTERSET", "AL16UTF16")));
    }

    /** The database's character set and national character set, from {@code NLS_DATABASE_PARAMETERS}. */
    static Map<String, String> charsets(Connection c) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT PARAMETER, VALUE FROM "
            + "NLS_DATABASE_PARAMETERS WHERE PARAMETER IN ('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET')")) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getString(2));
            }
        }
        return out;
    }

    /**
     * {@code t} as the catalogue names it: each part as written when a schema or table of that exact
     * name exists, otherwise as Oracle would store it unquoted ({@code orders} as {@code ORDERS}).
     */
    static TableName resolve(Connection c, TableName t) throws SQLException {
        String schema = exists(c, "SELECT 1 FROM ALL_USERS WHERE USERNAME = ?", t.schema()) ? t.schema()
            : OracleWriteSql.stored(t.schema());
        String table = exists(c, "SELECT 1 FROM ALL_OBJECTS WHERE OWNER = ? AND OBJECT_NAME = ? AND OBJECT_TYPE IN "
            + "('TABLE', 'VIEW')", schema, t.table()) ? t.table() : OracleWriteSql.stored(t.table());
        return new TableName(schema, table);
    }

    private static boolean exists(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setString(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * The table's columns from {@code ALL_TAB_COLUMNS}, which a login sees with any privilege on the
     * table, so one that may only insert can still append; a timestamp's scale is its digits of a
     * second ({@code DATE}: none); a text column's precision is its size in bytes
     * ({@code DATA_LENGTH}, at most 4,000 unless {@code MAX_STRING_SIZE} is {@code EXTENDED}).
     */
    @Override
    List<WritePlan.TargetColumn> targetColumns(Connection connection, SourceConfig source, TableName table)
        throws SQLException {
        List<WritePlan.TargetColumn> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT COLUMN_NAME, DATA_TYPE, DATA_PRECISION, "
            + "DATA_SCALE, CHAR_LENGTH, CHAR_USED, DATA_LENGTH FROM ALL_TAB_COLUMNS WHERE OWNER = ? AND TABLE_NAME = ? "
            + "ORDER BY COLUMN_ID")) {
            ps.setString(1, table.schema());
            ps.setString(2, table.table());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String dataType = rs.getString(2);
                    String base = OracleTypes.baseName(dataType);
                    Integer precision = OracleCatalog.intOrNull(rs, 3);
                    Integer scale = OracleCatalog.intOrNull(rs, 4);
                    out.add(target(rs.getString(1), base, precision, scale, OracleCatalog.intOrNull(rs, 5),
                        "B".equals(rs.getString(6)), OracleCatalog.intOrNull(rs, 7)));
                }
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * One column as a writer matches its input to it.
     *
     * @param chars     {@code CHAR_LENGTH}: a text column's length in characters
     * @param byteSized {@code CHAR_USED} is {@code B}: the column is sized in bytes
     * @param bytes     {@code DATA_LENGTH}: its size in bytes
     */
    static WritePlan.TargetColumn target(String name, String base, Integer precision, Integer scale, Integer chars,
                                         boolean byteSized, Integer bytes) {
        JdbcColumn.Kind kind;
        int p = 0;
        int s = 0;
        int maxLength = 0;
        switch (base) {
            case "NUMBER" -> {
                kind = JdbcColumn.Kind.DECIMAL;
                p = precision == null ? (scale == null ? 0 : 38) : precision;
                s = scale == null ? 0 : scale;
            }
            // a decimal NUMBER of b bits: any scale, ceil(b × 0.30103) significant digits (the scale
            // carries them; OracleWriteSql.targetValueProblem checks each value)
            case "FLOAT" -> {
                kind = JdbcColumn.Kind.DECIMAL;
                s = floatDigits(precision == null ? 126 : precision);
            }
            case "BINARY_DOUBLE" -> kind = JdbcColumn.Kind.FLOAT64;
            case "BINARY_FLOAT" -> kind = JdbcColumn.Kind.FLOAT32;
            case "DATE" -> kind = JdbcColumn.Kind.TIMESTAMP; // scale 0: whole seconds
            case "TIMESTAMP" -> {
                kind = JdbcColumn.Kind.TIMESTAMP;
                s = scale == null ? 6 : scale;
            }
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMP WITH LOCAL TIME ZONE" -> {
                kind = JdbcColumn.Kind.TIMESTAMP_TZ;
                s = scale == null ? 6 : scale;
            }
            case "BOOLEAN" -> kind = JdbcColumn.Kind.BOOLEAN;
            case "RAW", "LONG RAW", "BLOB" -> kind = JdbcColumn.Kind.BINARY;
            case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR" -> {
                kind = JdbcColumn.Kind.TEXT;
                // sized in characters: checked in characters, and in bytes (DATA_LENGTH); sized in
                // bytes: in bytes only
                maxLength = byteSized || chars == null ? 0 : chars;
                p = bytes == null ? 0 : bytes;
            }
            default -> kind = JdbcColumn.Kind.TEXT; // CLOB, LONG, JSON, intervals, ... take text
        }
        return new WritePlan.TargetColumn(name, base, kind, p, s, maxLength);
    }

    /** The significant decimal digits a FLOAT of {@code bits} binary digits keeps: ceil(bits × 0.30103). */
    static int floatDigits(int bits) {
        return (int) Math.ceil(bits * 0.30103);
    }

    // ------------------------------------------------------------------ messages

    @Override
    protected String explain(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int code = e.getErrorCode();
        String hint;
        if ("08001".equals(e.getSQLState()) && message.contains("needs its driver")) {
            return message;
        } else if (code == 1017) {
            hint = "Check the user name and password.";
        } else if (code == 28000) {
            hint = "The account is locked: ask the database's administrator to unlock it.";
        } else if (code == 1045) {
            hint = "The login needs the CREATE SESSION privilege.";
        } else if (code == 12514 || message.contains("ORA-12514")) {
            hint = "The listener does not know that service: check service_name (a service, not a SID).";
        } else if (code == 12541 || code == 17002 || message.contains("ORA-12541")) {
            hint = "Check the host and port, that the listener runs and that no firewall blocks the port.";
        } else if (message.contains("PKIX") || message.contains("certificate") || message.contains("SSL")) {
            hint = "With encryption tls, the server's certificate must be trusted by this server's Java trust store "
                + "and name the host.";
        } else {
            return message;
        }
        return message + " " + hint;
    }

    /** For tests: whether the session settings this connector applies hold on {@code c}. */
    static Map<String, String> session(Connection c) throws SQLException {
        Map<String, String> out = new HashMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT SESSIONTIMEZONE, "
            + "(SELECT VALUE FROM NLS_SESSION_PARAMETERS WHERE PARAMETER = 'NLS_DATE_FORMAT') FROM DUAL")) {
            if (rs.next()) {
                out.put("time_zone", rs.getString(1));
                out.put("date_format", rs.getString(2));
            }
        }
        return out;
    }
}
