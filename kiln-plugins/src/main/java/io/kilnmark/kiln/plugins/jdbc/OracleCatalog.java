package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.kilnmark.kiln.connect.AssetFilter;

/**
 * An Oracle catalogue from the {@code ALL_*} dictionary views, where each user is a schema:
 * tables and views with their comments and the optimiser's row counts ({@code NUM_ROWS}, as of
 * the last statistics), columns with their types and comments, primary keys, unique constraints
 * and foreign keys. Only what the login may see is listed, as the database decides. Schemas Oracle
 * maintains itself ({@code ALL_USERS.ORACLE_MAINTAINED}: SYS, SYSTEM, XDB, MDSYS and the like) are
 * left out of a scan of every schema; tables in the recycle bin, nested and secondary tables and
 * index-organised overflow segments are left out always.
 */
final class OracleCatalog {

    private OracleCatalog() {
    }

    /** The schemas of the users Oracle does not maintain itself, that the login can see. */
    private static final String USER_SCHEMAS = "SELECT USERNAME FROM ALL_USERS WHERE ORACLE_MAINTAINED = 'N'";

    private static final String TABLES = "SELECT t.OWNER, t.TABLE_NAME, 'table', c.COMMENTS, t.NUM_ROWS, t.PARTITIONED, "
        + "t.TEMPORARY FROM ALL_TABLES t LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER "
        + "AND c.TABLE_NAME = t.TABLE_NAME WHERE t.NESTED = 'NO' AND t.SECONDARY = 'N' AND t.DROPPED = 'NO' "
        + "AND (t.IOT_TYPE IS NULL OR t.IOT_TYPE = 'IOT') AND t.OWNER %s "
        + "UNION ALL SELECT v.OWNER, v.VIEW_NAME, 'view', c.COMMENTS, NULL, 'NO', 'N' FROM ALL_VIEWS v "
        + "LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = v.OWNER AND c.TABLE_NAME = v.VIEW_NAME WHERE v.OWNER %s";

