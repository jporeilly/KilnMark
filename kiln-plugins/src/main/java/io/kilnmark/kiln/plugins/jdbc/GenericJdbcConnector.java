package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.ResultSetMetaData;
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

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.JdbcDrivers;
import io.kilnmark.kiln.connect.RegisteredDriver;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.connect.UrlHosts;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * Any database with a JDBC driver Kilnmark has no dedicated connector for (kind {@code jdbc}): Db2,
 * Teradata, Snowflake, Sybase, Informix, an H2 server and the like, through a driver an admin registered
 * (docs/design/drivers.md, "Registered drivers"). The driver is used only through the standard
 * JDBC interfaces, loaded on its own from the drivers folder while its jar is the one registered,
 * never through {@link java.sql.DriverManager}.
 *
 * <p>Settings: {@code driver} (a registered driver, by name), {@code url} (which must start as the
 * driver's URLs do, and which the driver must accept), {@code username}, the password as the
 * source's secret, {@code properties} (further driver properties, {@code name=value; ...}),
 * {@code schemas} and {@code read_only} (on by default: connections are marked read-only, and
 * pipelines cannot write). Only admins may set up such a source or change these settings
 * ({@link #adminOnly()}): the URL and properties go to code Kilnmark has not vetted. A URL that
 * does not name a network host as {@code //host}, or that names a database the driver would open
 * inside the server (SQLite, DuckDB, H2 or HSQLDB files and memory, embedded Derby and the like), is
 * refused for admins too ({@link #insideTheServer}).
 *
 * <p>The catalogue comes from {@link java.sql.DatabaseMetaData} ({@link GenericCatalog}), types are
 * mapped by {@link GenericTypes}, and rows are read with {@code SELECT *} and the driver's own row
 * limit. Writing ({@link GenericWriteSql}) is {@code append} and {@code create} only; {@code replace}
 * and {@code upsert} are refused, as no portable SQL makes them safe. Data quality rules are
 * checked on the rows read rather than as SQL in the database.
 */
public class GenericJdbcConnector extends JdbcConnector {

    static final String KIND = "jdbc";

    /** Settings the reader node receives besides the URL, user and password. */
    static final String READ_DRIVER = "jdbc-driver";
    static final String READ_CONTAINER = "jdbc-container";
    static final String READ_TABLE = "jdbc-table";

    /** What {@link #hosts} gives for a URL whose host Kilnmark cannot read, so an allow-list refuses it. */
    static final String UNREADABLE_HOST = "an address Kilnmark cannot read from this URL";

    /** A driver property's name: letters, digits, {@code . _ -}. */
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,99}");

    private final JdbcDrivers drivers;

    public GenericJdbcConnector() {
        this(JdbcDrivers.NONE);
    }

    GenericJdbcConnector(JdbcDrivers drivers) {
        this.drivers = drivers;
    }

    /** With the drivers the customer installed and registered. */
    @Override
    public Connector withContext(ConnectorContext context) {
        return new GenericJdbcConnector(context.drivers());
    }

    @Override
    protected JdbcDrivers drivers() {
        return drivers;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String displayName() {
        return "Generic JDBC";
    }

    @Override
    public String docsAnchor() {
        return "jdbc";
    }

    /** Only admins set it up: the URL and properties go to a driver whose features Kilnmark cannot vet. */
    @Override
    public boolean adminOnly() {
        return true;
    }

    /** Kilnmark does not know this database's SQL: rules are checked on the rows read. */
    @Override
    public boolean runsRulesInDatabase() {
        return false;
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:db2://db.example.com:50000/SALES";
    }

    // ------------------------------------------------------------------ settings

    @Override
    public List<Setting> settings() {
        List<String> names = drivers.registered().stream().map(RegisteredDriver::name).toList();
        Setting driver = names.isEmpty()
            ? new Setting("driver", "JDBC driver", true, "No JDBC driver is registered on this server yet: an admin "
                + "registers one under Settings, JDBC drivers", false)
            : new Setting("driver", "JDBC driver", true, "A driver an admin registered under Settings, JDBC drivers",
                false, SettingSpec.Kind.CHOICE, names, null);
        return List.of(
            driver,
            Setting.text("url", "JDBC URL", true, "The address as the driver takes it, starting as its URLs do, for "
                + "example " + exampleUrl() + ". No password in it: enter that as the source's password"),
            Setting.text("username", "User name", false, "A read-only account is enough for scanning; pipelines that "
                + "write to this source need a login with write rights"),
            Setting.text("properties", "Driver properties", false, "Further settings for the driver, as name=value "
                + "separated by semicolons, for example sslConnection=true; currentSchema=SALES. Only the properties "
                + "the driver was registered to take, in the URL or here; never a password, key or token"),
            new Setting("schemas", "Schemas", false, "Leave empty to scan every schema, or choose them from a list "
                + "(catalogues, for databases that have no schemas)", true),
            Setting.bool("read_only", "Read only", true, "On (the default), Kilnmark asks the driver for read-only "
                + "connections and pipelines cannot write here. Turn it off, and Allow writing on, to let pipelines "
                + "append to tables or create them"));
    }

    @Override
    public String example(String settingKey) {
        return switch (settingKey) {
            case "driver" -> "db2";
            case "url" -> exampleUrl();
            case "username" -> "KILN_READER";
            case "properties" -> "sslConnection=true; currentSchema=SALES";
            case "schemas" -> "SALES, HR";
            case "read_only" -> "true";
            default -> null;
        };
    }

    /** Whether the source keeps to reading ({@code read_only}, true unless set to false). */
    static boolean readOnly(SourceConfig source) {
        String v = source.setting("read_only");
        return v == null || !v.equalsIgnoreCase("false");
    }

    /**
     * The driver, the URL (with the driver's prefix, readable settings each on the driver's
     * allow-list, no secret, and accepted by the driver itself), the properties (on the allow-list
     * too), and {@code read_only}.
     */
    @Override
    protected List<String> validateAddress(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String name = source.setting("driver");
        RegisteredDriver driver = null;
        if (name == null) {
            problems.add("driver: choose a JDBC driver an admin registered under Settings, JDBC drivers");
        } else {
            driver = drivers.registration(name).orElse(null);
            if (driver == null) {
                problems.add("driver: " + JdbcDrivers.notRegistered(name));
            }
        }
        Properties extra = null;
        try {
            extra = properties(source.setting("properties"));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        String url = source.setting("url");
        if (url == null || !url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            problems.add("url: a JDBC URL, starting jdbc:, for example " + (driver == null ? exampleUrl()
                : driver.urlPrefix() + "..."));
        } else if (url.length() > 2000) {
            problems.add("url: at most 2,000 characters");
        } else if (driver != null && !driver.takes(url)) {
            problems.add("url: the driver " + driver.name() + " takes URLs starting " + driver.urlPrefix()
                + ", and this one does not");
        } else {
            String refused = refusal(driver, url, extra);
            if (refused != null) {
                problems.add(refused);
            } else if (driver != null) {
                try {
                    if (!drivers.driver(driver).acceptsURL(url)) {
                        problems.add("url: the driver " + driver.name() + " does not accept this URL. Check it against "
                            + "the driver's documentation");
                    }
                } catch (SQLException e) {
                    problems.add("driver: " + e.getMessage());
                } catch (RuntimeException | LinkageError e) {
                    problems.add("url: the driver " + driver.name() + " could not read this URL ("
                        + e.getClass().getSimpleName() + ")");
                }
            }
        }
        String readOnly = source.setting("read_only");
        if (readOnly != null && !readOnly.equalsIgnoreCase("true") && !readOnly.equalsIgnoreCase("false")) {
            problems.add("read_only: true or false, not '" + readOnly + "'");
        }
        return problems;
    }

    /** A user name and password before a host: {@code //user:secret@host}. */
    private static final Pattern CREDENTIALS_IN_URL = Pattern.compile("//[^/@;?]*:[^/@;?]*@");

    /**
     * Why a URL and properties may not go to driver {@code r}, starting with the setting at fault, or
     * null when they may: every setting in the URL ({@link JdbcUrlSettings}) and every property must be
     * on the driver's allow-list, none may carry a secret or a user name (they would be stored and
     * shown as plain text), and a URL Kilnmark cannot read is refused. With no registration, only the
     * secrets are checked. Never quotes the URL.
     */
    static String refusal(RegisteredDriver r, String url, Properties extra) {
        if (url != null) {
            if (CREDENTIALS_IN_URL.matcher(url).find()) {
                return "url: take the user name and password out of the URL; give them in their own fields, where "
                    + "the password is encrypted";
            }
            JdbcUrlSettings.Parsed parsed = JdbcUrlSettings.parse(url);
            if (parsed.problem() != null) {
                return "url: " + parsed.problem();
            }
            // What the URL opens first: it is refused whatever the driver allows, and says why.
            String inside = insideTheServer(url);
            if (inside != null) {
                return "url: " + inside;
            }
            for (JdbcUrlSettings.Pair pair : parsed.pairs()) {
                String why = refusedName(r, pair.key(), "url", "in the URL");
                if (why != null) {
                    return why;
                }
            }
        }
        if (extra != null) {
            if (url != null && url.toLowerCase(Locale.ROOT).contains(":h2:")
                && extra.stringPropertyNames().stream().anyMatch(k -> k.equalsIgnoreCase("init"))) {
                return "properties: " + H2_INIT;
            }
            if (url != null && firebird(url.toLowerCase(Locale.ROOT))
                && extra.stringPropertyNames().stream().anyMatch(k -> k.equalsIgnoreCase("type"))) {
                return "properties: " + FIREBIRD_TYPE;
            }
            for (String key : extra.stringPropertyNames()) {
                String why = refusedName(r, key, "properties", "as a property");
                if (why != null) {
                    return why;
                }
            }
        }
        return null;
    }

    /** Said of every database a driver would open inside the server. */
    private static final String INSIDE = " Such a database runs inside the Kilnmark server, so its SQL could read or "
        + "write the server's own files, or run code there, with no login of anyone's own (ADR 0027). Use a database "
        + "server reached over the network.";

    /** Drill's {@code zk=local}, which runs a Drillbit in the process, and not {@code zk=localhost}. */
    private static final Pattern DRILL_LOCAL = Pattern.compile("zk=local(?![a-z0-9._-])");

    /** Firebird's {@code type}, in the URL or as a property: EMBEDDED and NATIVE load its library into the server. */
    private static final String FIREBIRD_TYPE = "a Firebird type setting can open the database embedded or load "
        + "Firebird's native library inside the server; leave it out to use the network protocol." + INSIDE;

    private static boolean firebird(String lowerUrl) {
        return lowerUrl.contains(":firebirdsql:") || lowerUrl.contains(":firebird:");
    }

    /** H2's {@code INIT}, in the URL or as a property. */
    private static final String H2_INIT = "H2's INIT setting runs SQL, which can read the server's files, as each "
        + "connection opens." + INSIDE;

    /**
     * Why a URL would have the driver open a database inside the server's own process, or run SQL as
     * it connects, or null when it reaches a database server. Refused by name, looked for anywhere in
     * the URL so a wrapping driver ({@code jdbc:p6spy:h2:...}) is caught too: SQLite, DuckDB, UCanAccess,
     * the CSV driver ({@code relique:csv:}), Calcite, embedded, local and native Firebird, Drill with
     * {@code zk=local}, Ignite started from a configuration ({@code ignite:cfg:}) and Hive with no host
     * (embedded); H2 unless {@code tcp://} or {@code ssl://} and without {@code INIT}; HSQLDB unless
     * {@code hsql://}, {@code hsqls://}, {@code http://} or {@code https://}; Derby unless
     * {@code //host}. Then, as an allow-list by shape, every other URL that does not name a network
     * host ({@link #networkHosts}): a driver Kilnmark does not know could open anything else in the
     * process, so only a URL of the form {@code jdbc:...://host[:port][,host...]} goes to it, whether
     * or not an allow-list of hosts is set. Never quotes the URL.
     */
    static String insideTheServer(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(":sqlite:")) {
            return "SQLite databases are files the driver opens on the server." + INSIDE;
        }
        if (u.contains(":duckdb:")) {
            return "DuckDB databases are files or memory the driver opens on the server." + INSIDE;
        }
        if (u.contains(":firebirdsql:embedded:") || u.contains(":firebird:embedded:")) {
            return "an embedded Firebird database is opened by the driver on the server." + INSIDE;
        }
        if (u.contains(":firebirdsql:local:") || u.contains(":firebird:local:")
            || u.contains(":firebirdsql:native:") || u.contains(":firebird:native:")) {
            return "a local or native Firebird URL loads Firebird's own library into the server." + INSIDE;
        }
        int oracle = u.indexOf(":oracle:");
        if (oracle >= 0 && !u.startsWith("thin:", oracle + 8)) {
            return "an Oracle URL other than jdbc:oracle:thin: loads Oracle's native client library or runs inside "
                + "a database; use jdbc:oracle:thin:." + INSIDE;
        }
        if (firebird(u) && (u.contains("?type=") || u.contains("&type=") || u.contains(";type="))) {
            return FIREBIRD_TYPE;
        }
        if (u.contains(":snowflake:") || u.contains(":vertica:") || u.contains(":exa:") || u.contains(":clickhouse:")
            || u.contains(":ch:") || u.contains(":netezza:")) {
            return "this database's driver reads and writes files on the Kilnmark server for ordinary SQL (Snowflake "
                + "PUT and GET, Vertica COPY FROM LOCAL, Exasol IMPORT and EXPORT LOCAL, ClickHouse INFILE and "
                + "OUTFILE, Netezza external tables), so it is not used through Generic JDBC (ADR 0030).";
        }
        if (u.contains(":redshift:iam:")) {
            return "a Redshift IAM URL signs in with the Kilnmark server's own AWS identity; give the source a "
                + "database user and password instead." + INSIDE;
        }
        if (u.contains(":ucanaccess:")) {
            return "UCanAccess opens Access database files on the server." + INSIDE;
        }
        if (u.contains(":relique:csv:")) {
            return "the CSV driver reads files with SQL on the server." + INSIDE;
        }
        if (u.contains(":calcite:")) {
            return "Calcite runs its SQL and adapters on the server." + INSIDE;
        }
        if (u.contains(":drill:") && DRILL_LOCAL.matcher(u).find()) {
            return "a Drill URL with zk=local starts Drill on the server." + INSIDE;
        }
        if (u.contains(":ignite:cfg:")) {
            return "an Ignite cfg: URL starts an Ignite node on the server from a configuration file." + INSIDE;
        }
        int hive = u.indexOf(":hive2://");
        if (hive >= 0 && (hive + 9 == u.length() || "/;?#".indexOf(u.charAt(hive + 9)) >= 0)) {
            return "a Hive URL without a host runs Hive embedded on the server." + INSIDE;
        }
        int h2 = u.indexOf(":h2:");
        if (h2 >= 0) {
            String rest = u.substring(h2 + 4);
            if (rest.contains("init=")) {
                return H2_INIT;
            }
            if (!rest.startsWith("tcp://") && !rest.startsWith("ssl://")) {
                return "an H2 URL other than tcp:// or ssl:// opens a file or in-memory database "
                    + "on the server." + INSIDE;
            }
        }
        int hsql = u.indexOf(":hsqldb:");
        if (hsql >= 0) {
            String rest = u.substring(hsql + 8);
            if (!rest.startsWith("hsql://") && !rest.startsWith("hsqls://")
                && !rest.startsWith("http://") && !rest.startsWith("https://")) {
                return "an HSQLDB URL other than hsql:, hsqls:, http: or https: opens a file or in-memory database on "
                    + "the server." + INSIDE;
            }
        }
        int derby = u.indexOf(":derby:");
        if (derby >= 0 && !u.startsWith("//", derby + 7)) {
            return "a Derby URL without a //host opens an embedded database on the server." + INSIDE;
        }
        if (networkHosts(url) == null) {
            return "the URL does not name a network host as //host or //host:port, so the driver could open a "
                + "database on the server." + INSIDE;
        }
        return null;
    }

    /**
     * The start of a URL that names its hosts: {@code jdbc}, one or more {@code :subprotocol}s, then
     * {@code //} (after {@code @} too, as Oracle's thin URLs have it).
     */
    private static final Pattern NETWORK_URL = Pattern.compile("(?i)jdbc(:[a-z][a-z0-9+.-]*)+:@?//");

    /** The one common driver whose URLs name the host without {@code //}: {@code jdbc:sybase:Tds:host:port}. */
    private static final Pattern SYBASE_TDS = Pattern.compile("(?i)jdbc:sybase:tds:");

    /** A host name (labels of letters, digits, {@code -} and {@code _}) or a bracketed IPv6 address. */
    private static final Pattern NETWORK_HOST = Pattern.compile(
        "[a-z0-9]([a-z0-9_-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9_-]*[a-z0-9])?)*|\\[[0-9a-f:.]+(%[a-z0-9._-]+)?]",
        Pattern.CASE_INSENSITIVE);

    /** A port after a host. */
    private static final Pattern PORT = Pattern.compile(":[0-9]{1,5}");

    /**
     * The network hosts a URL names straight after its subprotocols ({@code jdbc:db2://host:50000/x},
     * {@code jdbc:sqlserver://a,b;...}, and jConnect's {@code jdbc:sybase:Tds:host:port}), or null
     * when it names none Kilnmark can read: no {@code //},
     * an empty authority ({@code jdbc:hive2:///}), or one that is not {@code host[:port]} entries
     * separated by commas ({@code //C:/data}, {@code //~/x}, {@code //host:}). A {@code user@} before
     * the hosts is skipped. Only the URL's own address counts here, not a URL inside its settings.
     */
    static List<String> networkHosts(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = NETWORK_URL.matcher(url);
        if (!m.lookingAt()) {
            m = SYBASE_TDS.matcher(url);
            if (!m.lookingAt()) {
                return null;
            }
        }
        int start = m.end();
        int end = start;
        while (end < url.length() && "/;?#\\ \t\r\n".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String authority = url.substring(start, end);
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        if (authority.isEmpty()) {
            return null;
        }
        List<String> hosts = new ArrayList<>();
        for (String entry : authority.split(",", -1)) {
            int split = entry.startsWith("[") ? entry.indexOf(']') + 1 : entry.indexOf(':');
            if (split <= 0) {
                split = entry.startsWith("[") ? 0 : entry.length();
            }
            String host = entry.substring(0, split);
            String port = entry.substring(split);
            if (!NETWORK_HOST.matcher(host).matches() || (!port.isEmpty() && !PORT.matcher(port).matches())) {
                return null;
            }
            hosts.add(host);
        }
        return hosts;
    }

    private static String refusedName(RegisteredDriver r, String key, String setting, String where) {
        String n = key.toLowerCase(Locale.ROOT);
        if (n.equals("user") || RegisteredDriver.isSecretName(n)) {
            return setting + ": " + key + " " + where + " would hold a user name or secret, which is stored and shown "
                + "as plain text. Give the user name and password in their own fields; the password is encrypted";
        }
        if (r != null && !r.allows(n)) {
            return setting + ": the driver " + r.name() + " is not registered to take the property " + key + ". An "
                + "admin adds it to the driver's allowed properties under Settings, JDBC drivers, once they know it "
                + "is safe";
        }
        return null;
    }

    /**
     * The {@code properties} setting as driver properties: {@code name=value} pairs separated by
     * semicolons or new lines. Which names are allowed is the driver's allow-list ({@link #refusal}).
     *
     * @throws IllegalArgumentException starting {@code properties:}, naming what is wrong
     */
    static Properties properties(String setting) {
        Properties p = new Properties();
        if (setting == null || setting.isBlank()) {
            return p;
        }
        for (String part : setting.split("[;\\n]")) {
            String pair = part.strip();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("properties: name=value pairs separated by semicolons, such as "
                    + "sslConnection=true");
            }
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            if (!PROPERTY_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("properties: a property's name is letters, digits, . _ and -, not '"
                    + name + "'");
            }
            if (value.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
                throw new IllegalArgumentException("properties: the value of " + name + " holds a control character");
            }
            if (p.stringPropertyNames().stream().anyMatch(k -> k.equalsIgnoreCase(name))) {
                throw new IllegalArgumentException("properties: " + name + " is given twice");
            }
            p.setProperty(name, value);
        }
        return p;
    }

    /** The extra properties go with every connection the source makes, never into the URL. */
    @Override
    protected Properties driverSettings(SourceConfig source) {
        try {
            return properties(source.setting("properties"));
        } catch (IllegalArgumentException e) {
            return new Properties(); // validation refuses the source first
        }
    }

    /** The driver, URL, user name and properties decide where the password goes. */
    @Override
    public Set<String> secretScope() {
        return Set.of("driver", "url", "username", "properties");
    }

    /**
     * Every host the driver could reach: the URL's, those its settings and properties name (failover
     * partners, server names, alternate servers, any value that is itself a URL), so the allowed-hosts
     * rule applies to all of them. When no host can be read from the URL, a placeholder no allow-list
     * holds.
     */
    @Override
    public List<String> hosts(SourceConfig source) {
        String url = source.setting("url");
        List<String> hosts = new ArrayList<>(UrlHosts.hosts(url));
        List<String> named = networkHosts(url);
        if (hosts.isEmpty() && named != null) {
            hosts.addAll(named); // jdbc:sybase:Tds:host:port, which has no //
        }
        JdbcUrlSettings.Parsed parsed = JdbcUrlSettings.parse(url);
        // a URL whose settings cannot be read could name hosts that cannot be seen, and one that names no
        // network host of its own is refused anyway: an allow-list refuses both
        boolean fromUrl = named != null && parsed.problem() == null;
        Map<String, String> settings = new java.util.LinkedHashMap<>();
        parsed.pairs().forEach(pair -> settings.put(pair.key() + "#" + settings.size(), pair.value()));
        driverSettings(source).forEach((k, v) -> settings.put(k + "#" + settings.size(), String.valueOf(v)));
        settings.forEach((key, value) -> {
            String k = key.substring(0, key.lastIndexOf('#'));
            List<String> found = new ArrayList<>(UrlHosts.hosts(value));
            if (found.isEmpty() && JdbcUrlSettings.namesHost(k)) {
                found = JdbcUrlSettings.hostsIn(value);
            }
            found.stream().filter(h -> !hosts.contains(h)).forEach(hosts::add);
        });
        if (!fromUrl) {
            hosts.add(0, UNREADABLE_HOST);
        }
        return hosts;
    }

    /** A URL is the driver's own business: no host, port and database form. */
    @Override
    public Map<String, String> addressSettings(String host, Integer port, String database) {
        return Map.of();
    }

    // ------------------------------------------------------------------ connecting

    /** The registered driver, as it is now. */
    RegisteredDriver registration(String name) throws SQLException {
        if (name == null) {
            throw new SQLException("Choose a JDBC driver for this source", "08001");
        }
        return drivers.registration(name).orElseThrow(() -> new SQLException(JdbcDrivers.notRegistered(name), "08001"));
    }

    @Override
    protected Connection open(SourceConfig source, Properties p) throws SQLException {
        return open(source.setting("driver"), jdbcUrl(source), p);
    }

    /** A connection through the registered driver {@code name}, used directly. */
    Connection open(String name, String url, Properties p) throws SQLException {
        RegisteredDriver r = registration(name);
        if (!r.takes(url)) {
            throw new SQLException("The driver " + r.name() + " takes URLs starting " + r.urlPrefix(), "08001");
        }
        Properties settings = new Properties();
        p.stringPropertyNames().stream().filter(k -> !k.equals("user") && !k.equals("password"))
            .forEach(k -> settings.setProperty(k, p.getProperty(k)));
        String refused = refusal(r, url, settings);
        if (refused != null) {
            throw new SQLException(refused, "08001");
        }
        Properties use = new Properties();
        use.putAll(p);
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(":mysql") || lower.contains(":mariadb:") || lower.contains(":singlestore:")) {
            // A MySQL or MariaDB server can ask the driver to send it any of the server's files (LOAD DATA
            // LOCAL), and some driver versions agree by default: never.
            for (String off : List.of("allowLoadLocalInfile", "allowLocalInfile", "allowUrlInLocalInfile",
                "autoDeserialize")) {
                use.setProperty(off, "false");
            }
        }
        Connection c = drivers.driver(r).connect(url, use);
        if (c == null) {
            throw new SQLException("The driver " + r.name() + " does not accept this source's URL", "08001");
        }
        return c;
    }

    /** Reading connections: marked read-only, and bounded by the check's time limit where the driver can be. */
    @Override
    protected Connection connect(SourceConfig source, Duration timeout) throws SQLException {
        Connection c = super.connect(source, timeout);
        try {
            if (timeout != null) {
                c.setNetworkTimeout(r -> Thread.ofVirtual().start(r), (int) Math.max(1000, timeout.toMillis() - 500));
            }
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            // a driver without network timeouts: the server abandons a check that runs over
        }
        try {
            c.setReadOnly(true);
        } catch (SQLException | RuntimeException e) {
            // advice only: a driver that cannot is read through SELECTs anyway
        }
        return c;
    }

    @Override
    protected String explain(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        if (state.equals("08001") && (message.contains("registered") || message.contains("drivers folder"))) {
            return message;
        }
        if (state.startsWith("28")) {
            return message + " Check the user name and password.";
        }
        if (state.startsWith("08")) {
            return message + " Check the URL, that the database runs and that no firewall blocks the way.";
        }
        return message;
    }

    // ------------------------------------------------------------------ the catalogue

    @Override
    public List<ContainerInfo> containers(SourceConfig source, Duration timeout) throws ConnectorException {
        checkValid(source);
        AssetFilter patterns = filter(source).allContainers();
        try (Connection c = connect(source, timeout)) {
            return GenericCatalog.counts(c, GenericCatalog.shape(c), patterns).entrySet().stream()
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
            for (String[] t : GenericCatalog.tables(c, GenericCatalog.shape(c), container)) {
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
        return GenericCatalog.read(connection, GenericCatalog.shape(connection), filter);
    }

    /** Declared foreign keys where tables sit in schemas; none where the driver reports only catalogues. */
    @Override
    protected List<JdbcCatalog.ForeignKey> readForeignKeys(Connection connection, List<JdbcCatalog.Table> tables)
        throws SQLException {
        if (GenericCatalog.shape(connection).level() != GenericCatalog.Level.SCHEMA) {
            return List.of();
        }
        return JdbcCatalog.foreignKeys(connection, tables);
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return GenericCatalog.isSystem(schema);
    }

    @Override
    public SqlDialect dialect() {
        return GenericWriteSql.PLACEHOLDER.dialect();
    }

    // ------------------------------------------------------------------ reading

    /**
     * Explore on a Generic JDBC source shows the columns and primary key the catalogue reads, and
     * no foreign keys, indexes or row estimate: drivers place schemas and catalogues their own
     * ways, and Kilnmark reads those only as far as a scan needs (ADR 0045).
     */
    @Override
    public io.kilnmark.kiln.connect.TableDetail explore(SourceConfig source, String path, Duration timeout)
        throws ConnectorException {
        AssetInfo a = asset(source, path);
        return new io.kilnmark.kiln.connect.TableDetail(a.path(), a.kind(), a.fields().stream()
            .map(f -> new io.kilnmark.kiln.connect.TableDetail.Column(f.name(), f.position(), f.sourceType(), null,
                null, f.nullable(), false, false, f.kind())).toList(),
            a.primaryKey(), List.of(), List.of(), List.of(), null);
    }

    /** Not offered on Generic JDBC sources: names are quoted as each driver says only once a read connects. */
    @Override
    public long countRows(SourceConfig source, String path, Duration timeout) throws ConnectorException {
        throw new ConnectorException("Counting rows exactly is not offered for Generic JDBC sources yet. "
            + "Scan the table to record its row count.");
    }

    /** Not used: the reader builds its query once connected, quoting as the driver says. */
    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return "SELECT * FROM " + quote(schema) + "." + quote(table);
    }

    /**
     * Reads {@code SELECT *} from the table, its name quoted as the driver says once connected, with
     * the driver's own row limit ({@link java.sql.Statement#setMaxRows}) for previews: no SQL row
     * limit works everywhere. Large tables are read in full, never sampled.
     */
    @Override
    public NodeSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        Map<String, String> s = new HashMap<>();
        s.put("url", jdbcUrl(source));
        if (source.setting("username") != null) {
            s.put("user", source.setting("username"));
        }
        if (source.secret() != null) {
            s.put("password", source.secret());
        }
        driverSettings(source).forEach((k, v) -> s.put(JdbcTableInput.DRIVER_PREFIX + k, String.valueOf(v)));
        String container = asset.hints().getOrDefault("schema", asset.container());
        String table = asset.hints().getOrDefault("table", asset.name());
        s.put(READ_DRIVER, source.setting("driver") == null ? "" : source.setting("driver"));
        s.put(READ_CONTAINER, container == null ? GenericCatalog.DEFAULT : container);
        s.put(READ_TABLE, table);
        s.put("query", selectSql(container, table, 0)); // replaced once connected (Reading.query)
        s.put("label", asset.path());
        s.put("asset-urn", read.assetUrn());
        s.put("row-limit", Long.toString(read.rowLimit()));
        if (read.rowLimit() > 0) {
            s.put(JdbcTableInput.MAX_ROWS, Long.toString(read.rowLimit()));
        }
        s.put("batch-size", Integer.toString(read.batchSize()));
        return new NodeSpec("read", tableInput(), s);
    }

    @Override
    protected JdbcTableInput tableInput() {
        return new JdbcTableInput(new Reading(this));
    }

    /** How the reader node connects (through the registered driver), names the table and maps columns. */
    private record Reading(GenericJdbcConnector connector) implements JdbcTableInput.Reading {

        @Override
        public Connection open(Map<String, String> settings) throws SQLException {
            return connector.open(settings.get(READ_DRIVER), settings.get("url"),
                JdbcTableInput.connectionProperties(settings));
        }

        @Override
        public JdbcTableInput.ColumnReader column(ResultSetMetaData md, int i, Map<String, String> settings)
            throws SQLException {
            return GenericTypes.reader(md, i);
        }

        @Override
        public boolean tolerant() {
            return true;
        }

        @Override
        public String query(Connection connection, String sql, Map<String, String> settings) throws SQLException {
            String table = settings.get(READ_TABLE);
            if (table == null) {
                return sql;
            }
            GenericCatalog.Shape shape = GenericCatalog.shape(connection);
            String container = settings.get(READ_CONTAINER);
            if (shape.quote().isEmpty() && !(GenericCatalog.plainName(table) && GenericCatalog.plainName(container))) {
                throw new SQLException("This database's driver offers no way to quote names, so only plain names "
                    + "(letters, digits and _) can be read", "42000");
            }
            return "SELECT * FROM " + shape.qualified(container, table);
        }
    }

    // ------------------------------------------------------------------ writing

    /** For capabilities and write settings: the SQL itself is read from the driver when a node writes. */
    @Override
    WriteSql writeSql() {
        return GenericWriteSql.PLACEHOLDER;
    }

    /** Rows per transaction and batch, and the length of created text columns; nothing that needs replace. */
    @Override
    List<SettingSpec> writeOptionsOfOwn() {
        return List.of(
            WriteSettings.number("commit_every", "Commit every N rows", 0,
                "0 (the default) writes in one transaction; otherwise commit after each batch reaching N rows. A "
                    + "failure then keeps the rows already committed"),
            WriteSettings.number("batch_size", "Rows per batch", 1000, "Rows sent to the database at a time"),
            WriteSettings.number("text_length", "Text column length", 0,
                "For create: text columns of this many characters; 0 (the default) for the longest text type the "
                    + "driver lists"));
    }

    /** Why replace is refused, for messages and documentation. */
    static final String NO_REPLACE = "mode: replace is not available for generic JDBC sources. Swapping a new table "
        + "in for an old one safely needs renaming, grants and transactional table definitions, which no SQL does the "
        + "same way on every database. Empty the table and use append, write to a new table with create, or use a "
        + "dedicated connector";

    /** Why upsert is refused. */
    static final String NO_UPSERT = "mode: upsert is not available for generic JDBC sources. Updating a row or "
        + "inserting it without a race needs MERGE or its like with the right locking, which differs from database to "
        + "database. Use append, or a dedicated connector";

    /** The checks that need no connection: read-only, the mode, the key, the format and the table's name. */
    private List<String> writeProblems(SourceConfig source, WriteOptions write) {
        List<String> problems = new ArrayList<>();
        if (readOnly(source)) {
            problems.add("read_only: " + source.name() + " is read-only. An admin turns Read only off (and Allow "
                + "writing on) to let pipelines append to it or create tables in it");
        }
        String mode = write.mode() == null ? "" : write.mode().strip().toLowerCase(Locale.ROOT);
        switch (mode) {
            case "append", "create" -> {
                // the two modes generic JDBC writes
            }
            case "replace" -> problems.add(NO_REPLACE);
            case "upsert" -> problems.add(NO_UPSERT);
            default -> problems.add("mode: append or create" + (mode.isEmpty() ? "" : ", not '" + write.mode() + "'"));
        }
        if (!write.key().isEmpty() && !mode.equals("upsert")) {
            problems.add("key: only upsert takes key columns, and generic JDBC sources do not upsert");
        }
        if (write.format() != null && !write.format().isBlank()) {
            problems.add("format: only files have a format; a table's columns decide its types");
        }
        try {
            TableName.parse(write.target());
        } catch (ConnectorException e) {
            problems.add(e.getMessage());
        }
        return problems;
    }

    /** Without connecting: the settings, read-only, and the mode (append and create only). */
    @Override
    public List<String> checkWrite(SourceConfig source, WriteOptions write) {
        List<String> problems = new ArrayList<>(validate(source));
        problems.addAll(writeProblems(source, write));
        return problems;
    }

    /**
     * A node appending to a table or creating one ({@link JdbcTableOutput}) with SQL read from the
     * driver ({@link GenericWriteSql}), to the table as the catalogue spells it.
     */
    @Override
    public io.kilnmark.kiln.Node writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        checkValid(source);
        List<String> problems = writeProblems(source, write);
        if (!problems.isEmpty()) {
            throw new ConnectorException(String.join("; ", problems));
        }
        GenericWriteSql sql;
        TableName target;
        try (Connection c = connect(source)) {
            GenericCatalog.Shape shape = GenericCatalog.shape(c);
            if (shape.level() == GenericCatalog.Level.NONE) {
                throw new ConnectorException("table: " + source.name() + "'s driver reports no schemas or catalogues, "
                    + "so Kilnmark cannot name a table to write to");
            }
            sql = GenericWriteSql.of(c, shape);
            target = resolve(c, shape, TableName.parse(write.target()));
        } catch (SQLException e) {
            throw new ConnectorException("Cannot write to " + source.name() + ": " + explain(e), e);
        }
        WriteOptions resolved = new WriteOptions(target.toString(), write.mode(), write.key(), write.format(),
            write.options(), write.assetUrn(), write.profiling());
        return writer(source, resolved, sql);
    }

    @Override
    public String writtenAssetPath(SourceConfig source, WriteOptions write) throws ConnectorException {
        checkValid(source);
        try (Connection c = connect(source)) {
            return resolve(c, GenericCatalog.shape(c), TableName.parse(write.target())).toString();
        } catch (SQLException e) {
            throw new ConnectorException("Cannot look up " + write.target() + " in " + source.name() + ": "
                + explain(e), e);
        }
    }

    /**
     * {@code t} as the catalogue spells it: a schema (or catalogue) of that exact name, or the only one
     * differing in case; the same for an existing table; a new table's name as written.
     */
    static TableName resolve(Connection c, GenericCatalog.Shape shape, TableName t) throws SQLException {
        List<String> containers = GenericCatalog.containers(c, shape);
        String container = GenericCatalog.resolve(t.schema(), containers);
        if (!containers.contains(container)) {
            // never a name the database has not reported: with no identifier quote, it would go into SQL as written
            throw new SQLException("There is no " + (shape.level() == GenericCatalog.Level.CATALOG ? "catalogue " : "schema ")
                + t.schema() + " in this database (the driver lists " + String.join(", ", containers.stream()
                .filter(x -> !GenericCatalog.isSystem(x)).limit(10).toList()) + ")", "42000");
        }
        List<String> tables = GenericCatalog.tables(c, shape, container).stream().map(x -> x[0]).toList();
        return new TableName(container, GenericCatalog.resolve(t.table(), tables));
    }

    /** Not used: the node's {@link GenericWriteSql} describes the table from the driver's metadata. */
    @Override
    List<WritePlan.TargetColumn> targetColumns(Connection connection, SourceConfig source, TableName table) {
        return null;
    }
}
