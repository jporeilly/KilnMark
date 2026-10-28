package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.kilnmark.kiln.connect.AssetFilter;

/**
 * The catalogue of a database Kilnmark knows only through its JDBC driver, read with standard
 * {@link DatabaseMetaData} calls, with a fallback wherever drivers differ or answer null:
 *
 * <ul>
 *   <li>Containers are the schemas of the connection's catalogue when the driver reports schemas;
 *       otherwise its catalogues (databases that have only those); otherwise one container,
 *       {@value #DEFAULT}, holding every table ({@link Level}).</li>
 *   <li>Table types are those the driver lists that are tables or views (not system tables, aliases,
 *       synonyms or temporary tables); when it lists none, every row is read and the same test is
 *       applied to each. A missing type is a table.</li>
 *   <li>Columns: a missing position is the order the driver gives; an unknown nullability allows
 *       nulls; a missing size or scale counts as none. Primary keys a driver cannot report are left
 *       out.</li>
 * </ul>
 */
final class GenericCatalog {

    /** The one container of a database whose driver reports neither schemas nor catalogues. */
    static final String DEFAULT = "default";

    /** What a database's tables sit in, as its driver reports them. */
    enum Level { SCHEMA, CATALOG, NONE }

    /**
     * How names work in this database.
     *
     * @param catalog        the connection's own catalogue (for {@link Level#SCHEMA}), or null
     * @param quote          the identifier quote, or empty when the driver says it has none
     * @param separator      between a catalogue and a table ({@link Level#CATALOG})
     * @param catalogAtStart whether the catalogue comes before the table
     */
    record Shape(Level level, String catalog, String quote, String separator, boolean catalogAtStart) {

        /** {@code name} quoted as the driver says; as it is when the driver has no quote. */
        String quoted(String name) {
            return quote.isEmpty() ? name : quote + name.replace(quote, quote + quote) + quote;
        }

        /** A table's full name in SQL, from its container as {@link GenericCatalog} names it. */
        String qualified(String container, String table) {
            return switch (level) {
                case SCHEMA -> quoted(container) + "." + quoted(table);
                case CATALOG -> catalogAtStart ? quoted(container) + separator + quoted(table)
                    : quoted(table) + separator + quoted(container);
                case NONE -> quoted(table);
            };
        }

        /** The catalogue argument for metadata calls about {@code container}. */
        String catalogArg(String container) {
            return switch (level) {
                case SCHEMA -> catalog;
                case CATALOG -> container;
                case NONE -> null;
            };
        }
    }

    /**
     * Schemas (or catalogues) that belong to databases themselves, which a scan of everything
     * skips: the SQL standard's, and those of the databases generic JDBC is most used for.
     */
    static final Set<String> SYSTEM = Set.of("information_schema", "sys", "sysibm", "syscat", "sysfun", "sysproc",
        "sysstat", "systools", "sysibmadm", "sysibminternal", "sysibmts", "syspublic", "nullid", "sqlj", "syscs_diag",
        "syscs_util", "dbc", "td_sysfnlib", "td_sysxml", "td_sysgpl", "syslib", "sysudtlib", "sysbar", "sysjdbc",
        "sysspatial", "tdstats", "tdqcd", "tdmaps", "tdwm", "td_server_db", "systemfe", "sysadmin", "sysuif",
        "pg_catalog", "mysql", "performance_schema", "sysmaster", "sysutils", "sysuser", "sybsystemprocs", "sybsystemdb", "sybsecurity", "master", "model", "tempdb");

    private GenericCatalog() {
    }

