package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.kilnmark.kiln.connect.TableDetail;

/**
 * Reads one table's columns, keys and indexes through standard JDBC metadata, for Explore (ADR
 * 0045). Names are passed to {@link DatabaseMetaData} as the driver asks: exact names for keys and
 * indexes, escaped search patterns for columns, whose results are then kept to the exact table.
 * Nothing here reads a row, and a column's default is only ever reported as present.
 */
final class JdbcExplorer {

    /**
     * Where a table is in the metadata calls: the catalog (MySQL and MariaDB keep databases there),
     * the schema as an exact name, and the schema as a search pattern. Null where the database has
     * no such level.
     */
    record Scope(String catalog, String schema, String schemaPattern) {
    }

    /** What an index shows for a part that is an expression rather than a column. */
    static final String EXPRESSION = "(expression)";

    private JdbcExplorer() {
    }

    static List<TableDetail.Column> columns(DatabaseMetaData md, Scope scope, String table) throws SQLException {
        List<TableDetail.Column> out = new ArrayList<>();
        try (ResultSet rs = md.getColumns(scope.catalog(), scope.schemaPattern(),
            JdbcCatalog.escapeWithDriver(md, table), "%")) {
            Set<String> labels = labels(rs);
            while (rs.next()) {
                if (!table.equals(rs.getString("TABLE_NAME")) || !sameScope(rs, scope, "TABLE_CAT", "TABLE_SCHEM")) {
                    continue; // a driver that ignores the escape matched a look-alike
                }
                int type = rs.getInt("DATA_TYPE");
                Integer size = sized(type) ? whole(rs, "COLUMN_SIZE") : null;
                Integer scale = type == java.sql.Types.DECIMAL || type == java.sql.Types.NUMERIC
                    ? whole(rs, "DECIMAL_DIGITS") : null;
                boolean generated = yes(rs, labels, "IS_AUTOINCREMENT") || yes(rs, labels, "IS_GENERATEDCOLUMN");
                out.add(new TableDetail.Column(rs.getString("COLUMN_NAME"), rs.getInt("ORDINAL_POSITION"),
                    rs.getString("TYPE_NAME"), size, scale, rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    labels.contains("COLUMN_DEF") && rs.getString("COLUMN_DEF") != null, generated));
            }
        }
        out.sort(java.util.Comparator.comparingInt(TableDetail.Column::position));
        return out;
    }

    /** The primary key's name (or null) and its columns in key order. */
    record Key(String name, List<String> columns) {
    }

    static Key primaryKey(DatabaseMetaData md, Scope scope, String table) throws SQLException {
        Map<Integer, String> cols = new TreeMap<>();
        String name = null;
        try (ResultSet rs = md.getPrimaryKeys(scope.catalog(), scope.schema(), table)) {
            while (rs.next()) {
                cols.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                name = rs.getString("PK_NAME");
            }
        }
        return new Key(name, List.copyOf(cols.values()));
    }

    /** The keys of {@code table} pointing at others ({@code imported}), or of others pointing at it. */
    static List<TableDetail.ForeignKey> foreignKeys(DatabaseMetaData md, Scope scope, String table, boolean imported)
        throws SQLException {
        Map<String, Map<Integer, String[]>> groups = new LinkedHashMap<>();
        Map<String, String[]> heads = new LinkedHashMap<>();
        int nameless = 0;
        try (ResultSet rs = imported ? md.getImportedKeys(scope.catalog(), scope.schema(), table)
            : md.getExportedKeys(scope.catalog(), scope.schema(), table)) {
            while (rs.next()) {
                String from = qualified(rs.getString("FKTABLE_CAT"), rs.getString("FKTABLE_SCHEM"),
                    rs.getString("FKTABLE_NAME"));
                String to = qualified(rs.getString("PKTABLE_CAT"), rs.getString("PKTABLE_SCHEM"),
                    rs.getString("PKTABLE_NAME"));
                String name = rs.getString("FK_NAME");
                int seq = rs.getInt("KEY_SEQ");
                if (name == null && seq == 1) {
                    nameless++;
                }
                String id = from + "\u0000" + to + "\u0000" + (name != null ? name : "#" + nameless);
                heads.putIfAbsent(id, new String[] {name, from, to});
                groups.computeIfAbsent(id, k -> new TreeMap<>())
                    .put(seq, new String[] {rs.getString("FKCOLUMN_NAME"), rs.getString("PKCOLUMN_NAME")});
            }
        }
        List<TableDetail.ForeignKey> out = new ArrayList<>();
        groups.forEach((id, cols) -> {
            String[] h = heads.get(id);
            out.add(new TableDetail.ForeignKey(h[0], h[1], cols.values().stream().map(c -> c[0]).toList(), h[2],
                cols.values().stream().map(c -> c[1]).toList()));
        });
        return out;
    }

