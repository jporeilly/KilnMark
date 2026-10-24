package io.kilnmark.server.ai;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.scan.ScanFinished;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Asset embeddings for search by meaning (ADR 0016). After every scan, each asset's text
 * ({@link AssetText}) is embedded with the {@code embed} feature's profile, if one is set and the
 * source's AI rule allows it, and stored in pgvector with the profile and its dimensions. Assets
 * whose text, model and dimensions are unchanged are skipped. When the profile changes, everything
 * is embedded again in the background.
 *
 * <p>Vectors of every size share one table; each profile gets a partial HNSW index on
 * {@code embedding::vector(n)} ({@code halfvec(n)} from 2,001 to 4,000 dimensions; above that,
 * searches scan without an index), limited to rows of that profile and that size.
 *
 * <p>Notes written to a scan's run are seen by every viewer, so they never hold a model's address
 * or a server's reply; the details are in the usage records, for admins.
 */
@ApplicationScoped
public class Embeddings {

    private static final Logger LOG = Logger.getLogger(Embeddings.class);
    /** pg_advisory_lock key for a full re-embed, so only one process runs it at a time. */
    private static final long REEMBED_LOCK = 0x6b6d_6169_656dL;
    /** First half of the two-part advisory lock key for building one profile's index (the profile id is the second). */
    static final int INDEX_LOCK_CLASS = 0x6b6d_6978;
    /** Assets read from the catalogue at a time, so a large source never sits in memory at once. */
    static final int PAGE = 500;

    private final Db db;
    private final ModelProfiles profiles;
    private final AiGateway gateway;
    private final EgressPolicy egress;
    private final UsageLog usage;
    private final GraphStore store;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    /** Full passes finished by this process (for tests and the log). */
    private final AtomicInteger passes = new AtomicInteger();

    public Embeddings(Db db, ModelProfiles profiles, AiGateway gateway, EgressPolicy egress, UsageLog usage,
                      GraphStore store, KilnmarkConfig config, ObjectMapper json) {
        this.db = db;
        this.profiles = profiles;
        this.gateway = gateway;
        this.egress = egress;
        this.usage = usage;
        this.store = store;
        this.config = config;
        this.json = json;
    }

    /** What embedding one source did, and the note shown on its scan. */
    public record Outcome(int embedded, int unchanged, int failed, String note) {
    }

    /** After each scan: embed the source's assets and add a note to the scan's run. Never throws. */
    void onScanFinished(@Observes ScanFinished e) {
        if ("failed".equals(e.status())) {
            return; // nothing new was catalogued; the next good scan embeds
        }
        try {
            Optional<Records.Source> source = store.source(e.sourceName());
            if (source.isEmpty()) {
                return;
            }
            Outcome o = embedSource(source.get(), "scan " + e.runId(), e.cancelled());
            if (o.note() != null) {
                appendToRun(e.runId(), o.note());
            }
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Embedding after scan %d of %s failed", e.runId(), e.sourceName());
            appendToRun(e.runId(), "Search by meaning: embedding failed. An admin can see why in the server log.");
        }
    }

    /** Embeds a source's assets that are new or changed since they were last embedded. */
    public Outcome embedSource(Records.Source source, String caller, BooleanSupplier cancelled) {
        Optional<ModelProfile> routed = profiles.route(AiFeature.EMBED);
        if (routed.isEmpty()) {
            return new Outcome(0, 0, 0, "Search by meaning: not embedded, because no embedding model is set. "
                + "An admin can choose one under AI models; search still matches words.");
        }
        ModelProfile p = routed.get();
        Optional<String> refused = egress.refusal(p, source);
        if (refused.isPresent()) {
            int dropped = deleteForSource(p.id(), source.id());
            usage.record(new UsageLog.Entry(AiFeature.EMBED.id(), p, source, null, null, 0, "refused", refused.get(),
                caller, null, null));
            return new Outcome(0, 0, 0, "Search by meaning: not embedded. " + refused.get()
                + (dropped > 0 ? " Earlier embeddings of " + dropped + " assets were removed." : ""));
        }
        ensureIndex(p);
        int maxChars = AssetText.maxChars(p);
        int batchSize = Math.max(1, config.ai().embedBatchSize());
        int embedded = 0;
        int unchanged = 0;
        int seen = 0;
        String after = "";
        List<Pending> batch = new ArrayList<>();
        while (true) {
            List<Candidate> page = candidates(source, p, after);
            for (Candidate c : page) {
                seen++;
                String text = AssetText.of(source.name(), source.settings().get("description"), c.kind(),
                    c.container(), c.name(), c.description(), c.fields(), maxChars);
                byte[] hash = AssetText.hash(text, p);
                if (c.storedHash() != null && Arrays.equals(c.storedHash(), hash)) {
                    unchanged++;
                } else {
                    batch.add(new Pending(c.urn(), text, hash));
                }
                if (batch.size() >= batchSize) {
                    String stop = embedBatch(source, p, batch, caller, cancelled);
                    if (stop != null) {
                        return new Outcome(embedded, unchanged, 0, "Search by meaning: " + embedded
                            + " changed assets embedded, then it stopped: " + stop);
                    }
                    embedded += batch.size();
                    batch.clear();
                }
            }
            if (page.size() < PAGE) {
                break;
            }
            after = page.getLast().urn();
        }
        if (!batch.isEmpty()) {
            String stop = embedBatch(source, p, batch, caller, cancelled);
            if (stop != null) {
                return new Outcome(embedded, unchanged, 0, "Search by meaning: " + embedded
                    + " changed assets embedded, then it stopped: " + stop);
            }
            embedded += batch.size();
        }
        String note = "Search by meaning: " + embedded + (embedded == 1 ? " asset" : " assets") + " embedded with "
            + p.name() + (unchanged > 0 ? " (" + unchanged + " unchanged)" : "") + ".";
        return new Outcome(embedded, unchanged, seen - embedded - unchanged, note);
    }