    /** A name that needs no quoting: letters, digits and _, not starting with a digit. */
    static boolean plainName(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    static boolean isSystem(String name) {
        return name != null && SYSTEM.contains(name.toLowerCase(Locale.ROOT));
    }

    /** How this connection's database names its tables. */
    static Shape shape(Connection c) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String quote = quote(md);
        String current = null;
        try {
            current = c.getCatalog();
        } catch (SQLException | RuntimeException e) {
            // a driver without catalogues
        }
        if (!schemas(md, current).isEmpty()) {
            return new Shape(Level.SCHEMA, current, quote, ".", true);
        }
        if (!catalogs(md).isEmpty()) {
            String separator = ".";
            boolean atStart = true;
            try {
                String s = md.getCatalogSeparator();
                separator = s == null || s.isEmpty() ? "." : s;
                atStart = md.isCatalogAtStart();
            } catch (SQLException | RuntimeException e) {
                // the usual: catalogue first, then a dot
            }
            return new Shape(Level.CATALOG, null, quote, separator, atStart);
        }
        return new Shape(Level.NONE, null, quote, ".", true);
    }

    private static String quote(DatabaseMetaData md) {
        try {
            String q = md.getIdentifierQuoteString();
            return q == null || q.isBlank() ? "" : q.strip();
        } catch (SQLException | RuntimeException e) {
            return "\"";
        }
    }

