package io.kilnmark.server.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.ai.AiException;
import io.kilnmark.server.ai.AiFeature;
import io.kilnmark.server.ai.AiGateway;
import io.kilnmark.server.ai.AssetText;
import io.kilnmark.server.ai.Embeddings;
import io.kilnmark.server.ai.ModelProfile;
import io.kilnmark.server.ai.ModelProfiles;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The assistant's sections in {@code knowledge_section} (V11). At start-up, on processes with the
 * api role, the documentation in this build is split into sections ({@link BundledDocs}) and
 * written: new and changed sections (by hash) are written again, sections no longer in the
 * documentation are deleted, the rest are left alone. Then, if an embedding model is set, sections
 * without an embedding from that model are embedded in the background. Neither ever stops the
 * server from starting: a failure is logged, and the assistant answers from words alone.
 */
@ApplicationScoped
public class KnowledgeIndex {

    /** The documentation domain. */
    public static final String DOCS = "docs";
    private static final Logger LOG = Logger.getLogger(KnowledgeIndex.class);
    /** pg_advisory_xact_lock key, so two processes starting together index one after the other. */
    private static final long INDEX_LOCK = 0x6b6d_6b6e_6f77L;
    /** pg_try_advisory_lock key, so only one process embeds the sections at a time. */
    private static final long EMBED_LOCK = 0x6b6d_6b6e_656dL;
    /** How often a search may start a background pass for sections still without an embedding. */
    private static final long CHECK_EVERY_MS = 60_000;
    /** After a failed pass, searches wait this long before trying again, doubling up to an hour. */
    private static final long FIRST_BACKOFF_MS = 5 * 60_000;
    private static final long MAX_BACKOFF_MS = 60 * 60_000;

    private final Db db;
    private final ModelProfiles profiles;
    private final AiGateway gateway;
    private final KilnmarkConfig config;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean again = new AtomicBoolean();
    private volatile boolean stopping;
    private volatile long lastCheck;
    private volatile long backoffMs;
    private volatile long retryAfter;

