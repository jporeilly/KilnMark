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
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.kilnmark.graph.store.DocumentRecords.Chunk;
import io.kilnmark.graph.store.DocumentRecords.Document;
import io.kilnmark.graph.store.DocumentRecords.Page;
import io.kilnmark.graph.store.DocumentRecords.Part;

/** {@link DocumentStore} on PostgreSQL (migration V7). */
public final class JdbcDocumentStore implements DocumentStore {

    private final DataSource db;

    public JdbcDocumentStore(DataSource db) {
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
            throw new JdbcGraphStore.StoreException(e.getMessage(), e);
        }
    }

    @Override
    public void setParent(String urn, String parentUrn) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.asset SET parent_urn = ? WHERE urn = ? AND parent_urn IS DISTINCT FROM ?")) {
                ps.setString(1, parentUrn);
                ps.setString(2, urn);
                ps.setString(3, parentUrn);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static final String PART_COLUMNS = "a.urn, a.kind, a.name, "
        + "coalesce(d.readable, a.kind <> 'document' AND a.description IS NULL), a.description, a.removed_at ";

    private static Part part(ResultSet rs) throws SQLException {
        Timestamp removed = rs.getTimestamp(6);
        return new Part(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getString(5),
            removed == null ? null : removed.toInstant());
    }

    @Override
    public Optional<Part> parent(String urn) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PART_COLUMNS + "FROM kilnmark.asset child "
                + "JOIN kilnmark.asset a ON a.urn = child.parent_urn "
                + "LEFT JOIN kilnmark.document d ON d.asset_urn = a.urn WHERE child.urn = ?")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(part(rs)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public List<Part> parts(String urn) {
        return tx(c -> {
            List<Part> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + PART_COLUMNS + "FROM kilnmark.asset a "
                + "LEFT JOIN kilnmark.document d ON d.asset_urn = a.urn WHERE a.parent_urn = ? "
                + "ORDER BY a.removed_at NULLS FIRST, a.urn")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(part(rs));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public void saveDocument(Document d, long runId) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.document (asset_urn, run_id, readable, problem, format, content_type, size_bytes,
                    pages, words, characters, language, title, author, created, modified, owner, tags, mail,
                    page_starts, page_labels, notes, truncated, text_kept, chunk_count, observed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (asset_urn) DO UPDATE SET run_id = EXCLUDED.run_id, readable = EXCLUDED.readable,
                    problem = EXCLUDED.problem, format = EXCLUDED.format, content_type = EXCLUDED.content_type,
                    size_bytes = EXCLUDED.size_bytes, pages = EXCLUDED.pages, words = EXCLUDED.words,
                    characters = EXCLUDED.characters, language = EXCLUDED.language, title = EXCLUDED.title,
                    author = EXCLUDED.author, created = EXCLUDED.created, modified = EXCLUDED.modified,
                    owner = EXCLUDED.owner, tags = EXCLUDED.tags, mail = EXCLUDED.mail,
                    page_starts = EXCLUDED.page_starts, page_labels = EXCLUDED.page_labels, notes = EXCLUDED.notes,
                    truncated = EXCLUDED.truncated, text_kept = EXCLUDED.text_kept,
                    chunk_count = EXCLUDED.chunk_count, observed_at = EXCLUDED.observed_at""")) {
                int i = 1;
                ps.setString(i++, d.urn());
                ps.setLong(i++, runId);
                ps.setBoolean(i++, d.readable());
                ps.setString(i++, d.problem());
                ps.setString(i++, d.format());
                ps.setString(i++, d.contentType());
                setLong(ps, i++, d.sizeBytes());
                setInt(ps, i++, d.pages());
                setLong(ps, i++, d.words());
                setLong(ps, i++, d.characters());
                ps.setString(i++, d.language());
                ps.setString(i++, d.title());
                ps.setString(i++, d.author());
                ps.setTimestamp(i++, d.created() == null ? null : Timestamp.from(d.created()));
                ps.setTimestamp(i++, d.modified() == null ? null : Timestamp.from(d.modified()));
                ps.setString(i++, d.owner());
                ps.setString(i++, FlatJson.write(d.tags()));
                ps.setString(i++, d.mail().isEmpty() ? null : FlatJson.write(d.mail()));
                ps.setArray(i++, c.createArrayOf("integer", d.pageList().stream().map(Page::start).toArray()));
                ps.setArray(i++, c.createArrayOf("text", d.pageList().stream().map(Page::label).toArray()));
                ps.setArray(i++, c.createArrayOf("text", d.notes().toArray()));
                ps.setBoolean(i++, d.truncated());
                ps.setBoolean(i++, d.textKept());
                ps.setInt(i++, d.chunkCount());
                ps.setTimestamp(i, Timestamp.from(d.observedAt()));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.asset SET last_scanned_at = ? WHERE urn = ?")) {
                ps.setTimestamp(1, Timestamp.from(d.observedAt()));
                ps.setString(2, d.urn());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    @Override
    public Optional<Document> document(String urn) {
        return tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT asset_urn, readable, problem, format, content_type, size_bytes, pages, words, characters,
                       language, title, author, created, modified, owner, tags::text, mail::text, page_starts,
                       page_labels, notes, truncated, text_kept, chunk_count, observed_at
                FROM kilnmark.document WHERE asset_urn = ?""")) {
                ps.setString(1, urn);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    List<Page> pages = new ArrayList<>();
                    Object[] starts = array(rs.getArray(18));
                    Object[] labels = array(rs.getArray(19));
                    for (int i = 0; i < starts.length; i++) {
                        pages.add(new Page(i + 1, ((Number) starts[i]).intValue(),
                            i < labels.length && labels[i] != null ? labels[i].toString() : null));
                    }
                    List<String> notes = new ArrayList<>();
                    for (Object n : array(rs.getArray(20))) {
                        notes.add(String.valueOf(n));
                    }
                    String mail = rs.getString(17);
                    return Optional.of(new Document(rs.getString(1), rs.getBoolean(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), longOrNull(rs, 6), intOrNull(rs, 7), longOrNull(rs, 8),
                        longOrNull(rs, 9), rs.getString(10), rs.getString(11), rs.getString(12),
                        instant(rs.getTimestamp(13)), instant(rs.getTimestamp(14)), rs.getString(15),
                        FlatJson.read(rs.getString(16)), mail == null ? null : FlatJson.read(mail), pages, notes,
                        rs.getBoolean(21), rs.getBoolean(22), rs.getInt(23), instant(rs.getTimestamp(24))));
                }
            }
        });
    }

    private static Object[] array(Array a) throws SQLException {
        return a == null ? new Object[0] : (Object[]) a.getArray();
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static Integer intOrNull(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Override
    public void replaceChunks(String urn, List<Chunk> chunks) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.document_chunk WHERE asset_urn = ?")) {
                ps.setString(1, urn);
                ps.executeUpdate();
            }
            if (chunks.isEmpty()) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.document_chunk "
                + "(asset_urn, seq, text, page, start_offset, end_offset) VALUES (?, ?, ?, ?, ?, ?)")) {
                int n = 0;
                for (Chunk k : chunks) {
                    ps.setString(1, urn);
                    ps.setInt(2, k.sequence());
                    ps.setString(3, k.text().replace("\0", ""));
                    setInt(ps, 4, k.page());
                    ps.setInt(5, k.start());
                    ps.setInt(6, k.end());
                    ps.addBatch();
                    if (++n % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public List<Chunk> chunks(String urn, int offset, int limit) {
        return tx(c -> {
            List<Chunk> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT seq, text, page, start_offset, end_offset "
                + "FROM kilnmark.document_chunk WHERE asset_urn = ? ORDER BY seq OFFSET ? LIMIT ?")) {
                ps.setString(1, urn);
                ps.setInt(2, Math.max(0, offset));
                ps.setInt(3, Math.max(0, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Chunk(rs.getInt(1), rs.getString(2), intOrNull(rs, 3), rs.getInt(4), rs.getInt(5)));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public List<Chunk> chunksBetween(String urn, int from, int to) {
        return tx(c -> {
            List<Chunk> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT seq, text, page, start_offset, end_offset "
                + "FROM kilnmark.document_chunk WHERE asset_urn = ? AND end_offset > ? AND start_offset < ? "
                + "ORDER BY seq")) {
                ps.setString(1, urn);
                ps.setInt(2, from);
                ps.setInt(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Chunk(rs.getInt(1), rs.getString(2), intOrNull(rs, 3), rs.getInt(4), rs.getInt(5)));
                    }
                }
            }
            return out;
        });
    }

    /** Notes about a document that could not be read again start with this. */
    public static final String KEPT_NOTE = "The last scan could not read it again";

    @Override
    public boolean keepStored(String urn, long runId, String note) {
        return tx(c -> {
            int n;
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.document SET notes = "
                + "ARRAY(SELECT x FROM unnest(notes) x WHERE x NOT LIKE ?) || ARRAY[?]::text[] WHERE asset_urn = ?")) {
                ps.setString(1, KEPT_NOTE + "%");
                ps.setString(2, note);
                ps.setString(3, urn);
                n = ps.executeUpdate();
            }
            if (n == 0) {
                return false;
            }
            keepParts(c, urn, runId, null);
            return true;
        });
    }

    @Override
    public void keepParts(String urn, long runId, String kind) {
        tx(c -> {
            keepParts(c, urn, runId, kind);
            return null;
        });
    }

    private static void keepParts(Connection c, String urn, long runId, String kind) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.asset SET last_seen_run = ? "
            + "WHERE parent_urn = ? AND removed_at IS NULL" + (kind == null ? "" : " AND kind = ?"))) {
            ps.setLong(1, runId);
            ps.setString(2, urn);
            if (kind != null) {
                ps.setString(3, kind);
            }
            ps.executeUpdate();
        }
    }

    @Override
    public void dropText(String urn) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.document_chunk WHERE asset_urn = ? "
                + "OR asset_urn IN (SELECT urn FROM kilnmark.asset WHERE parent_urn = ?)")) {
                ps.setString(1, urn);
                ps.setString(2, urn);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.document SET text_kept = false, "
                + "chunk_count = 0 WHERE asset_urn = ? OR asset_urn IN "
                + "(SELECT urn FROM kilnmark.asset WHERE parent_urn = ?)")) {
                ps.setString(1, urn);
                ps.setString(2, urn);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void addNote(String urn, String note) {
        tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.document SET notes = notes || ARRAY[?]::text[] WHERE asset_urn = ?")) {
                ps.setString(1, note);
                ps.setString(2, urn);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
