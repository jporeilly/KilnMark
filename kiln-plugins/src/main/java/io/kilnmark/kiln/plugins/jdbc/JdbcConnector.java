package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.UrlHosts;

/**
 * What every JDBC database shares: settings {@code url}, {@code username}, {@code schemas}; the
 * catalogue from JDBC metadata; reading through {@link JdbcTableInput}. A database adds its URL
 * prefix and the SQL that reads a table, with or without a row limit.
 */
public abstract class JdbcConnector implements Connector {

    /** A password written into a URL: {@code ;password=} (SQL Server) or {@code ?password=} / {@code &password=}. */
    private static final Pattern PASSWORD_IN_URL = Pattern.compile("[;?&]\\s*password\\s*=", Pattern.CASE_INSENSITIVE);

    /**
     * A property in a URL: {@code ?name=value&...} or {@code ;name=value;...}. Names may hold
     * spaces (SQL Server's {@code network address}).
     */
    private static final Pattern URL_PROPERTY = Pattern.compile("[;?&]([^=;?&]+)=([^;&]*)");

    /**
     * URL properties that make a driver act as the Kilnmark server itself (its Windows, Kerberos or
     * cloud identity) or read or write its files, or load classes: a source must not use them. Also
     * refused: any property whose name ends in File, Path, Factory, Class or ClassName. Names ignore case.
     */
    private static final Set<String> DENIED_PROPERTIES = Set.of(
        // SQL Server
        "integratedsecurity", "authenticationscheme", "truststore", "keystorelocation", "keystoresecret",
        "servercertificate", "keystoreauthentication", "msiclientid",
        // PostgreSQL
        "sslkey", "sslcert", "sslrootcert", "sslfactory", "sslfactoryarg", "sslpasswordcallback",
        "sslhostnameverifier", "socketfactory", "socketfactoryarg", "loggerfile", "jaaslogin", "jaasapplicationname",
        "gsslib", "kerberosservername", "usespnego", "gssusedefaultcreds", "sspiservicename",
        // PostgreSQL's pg_service.conf on this server
        "service");

    /**
     * URL properties that name another host, port or database than the URL's own, which the
     * allowed-hosts check could miss: refused, so the address is always the URL's.
     */
    private static final Set<String> ADDRESS_PROPERTIES = Set.of("pghost", "pgport", "pgdbname", "server", "address",
        "addr", "network address");

    /**
     * Timeouts the server sets for checks, which a URL must not loosen (pgjdbc lets URL values win
     * over the server's).
     */
    private static final Set<String> TIMEOUT_PROPERTIES = Set.of("logintimeout", "connecttimeout", "sockettimeout",
        "cancelsignaltimeout");

    /** The only SQL Server authentication values allowed: a user name and password of the source's own. */
    private static final Set<String> AUTHENTICATION_ALLOWED = Set.of("sqlpassword", "notspecified");

