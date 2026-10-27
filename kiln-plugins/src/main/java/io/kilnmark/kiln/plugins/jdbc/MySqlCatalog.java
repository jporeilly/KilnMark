package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.kilnmark.kiln.connect.AssetFilter;

/**
 * A MySQL or MariaDB catalogue from {@code information_schema}, where each database is a schema:
 * tables and views with their comments and approximate row counts ({@code TABLE_ROWS}, the
 * storage engine's estimate), columns with their types and comments, primary keys, unique keys and
 * foreign keys. Only what the login may see is listed, as the server decides.
 */
final class MySqlCatalog {

    /** The server's own databases, which a scan of every database skips. */
    static final Set<String> SYSTEM = Set.of("information_schema", "mysql", "performance_schema", "sys");

    /**
     * Table types listed: tables, views and MariaDB's system-versioned tables. Sequences (MariaDB)
     * and the server's own views are left out.
     */
    private static final String TYPES = "('BASE TABLE', 'VIEW', 'SYSTEM VERSIONED')";

    /** Above this many tables in a database, columns and keys are read for the whole database at once. */
    private static final int TABLES_BY_NAME = 200;

    private MySqlCatalog() {
    }

    static boolean isSystem(String database) {
        return SYSTEM.contains(database.toLowerCase(Locale.ROOT));
    }