    /** Every schema the login can see that Oracle does not maintain itself, in name order. */
    static List<String> schemas(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(USER_SCHEMAS + " ORDER BY USERNAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** Whether {@code schema} is one Oracle maintains itself (not listed among the user schemas). */
    static boolean isSystem(Connection c, String schema) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ORACLE_MAINTAINED FROM ALL_USERS WHERE USERNAME = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "Y".equals(rs.getString(1));
            }
        }
    }

    /**
     * The schemas to read: those {@code filter} chooses (exactly, or failing that ignoring case, as
     * an unquoted name would be), or every user schema when it names none.
     */
    static List<String> wanted(Connection c, AssetFilter filter) throws SQLException {
        List<String> all = schemas(c);
        if (filter.containers().isEmpty()) {
            return all;
        }
        List<String> out = new ArrayList<>();
        for (String requested : filter.containers()) {
            String found = all.contains(requested) ? requested : all.stream().filter(s -> s.equalsIgnoreCase(requested))
                .findFirst().orElse(requested);
            if (!out.contains(found)) {
                out.add(found);
            }
        }
        return out;
    }

    /** The tables and views of one schema, as name and kind ({@code table} or {@code view}), in name order. */
    static List<String[]> tables(Connection c, String schema) throws SQLException {
        Map<String, String> found = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(String.format(TABLES, "= ?", "= ?"))) {
            ps.setString(1, schema);
            ps.setString(2, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString(2), rs.getString(3));
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        found.forEach((name, kind) -> out.add(new String[] {name, kind}));
        return out;
    }

    /** How many tables and views in each user schema the patterns keep: one query. */
    static Map<String, Long> counts(Connection c, AssetFilter patterns) throws SQLException {
        Map<String, Long> counts = new TreeMap<>();
        for (String s : schemas(c)) {
            counts.put(s, 0L);
        }
        String in = "IN (" + USER_SCHEMAS + ")";
        try (PreparedStatement ps = c.prepareStatement(String.format(TABLES, in, in)); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String s = rs.getString(1);
                String t = rs.getString(2);
                if (counts.containsKey(s) && patterns.patterns(s + "." + t, t)) {
                    counts.merge(s, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /** One table or view as listed, before its columns are read. */
    private record Listed(String name, String kind, String comment, Long rows, boolean partitioned, boolean temporary) {
    }

    /**
     * Every table and view in {@code schemas} that the patterns keep, with columns, keys, comments
     * and, as hints, {@code row_estimate} (tables with statistics), {@code unique_keys}
     * ({@code name(column, ...)}, separated by {@code ; }), {@code partitioned} and
     * {@code temporary}. Three queries a schema.
     */
    static List<JdbcCatalog.Table> read(Connection c, List<String> schemas, AssetFilter filter) throws SQLException {
        List<JdbcCatalog.Table> out = new ArrayList<>();
        for (String schema : schemas) {
            Map<String, Listed> found = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement(String.format(TABLES, "= ?", "= ?"))) {
                ps.setString(1, schema);
                ps.setString(2, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String name = rs.getString(2);
                        if (!filter.patterns(schema + "." + name, name)) {
                            continue;
                        }
                        String comment = rs.getString(4);
                        long rows = rs.getLong(5);
                        Long estimate = rs.wasNull() ? null : rows;
                        found.put(name, new Listed(name, rs.getString(3), comment == null || comment.isBlank() ? null
                            : comment, estimate, "YES".equals(rs.getString(6)), "Y".equals(rs.getString(7))));
                    }
                }
            }
            if (found.isEmpty()) {
                continue;
            }
            Map<String, List<JdbcCatalog.Column>> columns = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT col.TABLE_NAME, col.COLUMN_NAME, col.COLUMN_ID, "
                + "col.DATA_TYPE, col.DATA_PRECISION, col.DATA_SCALE, col.NULLABLE, col.CHAR_LENGTH, col.CHAR_USED, "
                + "col.DATA_LENGTH, col.DATA_TYPE_OWNER, cc.COMMENTS FROM ALL_TAB_COLUMNS col "
                + "LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = col.OWNER AND cc.TABLE_NAME = col.TABLE_NAME "
                + "AND cc.COLUMN_NAME = col.COLUMN_NAME WHERE col.OWNER = ? ORDER BY col.TABLE_NAME, col.COLUMN_ID")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        if (!found.containsKey(table)) {
                            continue;
                        }
                        String dataType = rs.getString(4);
                        Integer precision = intOrNull(rs, 5);
                        Integer scale = intOrNull(rs, 6);
                        OracleTypes.Type type = OracleTypes.fromCatalog(dataType, precision, scale);
                        OracleTypes.Mapped m = OracleTypes.map(type);
                        Integer chars = intOrNull(rs, 8);
                        String described = describe(dataType, rs.getString(11), precision, scale, chars,
                            rs.getString(9), intOrNull(rs, 10));
                        int size = m.kind() == JdbcColumn.Kind.DECIMAL ? m.precision() : chars == null ? 0 : chars;
                        String comment = rs.getString(12);
                        columns.computeIfAbsent(table, k -> new ArrayList<>()).add(new JdbcCatalog.Column(
                            rs.getString(2), rs.getInt(3), described, 0, size, m.scale(), !"N".equals(rs.getString(7)),
                            comment == null || comment.isBlank() ? null : comment, m.kind()));
                    }
                }
            }
            Map<String, Map<String, List<String>>> keys = new TreeMap<>();
            Map<String, String> primaryNames = new TreeMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT c.TABLE_NAME, c.CONSTRAINT_NAME, c.CONSTRAINT_TYPE, "
                + "cc.COLUMN_NAME FROM ALL_CONSTRAINTS c JOIN ALL_CONS_COLUMNS cc ON cc.OWNER = c.OWNER "
                + "AND cc.CONSTRAINT_NAME = c.CONSTRAINT_NAME AND cc.TABLE_NAME = c.TABLE_NAME WHERE c.OWNER = ? "
                + "AND c.CONSTRAINT_TYPE IN ('P', 'U') AND c.STATUS = 'ENABLED' "
                + "ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME, cc.POSITION")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        String name = rs.getString(2);
                        if ("P".equals(rs.getString(3))) {
                            primaryNames.put(table, name);
                        }
                        keys.computeIfAbsent(table, k -> new LinkedHashMap<>()).computeIfAbsent(name,
                            k -> new ArrayList<>()).add(rs.getString(4));
                    }
                }
            }
            for (Listed t : found.values()) {
                Map<String, List<String>> unique = keys.getOrDefault(t.name(), Map.of());
                String primaryName = primaryNames.get(t.name());
                List<String> primary = primaryName == null ? List.of() : unique.getOrDefault(primaryName, List.of());
                Map<String, String> hints = new LinkedHashMap<>();
                if (t.rows() != null) {
                    hints.put("row_estimate", Long.toString(t.rows()));
                }
                String others = unique.entrySet().stream().filter(e -> !e.getKey().equals(primaryName))
                    .map(e -> e.getKey() + "(" + String.join(", ", e.getValue()) + ")").collect(Collectors.joining("; "));
                if (!others.isEmpty()) {
                    hints.put("unique_keys", others);
                }
                if (t.partitioned()) {
                    hints.put("partitioned", "true");
                }
                if (t.temporary()) {
                    hints.put("temporary", "true");
                }
                out.add(new JdbcCatalog.Table(schema, t.name(), t.kind(), t.comment(),
                    List.copyOf(columns.getOrDefault(t.name(), List.of())), List.copyOf(primary), hints));
            }
        }
        return out;
    }

    /** A type as people read it: {@code NUMBER(10,2)}, {@code VARCHAR2(40 CHAR)}, {@code MDSYS.SDO_GEOMETRY}. */
    static String describe(String dataType, String owner, Integer precision, Integer scale, Integer chars,
                           String charUsed, Integer length) {
        String base = dataType == null ? "" : dataType;
        String upper = base.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("NUMBER")) {
            if (precision == null) {
                return scale == null ? "NUMBER" : "NUMBER(*," + scale + ")";
            }
            return "NUMBER(" + precision + (scale == null || scale == 0 ? "" : "," + scale) + ")";
        }
        if (upper.equals("FLOAT") && precision != null) {
            return "FLOAT(" + precision + ")";
        }
        if (Set.of("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR").contains(upper) && chars != null) {
            return base + "(" + chars + ("B".equals(charUsed) ? " BYTE" : upper.startsWith("N") ? "" : " CHAR") + ")";
        }
        if (Set.of("RAW", "UROWID").contains(upper) && length != null) {
            return base + "(" + length + ")";
        }
        if (owner != null && !owner.isBlank() && !owner.equals("SYS") && !owner.equals("PUBLIC")) {
            return owner + "." + base;
        }
        return base;
    }

    /**
     * The foreign keys declared on {@code tables}, one query per schema, columns in key order. Keys
     * to schemas not read are kept; the caller decides.
     */
    static List<JdbcCatalog.ForeignKey> foreignKeys(Connection c, List<JdbcCatalog.Table> tables) throws SQLException {
        Set<String> wanted = new java.util.HashSet<>();
        tables.stream().filter(t -> t.type().equals("table")).forEach(t -> wanted.add(t.schema() + '\u0000' + t.name()));
        Map<String, Key> keys = new LinkedHashMap<>();
        for (String schema : tables.stream().map(JdbcCatalog.Table::schema).distinct().toList()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT c.TABLE_NAME, c.CONSTRAINT_NAME, fc.COLUMN_NAME, "
                + "r.OWNER, r.TABLE_NAME, rc.COLUMN_NAME FROM ALL_CONSTRAINTS c "
                + "JOIN ALL_CONSTRAINTS r ON r.OWNER = c.R_OWNER AND r.CONSTRAINT_NAME = c.R_CONSTRAINT_NAME "
                + "JOIN ALL_CONS_COLUMNS fc ON fc.OWNER = c.OWNER AND fc.CONSTRAINT_NAME = c.CONSTRAINT_NAME "
                + "AND fc.TABLE_NAME = c.TABLE_NAME "
                + "JOIN ALL_CONS_COLUMNS rc ON rc.OWNER = r.OWNER AND rc.CONSTRAINT_NAME = r.CONSTRAINT_NAME "
                + "AND rc.TABLE_NAME = r.TABLE_NAME AND rc.POSITION = fc.POSITION "
                + "WHERE c.OWNER = ? AND c.CONSTRAINT_TYPE = 'R' ORDER BY c.TABLE_NAME, c.CONSTRAINT_NAME, fc.POSITION")) {
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String table = rs.getString(1);
                        if (!wanted.contains(schema + '\u0000' + table)) {
                            continue;
                        }
                        String name = rs.getString(2);
                        String id = schema + '\u0000' + table + '\u0000' + name;
                        Key k = keys.computeIfAbsent(id, x -> new Key(schema, table, name));
                        k.toSchema = rs.getString(4);
                        k.toTable = rs.getString(5);
                        k.from.add(rs.getString(3));
                        k.to.add(rs.getString(6));
                    }
                }
            }
        }
        List<JdbcCatalog.ForeignKey> out = new ArrayList<>();
        for (Key k : keys.values()) {
            out.add(new JdbcCatalog.ForeignKey(k.schema, k.table, List.copyOf(k.from), k.toSchema, k.toTable,
                List.copyOf(k.to), k.name));
        }
        return out;
    }

    /** A foreign key being gathered, column by column. */
    private static final class Key {
        final String schema;
        final String table;
        final String name;
        String toSchema;
        String toTable;
        final List<String> from = new ArrayList<>();
        final List<String> to = new ArrayList<>();

        Key(String schema, String table, String name) {
            this.schema = schema;
            this.table = table;
            this.name = name;
        }
    }

    static Integer intOrNull(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }
}