    /** A name as drivers compare them: decoded, lower case, single spaces. */
    static String normalName(String name) {
        String n = name;
        try {
            n = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // not valid percent-encoding: compare it as written
        }
        return n.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** A value as SQL Server reads it: without surrounding braces or quotes. */
    static String bareValue(String value) {
        String v = value == null ? "" : value.strip();
        while (v.length() >= 2 && (v.startsWith("{") && v.endsWith("}") || v.startsWith("\"") && v.endsWith("\"")
            || v.startsWith("'") && v.endsWith("'"))) {
            v = v.substring(1, v.length() - 1).strip();
        }
        return v;
    }

    /**
     * The properties the database driver itself reads from {@code url}, by lower-case name, from
     * its own parser ({@link java.sql.Driver#getPropertyInfo}), so what is checked is what the driver will
     * use. Empty when no driver accepts the URL.
     */
    static Map<String, String> driverProperties(String url) {
        Map<String, String> out = new HashMap<>();
        if (url == null) {
            return out;
        }
        try {
            java.sql.Driver d = DriverManager.getDriver(url);
            for (DriverPropertyInfo i : d.getPropertyInfo(url, new Properties())) {
                if (i.name != null && i.value != null) {
                    out.put(i.name.toLowerCase(Locale.ROOT), i.value);
                }
            }
        } catch (SQLException | RuntimeException e) {
            // no driver for it, or it cannot parse the URL: validation and connecting say so
        }
        return out;
    }

    /**
     * The properties written into a JDBC URL, by lower-case name. Values are as written.
     */
    static Map<String, String> urlProperties(String url) {
        Map<String, String> out = new HashMap<>();
        if (url == null) {
            return out;
        }
        Matcher m = URL_PROPERTY.matcher(url);
        while (m.find()) {
            out.put(normalName(m.group(1)), m.group(2).trim());
        }
        return out;
    }

    /** Why a URL property is refused, or null when it is allowed. Names and values compare as drivers read them. */
    static String deniedProperty(String name, String value) {
        String n = normalName(name);
        String v = bareValue(value).toLowerCase(Locale.ROOT);
        if (ADDRESS_PROPERTIES.contains(n)) {
            return "url: the property " + name + " names another host, port or database than the URL's own. Write "
                + "the address in the URL itself, for example " + "jdbc:postgresql://db.example.com:5432/sales";
        }
        if (TIMEOUT_PROPERTIES.contains(n)) {
            return "url: the property " + name + " would change the time limits Kilnmark sets for connecting and "
                + "reading. Take it out of the URL";
        }
        boolean denied = DENIED_PROPERTIES.contains(n) || n.endsWith("file") || n.endsWith("path")
            || n.endsWith("factory") || n.endsWith("class") || n.endsWith("classname")
            || n.equals("authentication") && !AUTHENTICATION_ALLOWED.contains(v);
        return denied ? "url: the property " + name + " makes the database driver use this server's own identity, "
            + "files or code, which a source may not do. Take it out of the URL, and give the source its own user "
            + "name and password" : null;
    }

    /**
     * Problems with what the driver itself reads from the URL, so encodings and braces the
     * driver undoes cannot hide a refused setting: SQL Server's authentication and
     * integratedSecurity.
     */
    static String deniedByDriver(Map<String, String> driver) {
        String auth = bareValue(driver.get("authentication")).toLowerCase(Locale.ROOT);
        if (!auth.isEmpty() && !AUTHENTICATION_ALLOWED.contains(auth)) {
            return deniedProperty("authentication", auth);
        }
        if ("true".equalsIgnoreCase(bareValue(driver.get("integratedsecurity")))) {
            return deniedProperty("integratedSecurity", "true");
        }
        return null;
    }

    /**
     * Driver properties that bound how long connecting, logging in and each network read may take,
     * so a silent server cannot hold a check for ever. None by default; each database sets its own.
     */
    protected Properties timeouts(Duration timeout) {
        return new Properties();
    }

    /** Whole seconds for a driver timeout: a little under {@code timeout}, at least one. */
    protected static int seconds(Duration timeout) {
        return (int) Math.max(1, timeout.toSeconds() - 1);
    }

    /** The JDBC URL prefix this database's driver accepts, for example {@code jdbc:postgresql://}. */
    protected abstract String urlPrefix();

    /** An example URL for hints and error messages. */
    protected abstract String exampleUrl();

    /** SQL reading every column of {@code schema.table}, at most {@code rowLimit} rows when above 0. */
    protected abstract String selectSql(String schema, String table, long rowLimit);

    /**
     * SQL reading {@code asset}, at most {@code rowLimit} rows when above 0. By default
     * {@link #selectSql(String, String, long)} on the schema and table hints; override to choose
     * how particular columns are read.
     */
    protected String selectSql(AssetInfo asset, long rowLimit) {
        return selectSql(asset.hints().get("schema"), asset.hints().get("table"), rowLimit);
    }

    /**
     * The tables and views {@code filter} keeps (its schemas, every user schema when it names
     * none, and its patterns). By default standard JDBC metadata; override to add what the driver
     * does not report, such as comments.
     */
    protected List<JdbcCatalog.Table> readCatalog(Connection connection, AssetFilter filter) throws SQLException {
        return JdbcCatalog.read(connection, filter, dialect());
    }

    /** The URL for a host, port (null for the usual one) and database; null if this database has no such form. */
    protected String addressUrl(String host, Integer port, String database) {
        return null;
    }

    private JdbcCatalog.Dialect dialect() {
        return new JdbcCatalog.Dialect() {
            @Override
            public boolean isSystemSchema(String schema) {
                return JdbcConnector.this.isSystemSchema(schema);
            }

            @Override
            public String schemaPattern(DatabaseMetaData md, String schema) throws SQLException {
                return JdbcConnector.this.schemaPattern(md, schema);
            }
        };
    }

    /**
     * True for a schema that belongs to the database itself, which a scan of every schema skips.
     * By default only {@code information_schema}; each database lists its own.
     */
    protected boolean isSystemSchema(String schema) {
        return schema.equalsIgnoreCase("information_schema");
    }

    /**
     * {@code schema} as a metadata search pattern matching only itself. By default its wildcards
     * are escaped with the driver's search-string escape; override where the driver differs.
     */
    protected String schemaPattern(DatabaseMetaData md, String schema) throws SQLException {
        return JdbcCatalog.escapeWithDriver(md, schema);
    }

    /** Extra settings for {@link JdbcTableInput} when reading from {@code source}; none by default. */
    protected Map<String, String> readSettings(SourceConfig source) {
        return Map.of();
    }

    /**
     * What a person should read when connecting or reading the catalogue fails: by default the
     * driver's message; override to add what to do about failures this database is known for.
     */
    protected String explain(SQLException e) {
        return e.getMessage();
    }

    /** Quotes an identifier the standard way; override for databases that differ. */
    protected String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("url", "JDBC URL", true, "For example " + exampleUrl(), false),
            new Setting("username", "User name", false, "A read-only account is enough", false),
            new Setting("schemas", "Schemas", false, "Leave empty to scan every schema, or choose them from a list", true));
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String url = source.setting("url");
        if (url == null || !url.startsWith(urlPrefix())) {
            problems.add("url: a " + displayName() + " JDBC URL, for example " + exampleUrl());
        } else if (PASSWORD_IN_URL.matcher(url).find()) {
            problems.add("url: take the password out of the URL and enter it as the source's password instead; "
                + "the URL is stored and shown as plain text, the password is encrypted");
        } else {
            String denied = null;
            for (var e : urlProperties(url).entrySet()) {
                denied = deniedProperty(e.getKey(), e.getValue());
                if (denied != null) {
                    break;
                }
            }
            if (denied == null) {
                denied = deniedByDriver(driverProperties(url));
            }
            if (denied != null) {
                problems.add(denied);
            }
        }
        return problems;
    }

    @Override
    public ContainerKind containerKind() {
        return new ContainerKind("schemas", "Schemas", "tables and views", ".");
    }

    @Override
    public List<ContainerInfo> containers(SourceConfig source) throws ConnectorException {
        return containers(source, null);
    }

    @Override
    public List<ContainerInfo> containers(SourceConfig source, Duration timeout) throws ConnectorException {
        checkValid(source);
        AssetFilter patterns = filter(source).allContainers();
        try (Connection c = connect(source, timeout)) {
            return JdbcCatalog.counts(c, patterns, dialect()).entrySet().stream()
                .map(e -> new ContainerInfo(e.getKey(), e.getValue())).toList();
        } catch (SQLException e) {
            throw new ConnectorException("Cannot list the schemas of " + source.name() + ": " + explain(e), e);
        }
    }

    @Override
    public void test(SourceConfig source) throws ConnectorException {
        test(source, null);
    }

    @Override
    public void test(SourceConfig source, Duration timeout) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source, timeout)) {
            if (!c.isValid(5)) {
                throw new ConnectorException("Connected to " + source.name() + ", but the database did not answer "
                    + "a check within 5 seconds");
            }
        } catch (SQLException e) {
            throw new ConnectorException("Cannot connect to " + source.name() + ": " + explain(e), e);
        }
    }

    /** The URL and user name decide where the password goes; schemas and patterns do not. */
    @Override
    public Set<String> secretScope() {
        return Set.of("url", "username");
    }

    @Override
    public Map<String, String> addressSettings(String host, Integer port, String database) {
        String url = addressUrl(host, port, database);
        return url == null ? Map.of() : Map.of("url", url);
    }

    @Override
    public Set<String> addressKeys() {
        return Set.of("url");
    }

    @Override
    public String category() {
        return "databases";
    }

    @Override
    public String family() {
        return "relational";
    }

    @Override
    public String icon() {
        return "database";
    }

    @Override
    public String example(String settingKey) {
        return switch (settingKey) {
            case "url" -> exampleUrl();
            case "username" -> "kilnmark_reader";
            case "schemas" -> "sales, crm";
            default -> null;
        };
    }

    /** The tables and views in one schema, from one metadata call; no columns are read. */
    @Override
    public List<AssetInfo> assets(SourceConfig source, String container) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (String[] t : JdbcCatalog.tables(c, container, dialect())) {
                boolean table = t[1].equals("table") || t[1].equals("partitioned table");
                out.add(new AssetInfo(container + "." + t[0], t[1], container, t[0], null, List.of(), List.of(),
                    !table, true, Map.of("schema", container, "table", t[0])));
            }
            return out;
        } catch (SQLException e) {
            throw new ConnectorException("Cannot list the tables of " + container + " in " + source.name() + ": "
                + explain(e), e);
        }
    }

    /** One table or view with its columns: the catalogue of its schema only, kept to that table. */
    @Override
    public AssetInfo asset(SourceConfig source, String path) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source)) {
            for (int dot = path.indexOf('.'); dot > 0; dot = path.indexOf('.', dot + 1)) {
                String schema = path.substring(0, dot);
                String table = path.substring(dot + 1);
                // only that table's keys and comments are read; a name a pattern cannot hold reads its schema
                String only = table.matches(".*[,;.].*") ? null : table.replaceAll("([\\\\*?\\[\\]{}])", "\\\\$1");
                for (JdbcCatalog.Table t : readCatalog(c, AssetFilter.of(List.of(schema), only, null, "."))) {
                    if (t.path().equals(path)) {
                        return info(t);
                    }
                }
            }
        } catch (SQLException e) {
            throw new ConnectorException("Cannot read " + path + " in " + source.name() + ": " + explain(e), e);
        }
        throw new ConnectorException("There is no table or view " + path + " in " + source.name());
    }

    private static AssetInfo info(JdbcCatalog.Table t) {
        List<AssetInfo.FieldInfo> fields = t.columns().stream().map(col -> new AssetInfo.FieldInfo(col.name(),
            col.position(), col.sourceType(), col.kind().name().toLowerCase(Locale.ROOT), col.nullable(),
            col.comment())).toList();
        boolean table = t.type().equals("table") || t.type().equals("partitioned table");
        return new AssetInfo(t.path(), t.type(), t.schema(), t.name(), t.comment(), fields, t.primaryKey(),
            !table, true, Map.of("schema", t.schema(), "table", t.name()));
    }

    /**
     * Every host the driver would reach: the URL's, and those its own parser reads (PostgreSQL's
     * PGHOST list; SQL Server's serverName and failoverPartner, without an instance name).
     */
    @Override
    public List<String> hosts(SourceConfig source) {
        String url = source.setting("url");
        List<String> hosts = new ArrayList<>(UrlHosts.hosts(url));
        // the driver's own reading, and the URL's as written in case the driver cannot be asked
        List<Map<String, String>> readings = List.of(driverProperties(url), urlProperties(url));
        for (Map<String, String> props : readings) {
            for (String key : List.of("pghost", "servername", "failoverpartner", "server", "address", "addr",
                "network address")) {
                addHosts(hosts, bareValue(props.get(key)));
            }
        }
        return hosts;
    }

    /** Adds each host of a comma-separated list, without a SQL Server instance name. */
    private static void addHosts(List<String> hosts, String v) {
        for (String h : v.split(",")) {
            String host = h.strip();
            int cut = host.indexOf('\\');
            host = cut < 0 ? host : host.substring(0, cut);
            if (!host.isEmpty() && !hosts.contains(host)) {
                hosts.add(host);
            }
        }
    }

    private void checkValid(SourceConfig source) throws ConnectorException {
        List<String> problems = validate(source);
        if (!problems.isEmpty()) {
            throw new ConnectorException("Check the settings of " + source.name() + ": " + String.join("; ", problems));
        }
    }

    private Connection connect(SourceConfig source) throws SQLException {
        return connect(source, null);
    }

    /** Connects with the source's user name and secret and, for checks, the driver's own timeouts. */
    private Connection connect(SourceConfig source, Duration timeout) throws SQLException {
        Properties p = timeout == null ? new Properties() : timeouts(timeout);
        if (source.setting("username") != null) {
            p.setProperty("user", source.setting("username"));
        }
        if (source.secret() != null) {
            p.setProperty("password", source.secret());
        }
        return DriverManager.getConnection(source.setting("url"), p);
    }

    private AssetFilter filter(SourceConfig source) throws ConnectorException {
        try {
            return AssetFilter.of(this, source);
        } catch (IllegalArgumentException e) {
            throw new ConnectorException("Check the settings of " + source.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<AssetInfo> catalog(SourceConfig source) throws ConnectorException {
        checkValid(source); // also for sources saved before a rule existed
        AssetFilter filter = filter(source);
        try (Connection c = connect(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (JdbcCatalog.Table t : readCatalog(c, filter)) {
                out.add(info(t));
            }
            return out;
        } catch (SQLException e) {
            throw new ConnectorException("Cannot read the catalogue of " + source.name() + ": " + explain(e), e);
        }
    }

    @Override
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        Map<String, String> s = new HashMap<>();
        s.put("url", source.setting("url"));
        if (source.setting("username") != null) {
            s.put("user", source.setting("username"));
        }
        if (source.secret() != null) {
            s.put("password", source.secret());
        }
        s.put("query", selectSql(asset, read.rowLimit()));
        s.put("label", asset.path());
        s.put("asset-urn", read.assetUrn());
        s.put("row-limit", Long.toString(read.rowLimit()));
        s.put("batch-size", Integer.toString(read.batchSize()));
        s.putAll(readSettings(source));
        return new StepSpec("read", new JdbcTableInput(), s);
    }
}
