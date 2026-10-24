package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * Microsoft SQL Server, through Microsoft's JDBC driver.
 *
 * <p>Identifiers are quoted {@code [like this]}; a row limit is {@code SELECT TOP (n)}. Table,
 * view and column descriptions come from {@code MS_Description} extended properties, which is
 * where SQL Server Management Studio keeps them. Columns of type geography, geometry and
 * hierarchyid are read through their {@code ToString()} method, so they arrive as well-known text
 * and paths ({@code /1/2/}) rather than as the server's internal bytes.
 *
 * <p>Reads run at the isolation level in the {@code isolation} setting: {@code read-uncommitted}
 * by default, so profiling a large table takes no shared locks and never blocks the
 * application's writes; {@code snapshot} for a consistent view without locks, where the database
 * allows snapshot isolation; or {@code read-committed}.
 */
public final class SqlServerConnector extends JdbcConnector {

    /** SQL Server's own schemas: sys, INFORMATION_SCHEMA, guest and the fixed database roles. */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of("sys", "information_schema", "guest", "db_owner",
        "db_accessadmin", "db_securityadmin", "db_ddladmin", "db_backupoperator", "db_datareader", "db_datawriter",
        "db_denydatareader", "db_denydatawriter");

    /** Snapshot isolation, as Microsoft's driver numbers it ({@code SQLServerConnection.TRANSACTION_SNAPSHOT}). */
    private static final int TRANSACTION_SNAPSHOT = 0x1000;

    /** The {@code isolation} setting's values and the JDBC levels they set; the first is the default. */
    private static final Map<String, Integer> ISOLATION = isolationLevels();

    /** Types whose readable form is their {@code ToString()} method rather than their bytes. */
    private static final Set<String> READ_THROUGH_TO_STRING = Set.of("geography", "geometry", "hierarchyid");

    /** Every MS_Description on a table, a view or one of their columns in the current database. */
    private static final String DESCRIPTIONS_SQL = """
        SELECT s.name, o.name, c.name, CONVERT(nvarchar(4000), ep.value)
        FROM sys.extended_properties ep
        JOIN sys.objects o ON o.object_id = ep.major_id
        JOIN sys.schemas s ON s.schema_id = o.schema_id
        LEFT JOIN sys.columns c ON c.object_id = ep.major_id AND c.column_id = ep.minor_id
        WHERE ep.class = 1 AND ep.name = N'MS_Description' AND o.type IN ('U', 'V')""";

    /** SQL Server error numbers people meet when first connecting. */
    private static final int LOGIN_FAILED = 18456;
    private static final int CANNOT_OPEN_DATABASE = 4060;

    @Override
    public String kind() {
        return "sqlserver";
    }

    @Override
    public String displayName() {
        return "SQL Server";
    }

    @Override
    protected String urlPrefix() {
        return "jdbc:sqlserver://";
    }

    @Override
    protected String exampleUrl() {
        return "jdbc:sqlserver://db.example.com:1433;databaseName=sales;encrypt=true";
    }

    @Override
    public List<Setting> settings() {
        List<Setting> settings = new ArrayList<>(super.settings());
        settings.add(new Setting("isolation", "Read isolation", false, "read-uncommitted (the default) takes no "
            + "shared locks, so reading never blocks the application's writes, but may see changes not yet "
            + "committed; snapshot reads a consistent view without locks if the database allows snapshot "
            + "isolation; read-committed takes shared locks and can block writers", false));
        return List.copyOf(settings);
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>(super.validate(source));
        String isolation = source.setting("isolation");
        if (isolation != null && !ISOLATION.containsKey(isolation.toLowerCase(Locale.ROOT))) {
            problems.add("isolation: one of " + String.join(", ", ISOLATION.keySet())
                + ", or leave it empty for read-uncommitted");
        }
        return problems;
    }

    @Override
    protected Map<String, String> readSettings(SourceConfig source) {
        String isolation = source.setting("isolation");
        Integer level = ISOLATION.get(isolation == null ? "read-uncommitted" : isolation.toLowerCase(Locale.ROOT));
        return Map.of(JdbcTableInput.ISOLATION, Integer.toString(level == null ? Connection.TRANSACTION_READ_UNCOMMITTED
            : level));
    }

    private static Map<String, Integer> isolationLevels() {
        Map<String, Integer> levels = new LinkedHashMap<>();
        levels.put("read-uncommitted", Connection.TRANSACTION_READ_UNCOMMITTED);
        levels.put("snapshot", TRANSACTION_SNAPSHOT);
        levels.put("read-committed", Connection.TRANSACTION_READ_COMMITTED);
        return Collections.unmodifiableMap(levels);
    }