    /**
     * The table's indexes, from {@link DatabaseMetaData#getIndexInfo} with {@code approximate} on,
     * so no database gathers statistics to answer (Oracle would otherwise analyse the table). A
     * part of an index that is not one of {@code columns} is an expression, which some drivers
     * report as its text: it shows as {@value #EXPRESSION}, since an expression can quote a value.
     * A partial index's condition ({@code FILTER_CONDITION}) is never read, for the same reason.
     */
    static List<TableDetail.Index> indexes(DatabaseMetaData md, Scope scope, String table, Key primaryKey,
                                           Set<String> columns) throws SQLException {
        Map<String, Map<Integer, String>> cols = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        Map<String, String> method = new LinkedHashMap<>();
        try (ResultSet rs = md.getIndexInfo(scope.catalog(), scope.schema(), table, false, true)) {
            while (rs.next()) {
                short type = rs.getShort("TYPE");
                String name = rs.getString("INDEX_NAME");
                if (type == DatabaseMetaData.tableIndexStatistic || name == null) {
                    continue;
                }
                String column = rs.getString("COLUMN_NAME");
                cols.computeIfAbsent(name, k -> new TreeMap<>())
                    .put((int) rs.getShort("ORDINAL_POSITION"), column != null && columns.contains(column)
                        ? column : EXPRESSION);
                unique.putIfAbsent(name, !rs.getBoolean("NON_UNIQUE"));
                method.putIfAbsent(name, type == DatabaseMetaData.tableIndexClustered ? "clustered"
                    : type == DatabaseMetaData.tableIndexHashed ? "hash" : null);
            }
        }
        List<TableDetail.Index> out = new ArrayList<>();
        boolean pkFound = false;
        for (Map.Entry<String, Map<Integer, String>> e : cols.entrySet()) {
            List<String> parts = List.copyOf(e.getValue().values());
            boolean isPk = !pkFound && !primaryKey.columns().isEmpty() && unique.get(e.getKey())
                && (e.getKey().equals(primaryKey.name()) || parts.equals(primaryKey.columns()));
            pkFound |= isPk;
            out.add(new TableDetail.Index(e.getKey(), parts, unique.get(e.getKey()), isPk, method.get(e.getKey())));
        }
        return out;
    }

    private static boolean sameScope(ResultSet rs, Scope scope, String catalogColumn, String schemaColumn)
        throws SQLException {
        if (scope.catalog() != null && !scope.catalog().equals(rs.getString(catalogColumn))) {
            return false;
        }
        return scope.schema() == null || scope.schema().equals(rs.getString(schemaColumn));
    }

    /** {@code schema.table}, with the catalog in place of a schema where the database has none. */
    private static String qualified(String catalog, String schema, String table) {
        String container = schema != null ? schema : catalog;
        return container == null ? table : container + "." + table;
    }

    private static boolean sized(int type) {
        return switch (type) {
            case java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.NCHAR, java.sql.Types.NVARCHAR,
                 java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> true;
            default -> false;
        };
    }

    private static Integer whole(ResultSet rs, String column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static boolean yes(ResultSet rs, Set<String> labels, String column) throws SQLException {
        return labels.contains(column) && "YES".equalsIgnoreCase(rs.getString(column));
    }

    private static Set<String> labels(ResultSet rs) throws SQLException {
        ResultSetMetaData m = rs.getMetaData();
        Set<String> out = new HashSet<>();
        for (int i = 1; i <= m.getColumnCount(); i++) {
            out.add(m.getColumnLabel(i).toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** A connection for Explore: read-only, in a transaction it never commits. */
    static void readOnly(Connection c) throws SQLException {
        c.setAutoCommit(false);
        c.setReadOnly(true);
    }
}
