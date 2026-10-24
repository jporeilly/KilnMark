package io.kilnmark.graph.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

/** {@link RelationshipStore} on PostgreSQL, in the {@code kilnmark} schema (migration V8). */
public final class JdbcRelationshipStore implements RelationshipStore {

    private final DataSource db;
    private final long statementTimeoutMs;

    /** With statements limited to five minutes. */
    public JdbcRelationshipStore(DataSource db) {
        this(db, java.time.Duration.ofMinutes(5));
    }

    /** {@code statementTimeout}: the longest any one statement of this store may run. */
    public JdbcRelationshipStore(DataSource db, java.time.Duration statementTimeout) {
        this.db = db;
        this.statementTimeoutMs = Math.max(1, statementTimeout.toMillis());
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
                // Bounds every statement, including the refresh's work under the relationship lock.
                try (java.sql.Statement st = c.createStatement()) {
                    st.execute("SET LOCAL statement_timeout = " + statementTimeoutMs);
                }
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
            throw new JdbcGraphStore.StoreException(e.getMessage(), e);
        }
    }

    @Override
    public int saveDeclared(long sourceId, List<Declared> unsorted) {
        // Rows in one order everywhere, so concurrent writers wait rather than deadlock.
        List<Declared> keys = unsorted.stream().sorted(java.util.Comparator.comparing(Declared::fromUrn)
            .thenComparing(k -> String.join("\u0000", k.fromFields())).thenComparing(Declared::toUrn)
            .thenComparing(k -> String.join("\u0000", k.toFields()))).toList();
        return tx(c -> {
            List<Long> kept = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.relationship AS r (from_urn, from_fields, to_urn, to_fields, kind, status, name)
                SELECT ?, ?, ?, ?, 'declared', 'accepted', ?
                WHERE EXISTS (SELECT 1 FROM kilnmark.asset WHERE urn = ?)
                  AND EXISTS (SELECT 1 FROM kilnmark.asset WHERE urn = ?)
                ON CONFLICT (from_urn, from_fields, to_urn, to_fields) DO UPDATE
                SET kind = 'declared', status = 'accepted', name = EXCLUDED.name, confidence = NULL,
                    evidence = '{}', refreshed_at = now()
                RETURNING r.id""")) {
                for (Declared k : keys) {
                    ps.setString(1, k.fromUrn());
                    ps.setArray(2, c.createArrayOf("text", k.fromFields().toArray()));
                    ps.setString(3, k.toUrn());
                    ps.setArray(4, c.createArrayOf("text", k.toFields().toArray()));
                    ps.setString(5, k.name());
                    ps.setString(6, k.fromUrn());
                    ps.setString(7, k.toUrn());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            kept.add(rs.getLong(1));
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM kilnmark.relationship r USING kilnmark.asset a
                WHERE a.urn = r.from_urn AND a.source_id = ? AND r.kind = 'declared' AND NOT (r.id = ANY (?))""")) {
                ps.setLong(1, sourceId);
                ps.setArray(2, c.createArrayOf("bigint", kept.toArray()));
                ps.executeUpdate();
            }
            return kept.size();
        });
    }

    @Override
    public void saveSketch(String urn, String field, long runId, byte[] sketch) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.profile SET hll = ? WHERE asset_urn = ? AND field_name = ? AND run_id = ?")) {
                ps.setBytes(1, sketch);
                ps.setString(2, urn);
                ps.setString(3, field);
                ps.setLong(4, runId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.profile SET hll = NULL "
                + "WHERE asset_urn = ? AND field_name = ? AND run_id <> ? AND hll IS NOT NULL")) {
                ps.setString(1, urn);
                ps.setString(2, field);
                ps.setLong(3, runId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<KeyColumn> keyColumns(long firstSourceId, int limit) {
        return tx(c -> {
            List<KeyColumn> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.urn, s.name, a.name, f.name, f.kind,
                       cardinality(a.primary_key) = 1 AND a.primary_key[1] = f.name,
                       p.stats IS NOT NULL,
                       coalesce((p.stats ->> 'rows')::bigint, 0), coalesce((p.stats ->> 'nulls')::bigint, 0),
                       coalesce((p.stats ->> 'distinct')::bigint, 0)
                FROM kilnmark.asset a
                JOIN kilnmark.data_source s ON s.id = a.source_id
                JOIN kilnmark.field f ON f.asset_urn = a.urn
                LEFT JOIN LATERAL (SELECT stats FROM kilnmark.profile p
                                   WHERE p.asset_urn = f.asset_urn AND p.field_name = f.name
                                   ORDER BY observed_at DESC LIMIT 1) p ON true
                WHERE a.removed_at IS NULL
                  AND f.kind IN ('int16', 'int32', 'int64', 'decimal', 'float32', 'float64', 'text')
                  AND (lower(regexp_replace(f.name, '[^[:alnum:]]', '', 'g')) ~ '(number|uuid|code|key|ref|num|id|no)$'
                       OR (cardinality(a.primary_key) = 1 AND a.primary_key[1] = f.name))
                ORDER BY (a.source_id = ?) DESC, a.urn, f.position
                LIMIT ?""")) {
                ps.setLong(1, firstSourceId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new KeyColumn(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getBoolean(6), rs.getBoolean(7), rs.getLong(8), rs.getLong(9),
                            rs.getLong(10)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public Map<FieldRef, byte[]> sketches(Collection<FieldRef> fields) {
        if (fields.isEmpty()) {
            return Map.of();
        }
        return tx(c -> {
            Map<FieldRef, byte[]> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT ON (p.asset_urn, p.field_name) p.asset_urn, p.field_name, p.hll
                FROM unnest(?::text[], ?::text[]) AS w(urn, field)
                JOIN kilnmark.profile p ON p.asset_urn = w.urn AND p.field_name = w.field
                WHERE p.hll IS NOT NULL
                ORDER BY p.asset_urn, p.field_name, p.observed_at DESC""")) {
                ps.setArray(1, c.createArrayOf("text", fields.stream().map(FieldRef::urn).toArray()));
                ps.setArray(2, c.createArrayOf("text", fields.stream().map(FieldRef::field).toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(new FieldRef(rs.getString(1), rs.getString(2)), rs.getBytes(3));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public Refresh refreshInferred(long sourceId, List<Inferred> unsorted) {
        // Rows in one order everywhere, so concurrent writers wait rather than deadlock.
        List<Inferred> found = unsorted.stream().sorted(java.util.Comparator.comparing(Inferred::fromUrn)
            .thenComparing(Inferred::fromField).thenComparing(Inferred::toUrn).thenComparing(Inferred::toField)).toList();
        return tx(c -> {
            int added = 0;
            List<Long> kept = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.relationship AS r (from_urn, from_fields, to_urn, to_fields, kind, status,
                                                        confidence, evidence)
                SELECT ?, ARRAY[?], ?, ARRAY[?], 'inferred', 'proposed', ?, ?::jsonb
                WHERE EXISTS (SELECT 1 FROM kilnmark.asset WHERE urn = ?)
                  AND EXISTS (SELECT 1 FROM kilnmark.asset WHERE urn = ?)
                ON CONFLICT (from_urn, from_fields, to_urn, to_fields) DO UPDATE
                SET confidence = EXCLUDED.confidence, evidence = EXCLUDED.evidence, refreshed_at = now()
                WHERE r.kind = 'inferred'
                RETURNING r.id, (xmax = 0)""")) {
                for (Inferred f : found) {
                    ps.setString(1, f.fromUrn());
                    ps.setString(2, f.fromField());
                    ps.setString(3, f.toUrn());
                    ps.setString(4, f.toField());
                    ps.setDouble(5, f.confidence());
                    ps.setString(6, f.evidenceJson());
                    ps.setString(7, f.fromUrn());
                    ps.setString(8, f.toUrn());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            kept.add(rs.getLong(1));
                            if (rs.getBoolean(2)) {
                                added++;
                            }
                        }
                    }
                }
            }
            int removed;
            try (PreparedStatement ps = c.prepareStatement("""
                DELETE FROM kilnmark.relationship r
                WHERE r.kind = 'inferred'
                  AND (EXISTS (SELECT 1 FROM kilnmark.asset a WHERE a.urn = r.from_urn AND a.source_id = ?)
                       OR EXISTS (SELECT 1 FROM kilnmark.asset a WHERE a.urn = r.to_urn AND a.source_id = ?))
                  AND ((r.status = 'proposed' AND NOT (r.id = ANY (?)))
                       OR NOT r.from_fields <@ (SELECT coalesce(array_agg(name), '{}') FROM kilnmark.field
                                                WHERE asset_urn = r.from_urn)
                       OR NOT r.to_fields <@ (SELECT coalesce(array_agg(name), '{}') FROM kilnmark.field
                                              WHERE asset_urn = r.to_urn))""")) {
                ps.setLong(1, sourceId);
                ps.setLong(2, sourceId);
                ps.setArray(3, c.createArrayOf("bigint", kept.toArray()));
                removed = ps.executeUpdate();
            }
            return new Refresh(kept.size(), added, removed);
        });
    }

    private static final String SELECT = """
        SELECT r.id, r.kind, r.status, r.name, r.confidence, r.evidence::text, r.decided_by, r.decided_at,
               r.found_at, r.refreshed_at,
               r.from_urn, fs.name, fa.container, fa.name, fa.kind, r.from_fields,
               r.to_urn, ts.name, ta.container, ta.name, ta.kind, r.to_fields
        FROM kilnmark.relationship r
        JOIN kilnmark.asset fa ON fa.urn = r.from_urn AND fa.removed_at IS NULL
        JOIN kilnmark.data_source fs ON fs.id = fa.source_id
        JOIN kilnmark.asset ta ON ta.urn = r.to_urn AND ta.removed_at IS NULL
        JOIN kilnmark.data_source ts ON ts.id = ta.source_id
        """;

    private static final String ORDER = """
         ORDER BY CASE r.status WHEN 'proposed' THEN 0 WHEN 'accepted' THEN 1 ELSE 2 END,
                  r.confidence DESC NULLS LAST, fa.container, fa.name, r.id""";

    private static Relationship relationship(ResultSet rs) throws SQLException {
        float conf = rs.getFloat(5);
        Double confidence = rs.wasNull() ? null : Math.round(conf * 1000) / 1000.0;
        return new Relationship(rs.getLong(1),
            new End(rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                strings(rs.getArray(16))),
            new End(rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getString(21),
                strings(rs.getArray(22))),
            rs.getString(2), rs.getString(3), rs.getString(4), confidence, rs.getString(6), rs.getString(7),
            instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9)), instant(rs.getTimestamp(10)));
    }

    private List<Relationship> query(String where, String tail, Binder binder) {
        return tx(c -> {
            List<Relationship> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + where + " " + ORDER + tail)) {
                binder.bind(c, ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(relationship(rs));
                    }
                }
            }
            return out;
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(Connection c, PreparedStatement ps) throws SQLException;
    }

    @Override
    public List<Relationship> among(Collection<String> urns, boolean touching, int limit) {
        if (urns.isEmpty()) {
            return List.of();
        }
        String op = touching ? " OR " : " AND ";
        return query("WHERE (r.from_urn = ANY (?)" + op + "r.to_urn = ANY (?))", " LIMIT " + Math.max(0, limit),
            (c, ps) -> {
                Array a = c.createArrayOf("text", urns.toArray());
                ps.setArray(1, a);
                ps.setArray(2, a);
            });
    }

    @Override
    public Page list(String asset, String source, String status, String kind, int offset, int limit) {
        String where = "WHERE (?::text IS NULL OR r.from_urn = ? OR r.to_urn = ?) "
            + "AND (?::text IS NULL OR fs.name = ? OR ts.name = ?) AND (?::text IS NULL OR r.status = ?) "
            + "AND (?::text IS NULL OR r.kind = ?)";
        Binder bind = (c, ps) -> {
            ps.setString(1, asset);
            ps.setString(2, asset);
            ps.setString(3, asset);
            ps.setString(4, source);
            ps.setString(5, source);
            ps.setString(6, source);
            ps.setString(7, status);
            ps.setString(8, status);
            ps.setString(9, kind);
            ps.setString(10, kind);
        };
        return tx(c -> {
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM (" + SELECT + where + ") x")) {
                bind.bind(c, ps);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    total = rs.getInt(1);
                }
            }
            List<Relationship> items = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + where + " " + ORDER + " LIMIT ? OFFSET ?")) {
                bind.bind(c, ps);
                ps.setInt(11, limit);
                ps.setInt(12, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        items.add(relationship(rs));
                    }
                }
            }
            return new Page(items, total);
        });
    }

    /** Key of the relationship lock (any constant, the same in every process): "kmrel" in ASCII. */
    static final long LOCK_KEY = 0x6b6d72656cL;

    @Override
    public <T> T serialised(java.util.function.Supplier<T> work, java.time.Duration wait) {
        // A session lock on a connection of its own, held while work runs its own transactions; it
        // is released when the work ends, or by PostgreSQL if this connection is lost.
        long deadline = System.nanoTime() + wait.toNanos();
        Connection c;
        try {
            c = db.getConnection();
        } catch (SQLException e) {
            throw new JdbcGraphStore.StoreException(e.getMessage(), e);
        }
        boolean reusable = true;
        try {
            while (!tryLock(c)) {
                if (System.nanoTime() >= deadline) {
                    throw new Busy("Another relationship refresh held the lock for more than " + wait.toSeconds()
                        + " seconds");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Busy("Interrupted while waiting for the relationship lock");
                }
            }
            try {
                return work.get();
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, LOCK_KEY);
                    ps.execute();
                } catch (SQLException e) {
                    // The lock may still be held by this session: never hand the connection back to the
                    // pool holding it. Aborting closes the physical connection, which releases the lock.
                    reusable = false;
                    abort(c);
                }
            }
        } catch (SQLException e) {
            reusable = false;
            abort(c);
            throw new JdbcGraphStore.StoreException(e.getMessage(), e);
        } finally {
            if (reusable) {
                try {
                    c.close();
                } catch (SQLException e) {
                    // closing a pooled connection that failed: nothing more to do
                }
            }
        }
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void abort(Connection c) {
        try {
            c.abort(Runnable::run);
        } catch (SQLException | RuntimeException e) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }

    @Override
    public Optional<Relationship> get(long id) {
        return query("WHERE r.id = ?", "", (c, ps) -> ps.setLong(1, id)).stream().findFirst();
    }

    @Override
    public Optional<Relationship> decide(long id, String status, String who) {
        if (!status.equals("accepted") && !status.equals("rejected")) {
            throw new IllegalArgumentException("status: accepted or rejected");
        }
        String kind = tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind FROM kilnmark.relationship WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    if (rs.getString(1).equals("declared")) {
                        return "declared";
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.relationship SET status = ?, "
                + "decided_by = ?, decided_at = now() WHERE id = ?")) {
                ps.setString(1, status);
                if (who == null) {
                    ps.setNull(2, Types.VARCHAR);
                } else {
                    ps.setString(2, who);
                }
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return "inferred";
        });
        if (kind == null) {
            return Optional.empty();
        }
        if (kind.equals("declared")) {
            throw new IllegalStateException("This relationship is declared by its source, so it cannot be changed here");
        }
        return get(id);
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