    /** The schemas in catalogue {@code current} (every schema when the driver does not say which catalogue). */
    private static List<String> schemas(DatabaseMetaData md, String current) {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getSchemas()) {
            boolean hasCatalog = rs.getMetaData().getColumnCount() >= 2;
            while (rs.next()) {
                String schema = rs.getString(1);
                String catalog = hasCatalog ? rs.getString(2) : null;
                if (schema != null && !schema.isEmpty() && !out.contains(schema)
                    && (current == null || catalog == null || catalog.equals(current))) {
                    out.add(schema);
                }
            }
        } catch (SQLException | RuntimeException e) {
            // no schemas: catalogues, or neither
        }
        return out;
    }

    private static List<String> catalogs(DatabaseMetaData md) {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getCatalogs()) {
            while (rs.next()) {
                String catalog = rs.getString(1);
                if (catalog != null && !catalog.isEmpty() && !out.contains(catalog)) {
                    out.add(catalog);
                }
            }
        } catch (SQLException | RuntimeException e) {
            // none
        }
        return out;
    }

    /** Every container, system ones included, as the shape names them. */
    static List<String> containers(Connection c, Shape shape) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        return switch (shape.level()) {
            case SCHEMA -> schemas(md, shape.catalog());
            case CATALOG -> catalogs(md);
            case NONE -> List.of(DEFAULT);
        };
    }

    /** Whether a table type is a table or view a scan reads. */
    static boolean wantedType(String type) {
        String t = type == null ? "TABLE" : type.toUpperCase(Locale.ROOT);
        return (t.contains("TABLE") || t.contains("VIEW")) && !t.contains("SYSTEM") && !t.contains("TEMPORARY")
            && !t.contains("ALIAS") && !t.contains("SYNONYM") && !t.startsWith("GLOBAL") && !t.startsWith("LOCAL");
    }

    /** The table types to ask for: those the driver lists that a scan reads, or null for every type. */
    static String[] types(DatabaseMetaData md) {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getTableTypes()) {
            while (rs.next()) {
                String t = rs.getString(1);
                if (t != null && wantedType(t.strip())) {
                    out.add(t.strip());
                }
            }
        } catch (SQLException | RuntimeException e) {
            return null;
        }
        return out.isEmpty() ? null : out.toArray(String[]::new);
    }

    /** A table type as the catalogue records it: {@code view}, {@code materialized view}, or {@code table}. */
    static String typeLabel(String type) {
        String t = type == null ? "table" : type.strip().toLowerCase(Locale.ROOT);
        return t.contains("view") ? t : "table";
    }

    /** {@code name} as a metadata search pattern matching only itself. */
    static String pattern(DatabaseMetaData md, String name) {
        try {
            return JdbcCatalog.escapeWithDriver(md, name);
        } catch (SQLException | RuntimeException e) {
            return name;
        }
    }

    private static String schemaArg(DatabaseMetaData md, Shape shape, String container) {
        return shape.level() == Level.SCHEMA ? pattern(md, container) : null;
    }

    /** Whether a {@code getTables} or {@code getColumns} row belongs to {@code container}. */
    private static boolean inContainer(ResultSet rs, Shape shape, String container) throws SQLException {
        return switch (shape.level()) {
            case SCHEMA -> container.equals(rs.getString("TABLE_SCHEM"));
            case CATALOG -> container.equals(rs.getString("TABLE_CAT"));
            case NONE -> true;
        };
    }

    /** The container a {@code getTables} row is in, or null for one outside the shape. */
    private static String containerOf(ResultSet rs, Shape shape) throws SQLException {
        return switch (shape.level()) {
            case SCHEMA -> {
                String catalog = rs.getString("TABLE_CAT");
                yield shape.catalog() != null && catalog != null && !catalog.equals(shape.catalog()) ? null
                    : rs.getString("TABLE_SCHEM");
            }
            case CATALOG -> rs.getString("TABLE_CAT");
            case NONE -> DEFAULT;
        };
    }

    /** Every container that is not a system one, with how many tables and views in it {@code patterns} keep. */
    static Map<String, Long> counts(Connection c, Shape shape, AssetFilter patterns) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        Map<String, Long> counts = new TreeMap<>();
        for (String container : containers(c, shape)) {
            if (!isSystem(container)) {
                counts.put(container, 0L);
            }
        }
        String[] types = types(md);
        try (ResultSet rs = md.getTables(shape.level() == Level.SCHEMA ? shape.catalog() : null, null, "%", types)) {
            while (rs.next()) {
                String container = containerOf(rs, shape);
                String table = rs.getString("TABLE_NAME");
                if (container != null && table != null && counts.containsKey(container)
                    && (types != null || wantedType(rs.getString("TABLE_TYPE")))
                    && patterns.patterns(container + "." + table, table)) {
                    counts.merge(container, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /** The tables and views in one container, as name and type, sorted by name: one metadata call. */
    static List<String[]> tables(Connection c, Shape shape, String container) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        String[] types = types(md);
        Map<String, String> found = new TreeMap<>();
        try (ResultSet rs = md.getTables(shape.catalogArg(container), schemaArg(md, shape, container), "%", types)) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String type = rs.getString("TABLE_TYPE");
                if (table != null && inContainer(rs, shape, container) && (types != null || wantedType(type))) {
                    found.put(table, typeLabel(type));
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        found.forEach((name, type) -> out.add(new String[] {name, type}));
        return out;
    }

    /** {@code requested} as the database spells it: the same, or the only one differing in case. */
    static String resolve(String requested, List<String> all) {
        if (all.contains(requested)) {
            return requested;
        }
        List<String> sameButCase = all.stream().filter(s -> s.equalsIgnoreCase(requested)).toList();
        return sameButCase.size() == 1 ? sameButCase.get(0) : requested;
    }

    /** Above this many tables in a container, columns are read for the whole container in one call. */
    private static final int PER_TABLE_LIMIT = 50;

    /**
     * Every table and view {@code filter} keeps, with columns (mapped by {@link GenericTypes}, a note
     * in the comment where a type is read as text) and primary keys.
     */
    static List<JdbcCatalog.Table> read(Connection c, Shape shape, AssetFilter filter) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        List<String> all = containers(c, shape);
        List<String> wanted = filter.containers().isEmpty()
            ? all.stream().filter(s -> !isSystem(s)).toList()
            : filter.containers().stream().map(s -> shape.level() == Level.NONE ? DEFAULT : resolve(s, all))
                .distinct().toList();
        String[] types = types(md);
        List<JdbcCatalog.Table> out = new ArrayList<>();
        for (String container : wanted) {
            String catalogArg = shape.catalogArg(container);
            String schemaArg = schemaArg(md, shape, container);
            Map<String, String[]> found = new TreeMap<>();
            try (ResultSet rs = md.getTables(catalogArg, schemaArg, "%", types)) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String type = rs.getString("TABLE_TYPE");
                    if (table != null && inContainer(rs, shape, container) && (types != null || wantedType(type))
                        && filter.patterns(container + "." + table, table)) {
                        found.put(table, new String[] {typeLabel(type), rs.getString("REMARKS")});
                    }
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            Map<String, List<JdbcCatalog.Column>> columns = new LinkedHashMap<>();
            if (found.size() <= PER_TABLE_LIMIT) {
                for (String table : found.keySet()) {
                    try (ResultSet rs = md.getColumns(catalogArg, schemaArg, pattern(md, table), "%")) {
                        addColumns(rs, shape, container, found, columns);
                    }
                }
            } else {
                try (ResultSet rs = md.getColumns(catalogArg, schemaArg, "%", "%")) {
                    addColumns(rs, shape, container, found, columns);
                }
            }
            for (var e : found.entrySet()) {
                out.add(new JdbcCatalog.Table(container, e.getKey(), e.getValue()[0], e.getValue()[1],
                    List.copyOf(columns.getOrDefault(e.getKey(), List.of())),
                    e.getValue()[0].equals("table") ? primaryKey(md, catalogArg, shape, container, e.getKey())
                        : List.of()));
            }
        }
        return out;
    }

    private static void addColumns(ResultSet rs, Shape shape, String container, Map<String, String[]> found,
                                   Map<String, List<JdbcCatalog.Column>> columns) throws SQLException {
        while (rs.next()) {
            String table = rs.getString("TABLE_NAME");
            if (table == null || !found.containsKey(table) || !inContainer(rs, shape, container)) {
                continue;
            }
            List<JdbcCatalog.Column> cols = columns.computeIfAbsent(table, k -> new ArrayList<>());
            String name = rs.getString("COLUMN_NAME");
            int jdbcType = rs.getInt("DATA_TYPE");
            String typeName = rs.getString("TYPE_NAME");
            int size = rs.getInt("COLUMN_SIZE");
            if (rs.wasNull()) {
                size = 0;
            }
            int scale = rs.getInt("DECIMAL_DIGITS");
            if (rs.wasNull()) {
                scale = 0;
            }
            int position = rs.getInt("ORDINAL_POSITION");
            if (rs.wasNull() || position <= 0) {
                position = cols.size() + 1;
            }
            boolean nullable = rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
            String remarks = rs.getString("REMARKS");
            GenericTypes.Mapped m = GenericTypes.map(jdbcType, typeName, size, scale);
            String comment = m.note() == null ? remarks
                : remarks == null || remarks.isBlank() ? m.note() : remarks + " (" + m.note() + ")";
            cols.add(new JdbcCatalog.Column(name == null ? "column " + position : name, position,
                typeName == null ? GenericTypes.jdbcName(jdbcType) : typeName, jdbcType, size, scale, nullable,
                comment, m.kind()));
        }
        for (List<JdbcCatalog.Column> cols : columns.values()) {
            cols.sort((a, b) -> Integer.compare(a.position(), b.position()));
        }
    }

    private static List<String> primaryKey(DatabaseMetaData md, String catalogArg, Shape shape, String container,
                                           String table) {
        Map<Integer, String> cols = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(catalogArg, shape.level() == Level.SCHEMA ? container : null, table)) {
            int n = 0;
            while (rs.next()) {
                int seq = rs.getInt("KEY_SEQ");
                cols.put(rs.wasNull() || seq <= 0 ? ++n : seq, rs.getString("COLUMN_NAME"));
            }
        } catch (SQLException | RuntimeException e) {
            return List.of(); // a driver that cannot say: no key recorded
        }
        return List.copyOf(cols.values());
    }
}