    public KnowledgeIndex(Db db, ModelProfiles profiles, AiGateway gateway, KilnmarkConfig config) {
        this.db = db;
        this.profiles = profiles;
        this.gateway = gateway;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.roles().contains(Role.API)) {
            return;
        }
        try {
            Outcome o = index(DOCS, BundledDocs.sections());
            LOG.infof("Assistant: documentation indexed, %d sections added, %d changed, %d unchanged, %d removed",
                o.added(), o.changed(), o.unchanged(), o.removed());
        } catch (RuntimeException e) {
            LOG.warnf("Assistant: could not index the documentation; it answers from what was indexed before: %s",
                e.getMessage());
        }
        embedInBackground();
    }

    void onStop(@Observes ShutdownEvent event) {
        stopping = true;
    }

    /** What indexing a domain did. */
    public record Outcome(int added, int changed, int unchanged, int removed) {
    }

    /** Makes a domain's stored sections match {@code sections}, writing only what changed. */
    public Outcome index(String domain, List<DocSections.Section> sections) {
        if (sections.isEmpty()) {
            // A build without its documentation: keep what was indexed rather than delete it all.
            LOG.warnf("Assistant: no sections for %s in this build; the index is left as it was", domain);
            return new Outcome(0, 0, 0, 0);
        }
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                ps.setLong(1, INDEX_LOCK);
                ps.execute();
            }
            record Stored(byte[] hash, int position, String page) {
            }
            Map<String, Stored> stored = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT section_id, hash, position, page FROM kilnmark.knowledge_section WHERE domain = ?")) {
                ps.setString(1, domain);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        stored.put(rs.getString(1), new Stored(rs.getBytes(2), rs.getInt(3), rs.getString(4)));
                    }
                }
            }
            int added = 0;
            int changed = 0;
            int unchanged = 0;
            try (PreparedStatement write = c.prepareStatement("""
                     INSERT INTO kilnmark.knowledge_section (domain, section_id, page, position, title_path, body, hash)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT (domain, section_id) DO UPDATE SET page = EXCLUDED.page, position = EXCLUDED.position,
                         title_path = EXCLUDED.title_path, body = EXCLUDED.body, hash = EXCLUDED.hash, embedding = NULL,
                         embed_key = NULL, embed_dimensions = NULL, embedded_at = NULL, indexed_at = now()""");
                 PreparedStatement move = c.prepareStatement("UPDATE kilnmark.knowledge_section SET position = ?, "
                     + "page = ? WHERE domain = ? AND section_id = ?")) {
                for (DocSections.Section s : sections) {
                    Stored old = stored.get(s.id());
                    byte[] hash = s.hash();
                    if (old != null && Arrays.equals(old.hash(), hash)) {
                        unchanged++;
                        if (old.position() != s.position() || !old.page().equals(s.page())) {
                            move.setInt(1, s.position());
                            move.setString(2, s.page());
                            move.setString(3, domain);
                            move.setString(4, s.id());
                            move.addBatch();
                        }
                        continue;
                    }
                    if (old == null) {
                        added++;
                    } else {
                        changed++;
                    }
                    write.setString(1, domain);
                    write.setString(2, s.id());
                    write.setString(3, s.page());
                    write.setInt(4, s.position());
                    write.setString(5, s.titlePath());
                    write.setString(6, s.body());
                    write.setBytes(7, hash);
                    write.addBatch();
                }
                write.executeBatch();
                move.executeBatch();
            }
            int removed;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.knowledge_section WHERE domain = ? "
                + "AND NOT (section_id = ANY (?))")) {
                ps.setString(1, domain);
                ps.setArray(2, c.createArrayOf("text", sections.stream().map(DocSections.Section::id).toArray()));
                removed = ps.executeUpdate();
            }
            return new Outcome(added, changed, unchanged, removed);
        });
    }

    /**
     * Which model made a vector: provider, address, model and size. Vectors are compared only with
     * vectors of the same key, so a changed embedding model never mixes with the old one's.
     */
    public static byte[] vectorKey(ModelProfile p) {
        try {
            return MessageDigest.getInstance("SHA-256").digest((p.provider().id() + "\n" + p.effectiveBaseUrl() + "\n"
                + p.model() + "\n" + p.dimensions()).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Embeds, in the background, every section without an embedding from the current embedding
     * model. A request while a pass runs makes it go round once more. Asked for explicitly (at
     * start-up, or after an admin changed the embedding model), so any back-off after failures ends.
     *
     * @return true if a pass started now
     */
    public boolean embedInBackground() {
        backoffMs = 0;
        retryAfter = 0;
        return start();
    }

    private boolean start() {
        again.set(true);
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Thread.ofVirtual().name("kilnmark-knowledge-embed").start(() -> {
            do {
                try {
                    while (again.getAndSet(false) && !stopping) {
                        embedPendingAlone();
                    }
                } catch (RuntimeException e) {
                    LOG.warnf("Assistant: embedding the documentation failed; it is searched by words: %s",
                        e.getMessage());
                } finally {
                    running.set(false);
                }
            } while (again.get() && !stopping && running.compareAndSet(false, true));
        });
        return true;
    }

    /** Whether a background pass is running in this process. */
    public boolean embedding() {
        return running.get();
    }

    /**
     * Called by searches that use meaning: starts a background pass, at most once a minute, when
     * some sections have no embedding from {@code p} (after the model changed, say).
     */
    void embedIfMissing(ModelProfile p, String domain) {
        long now = System.currentTimeMillis();
        if (running.get() || now - lastCheck < CHECK_EVERY_MS || now < retryAfter) {
            return;
        }
        lastCheck = now;
        boolean missing = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT EXISTS (SELECT 1 FROM kilnmark.knowledge_section "
                + "WHERE domain = ? AND (embed_key IS DISTINCT FROM ? OR embedding IS NULL))")) {
                ps.setString(1, domain);
                ps.setBytes(2, vectorKey(p));
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            }
        });
        if (missing) {
            start();
        }
    }

    /** One pass, while holding a lock that other processes then cannot take: they skip, and this one embeds. */
    private void embedPendingAlone() {
        try (Connection c = db.connection()) {
            c.setAutoCommit(true);
            if (!lock(c, "pg_try_advisory_lock")) {
                return;
            }
            try {
                embedPending();
            } finally {
                lock(c, "pg_advisory_unlock");
            }
        } catch (SQLException e) {
            LOG.warnf("Assistant: could not embed the documentation: %s", e.getMessage());
        }
    }

    private static boolean lock(Connection c, String function) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + function + "(?)")) {
            ps.setLong(1, EMBED_LOCK);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private record Pending(String domain, String id, byte[] hash, String text) {
    }

    private void embedPending() {
        Optional<ModelProfile> routed = profiles.route(AiFeature.EMBED);
        if (routed.isEmpty()) {
            return;
        }
        ModelProfile p = routed.get();
        byte[] key = vectorKey(p);
        int maxChars = AssetText.maxChars(p);
        List<Pending> pending = db.tx(c -> {
            List<Pending> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT domain, section_id, hash, title_path, body "
                + "FROM kilnmark.knowledge_section WHERE embed_key IS DISTINCT FROM ? OR embedding IS NULL "
                + "ORDER BY domain, page, position")) {
                ps.setBytes(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String text = rs.getString(4) + "\n" + rs.getString(5);
                        out.add(new Pending(rs.getString(1), rs.getString(2), rs.getBytes(3),
                            text.length() <= maxChars ? text : text.substring(0, maxChars)));
                    }
                }
            }
            return out;
        });
        int batchSize = Math.max(1, config.ai().embedBatchSize());
        int done = 0;
        for (int i = 0; i < pending.size(); i += batchSize) {
            if (stopping) {
                return;
            }
            // The model may have been changed meanwhile: its vectors must not be stored under the old key.
            Optional<ModelProfile> now = profiles.route(AiFeature.EMBED);
            if (now.isEmpty() || !Arrays.equals(vectorKey(now.get()), key)) {
                again.set(true);
                return;
            }
            List<Pending> batch = pending.subList(i, Math.min(pending.size(), i + batchSize));
            List<float[]> vectors;
            try {
                vectors = gateway.embedWith(now.get(), AiFeature.EMBED.id(), null,
                    batch.stream().map(Pending::text).toList(), "assistant: documentation", () -> stopping,
                    AiGateway.Budget.BACKGROUND).answer().vectors();
            } catch (AiException e) {
                backoffMs = backoffMs == 0 ? FIRST_BACKOFF_MS : Math.min(MAX_BACKOFF_MS, backoffMs * 2);
                retryAfter = System.currentTimeMillis() + backoffMs;
                LOG.infof("Assistant: documentation sections not embedded (%d of %d done), trying again in %d minutes "
                    + "or when the embedding model changes: %s", done, pending.size(), backoffMs / 60_000,
                    e.publicMessage());
                return;
            }
            save(batch, vectors, key, p.dimensions());
            done += batch.size();
        }
        backoffMs = 0;
        retryAfter = 0;
        if (done > 0) {
            LOG.infof("Assistant: %d documentation sections embedded with %s", done, p.name());
        }
    }

    /** Saves vectors for sections whose text has not changed since they were read. */
    private void save(List<Pending> batch, List<float[]> vectors, byte[] key, int dimensions) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.knowledge_section SET embedding = "
                + "?::public.vector, embed_key = ?, embed_dimensions = ?, embedded_at = now() "
                + "WHERE domain = ? AND section_id = ? AND hash = ?")) {
                for (int i = 0; i < batch.size(); i++) {
                    Pending s = batch.get(i);
                    ps.setString(1, Embeddings.literal(vectors.get(i)));
                    ps.setBytes(2, key);
                    ps.setInt(3, dimensions);
                    ps.setString(4, s.domain());
                    ps.setString(5, s.id());
                    ps.setBytes(6, s.hash());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }
}
