package io.kilnmark.graph.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.kilnmark.graph.store.Records.Asset;
import io.kilnmark.graph.store.Records.AssetDetail;
import io.kilnmark.graph.store.Records.Field;
import io.kilnmark.graph.store.Records.FieldDetail;
import io.kilnmark.graph.store.Records.NewSource;
import io.kilnmark.graph.store.Records.Run;
import io.kilnmark.graph.store.Records.SearchHit;
import io.kilnmark.graph.store.Records.Source;

/** {@link GraphStore} on PostgreSQL, in the {@code kilnmark} schema (migration V2). */
public final class JdbcGraphStore implements GraphStore {

    private final DataSource db;

    public JdbcGraphStore(DataSource db) {
        this.db = db;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T tx(Work<T> work) {
        try (Connection c = db.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new StoreException(e.getMessage(), e);
        }
    }

    private static final String SOURCE_COLUMNS =
        "s.id, s.name, s.kind, s.settings::text, s.secret IS NOT NULL, s.created_at";

    private static Source source(ResultSet rs) throws SQLException {
        return new Source(rs.getLong(1), rs.getString(2), rs.getString(3), FlatJson.read(rs.getString(4)),
            rs.getBoolean(5), instant(rs.getTimestamp(6)));
    }

    @Override
    public Source addSource(NewSource s) {
        return tx(c -> insertSource(c, s));
    }

    @Override
    public List<Source> addSources(List<NewSource> sources) {
        return tx(c -> {
            List<Source> out = new ArrayList<>();
            for (NewSource s : sources) {
                out.add(insertSource(c, s));
            }
            return out;
        });
    }

    private static Source insertSource(Connection c, NewSource s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "INSERT INTO kilnmark.data_source AS s (name, kind, settings, secret) "
                + "VALUES (?, ?, ?::jsonb, ?) RETURNING " + SOURCE_COLUMNS)) {
            ps.setString(1, s.name());
            ps.setString(2, s.kind());
            ps.setString(3, FlatJson.write(s.settings()));
            ps.setBytes(4, s.secret());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return source(rs);
            }
        }
    }

    @Override
    public Optional<Source> source(String name) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + SOURCE_COLUMNS + " FROM kilnmark.data_source s WHERE s.name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(source(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Source> sources() {
        return tx(c -> {
            List<Source> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + SOURCE_COLUMNS + " FROM kilnmark.data_source s ORDER BY s.name");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(source(rs));
                }
            }
            return out;
        });
    }

    @Override
    public Optional<byte[]> sourceSecret(String name) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT secret FROM kilnmark.data_source WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.ofNullable(rs.getBytes(1)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public long queueRun(long sourceId, String kind) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kilnmark.run (source_id, kind, status) VALUES (?, ?, 'queued') RETURNING id")) {
                ps.setLong(1, sourceId);
                ps.setString(2, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    @Override
    public void startRun(long runId) {
        update("UPDATE kilnmark.run SET status = 'running', started_at = now() WHERE id = ?", runId);
    }

    @Override
    public void finishRun(long runId, String status, int assetsSeen, int assetsFailed, long rowsRead, String detail) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run SET status = ?, finished_at = now(), "
                + "assets_seen = ?, assets_failed = ?, rows_read = ?, detail = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setInt(2, assetsSeen);
                ps.setInt(3, assetsFailed);
                ps.setLong(4, rowsRead);
                ps.setString(5, detail);
                ps.setLong(6, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static final String RUN_COLUMNS = "r.id, s.name, r.kind, r.status, r.requested_at, r.started_at, "
        + "r.finished_at, r.assets_seen, r.assets_failed, r.rows_read, r.detail "
        + "FROM kilnmark.run r JOIN kilnmark.data_source s ON s.id = r.source_id ";

    private static Run run(ResultSet rs) throws SQLException {
        return new Run(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), instant(rs.getTimestamp(5)),
            instant(rs.getTimestamp(6)), instant(rs.getTimestamp(7)), rs.getInt(8), rs.getInt(9), rs.getLong(10),
            rs.getString(11));
    }

    @Override
    public Optional<Run> run(long runId) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS + "WHERE r.id = ?")) {
                ps.setLong(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(run(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Run> runs(String sourceName, int limit) {
        return tx(c -> {
            List<Run> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + RUN_COLUMNS + "WHERE s.name = ? ORDER BY r.id DESC LIMIT ?")) {
                ps.setString(1, sourceName);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(run(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void saveAsset(long sourceId, long runId, Asset a, List<Field> fields) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.asset (urn, source_id, kind, container, name, description, primary_key,
                                            last_seen_run, removed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                ON CONFLICT (urn) DO UPDATE SET kind = EXCLUDED.kind, description = EXCLUDED.description,
                    primary_key = EXCLUDED.primary_key, last_seen_run = EXCLUDED.last_seen_run, removed_at = NULL""")) {
                ps.setString(1, a.urn());
                ps.setLong(2, sourceId);
                ps.setString(3, a.kind());
                ps.setString(4, a.container());
                ps.setString(5, a.name());
                ps.setString(6, a.description());
                ps.setArray(7, c.createArrayOf("text", a.primaryKey().toArray()));
                ps.setLong(8, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM kilnmark.field WHERE asset_urn = ? AND NOT (name = ANY (?))")) {
                ps.setString(1, a.urn());
                ps.setArray(2, c.createArrayOf("text", fields.stream().map(Field::name).toArray()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.field (asset_urn, name, position, source_type, kind, nullable, description)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_urn, name) DO UPDATE SET position = EXCLUDED.position,
                    source_type = EXCLUDED.source_type, kind = EXCLUDED.kind, nullable = EXCLUDED.nullable,
                    description = EXCLUDED.description""")) {
                for (Field f : fields) {
                    ps.setString(1, a.urn());
                    ps.setString(2, f.name());
                    ps.setInt(3, f.position());
                    ps.setString(4, f.sourceType());
                    ps.setString(5, f.kind());
                    ps.setBoolean(6, f.nullable());
                    ps.setString(7, f.description());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public int markUnseenRemoved(long sourceId, long runId) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.asset SET removed_at = now() "
                + "WHERE source_id = ? AND removed_at IS NULL AND last_seen_run IS DISTINCT FROM ?")) {
                ps.setLong(1, sourceId);
                ps.setLong(2, runId);
                return ps.executeUpdate();
            }
        });
    }

    @Override
    public void saveRowCount(String urn, long rows, boolean complete, Instant at) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.asset SET row_count = ?, "
                + "row_count_complete = ?, last_scanned_at = ? WHERE urn = ?")) {
                ps.setLong(1, rows);
                ps.setBoolean(2, complete);
                ps.setTimestamp(3, Timestamp.from(at));
                ps.setString(4, urn);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void saveProfile(String urn, String field, long runId, String statsJson, Instant at) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.profile "
                + "(asset_urn, field_name, run_id, stats, observed_at) VALUES (?, ?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (asset_urn, field_name, run_id) DO UPDATE SET stats = EXCLUDED.stats, "
                + "observed_at = EXCLUDED.observed_at")) {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setLong(3, runId);
                ps.setString(4, statsJson);
                ps.setTimestamp(5, Timestamp.from(at));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Keyword search over asset names, descriptions and field names: full-text words plus trigram
     * similarity, so partial and misspelt names still match. Field-name matches rank a little
     * below asset-name matches.
     */
    @Override
    public List<SearchHit> search(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        return tx(c -> {
            List<SearchHit> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                WITH q AS (SELECT ?::text AS t, plainto_tsquery('simple', ?) AS ts, ? AS like_t),
                hits AS (
                    SELECT a.urn,
                           greatest(public.word_similarity(q.t, lower(a.name)),
                                    public.word_similarity(q.t, lower(a.container || '.' || a.name)),
                                    CASE WHEN a.search @@ q.ts THEN 0.6 ELSE 0 END) AS asset_score
                    FROM kilnmark.asset a, q
                    WHERE a.removed_at IS NULL
                ),
                fields AS (
                    SELECT f.asset_urn, f.name, public.word_similarity(q.t, lower(f.name)) AS score
                    FROM kilnmark.field f, q
                    WHERE lower(f.name) LIKE q.like_t OR public.word_similarity(q.t, lower(f.name)) >= 0.5
                )
                SELECT a.urn, s.name, a.kind, a.container, a.name, a.description, a.row_count,
                       coalesce(array_agg(f.name ORDER BY f.score DESC) FILTER (WHERE f.name IS NOT NULL), '{}'),
                       greatest(h.asset_score, coalesce(max(f.score) * 0.9, 0)) AS score
                FROM hits h
                JOIN kilnmark.asset a ON a.urn = h.urn
                JOIN kilnmark.data_source s ON s.id = a.source_id
                LEFT JOIN fields f ON f.asset_urn = a.urn
                GROUP BY a.urn, s.name, h.asset_score
                HAVING greatest(h.asset_score, coalesce(max(f.score) * 0.9, 0)) >= 0.4
                ORDER BY score DESC, h.asset_score DESC, a.container, a.name
                LIMIT ?""")) {
                ps.setString(1, q);
                ps.setString(2, q);
                ps.setString(3, "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                ps.setInt(4, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long rows = rs.getLong(7);
                        Long rowCount = rs.wasNull() ? null : rows;
                        out.add(new SearchHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rowCount, strings(rs.getArray(8)),
                            Math.round(rs.getDouble(9) * 1000) / 1000.0));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public Optional<AssetDetail> asset(String urn) {
        return tx(c -> {
            AssetDetail head;
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.urn, s.name, a.kind, a.container, a.name, a.description, a.primary_key, a.row_count,
                       a.row_count_complete, a.first_seen_at, a.last_scanned_at, a.removed_at
                FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id WHERE a.urn = ?""")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    long rows = rs.getLong(8);
                    Long rowCount = rs.wasNull() ? null : rows;
                    boolean complete = rs.getBoolean(9);
                    Boolean rowCountComplete = rs.wasNull() ? null : complete;
                    head = new AssetDetail(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), strings(rs.getArray(7)), rowCount, rowCountComplete,
                        instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), instant(rs.getTimestamp(12)),
                        List.of());
                }
            }
            List<FieldDetail> fields = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT f.name, f.position, f.source_type, f.kind, f.nullable, f.description, p.stats::text,
                       p.observed_at
                FROM kilnmark.field f
                LEFT JOIN LATERAL (SELECT stats, observed_at FROM kilnmark.profile p
                                   WHERE p.asset_urn = f.asset_urn AND p.field_name = f.name
                                   ORDER BY observed_at DESC LIMIT 1) p ON true
                WHERE f.asset_urn = ? ORDER BY f.position""")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        fields.add(new FieldDetail(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                            rs.getBoolean(5), rs.getString(6), rs.getString(7), instant(rs.getTimestamp(8))));
                    }
                }
            }
            return Optional.of(new AssetDetail(head.urn(), head.source(), head.kind(), head.container(), head.name(),
                head.description(), head.primaryKey(), head.rowCount(), head.rowCountComplete(), head.firstSeenAt(),
                head.lastScannedAt(), head.removedAt(), fields));
        });
    }

    private void update(String sql, long id) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        Object[] values = (Object[]) a.getArray();
        return Arrays.stream(values).map(String::valueOf).toList();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** A store operation failed. */
    public static final class StoreException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