    /** Every database the login can see that is not the server's own, in name order. */
    static List<String> databases(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA "
            + "ORDER BY SCHEMA_NAME"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (!isSystem(rs.getString(1))) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /**
     * The databases to read: {@code only} when the source names one; else those {@code filter}
     * chooses (exactly, or failing that ignoring case), or every database when it names none.
     */
    static List<String> wanted(Connection c, String only, AssetFilter filter) throws SQLException {
        if (only != null) {
            return List.of(only);
        }
        List<String> all = databases(c);
        if (filter.containers().isEmpty()) {
            return all;
        }
        List<String> out = new ArrayList<>();
        for (String requested : filter.containers()) {
            String found = all.contains(requested) ? requested : all.stream().filter(d -> d.equalsIgnoreCase(requested))
                .findFirst().orElse(requested);
            if (!out.contains(found)) {
                out.add(found);
            }
        }
        return out;
    }

    /** The tables and views of one database, as name and lower-case kind ({@code table} or {@code view}). */
    static List<String[]> tables(Connection c, String database) throws SQLException {
        Map<String, String> found = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, TABLE_TYPE FROM information_schema.TABLES "
            + "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN " + TYPES)) {
            ps.setString(1, database);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString(1), kind(rs.getString(2)));
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        found.forEach((name, kind) -> out.add(new String[] {name, kind}));
        return out;
    }

    /**
     * How many tables and views in each database (all, or only {@code only}) the patterns keep:
     * two queries, whatever the number of databases.
     */
    static Map<String, Long> counts(Connection c, String only, AssetFilter patterns) throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        for (String d : only != null ? List.of(only) : databases(c)) {
            counts.put(d, 0L);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_SCHEMA, TABLE_NAME FROM information_schema.TABLES "
            + "WHERE TABLE_TYPE IN " + TYPES); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String d = rs.getString(1);
                String t = rs.getString(2);
                if (counts.containsKey(d) && patterns.patterns(d + "." + t, t)) {
                    counts.merge(d, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /** One table or view as listed, before its columns are read. */
    private record Listed(String name, String kind, String comment, Long rows, String engine) {
    }

    /**
     * Every table and view in {@code databases} that the patterns keep, with columns, keys,
     * comments and, as hints, {@code row_estimate} (tables only) and {@code unique_keys}
     * ({@code name(column, ...)}, separated by {@code ; }).
     */
    static List<JdbcCatalog.Table> read(Connection c, List<String> databases, AssetFilter filter,
                                        boolean tinyint1AsBoolean) throws SQLException {
        List<JdbcCatalog.Table> out = new ArrayList<>();
        for (String database : databases) {
            Map<String, Listed> found = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, TABLE_ROWS, "
                + "ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN " + TYPES)) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(1);
                        if (!filter.patterns(database + "." + name, name)) {
                            continue;
                        }
                        String kind = kind(rs.getString(2));
                        String comment = rs.getString(3);
                        // a view's TABLE_COMMENT is the word VIEW (or a note that it is invalid)
                        if (kind.equals("view") || comment == null || comment.isBlank()) {
                            comment = null;
                        }
                        long rows = rs.getLong(4);
                        found.put(name, new Listed(name, kind, comment, rs.wasNull() || kind.equals("view") ? null : rows,
                            rs.getString(5)));
                    }
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            String only = found.size() <= TABLES_BY_NAME
                ? " AND TABLE_NAME IN (" + found.keySet().stream().map(t -> "?").collect(Collectors.joining(", ")) + ")"
                : "";
            List<String> params = new ArrayList<>();
            params.add(database);
            if (!only.isEmpty()) {
                params.addAll(found.keySet());
            }
            Map<String, List<JdbcCatalog.Column>> columns = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, "
                + "COLUMN_TYPE, IS_NULLABLE, NUMERIC_PRECISION, NUMERIC_SCALE, COLUMN_COMMENT, CHARACTER_MAXIMUM_LENGTH "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?" + only
                + " ORDER BY TABLE_NAME, ORDINAL_POSITION")) {
                bind(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        if (!found.containsKey(table)) {
                            continue;
                        }
                        MySqlTypes.Type type = MySqlTypes.fromCatalog(rs.getString(4), rs.getString(5),
                            longOrNull(rs, 7), longOrNull(rs, 8));
                        MySqlTypes.Mapped m = MySqlTypes.map(type, tinyint1AsBoolean);
                        Long chars = longOrNull(rs, 10);
                        int size = m.kind() == JdbcColumn.Kind.DECIMAL ? m.precision()
                            : chars == null ? 0 : (int) Math.min(Integer.MAX_VALUE, chars);
                        String comment = rs.getString(9);
                        columns.computeIfAbsent(table, k -> new ArrayList<>()).add(new JdbcCatalog.Column(
                            rs.getString(2), rs.getInt(3), rs.getString(5), 0, size, m.scale(),
                            !"NO".equalsIgnoreCase(rs.getString(6)), comment == null || comment.isBlank() ? null
                            : comment, m.kind()));
                    }
                }
            }
            Map<String, Map<String, List<String>>> keys = uniqueKeys(c, "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = ?" + only + " AND NON_UNIQUE = 0 "
                + "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX", params);
            for (Listed t : found.values()) {
                Map<String, List<String>> unique = keys.getOrDefault(t.name(), Map.of());
                List<String> primary = unique.getOrDefault("PRIMARY", List.of());
                Map<String, String> hints = new LinkedHashMap<>();
                if (t.rows() != null) {
                    hints.put("row_estimate", Long.toString(t.rows()));
                }
                String others = unique.entrySet().stream().filter(e -> !e.getKey().equals("PRIMARY"))
                    .map(e -> e.getKey() + "(" + String.join(", ", e.getValue()) + ")").collect(Collectors.joining("; "));
                if (!others.isEmpty()) {
                    hints.put("unique_keys", others);
                }
                if (t.engine() != null) {
                    hints.put("engine", t.engine());
                }
                out.add(new JdbcCatalog.Table(database, t.name(), t.kind(), t.comment(),
                    List.copyOf(columns.getOrDefault(t.name(), List.of())), List.copyOf(primary), hints));
            }
        }
        return out;
    }

    /**
     * Unique indexes by table and name, their columns in order. An index with a part that is not
     * a whole column (an expression, or a prefix of the column) is left out: it does not make the
     * column's values unique.
     */
    private static Map<String, Map<String, List<String>>> uniqueKeys(Connection c, String sql, List<String> params)
        throws SQLException {
        Map<String, Map<String, List<String>>> out = new TreeMap<>();
        Set<String> partial = new java.util.HashSet<>();
        String withPart = sql.replace("SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME ",
            "SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, SUB_PART ");
        try (PreparedStatement ps = c.prepareStatement(withPart)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String index = rs.getString(2);
                    String column = rs.getString(3);
                    rs.getLong(4);
                    if (column == null || !rs.wasNull()) {
                        partial.add(table + '\u0000' + index);
                    }
                    out.computeIfAbsent(table, k -> new LinkedHashMap<>()).computeIfAbsent(index, k -> new ArrayList<>())
                        .add(column);
                }
            }
        }
        out.forEach((table, indexes) -> indexes.keySet().removeIf(i -> partial.contains(table + '\u0000' + i)));
        return out;
    }

    /**
     * The foreign keys declared on {@code tables}, one query per database, columns in key order.
     * Keys to databases not read are kept; the caller decides.
     */
    static List<JdbcCatalog.ForeignKey> foreignKeys(Connection c, List<JdbcCatalog.Table> tables) throws SQLException {
        Set<String> wanted = new java.util.HashSet<>();
        tables.stream().filter(t -> t.type().equals("table")).forEach(t -> wanted.add(t.schema() + '\u0000' + t.name()));
        Map<String, Key> keys = new LinkedHashMap<>();
        for (String database : tables.stream().map(JdbcCatalog.Table::schema).distinct().toList()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT TABLE_NAME, CONSTRAINT_NAME, COLUMN_NAME, "
                + "REFERENCED_TABLE_SCHEMA, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME FROM "
                + "information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME IS NOT NULL "
                + "ORDER BY TABLE_NAME, CONSTRAINT_NAME, ORDINAL_POSITION")) {
                ps.setString(1, database);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        if (!wanted.contains(database + '\u0000' + table)) {
                            continue;
                        }
                        String name = rs.getString(2);
                        String id = database + '\u0000' + table + '\u0000' + name;
                        Key k = keys.get(id);
                        if (k == null) {
                            k = new Key(database, table, name, rs.getString(4), rs.getString(5));
                            keys.put(id, k);
                        }
                        k.from.add(rs.getString(3));
                        k.to.add(rs.getString(6));
                    }
                }
            }
        }
        List<JdbcCatalog.ForeignKey> out = new ArrayList<>();
        for (Key k : keys.values()) {
            out.add(new JdbcCatalog.ForeignKey(k.database, k.table, List.copyOf(k.from), k.toDatabase, k.toTable,
                List.copyOf(k.to), k.name));
        }
        return out;
    }

    /** A foreign key being gathered, column by column. */
    private static final class Key {
        final String database;
        final String table;
        final String name;
        final String toDatabase;
        final String toTable;
        final List<String> from = new ArrayList<>();
        final List<String> to = new ArrayList<>();

        Key(String database, String table, String name, String toDatabase, String toTable) {
            this.database = database;
            this.table = table;
            this.name = name;
            this.toDatabase = toDatabase;
            this.toTable = toTable;
        }
    }

    private static String kind(String tableType) {
        return "VIEW".equalsIgnoreCase(tableType) ? "view" : "table";
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static void bind(PreparedStatement ps, List<String> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            ps.setString(i + 1, params.get(i));
        }
    }
}
