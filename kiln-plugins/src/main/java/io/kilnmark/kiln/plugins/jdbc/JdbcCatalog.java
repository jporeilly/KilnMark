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
import java.util.TreeMap;

/**
 * Reads a database's catalogue (schemas, tables, views and columns) through standard JDBC
 * metadata, so it works for any database with a driver.
 */
public final class JdbcCatalog {

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW", "PARTITIONED TABLE",
        "FOREIGN TABLE"};

    /**
     * What differs between databases when reading the catalogue: which schemas are the database's
     * own, and how to write a schema name as a metadata search pattern that matches only itself.
     */
    public interface Dialect {

        /** True for a schema that belongs to the database itself, never to users. */
        boolean isSystemSchema(String schema);

        /**
         * {@code schema} as a pattern for {@link DatabaseMetaData#getTables} and
         * {@link DatabaseMetaData#getColumns}, with its wildcard characters escaped.
         */
        String schemaPattern(DatabaseMetaData md, String schema) throws SQLException;
    }

    public record Column(String name, int position, String sourceType, int jdbcType, int size, int scale,
                         boolean nullable, String comment, JdbcColumn.Kind kind) {
    }

    public record Table(String schema, String name, String type, String comment, List<Column> columns,
                        List<String> primaryKey) {

        public String path() {
            return schema + "." + name;
        }
    }

    private JdbcCatalog() {
    }

    /**
     * Every table and view in {@code schemas}, or in every schema that is not a system schema when
     * {@code schemas} is empty, with their columns and primary keys. A requested schema matches a
     * schema of the same name, or failing that one that differs only in case.
     */
    public static List<Table> read(Connection connection, List<String> schemas, Dialect dialect) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> all = allSchemas(md);
        List<String> wanted = schemas.isEmpty()
            ? all.stream().filter(s -> !dialect.isSystemSchema(s)).toList()
            : schemas.stream().map(s -> resolve(s, all)).distinct().toList();
        List<Table> tables = new ArrayList<>();
        for (String schema : wanted) {
            // Patterns are a first filter; only rows of exactly this schema are kept.
            String pattern = dialect.schemaPattern(md, schema);
            Map<String, String[]> found = new TreeMap<>();
            try (ResultSet rs = md.getTables(null, pattern, "%", TABLE_TYPES)) {
                while (rs.next()) {
                    if (schema.equals(rs.getString("TABLE_SCHEM"))) {
                        found.put(rs.getString("TABLE_NAME"),
                            new String[] {rs.getString("TABLE_TYPE"), rs.getString("REMARKS")});
                    }
                }
            }
            Map<String, List<Column>> columns = new LinkedHashMap<>();
            try (ResultSet rs = md.getColumns(null, pattern, "%", "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (!schema.equals(rs.getString("TABLE_SCHEM")) || !found.containsKey(table)) {
                        continue;
                    }
                    int jdbcType = rs.getInt("DATA_TYPE");
                    String typeName = rs.getString("TYPE_NAME");
                    int size = rs.getInt("COLUMN_SIZE");
                    int scale = rs.getInt("DECIMAL_DIGITS");
                    String name = rs.getString("COLUMN_NAME");
                    columns.computeIfAbsent(table, k -> new ArrayList<>()).add(new Column(name,
                        rs.getInt("ORDINAL_POSITION"), typeName, jdbcType, size, scale,
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getString("REMARKS"),
                        JdbcColumn.map(name, jdbcType, typeName, size, scale).kind()));
                }
            }
            for (var e : found.entrySet()) {
                List<Column> cols = new ArrayList<>(columns.getOrDefault(e.getKey(), List.of()));
                cols.sort((a, b) -> Integer.compare(a.position(), b.position()));
                tables.add(new Table(schema, e.getKey(), e.getValue()[0].toLowerCase(Locale.ROOT), e.getValue()[1],
                    List.copyOf(cols), primaryKey(md, schema, e.getKey())));
            }
        }
        return tables;
    }

    /**
     * {@code name} with the driver's search-string escape before each wildcard ({@code _},
     * {@code %}) and before the escape itself, as {@link DatabaseMetaData} documents.
     */
    public static String escapeWithDriver(DatabaseMetaData md, String name) throws SQLException {
        String e = md.getSearchStringEscape();
        if (e == null || e.isEmpty()) {
            return name;
        }
        return name.replace(e, e + e).replace("_", e + "_").replace("%", e + "%");
    }

    private static List<String> allSchemas(DatabaseMetaData md) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getSchemas()) {
            while (rs.next()) {
                out.add(rs.getString("TABLE_SCHEM"));
            }
        }
        return out;
    }

    private static String resolve(String requested, List<String> all) {
        if (all.contains(requested)) {
            return requested;
        }
        List<String> sameButCase = all.stream().filter(s -> s.equalsIgnoreCase(requested)).toList();
        return sameButCase.size() == 1 ? sameButCase.get(0) : requested;
    }

    private static List<String> primaryKey(DatabaseMetaData md, String schema, String table) throws SQLException {
        Map<Integer, String> cols = new TreeMap<>();
        try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
            while (rs.next()) {
                cols.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        return List.copyOf(cols.values());
    }
}