    /** Microsoft's driver: login timeout in seconds, socket timeout in milliseconds. */
    @Override
    protected java.util.Properties timeouts(java.time.Duration timeout) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("loginTimeout", Integer.toString(seconds(timeout)));
        p.setProperty("socketTimeout", Long.toString(seconds(timeout) * 1000L));
        return p;
    }

    /** {@code jdbc:sqlserver://host:1433;databaseName=database}; the driver encrypts by default. */
    @Override
    protected String addressUrl(String host, Integer port, String database) {
        return "jdbc:sqlserver://" + host + ":" + (port == null ? 1433 : port)
            + (database == null || database.isBlank() ? "" : ";databaseName=" + database);
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
    }

    /**
     * SQL Server's metadata procedures match schema names with {@code LIKE}, where {@code [}
     * opens a character class: wildcards are escaped by bracketing them.
     */
    @Override
    protected String schemaPattern(DatabaseMetaData md, String schema) {
        return schema.replace("[", "[[]").replace("_", "[_]").replace("%", "[%]");
    }

    @Override
    protected String quote(String identifier) {
        return "[" + identifier.replace("]", "]]") + "]";
    }

    @Override
    protected String selectSql(String schema, String table, long rowLimit) {
        return select("*", schema, table, rowLimit);
    }

    @Override
    protected String selectSql(AssetInfo asset, long rowLimit) {
        String schema = asset.hints().get("schema");
        String table = asset.hints().get("table");
        boolean convert = asset.fields().stream().anyMatch(SqlServerConnector::readThroughToString);
        if (!convert) {
            return selectSql(schema, table, rowLimit);
        }
        String columns = asset.fields().stream()
            .map(f -> readThroughToString(f) ? quote(f.name()) + ".ToString() AS " + quote(f.name()) : quote(f.name()))
            .collect(Collectors.joining(", "));
        return select(columns, schema, table, rowLimit);
    }

    private String select(String columns, String schema, String table, long rowLimit) {
        return "SELECT " + (rowLimit > 0 ? "TOP (" + rowLimit + ") " : "") + columns + " FROM " + quote(schema) + "."
            + quote(table);
    }

    private static boolean readThroughToString(AssetInfo.FieldInfo field) {
        return field.sourceType() != null && READ_THROUGH_TO_STRING.contains(field.sourceType().toLowerCase(Locale.ROOT));
    }

    @Override
    protected List<JdbcCatalog.Table> readCatalog(Connection connection, AssetFilter filter) throws SQLException {
        List<JdbcCatalog.Table> tables = super.readCatalog(connection, filter);
        Map<String, String> descriptions = descriptions(connection);
        if (descriptions.isEmpty()) {
            return tables;
        }
        return tables.stream().map(t -> new JdbcCatalog.Table(t.schema(), t.name(), t.type(),
            descriptions.getOrDefault(key(t.schema(), t.name(), null), t.comment()),
            t.columns().stream().map(c -> new JdbcCatalog.Column(c.name(), c.position(), c.sourceType(), c.jdbcType(),
                c.size(), c.scale(), c.nullable(),
                descriptions.getOrDefault(key(t.schema(), t.name(), c.name()), c.comment()), c.kind())).toList(),
            t.primaryKey())).toList();
    }

    /**
     * MS_Description values by schema, object and column (none for the object's own). One query
     * for the whole database. Descriptions are a nicety: where the catalogue views cannot be read
     * (some Azure editions, restricted accounts) the catalogue is still recorded, without them.
     */
    private static Map<String, String> descriptions(Connection connection) {
        Map<String, String> out = new HashMap<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(DESCRIPTIONS_SQL)) {
            while (rs.next()) {
                String text = rs.getString(4);
                if (text != null && !text.isBlank()) {
                    out.put(key(rs.getString(1), rs.getString(2), rs.getString(3)), text);
                }
            }
        } catch (SQLException e) {
            return Map.of();
        }
        return out;
    }

    private static String key(String schema, String object, String column) {
        return schema + '\u0000' + object + '\u0000' + (column == null ? "" : column);
    }

    @Override
    protected String explain(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String hint;
        if (e.getErrorCode() == LOGIN_FAILED) {
            hint = "Check the user name and password, and that the server allows SQL Server authentication.";
        } else if (e.getErrorCode() == CANNOT_OPEN_DATABASE) {
            hint = "Check databaseName in the URL, and that this user has access to that database.";
        } else if (message.contains("PKIX") || message.contains("secure connection") || message.contains("certificate")) {
            hint = "The server's TLS certificate is not trusted: add it to Kilnmark's trust store, or, for a lab "
                + "server only, add trustServerCertificate=true to the URL.";
        } else if ("08S01".equals(e.getSQLState())) {
            hint = "Check the host and port in the URL, that SQL Server accepts TCP/IP connections, and that no "
                + "firewall blocks the port.";
        } else {
            return message;
        }
        return message + " " + hint;
    }
}
