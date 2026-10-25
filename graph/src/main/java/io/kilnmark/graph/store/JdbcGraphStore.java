package io.kilnmark.graph.store;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import io.kilnmark.graph.store.Records.Asset;
import io.kilnmark.graph.store.Records.AssetDetail;
import io.kilnmark.graph.store.Records.Field;
import io.kilnmark.graph.store.Records.FieldDetail;
import io.kilnmark.graph.store.Records.NewSource;
import io.kilnmark.graph.store.Records.Run;
import io.kilnmark.graph.store.Records.SearchHit;
import io.kilnmark.graph.store.Records.Source;
import io.kilnmark.graph.store.Records.SourceChange;

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
        "s.id, s.name, s.kind, s.settings::text, s.secret IS NOT NULL, s.created_at, s.display_name";

    private static Source source(ResultSet rs) throws SQLException {
        return new Source(rs.getLong(1), rs.getString(2), rs.getString(3), FlatJson.read(rs.getString(4)),
            rs.getBoolean(5), instant(rs.getTimestamp(6)), rs.getString(7));
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
            "INSERT INTO kilnmark.data_source AS s (name, kind, settings, secret, display_name) "
                + "VALUES (?, ?, ?::jsonb, ?, coalesce(?, ?)) RETURNING " + SOURCE_COLUMNS)) {
            ps.setString(1, s.name());
            ps.setString(2, s.kind());
            ps.setString(3, FlatJson.write(s.settings()));
            ps.setBytes(4, s.secret());
            ps.setString(5, s.displayName());
            ps.setString(6, s.name());
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

    /** Source names as the database's check constraint allows them; safe to write into a Cypher string. */
    private static final Pattern SOURCE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");
    private static final String URN_PREFIX = "urn:kilnmark:";

    @Override
    public Optional<Source> updateSource(long id, SourceChange change) {
        return tx(c -> lockSource(c, id) == null ? Optional.<Source>empty() : Optional.of(writeSource(c, id, change)));
    }

    @Override
    public List<Source> saveSources(List<NewSource> creates, Map<Long, SourceChange> updates) {
        return tx(c -> {
            List<Source> out = new ArrayList<>();
            for (NewSource s : creates) {
                out.add(insertSource(c, s));
            }
            for (var e : updates.entrySet()) {
                if (lockSource(c, e.getKey()) == null) {
                    throw new IllegalStateException("Source " + e.getKey() + " was deleted meanwhile. Run the import "
                        + "again");
                }
                out.add(writeSource(c, e.getKey(), e.getValue()));
            }
            return out;
        });
    }

    /** Refuses a change that keeps the secret when the settings it depends on changed since they were read. */
    private static void checkScope(Connection c, long id, SourceChange change) throws SQLException {
        if (change.replaceSecret() || change.keepSecretIf().isEmpty()) {
            return;
        }
        Map<String, String> now;
        try (PreparedStatement ps = c.prepareStatement("SELECT settings::text FROM kilnmark.data_source WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                now = FlatJson.read(rs.getString(1));
            }
        }
        for (var e : change.keepSecretIf().entrySet()) {
            if (!java.util.Objects.equals(blank(e.getValue()), blank(now.get(e.getKey())))) {
                throw new SourceChangedException("Someone changed this source's " + e.getKey() + " while you were "
                    + "editing it. Open it again, check the settings, and save again");
            }
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Source writeSource(Connection c, long id, SourceChange change) throws SQLException {
        checkScope(c, id, change);
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.data_source AS s SET "
            + "display_name = coalesce(?, s.name), settings = ?::jsonb, "
            + "secret = CASE WHEN ? THEN ? ELSE s.secret END, updated_at = now() "
            + "WHERE s.id = ? RETURNING " + SOURCE_COLUMNS)) {
            ps.setString(1, change.displayName());
            ps.setString(2, FlatJson.write(change.settings()));
            ps.setBoolean(3, change.replaceSecret());
            ps.setBytes(4, change.secret());
            ps.setLong(5, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return source(rs);
            }
        }
    }

    /** Locks a source's row until the transaction ends, so no scan can be queued meanwhile; its name, or null. */
    private static String lockSource(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM kilnmark.data_source WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static boolean activeRun(Connection c, long sourceId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM kilnmark.run WHERE source_id = ? AND status IN ('queued', 'running')")) {
            ps.setLong(1, sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void requireName(String name) {
        if (!SOURCE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a source name: " + name);
        }
    }

    /**
     * Runs a Cypher statement on the catalogue graph (Apache AGE) inside the current transaction.
     * Callers only build statements from source names checked against {@link #SOURCE_NAME}.
     */
    private static void cypher(Connection c, String statement) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL search_path = ag_catalog, kilnmark, public");
            s.execute("SELECT * FROM ag_catalog.cypher('kilnmark_graph', $$ " + statement
                + " $$) AS (v ag_catalog.agtype)");
            s.execute("SET LOCAL search_path = kilnmark, public");
        }
    }

    @Override
    public Optional<Records.Deleted> deleteSource(long id) {
        return tx(c -> {
            String name = lockSource(c, id);
            if (name == null) {
                return Optional.empty();
            }
            requireName(name);
            if (activeRun(c, id) || !scanLock(c, id)) {
                throw new SourceBusyException("A scan or quality check of " + name + " is queued or running. Wait for it "
                    + "to finish, then delete the source.");
            }
            int assets = count(c, "SELECT count(*) FROM kilnmark.asset WHERE source_id = ?", id);
            int runs = count(c, "SELECT count(*) FROM kilnmark.run WHERE source_id = ?", id);
            cypher(c, "MATCH (n) WHERE n.source = '" + name + "' OR n.urn STARTS WITH '" + URN_PREFIX + name
                + ":' DETACH DELETE n");
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.job WHERE kind IN ('scan', 'quality') "
                + "AND (payload ->> 'sourceId')::bigint = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            // runs, assets, fields and profiles go with it (ON DELETE CASCADE)
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.data_source WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return Optional.of(new Records.Deleted(name, assets, runs));
        });
    }

    /**
     * Advisory lock class and key a worker holds while it scans a source (the server's
     * {@code JobQueue.lockSource}): taken here for the rest of the transaction, so a scan cannot
     * be running unseen while its source is deleted. False if a worker holds it.
     */
    static final int SOURCE_LOCK_CLASS = 0x4B53;

    private static boolean scanLock(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_xact_lock(?, ?)")) {
            ps.setInt(1, SOURCE_LOCK_CLASS);
            ps.setInt(2, (int) id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static int count(Connection c, String sql, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    @Override
    public Map<Long, Integer> assetCounts() {
        return tx(c -> {
            Map<Long, Integer> out = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT source_id, count(*) FROM kilnmark.asset GROUP BY source_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getLong(1), rs.getInt(2));
                }
            }
            return out;
        });
    }

    @Override
    public Map<Long, Map<String, Integer>> fileFormats() {
        return tx(c -> {
            Map<Long, Map<String, Integer>> out = new java.util.HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source_id, "
                + "coalesce(lower(substring(name from '\\.([^./]+)$')), '(none)') AS ext, count(*) "
                + "FROM kilnmark.asset WHERE kind IN ('file', 'document') AND removed_at IS NULL "
                + "GROUP BY source_id, ext");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getLong(1), k -> new java.util.TreeMap<>()).put(rs.getString(2), rs.getInt(3));
                }
            }
            return out;
        });
    }

    @Override
    public boolean hasActiveRun(long sourceId) {
        return tx(c -> activeRun(c, sourceId));
    }

    @Override
    public List<Records.RunHistory> runHistory(String sourceName, int limit) {
        return tx(c -> {
            List<Records.RunHistory> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + RUN_COLUMNS.replace("FROM kilnmark.run r ",
                ", j.attempts, j.last_error FROM kilnmark.run r ")
                + "LEFT JOIN kilnmark.job j ON j.kind = 'scan' AND (j.payload ->> 'runId')::bigint = r.id "
                + "WHERE s.name = ? AND r.kind = 'scan' ORDER BY r.id DESC LIMIT ?")) {
                ps.setString(1, sourceName);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int attempts = rs.getInt(12);
                        Integer a = rs.wasNull() ? null : attempts;
                        out.add(new Records.RunHistory(run(rs), a, rs.getString(13)));
                    }
                }
            }
            return out;
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
                // Scans only: a pipeline's write is recorded as a finished run of kind 'pipeline' (V12).
                "SELECT " + RUN_COLUMNS + "WHERE s.name = ? AND r.kind = 'scan' ORDER BY r.id DESC LIMIT ?")) {
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
            int n;
            // Assets a pipeline wrote after this scan began were not there for it to see: keep them.
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.asset a SET removed_at = now() "
                + "WHERE a.source_id = ? AND a.removed_at IS NULL AND a.last_seen_run IS DISTINCT FROM ? "
                + "AND NOT EXISTS (SELECT 1 FROM kilnmark.run w, kilnmark.run scan WHERE w.id = a.last_seen_run "
                + "AND w.kind = 'pipeline' AND scan.id = ? "
                + "AND w.started_at >= coalesce(scan.started_at, scan.requested_at))")) {
                ps.setLong(1, sourceId);
                ps.setLong(2, runId);
                ps.setLong(3, runId);
                n = ps.executeUpdate();
            }
            // the text of a document no longer in its source is not kept (V7)
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.document_chunk c USING kilnmark.asset a "
                + "WHERE a.urn = c.asset_urn AND a.source_id = ? AND a.removed_at IS NOT NULL")) {
                ps.setLong(1, sourceId);
                ps.executeUpdate();
            }
            return n;
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
     * below asset-name matches; exact names come first.
     */
    @Override
    public List<SearchHit> search(String query, int limit) {
        return search(query, limit, List.of());
    }

    /**
     * The source filter is applied in every candidate branch, before any limit, so a small source is
     * not crowded out by others.
     */
    @Override
    public List<SearchHit> search(String query, int limit, List<String> sources) {
        return search(query, limit, sources, true);
    }

    @Override
    public List<SearchHit> search(String query, int limit, List<String> sources, boolean everyText) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            return List.of();
        }
        return tx(c -> {
            List<SearchHit> out = new ArrayList<>();
            try (PreparedStatement ps = prepareKeywordSearch(c, "", q, limit, sources, everyText)) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long rows = rs.getLong(7);
                        Long rowCount = rs.wasNull() ? null : rows;
                        out.add(new SearchHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rowCount, strings(rs.getArray(8)),
                            Math.round(rs.getDouble(9) * 1000) / 1000.0, rs.getBoolean(10)
                            ? new DocumentRecords.Snippet("", List.of()) : null));
                    }
                }
            }
            return snippets(c, q, out);
        });
    }

    /**
     * Documents whose text is ranked for one search, at most: those with the most matching chunks.
     * A document is missed only when this many others match the words more often.
     */
    static final int TEXT_CANDIDATES = 200;
    /** Assets gathered by each kind of name match (at least; more when more results are asked for). */
    static final int NAME_CANDIDATES = 100;
    /** Assets gathered whose words (name, schema, description) hold every word searched for. */
    static final int WORD_CANDIDATES = 200;
    /** Fields gathered whose names are alike enough. */
    static final int FIELD_CANDIDATES = 300;
    /** Matching document chunks looked at, at most: documents are ranked from these alone. */
    static final int CHUNK_SAMPLE = 2000;
    /** A document's matching chunks, in order, from which its snippet's chunk is chosen. */
    static final int SNIPPET_CHUNKS = 50;
    /**
     * Assets whose text is withheld from all but admins: those with a column marked sensitive and
     * every asset holding them, up the whole chain (a sheet's workbook, that workbook's mail).
     */
    public static final String WITHHELD_TEXT = """
        WITH RECURSIVE up(urn) AS (
            SELECT asset_urn FROM kilnmark.sensitive_column
            UNION
            SELECT a.parent_urn FROM kilnmark.asset a JOIN up ON a.urn = up.urn WHERE a.parent_urn IS NOT NULL)
        SELECT urn FROM up""";

    /**
     * Each branch that takes some of many equal matches first takes up to this many times as many,
     * in whatever order the index gives them, then the first by URN of those: bounded first, then
     * ordered, so a word whose matches all sit at the end of URN order cannot walk the table.
     */
    static final int POOL = 4;
    /** How alike a field name must be (word similarity) to match. */
    static final double FIELD_SIMILARITY = 0.5;

    /**
     * The keyword search, ready to run on {@code c} (in a transaction): {@code prefix} goes before
     * the query, so the container tests can {@code EXPLAIN} exactly what runs.
     *
     * <p>Bounded however common the words: candidates are gathered first, a fixed number at most
     * and each through an index (exact names; the names nearest the query in a GiST trigram index's
     * own distance order, by word and by whole name; names and descriptions holding every word;
     * field names alike enough; a sample of matching document chunks), and only those are scored,
     * as before: the work grows with the candidates and their columns, not with the catalogue. A
     * word in every asset, field or chunk ("id", "the") costs about what a rare one does.
     *
     * <p>Where a fixed number is taken from more matches that score alike, it is the first by URN
     * (and field name, or chunk number), so the same search always gathers the same candidates:
     * words and descriptions all score 0.6; fields are scored again with the asset; the chunk
     * sample is the first matching chunks of documents in URN order. The searched words are
     * written into each branch (not read from the {@code q} row) so the planner, planning afresh
     * for every search, sees how common they are and picks an index or an ordered scan to match.
     * Package-private: the container tests reach it to {@code EXPLAIN} exactly what runs.
     */
    static PreparedStatement prepareKeywordSearch(Connection c, String prefix, String q, int limit,
                                                  List<String> sources) throws SQLException {
        return prepareKeywordSearch(c, prefix, q, limit, sources, true);
    }

    /** With {@code everyText} false, withheld documents' text is not matched (see {@link GraphStore#search}). */
    static PreparedStatement prepareKeywordSearch(Connection c, String prefix, String q, int limit,
                                                  List<String> sources, boolean everyText) throws SQLException {
        // Kept to these sources (none means all) inside every branch, their ids written in so each branch's plan
        // stays as simple as without (ids are numbers read from the database, never text from the caller).
        String inSources = "";
        if (!sources.isEmpty()) {
            List<String> ids = new ArrayList<>();
            try (PreparedStatement st = c.prepareStatement("SELECT id FROM kilnmark.data_source WHERE name = ANY (?)")) {
                st.setArray(1, c.createArrayOf("text", sources.toArray()));
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        ids.add(Long.toString(rs.getLong(1)));
                    }
                }
            }
            inSources = ids.isEmpty() ? "AND false" : "AND a.source_id IN (" + String.join(", ", ids) + ")";
        }
        String urnInSources = inSources.isEmpty() || inSources.equals("AND false") ? inSources
            : "AND %s IN (SELECT a.urn FROM kilnmark.asset a WHERE true " + inSources + ")";
        String chunkScope = String.format(java.util.Locale.ROOT, urnInSources, "c.asset_urn")
            + (everyText ? "" : " AND c.asset_urn NOT IN (" + WITHHELD_TEXT + ")");
        try (PreparedStatement st = c.prepareStatement("""
                SELECT set_config('pg_trgm.word_similarity_threshold', ?, true),
                       set_config('plan_cache_mode', 'force_custom_plan', true),
                       set_config('enable_seqscan', 'off', true)""")) {
            // the threshold of the <% operator below: fields whose word similarity is at least this;
            // planned for the words given each time, as a plan cached for any words could scan every
            // field for a rare one to find the few a common one finds at once; and matches found
            // through the indexes only, as a scan that stops at its limit walks the whole table when
            // the matches sit at its end (the nearest-name branches still use their GiST order)
            st.setString(1, Double.toString(FIELD_SIMILARITY));
            st.executeQuery().close();
        }
        PreparedStatement ps = c.prepareStatement(prefix + String.format(java.util.Locale.ROOT, """
            WITH q AS (SELECT ?::text AS t, plainto_tsquery('simple', ?) AS ts, ? AS like_t),
            candidates AS (
                (SELECT a.urn FROM kilnmark.asset a, q
                 WHERE (lower(a.name) = q.t OR lower(a.container || '.' || a.name) = q.t) AND a.removed_at IS NULL %6$s
                 LIMIT ?)
                UNION
                (SELECT a.urn FROM kilnmark.asset a WHERE a.removed_at IS NULL %6$s
                 ORDER BY ?::text OPERATOR(public.<<->) a.name LIMIT ?)
                UNION
                (SELECT a.urn FROM kilnmark.asset a WHERE a.removed_at IS NULL %6$s
                 ORDER BY ?::text OPERATOR(public.<->) a.name LIMIT ?)
                UNION
                (SELECT a.urn FROM kilnmark.asset a WHERE strpos(?::text, '.') > 0 AND a.removed_at IS NULL %6$s
                 ORDER BY ?::text OPERATOR(public.<<->) (a.container || '.' || a.name) LIMIT ?)
                UNION
                (SELECT w.urn FROM (SELECT a.urn FROM kilnmark.asset a
                                     WHERE a.search @@ plainto_tsquery('simple', ?) AND a.removed_at IS NULL %6$s
                                     LIMIT %9$d) w
                 ORDER BY w.urn LIMIT %1$d)
                UNION
                (SELECT f.asset_urn FROM (SELECT f.asset_urn, f.name FROM kilnmark.field f
                                          WHERE ?::text OPERATOR(public.<%%) f.name %7$s LIMIT %10$d) f
                 ORDER BY f.asset_urn, f.name LIMIT %2$d)
            ),
            chunks AS (
                SELECT c.asset_urn, ts_rank_cd(c.search, q.ts) AS rank
                FROM (SELECT c.asset_urn, c.search FROM (SELECT c.asset_urn, c.seq, c.search FROM kilnmark.document_chunk c
                                                         WHERE c.search @@ plainto_tsquery('simple', ?) %8$s
                                                         LIMIT %11$d) c
                      ORDER BY c.asset_urn, c.seq LIMIT %3$d) c, q
            ),
            texts AS (
                -- documents whose text has every word: below name and description matches (0.41 to 0.5),
                -- those with the most matching chunks in the sample first; ties by name, never by age
                SELECT c.asset_urn, 0.41 + 0.09 * (max(c.rank) / (max(c.rank) + 1)) AS score
                FROM chunks c GROUP BY c.asset_urn ORDER BY count(*) DESC, c.asset_urn LIMIT %4$d
            ),
            scored AS (
                SELECT a.urn, a.source_id, a.kind, a.container, a.name, a.description, a.row_count,
                       lower(a.name) = q.t OR lower(a.container || '.' || a.name) = q.t AS exact,
                       greatest(public.word_similarity(q.t, lower(a.name)),
                                public.word_similarity(q.t, lower(a.container || '.' || a.name)),
                                CASE WHEN a.search @@ q.ts THEN 0.6 ELSE 0 END) AS asset_score,
                       fs.names, fs.best
                FROM (SELECT urn FROM candidates UNION SELECT asset_urn FROM texts) x
                JOIN kilnmark.asset a ON a.urn = x.urn AND a.removed_at IS NULL
                CROSS JOIN q
                CROSS JOIN LATERAL (
                    SELECT coalesce(array_agg(f.name ORDER BY f.score DESC), '{}') AS names, max(f.score) AS best
                    FROM (SELECT f.name, public.word_similarity(q.t, lower(f.name)) AS score
                          FROM kilnmark.field f WHERE f.asset_urn = a.urn) f
                    WHERE f.score >= %5$s OR lower(f.name) LIKE q.like_t) fs
            )
            SELECT h.urn, s.name, h.kind, h.container, h.name, h.description, h.row_count, h.names,
                   greatest(h.asset_score, coalesce(h.best * 0.9, 0), coalesce(t.score, 0)) AS score,
                   t.asset_urn IS NOT NULL
            FROM scored h
            JOIN kilnmark.data_source s ON s.id = h.source_id
            LEFT JOIN texts t ON t.asset_urn = h.urn
            CROSS JOIN q
            WHERE greatest(h.asset_score, coalesce(h.best * 0.9, 0), coalesce(t.score, 0)) >= 0.4
            ORDER BY h.exact DESC, score DESC, h.asset_score DESC, public.similarity(q.t, h.name) DESC,
                     h.container, h.name
            LIMIT ?""", WORD_CANDIDATES, FIELD_CANDIDATES, CHUNK_SAMPLE, TEXT_CANDIDATES, FIELD_SIMILARITY, inSources,
            String.format(java.util.Locale.ROOT, urnInSources, "f.asset_urn"),
            chunkScope, WORD_CANDIDATES * POOL,
            FIELD_CANDIDATES * POOL, CHUNK_SAMPLE * POOL));
        int names = Math.max(limit, NAME_CANDIDATES);
        int i = 0;
        ps.setString(++i, q);
        ps.setString(++i, q);
        ps.setString(++i, "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        ps.setInt(++i, names);
        ps.setString(++i, q);
        ps.setInt(++i, names);
        ps.setString(++i, q);
        ps.setInt(++i, names);
        ps.setString(++i, q);
        ps.setString(++i, q);
        ps.setInt(++i, names);
        ps.setString(++i, q); // words and descriptions
        ps.setString(++i, q); // field names
        ps.setString(++i, q); // document chunks
        ps.setInt(++i, limit);
        return ps;
    }

    /** Fills in the snippets of hits whose text matched: a passage of each one's best chunk. */
    private static List<SearchHit> snippets(Connection c, String q, List<SearchHit> hits) throws SQLException {
        List<String> urns = hits.stream().filter(h -> h.snippet() != null).map(SearchHit::urn).toList();
        if (urns.isEmpty()) {
            return hits;
        }
        java.util.Map<String, DocumentRecords.Snippet> found = new java.util.HashMap<>();
        // the best of each document's first matching chunks, then a headline for that one chunk only
        try (PreparedStatement ps = c.prepareStatement("""
            WITH q AS (SELECT plainto_tsquery('simple', ?) AS ts)
            SELECT u.urn,
                   ts_headline('simple', b.text, q.ts,
                               'StartSel=' || chr(57344) || ', StopSel=' || chr(57345)
                               || ', MaxWords=30, MinWords=12, ShortWord=2, MaxFragments=1')
            FROM unnest(?::text[]) u(urn), q
            CROSS JOIN LATERAL (
                SELECT c.text FROM (SELECT c.text, c.search, c.seq FROM kilnmark.document_chunk c
                                    WHERE c.asset_urn = u.urn AND c.search @@ q.ts ORDER BY c.seq LIMIT %d) c
                ORDER BY ts_rank_cd(c.search, q.ts) DESC, c.seq LIMIT 1) b""".formatted(SNIPPET_CHUNKS))) { // ints only
            ps.setString(1, q);
            ps.setArray(2, c.createArrayOf("text", urns.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    found.put(rs.getString(1), DocumentRecords.snippet(rs.getString(2)));
                }
            }
        }
        List<SearchHit> out = new ArrayList<>();
        for (SearchHit h : hits) {
            out.add(h.snippet() == null ? h : new SearchHit(h.urn(), h.source(), h.kind(), h.container(), h.name(),
                h.description(), h.rowCount(), h.matchedFields(), h.score(), found.get(h.urn())));
        }
        return out;
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
