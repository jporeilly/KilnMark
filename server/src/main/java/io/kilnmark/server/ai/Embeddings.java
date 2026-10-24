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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
 * searches scan without an index).
 */
@ApplicationScoped
public class Embeddings {

    private static final Logger LOG = Logger.getLogger(Embeddings.class);
    /** pg_advisory_lock key for a full re-embed, so only one process runs it at a time. */
    private static final long REEMBED_LOCK = 0x6b6d_6169_656dL;

    private final Db db;
    private final ModelProfiles profiles;
    private final AiGateway gateway;
    private final EgressPolicy egress;
    private final UsageLog usage;
    private final GraphStore store;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final Set<String> indexes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean reembedding = new AtomicBoolean();

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
            appendToRun(e.runId(), "Search by meaning: embedding failed (" + ex.getMessage() + ").");
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
        List<Pending> todo = new ArrayList<>();
        int unchanged = 0;
        for (Candidate c : candidates(source, p)) {
            String text = AssetText.of(source.name(), source.settings().get("description"), c.kind(), c.container(),
                c.name(), c.description(), c.fields(), maxChars);
            byte[] hash = AssetText.hash(text, p);
            if (c.storedHash() != null && Arrays.equals(c.storedHash(), hash)) {
                unchanged++;
            } else {
                todo.add(new Pending(c.urn(), text, hash));
            }
        }
        int batch = Math.max(1, config.ai().embedBatchSize());
        int embedded = 0;
        for (int i = 0; i < todo.size(); i += batch) {
            if (cancelled.getAsBoolean()) {
                return new Outcome(embedded, unchanged, todo.size() - embedded, "Search by meaning: stopped after "
                    + embedded + " assets; the rest are embedded at the next scan.");
            }
            List<Pending> part = todo.subList(i, Math.min(todo.size(), i + batch));
            try {
                List<float[]> vectors = gateway.embedWith(p, AiFeature.EMBED.id(), source,
                    part.stream().map(Pending::text).toList(), caller, cancelled).answer().vectors();
                check(p, part.size(), vectors);
                save(p, part, vectors);
                embedded += part.size();
            } catch (AiException e) {
                return new Outcome(embedded, unchanged, todo.size() - embedded, "Search by meaning: "
                    + embedded + " of " + todo.size() + " changed assets embedded, then it stopped: " + e.getMessage());
            }
        }
        String note = "Search by meaning: " + embedded + (embedded == 1 ? " asset" : " assets") + " embedded with "
            + p.name() + (unchanged > 0 ? " (" + unchanged + " unchanged)" : "") + ".";
        return new Outcome(embedded, unchanged, 0, note);
    }

    private static void check(ModelProfile p, int expected, List<float[]> vectors) {
        if (vectors.size() != expected) {
            throw new AiException(AiException.Kind.FAILED, "the model returned " + vectors.size() + " embeddings for "
                + expected + " texts");
        }
        for (float[] v : vectors) {
            if (v.length != p.dimensions()) {
                throw new AiException(AiException.Kind.FAILED, "model " + p.model() + " returns " + v.length
                    + " numbers per text, but profile " + p.name() + " says " + p.dimensions()
                    + ". Set the profile's dimensions to " + v.length + ".");
            }
        }
    }

    private record Pending(String urn, String text, byte[] hash) {
    }

    private record Candidate(String urn, String kind, String container, String name, String description,
                             List<AssetText.Field> fields, byte[] storedHash) {
    }

    private List<Candidate> candidates(Records.Source source, ModelProfile p) {
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
                WHERE a.source_id = ? AND a.removed_at IS NULL
                ORDER BY a.urn""")) {
                ps.setLong(1, p.id());
                ps.setLong(2, source.id());
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

    private void save(ModelProfile p, List<Pending> part, List<float[]> vectors) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.asset_embedding (profile_id, asset_urn, model, dimensions, text_hash, embedding)
                VALUES (?, ?, ?, ?, ?, ?::public.vector)
                ON CONFLICT (profile_id, asset_urn) DO UPDATE SET model = EXCLUDED.model,
                    dimensions = EXCLUDED.dimensions, text_hash = EXCLUDED.text_hash, embedding = EXCLUDED.embedding,
                    embedded_at = now()""")) {
                for (int i = 0; i < part.size(); i++) {
                    ps.setLong(1, p.id());
                    ps.setString(2, part.get(i).urn());
                    ps.setString(3, p.model());
                    ps.setInt(4, p.dimensions());
                    ps.setBytes(5, part.get(i).hash());
                    ps.setString(6, literal(vectors.get(i)));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
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

    private static String indexName(ModelProfile p) {
        return "asset_embedding_hnsw_p" + p.id() + "_d" + p.dimensions();
    }

    /** Creates the profile's partial HNSW index if it is not there yet. */
    void ensureIndex(ModelProfile p) {
        String name = indexName(p);
        if (p.dimensions() == null || p.dimensions() > 4000 || indexes.contains(name)) {
            return;
        }
        String type = p.dimensions() <= 2000 ? "vector" : "halfvec";
        // Only numbers go into this statement: the profile id and its validated dimensions.
        String sql = "CREATE INDEX IF NOT EXISTS " + name + " ON kilnmark.asset_embedding USING hnsw ((embedding::"
            + cast(p.dimensions()) + ") public." + type + "_cosine_ops) WHERE profile_id = " + p.id();
        try (Connection c = db.connection(); Statement s = c.createStatement()) {
            s.execute(sql);
            indexes.add(name);
        } catch (SQLException e) {
            LOG.warnf("Could not create the search index %s (searches still work, more slowly): %s", name,
                e.getMessage());
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
        indexes.removeIf(n -> n.startsWith("asset_embedding_hnsw_p" + profileId + "_"));
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

    /** Whether a full re-embed is running in this process. */
    public boolean reembedding() {
        return reembedding.get();
    }

    /**
     * Embeds every source again in the background, after the embedding profile changed. Only one
     * process does it at a time (a PostgreSQL advisory lock); unchanged assets are skipped.
     *
     * @return false if one is already running here
     */
    public boolean reembedAllInBackground(String caller) {
        if (!reembedding.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("kilnmark-reembed").start(() -> {
            try (Connection lock = db.connection()) {
                if (!tryLock(lock)) {
                    LOG.info("Another Kilnmark process is already embedding assets; leaving it to that one");
                    return;
                }
                try {
                    for (Records.Source s : store.sources()) {
                        Outcome o = embedSource(s, caller, () -> false);
                        LOG.infof("Embedding %s: %s", s.name(), o.note());
                    }
                } finally {
                    try (PreparedStatement ps = lock.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                        ps.setLong(1, REEMBED_LOCK);
                        ps.execute();
                    }
                }
            } catch (SQLException | RuntimeException e) {
                LOG.warnf(e, "Embedding every source failed");
            } finally {
                reembedding.set(false);
            }
        });
        return true;
    }

    private static boolean tryLock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            ps.setLong(1, REEMBED_LOCK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
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
                        WHERE e.profile_id = ? AND a.removed_at IS NULL),
                       (SELECT count(*) FROM kilnmark.asset WHERE removed_at IS NULL)""")) {
                ps.setLong(1, p.get().id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Status(p.get().name(), rs.getLong(1), rs.getLong(2), reembedding.get());
                }
            }
        }));
    }
}
