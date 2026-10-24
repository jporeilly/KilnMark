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

/**
 * Reads a database's catalogue (schemas, tables, views and columns) through standard JDBC
 * metadata, so it works for any database with a driver.
 */
public final class JdbcCatalog {

    /** Schemas that belong to the database itself, never to users. */
    static final Set<String> SYSTEM_SCHEMAS = Set.of("pg_catalog", "information_schema", "pg_toast",
        "ag_catalog", "sys", "guest", "db_owner", "db_accessadmin", "db_securityadmin", "db_ddladmin",
        "db_backupoperator", "db_datareader", "db_datawriter", "db_denydatareader", "db_denydatawriter");

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW", "PARTITIONED TABLE",
        "FOREIGN TABLE"};

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
     * Every table and view in {@code schemas}, or in every non-system schema when {@code schemas}
     * is empty, with their columns and primary keys.
     */
    public static List<Table> read(Connection connection, List<String> schemas) throws SQLException {
        DatabaseMetaData md = connection.getMetaData();
        List<String> wanted = schemas.isEmpty() ? userSchemas(md) : schemas;
        List<Table> tables = new ArrayList<>();
        for (String schema : wanted) {
            Map<String, String[]> found = new TreeMap<>();
            try (ResultSet rs = md.getTables(null, schema, "%", TABLE_TYPES)) {
                while (rs.next()) {
                    found.put(rs.getString("TABLE_NAME"),
                        new String[] {rs.getString("TABLE_TYPE"), rs.getString("REMARKS")});
                }
            }
            Map<String, List<Column>> columns = new LinkedHashMap<>();
            try (ResultSet rs = md.getColumns(null, schema, "%", "%")) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    if (!found.containsKey(table)) {
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
                List<Column> cols = columns.getOrDefault(e.getKey(), List.of());
                cols.sort((a, b) -> Integer.compare(a.position(), b.position()));
                tables.add(new Table(schema, e.getKey(), e.getValue()[0].toLowerCase(Locale.ROOT), e.getValue()[1],
                    List.copyOf(cols), primaryKey(md, schema, e.getKey())));
            }
        }
        return tables;
    }

    private static List<String> userSchemas(DatabaseMetaData md) throws SQLException {
        List<String> out = new ArrayList<>();
        try (ResultSet rs = md.getSchemas()) {
            while (rs.next()) {
                String s = rs.getString("TABLE_SCHEM");
                if (!SYSTEM_SCHEMAS.contains(s.toLowerCase(Locale.ROOT)) && !s.startsWith("pg_temp")
                    && !s.startsWith("pg_toast")) {
                    out.add(s);
                }
            }
        }
        return out;
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