    /**
     * Embeds and saves one batch, after reading the source and the embedding profile again, so a
     * change to either (the source's AI rule, the profile's address or size) applies at once.
     *
     * @return why embedding must stop (words safe for viewers), or null
     */
    private String embedBatch(Records.Source source, ModelProfile p, List<Pending> batch, String caller,
                              BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            return "the server is stopping; the rest are embedded at the next scan.";
        }
        Optional<Records.Source> fresh = store.source(source.name());
        Optional<ModelProfile> current = profiles.route(AiFeature.EMBED);
        if (fresh.isEmpty()) {
            return "the source was removed.";
        }
        if (current.isEmpty()) {
            return "search by meaning was turned off meanwhile.";
        }
        if (current.get().id() != p.id()) {
            return "another embedding model was chosen meanwhile; it embeds every source again.";
        }
        if (!sameVectors(p, current.get())) {
            return "the embedding model's address, model or size changed meanwhile; every source is embedded again.";
        }
        try {
            List<float[]> vectors = gateway.embedWith(current.get(), AiFeature.EMBED.id(), fresh.get(),
                batch.stream().map(Pending::text).toList(), caller, cancelled, AiGateway.Budget.BACKGROUND)
                .answer().vectors();
            if (!saveIfCurrent(p, batch.stream().map(Pending::urn).toList(),
                batch.stream().map(Pending::hash).toList(), vectors)) {
                return "the embedding model's address, model or size changed meanwhile; every source is embedded "
                    + "again.";
            }
            return null;
        } catch (AiException e) {
            return e.publicMessage();
        }
    }

    private record Pending(String urn, String text, byte[] hash) {
    }

    private record Candidate(String urn, String kind, String container, String name, String description,
                             List<AssetText.Field> fields, byte[] storedHash) {
    }

    /** One page of a source's assets, by URN after {@code after}, with what was last embedded for each. */
    private List<Candidate> candidates(Records.Source source, ModelProfile p, String after) {
        return db.tx(c -> {
            List<Candidate> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT a.urn, a.kind, a.container, a.name, a.description,
                       coalesce((SELECT json_agg(json_build_object('n', f.name, 't', f.source_type, 'd', f.description)
                                                 ORDER BY f.position)
                                 FROM kilnmark.field f WHERE f.asset_urn = a.urn), '[]')::text,
                       e.text_hash
                FROM kilnmark.asset a
                LEFT JOIN kilnmark.asset_embedding e ON e.asset_urn = a.urn AND e.profile_id = ?
                WHERE a.source_id = ? AND a.removed_at IS NULL AND a.urn > ?
                ORDER BY a.urn LIMIT ?""")) {
                ps.setLong(1, p.id());
                ps.setLong(2, source.id());
                ps.setString(3, after);
                ps.setInt(4, PAGE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Candidate(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), fields(rs.getString(6)), rs.getBytes(7)));
                    }
                }
            }
            return out;
        });
    }

    private List<AssetText.Field> fields(String text) {
        List<AssetText.Field> out = new ArrayList<>();
        try {
            for (JsonNode f : json.readTree(text)) {
                out.add(new AssetText.Field(f.path("n").asText(), f.path("t").asText(null), f.path("d").asText(null)));
            }
        } catch (java.io.IOException e) {
            LOG.debugf("Unreadable field list: %s", e.getMessage());
        }
        return out;
    }

    /**
     * Whether two versions of a profile make the same vectors: same provider, address, model and
     * size. A change to its timeout or concurrency does not matter.
     */
    static boolean sameVectors(ModelProfile a, ModelProfile b) {
        return a.provider() == b.provider() && java.util.Objects.equals(a.effectiveBaseUrl(), b.effectiveBaseUrl())
            && a.model().equals(b.model()) && java.util.Objects.equals(a.dimensions(), b.dimensions());
    }

    /**
     * Saves vectors, but only while the profile still makes the same vectors (provider, address,
     * model and size) as when they were made: the profile row is share-locked in the same
     * transaction, so such an edit cannot slip in between, and rows of an old size never land under
     * a profile. Other edits (timeout, concurrency) do not stop it.
     *
     * @return false (nothing saved) if the profile changed that way or was deleted
     */
    boolean saveIfCurrent(ModelProfile p, List<String> urns, List<byte[]> hashes, List<float[]> vectors) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT provider, coalesce(base_url, ''), model, dimensions "
                + "FROM kilnmark.model_profile WHERE id = ? FOR SHARE")) {
                ps.setLong(1, p.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getString(1).equals(p.provider().id())
                        || !rs.getString(2).equals(p.baseUrl() == null ? "" : p.baseUrl())
                        || !rs.getString(3).equals(p.model()) || rs.getInt(4) != p.dimensions()) {
                        return false;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.asset_embedding (profile_id, asset_urn, model, dimensions, text_hash, embedding)
                SELECT ?, ?, ?, ?, ?, ?::public.vector
                WHERE EXISTS (SELECT 1 FROM kilnmark.asset WHERE urn = ?)
                ON CONFLICT (profile_id, asset_urn) DO UPDATE SET model = EXCLUDED.model,
                    dimensions = EXCLUDED.dimensions, text_hash = EXCLUDED.text_hash, embedding = EXCLUDED.embedding,
                    embedded_at = now()""")) {
                for (int i = 0; i < urns.size(); i++) {
                    ps.setLong(1, p.id());
                    ps.setString(2, urns.get(i));
                    ps.setString(3, p.model());
                    ps.setInt(4, p.dimensions());
                    ps.setBytes(5, hashes.get(i));
                    ps.setString(6, literal(vectors.get(i)));
                    ps.setString(7, urns.get(i));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return true;
        });
    }

    /** pgvector's text form: [1.0,2.5,...]. */
    static String literal(float[] v) {
        StringBuilder b = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            float x = v[i];
            b.append(Float.isFinite(x) ? Float.toString(x) : "0");
        }
        return b.append(']').toString();
    }

    /** The cast that a profile's index is built on, and that searches must use to hit it. */
    static String cast(int dimensions) {
        return dimensions <= 2000 ? "public.vector(" + dimensions + ")" : "public.halfvec(" + dimensions + ")";
    }

    static String indexName(ModelProfile p) {
        return "asset_embedding_hnsw_p" + p.id() + "_d" + p.dimensions();
    }

    /**
     * Creates the profile's partial HNSW index if {@code pg_index} does not have a valid one, with
     * {@code CREATE INDEX CONCURRENTLY} outside any transaction, so scans and searches go on while it
     * builds. Building takes a per-profile advisory lock: while another process holds it (its build
     * shows as an invalid index until it finishes), this one skips. Only the holder drops an invalid
     * index (a build that failed half-way) and builds it again.
     */
    void ensureIndex(ModelProfile p) {
        if (p.dimensions() == null || p.dimensions() > 4000) {
            return;
        }
        String name = indexName(p);
        String type = p.dimensions() <= 2000 ? "vector" : "halfvec";
        // Only numbers go into these statements: the profile id and its validated dimensions.
        String create = "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON kilnmark.asset_embedding USING hnsw "
            + "((embedding::" + cast(p.dimensions()) + ") public." + type + "_cosine_ops) WHERE profile_id = " + p.id()
            + " AND dimensions = " + p.dimensions();
        try (Connection c = db.connection()) {
            c.setAutoCommit(true);
            if (Boolean.TRUE.equals(validIndex(c, name))) {
                return;
            }
            if (!indexLock(c, "pg_try_advisory_lock", p.id())) {
                return; // another process is building it; searches scan until it is ready
            }
            try {
                Boolean valid = validIndex(c, name);
                if (Boolean.TRUE.equals(valid)) {
                    return;
                }
                try (Statement s = c.createStatement()) {
                    if (Boolean.FALSE.equals(valid)) {
                        s.execute("DROP INDEX CONCURRENTLY IF EXISTS kilnmark." + name);
                    }
                    s.execute(create);
                }
            } finally {
                indexLock(c, "pg_advisory_unlock", p.id());
            }
        } catch (SQLException e) {
            LOG.warnf("Could not create the search index %s (searches still work, more slowly): %s", name,
                e.getMessage());
        }
    }

    private static boolean indexLock(Connection c, String function, long profileId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + function + "(?, ?)")) {
            ps.setInt(1, INDEX_LOCK_CLASS);
            ps.setInt(2, (int) profileId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Whether the index exists and is valid; null when it does not exist. */
    private static Boolean validIndex(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT i.indisvalid FROM pg_index i JOIN pg_class x "
            + "ON x.oid = i.indexrelid JOIN pg_namespace n ON n.oid = x.relnamespace "
            + "WHERE n.nspname = 'kilnmark' AND x.relname = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    /** Drops a profile's stored embeddings and indexes, after its model or dimensions changed or it was deleted. */
    public void forgetProfile(long profileId) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.asset_embedding WHERE profile_id = ?")) {
                ps.setLong(1, profileId);
                ps.executeUpdate();
            }
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT indexname FROM pg_indexes WHERE schemaname = "
                + "'kilnmark' AND tablename = 'asset_embedding' AND indexname LIKE ?")) {
                ps.setString(1, "asset\\_embedding\\_hnsw\\_p" + profileId + "\\_d%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            try (Statement s = c.createStatement()) {
                for (String n : names) {
                    if (n.matches("asset_embedding_hnsw_p\\d+_d\\d+")) {
                        s.execute("DROP INDEX IF EXISTS kilnmark." + n);
                    }
                }
            }
            return null;
        });
        gateway.forget(profileId);
    }

    private int deleteForSource(long profileId, long sourceId) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.asset_embedding e USING kilnmark.asset a "
                + "WHERE e.asset_urn = a.urn AND e.profile_id = ? AND a.source_id = ?")) {
                ps.setLong(1, profileId);
                ps.setLong(2, sourceId);
                return ps.executeUpdate();
            }
        });
    }

    private void appendToRun(long runId, String note) {
        try {
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run SET detail = "
                    + "CASE WHEN detail IS NULL OR detail = '' THEN ? "
                    + "ELSE detail || CASE WHEN detail ~ '[.!?]$' THEN ' ' ELSE '. ' END || ? END WHERE id = ?")) {
                    ps.setString(1, note);
                    ps.setString(2, note);
                    ps.setLong(3, runId);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warnf("Could not add the embedding note to run %d: %s", runId, e.getMessage());
        }
    }

    /** Full re-embed passes this process has finished. */
    public int passes() {
        return passes.get();
    }

    /** Whether a full re-embed is running, or waiting for another process's, in this process. */
    public boolean reembedding() {
        return running.get();
    }

    /**
     * Embeds every source again in the background, after the embedding profile changed. Requests
     * that arrive while one is running are not dropped: the run goes round once more when it ends.
     * Across processes, a PostgreSQL advisory lock makes a second process wait for the first, then
     * run (finding most assets unchanged).
     *
     * @return true if a run started now, false if the request was added to the one running
     */
    public boolean reembedAllInBackground(String caller) {
        again.set(true);
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("kilnmark-reembed").start(() -> {
            do {
                try {
                    passAll(caller);
                } finally {
                    running.set(false);
                }
            } while (again.get() && running.compareAndSet(false, true));
        });
        return true;
    }

    private void passAll(String caller) {
        try (Connection lock = db.connection()) {
            lock.setAutoCommit(true);
            try (PreparedStatement ps = lock.prepareStatement("SELECT pg_advisory_lock(?)")) {
                ps.setLong(1, REEMBED_LOCK);
                ps.execute(); // waits while another process embeds
            }
            try {
                while (again.getAndSet(false)) {
                    for (Records.Source s : store.sources()) {
                        Outcome o = embedSource(s, caller, () -> false);
                        LOG.infof("Embedding %s: %s", s.name(), o.note());
                    }
                    passes.incrementAndGet();
                }
            } finally {
                try (PreparedStatement ps = lock.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    ps.setLong(1, REEMBED_LOCK);
                    ps.execute();
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warnf(e, "Embedding every source failed");
        }
    }

    /** How far embedding has got for a profile: assets embedded out of those in the catalogue. */
    public record Status(String profile, long embedded, long assets, boolean running) {
    }

    public Optional<Status> status() {
        Optional<ModelProfile> p = profiles.route(AiFeature.EMBED);
        if (p.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT (SELECT count(*) FROM kilnmark.asset_embedding e JOIN kilnmark.asset a ON a.urn = e.asset_urn
                        WHERE e.profile_id = ? AND e.dimensions = ? AND a.removed_at IS NULL),
                       (SELECT count(*) FROM kilnmark.asset WHERE removed_at IS NULL)""")) {
                ps.setLong(1, p.get().id());
                ps.setInt(2, p.get().dimensions());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Status(p.get().name(), rs.getLong(1), rs.getLong(2), running.get());
                }
            }
        }));
    }
}
