package io.kilnmark.graph.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/** {@link BrowseStore} on PostgreSQL, in the {@code kilnmark} schema. */
public final class JdbcBrowseStore implements BrowseStore {

    /** Kinds of asset that have no columns to draw. */
    private static final String NO_COLUMNS = "('document')";

    private final DataSource db;

    public JdbcBrowseStore(DataSource db) {
        this.db = db;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T read(Work<T> work) {
        try (Connection c = db.getConnection()) {
            return work.run(c);
        } catch (SQLException e) {
            throw new JdbcGraphStore.StoreException(e.getMessage(), e);
        }
    }

    /** {@code text} as a case-insensitive LIKE pattern matching it anywhere, or null for none. */
    static String contains(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return "%" + text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    @Override
    public List<SourceNode> sources() {
        // display_name arrives with source management (migration V5); read it through to_jsonb so
        // this works with or without that column.
        return read(c -> {
            List<SourceNode> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT s.name, coalesce(nullif(to_jsonb(s) ->> 'display_name', ''), s.name) AS label, s.kind,
                       count(DISTINCT a.container)::int, count(a.urn)::int
                FROM kilnmark.data_source s
                LEFT JOIN kilnmark.asset a ON a.source_id = s.id AND a.removed_at IS NULL
                GROUP BY s.id
                ORDER BY lower(coalesce(nullif(to_jsonb(s) ->> 'display_name', ''), s.name)), s.name""");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SourceNode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getInt(5)));
                }
            }
            return out;
        });
    }

    @Override
    public Page<ContainerNode> containers(String source, String filter, int offset, int limit) {
        String like = contains(filter);
        String matching = "FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id "
            + "WHERE s.name = ? AND a.removed_at IS NULL AND (?::text IS NULL OR a.container ILIKE ? OR a.name ILIKE ?)";
        return read(c -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT a.container, lower(a.container) " + matching
                + " ORDER BY lower(a.container), a.container LIMIT ? OFFSET ?")) {
                ps.setString(1, source);
                ps.setString(2, like);
                ps.setString(3, like);
                ps.setString(4, like);
                ps.setInt(5, limit);
                ps.setInt(6, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            int total = count(c, "SELECT count(DISTINCT a.container) " + matching, source, like, like, like);
            Map<String, Map<String, Integer>> kinds = new LinkedHashMap<>();
            names.forEach(n -> kinds.put(n, new LinkedHashMap<>()));
            if (!names.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("SELECT a.container, a.kind, count(*)::int " + matching
                    + " AND a.container = ANY (?) GROUP BY a.container, a.kind ORDER BY a.container, a.kind")) {
                    ps.setString(1, source);
                    ps.setString(2, like);
                    ps.setString(3, like);
                    ps.setString(4, like);
                    ps.setArray(5, c.createArrayOf("text", names.toArray()));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            kinds.get(rs.getString(1)).put(rs.getString(2), rs.getInt(3));
                        }
                    }
                }
            }
            List<ContainerNode> out = new ArrayList<>();
            kinds.forEach((name, k) -> out.add(new ContainerNode(name,
                k.values().stream().mapToInt(Integer::intValue).sum(), k)));
            return new Page<>(out, total, offset, limit);
        });
    }

    @Override
    public Page<AssetNode> assets(String source, String container, String filter, int offset, int limit) {
        String like = contains(filter);
        return read(c -> {
            List<AssetNode> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.urn, a.name, a.kind, a.container, a.row_count,
                       (SELECT count(*) FROM kilnmark.field f WHERE f.asset_urn = a.urn)::int, a.description
                FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id
                WHERE s.name = ? AND a.container = ? AND a.removed_at IS NULL
                  AND (?::text IS NULL OR a.name ILIKE ?)
                ORDER BY lower(a.name), a.name LIMIT ? OFFSET ?""")) {
                ps.setString(1, source);
                ps.setString(2, container);
                ps.setString(3, like);
                ps.setString(4, like);
                ps.setInt(5, limit);
                ps.setInt(6, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long rows = rs.getLong(5);
                        out.add(new AssetNode(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.wasNull() ? null : rows, rs.getInt(6), rs.getString(7)));
                    }
                }
            }
            int total = count(c, "SELECT count(*) FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id "
                + "WHERE s.name = ? AND a.container = ? AND a.removed_at IS NULL AND (?::text IS NULL OR a.name ILIKE ?)",
                source, container, like, like);
            return new Page<>(out, total, offset, limit);
        });
    }

    @Override
    public Page<Table> containerTables(String source, String container, String filter, int limit) {
        String like = contains(filter);
        return read(c -> {
            List<String> urns = new ArrayList<>();
            String where = "FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id "
                + "WHERE s.name = ? AND a.container = ? AND a.removed_at IS NULL AND a.kind NOT IN " + NO_COLUMNS
                + " AND (?::text IS NULL OR a.name ILIKE ?)";
            try (PreparedStatement ps = c.prepareStatement("SELECT a.urn " + where
                + " ORDER BY lower(a.name), a.name LIMIT ?")) {
                ps.setString(1, source);
                ps.setString(2, container);
                ps.setString(3, like);
                ps.setString(4, like);
                ps.setInt(5, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        urns.add(rs.getString(1));
                    }
                }
            }
            int total = count(c, "SELECT count(*) " + where, source, container, like, like);
            return new Page<>(tables(c, urns), total, 0, limit);
        });
    }

    @Override
    public List<Table> tables(Collection<String> urns) {
        if (urns.isEmpty()) {
            return List.of();
        }
        return read(c -> tables(c, List.copyOf(urns)));
    }

    private static List<Table> tables(Connection c, List<String> urns) throws SQLException {
        if (urns.isEmpty()) {
            return List.of();
        }
        Map<String, Table> heads = new HashMap<>();
        Array wanted = c.createArrayOf("text", urns.toArray());
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT a.urn, s.name, a.container, a.name, a.kind, a.row_count, a.primary_key
            FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id
            WHERE a.urn = ANY (?) AND a.removed_at IS NULL""")) {
            ps.setArray(1, wanted);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(6);
                    Long rowCount = rs.wasNull() ? null : rows;
                    heads.put(rs.getString(1), new Table(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rowCount, strings(rs.getArray(7)), List.of()));
                }
            }
        }
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement("""
            SELECT asset_urn, name, position, source_type, kind, nullable FROM kilnmark.field
            WHERE asset_urn = ANY (?) ORDER BY asset_urn, position""")) {
            ps.setArray(1, wanted);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(new Column(rs.getString(2),
                        rs.getInt(3), rs.getString(4), rs.getString(5), rs.getBoolean(6)));
                }
            }
        }
        List<Table> out = new ArrayList<>();
        for (String urn : urns) {
            Table t = heads.get(urn);
            if (t != null) {
                out.add(new Table(t.urn(), t.source(), t.container(), t.name(), t.kind(), t.rowCount(), t.primaryKey(),
                    columns.getOrDefault(urn, List.of())));
            }
        }
        return out;
    }

    private static int count(Connection c, String sql, String... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                ps.setString(i + 1, values[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }
}
