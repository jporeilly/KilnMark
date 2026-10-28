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
import java.util.regex.Pattern;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.StepSpec;
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
 * Teradata, Snowflake, Sybase, Informix, H2 and the like, through a driver an admin registered
 * (docs/design/drivers.md, "Registered drivers"). The driver is used only through the standard
 * JDBC interfaces, loaded on its own from the drivers folder while its jar is the one registered,
 * never through {@link java.sql.DriverManager}.
 *
 * <p>Settings: {@code driver} (a registered driver, by name), {@code url} (which must start as the
 * driver's URLs do, and which the driver must accept), {@code username}, the password as the
 * source's secret, {@code properties} (further driver properties, {@code name=value; ...}),
 * {@code schemas} and {@code read_only} (on by default: connections are marked read-only, and
 * pipelines cannot write). Only admins may set up such a source or change these settings
 * ({@link #adminOnly()}): the URL and properties go to code Kilnmark has not vetted.
 *
 * <p>The catalogue comes from {@link java.sql.DatabaseMetaData} ({@link GenericCatalog}), types are
 * mapped by {@link GenericTypes}, and rows are read with {@code SELECT *} and the driver's own row
 * limit. Writing ({@link GenericWriteSql}) is {@code append} and {@code create} only; {@code replace}
 * and {@code upsert} are refused, as no portable SQL makes them safe. Data quality rules are
 * checked on the rows read rather than as SQL in the database.
 */
public class GenericJdbcConnector extends JdbcConnector {

    static final String KIND = "jdbc";

    /** Settings the reader step receives besides the URL, user and password. */
    static final String READ_DRIVER = "jdbc-driver";
    static final String READ_CONTAINER = "jdbc-container";
    static final String READ_TABLE = "jdbc-table";

    /** What {@link #hosts} gives for a URL whose host Kilnmark cannot read, so an allow-list refuses it. */
    static final String UNREADABLE_HOST = "an address Kilnmark cannot read from this URL";

    /** A driver property's name: letters, digits, {@code . _ -}. */
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,99}");

    /**
     * Driver properties that would point the driver at another host or port than the URL's, which
     * the allowed-hosts check reads: refused, so the address is always the URL's.
     */
    private static final Set<String> ADDRESS_NAMES = Set.of("host", "hostname", "servername", "server", "port",
        "portnumber", "address", "url", "dburl", "account");

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
                + "separated by semicolons, for example sslConnection=true; currentSchema=SALES. Properties that "
                + "read files, load classes, change time limits or name another host are refused"),
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
     * The driver, the URL (with the driver's prefix, no password, no refused property, and accepted
     * by the driver itself), the properties, and {@code read_only}.
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
        String url = source.setting("url");
        if (url == null || !url.regionMatches(true, 0, "jdbc:", 0, 5)) {
            problems.add("url: a JDBC URL, starting jdbc:, for example " + (driver == null ? exampleUrl()
                : driver.urlPrefix() + "..."));
        } else if (url.length() > 2000 || url.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            problems.add("url: at most 2,000 characters, on one line");
        } else if (driver != null && !driver.takes(url)) {
            problems.add("url: the driver " + driver.name() + " takes URLs starting " + driver.urlPrefix()
                + ", and this one does not");
        } else if (PASSWORD_IN_URL.matcher(url).find()) {
            problems.add("url: take the password out of the URL and enter it as the source's password instead; "
                + "the URL is stored and shown as plain text, the password is encrypted");
        } else {
            String denied = null;
            for (var e : urlProperties(url).entrySet()) {
                denied = deniedProperty(e.getKey(), e.getValue());
                if (denied == null && ADDRESS_NAMES.contains(normalName(e.getKey()))) {
                    denied = "url: the property " + e.getKey() + " names another host or port than the URL's own. "
                        + "Write the address in the URL itself";
                }
                if (denied != null) {
                    break;
                }
            }
            if (denied != null) {
                problems.add(denied);
            } else if (driver != null) {
                try {
                    if (!drivers.driver(driver).acceptsURL(url)) {
                        problems.add("url: the driver " + driver.name() + " does not accept this URL. Check it against "
                            + "the driver's documentation");
                    }
                } catch (SQLException e) {
                    problems.add("driver: " + e.getMessage());
                } catch (RuntimeException | LinkageError e) {
                    problems.add("url: the driver " + driver.name() + " could not read this URL: " + e);
                }
            }
        }
        try {
            properties(source.setting("properties"));
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        String readOnly = source.setting("read_only");
        if (readOnly != null && !readOnly.equalsIgnoreCase("true") && !readOnly.equalsIgnoreCase("false")) {
            problems.add("read_only: true or false, not '" + readOnly + "'");
        }
        return problems;
    }

    /** A password in a URL: {@code ;password=}, {@code ?password=} or {@code &password=}, or {@code :pass@} before a host. */
    private static final Pattern PASSWORD_IN_URL = Pattern.compile("[;?&:/]\\s*password\\s*=|//[^/@;?]*:[^/@;?]*@",
        Pattern.CASE_INSENSITIVE);

    /**
     * The {@code properties} setting as driver properties: {@code name=value} pairs separated by
     * semicolons or new lines.
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
                    + "sslConnection=true; not '" + pair + "'");
            }
            String name = pair.substring(0, eq).strip();
            String value = pair.substring(eq + 1).strip();
            if (!PROPERTY_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("properties: a property's name is letters, digits, . _ and -, not '"
                    + name + "'");
            }
            String n = normalName(name);
            if (n.equals("user") || n.equals("password")) {
                throw new IllegalArgumentException("properties: give the " + (n.equals("user") ? "user name" : "password")
                    + " in its own field, not as a property");
            }
            String denied = deniedProperty(name, value);
            if (denied != null) {
                throw new IllegalArgumentException("properties" + denied.substring(denied.indexOf(':')));
            }
            if (ADDRESS_NAMES.contains(n)) {
                throw new IllegalArgumentException("properties: " + name + " names another host or port than the "
                    + "URL's own. Write the address in the URL itself");
            }
            if (p.containsKey(name)) {
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

    /** The URL's hosts; when Kilnmark cannot read one from it, a placeholder no allow-list holds. */
    @Override
    public List<String> hosts(SourceConfig source) {
        List<String> hosts = new ArrayList<>(UrlHosts.hosts(source.setting("url")));
        return hosts.isEmpty() ? List.of(UNREADABLE_HOST) : hosts;
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
        Connection c = drivers.driver(r).connect(url, p);
        if (c == null) {
            throw new SQLException("The driver " + r.name() + " does not accept the address " + url, "08001");
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
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
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
        return new StepSpec("read", tableInput(), s);
    }

    @Override
    protected JdbcTableInput tableInput() {
        return new JdbcTableInput(new Reading(this));
    }

    /** How the reader step connects (through the registered driver), names the table and maps columns. */
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
        public String query(Connection connection, String sql, Map<String, String> settings) throws SQLException {
            String table = settings.get(READ_TABLE);
            if (table == null) {
                return sql;
            }
            return "SELECT * FROM " + GenericCatalog.shape(connection).qualified(settings.get(READ_CONTAINER), table);
        }
    }

    // ------------------------------------------------------------------ writing

    /** For capabilities and write settings: the SQL itself is read from the driver when a step writes. */
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
     * A step appending to a table or creating one ({@link JdbcTableOutput}) with SQL read from the
     * driver ({@link GenericWriteSql}), to the table as the catalogue spells it.
     */
    @Override
    public io.kilnmark.kiln.Step writer(SourceConfig source, WriteOptions write) throws ConnectorException {
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
        String container = GenericCatalog.resolve(t.schema(), GenericCatalog.containers(c, shape));
        List<String> tables = GenericCatalog.tables(c, shape, container).stream().map(x -> x[0]).toList();
        return new TableName(container, GenericCatalog.resolve(t.table(), tables));
    }

    /** Not used: the step's {@link GenericWriteSql} describes the table from the driver's metadata. */
    @Override
    List<WritePlan.TargetColumn> targetColumns(Connection connection, SourceConfig source, TableName table) {
        return null;
    }
}
