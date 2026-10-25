package io.kilnmark.server.ai.assistant;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.ai.AiException;
import io.kilnmark.server.ai.AiFeature;
import io.kilnmark.server.ai.AiGateway;
import io.kilnmark.server.ai.Embeddings;
import io.kilnmark.server.ai.ModelProfile;
import io.kilnmark.server.ai.ModelProfiles;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Finds the sections of a knowledge domain that answer a question: full-text search in
 * PostgreSQL, weighted so rare words count for more, plus the nearest sections by embedding when
 * an embedding model is set, merged by {@link KnowledgeRanking} with a boost for the page the
 * person is on. Without an embedding model, or when it cannot be used, words alone, with a note.
 */
@ApplicationScoped
public class KnowledgeSearch {

    private static final Logger LOG = Logger.getLogger(KnowledgeSearch.class);
    /** Candidates taken from each ranking before merging. */
    static final int CANDIDATES = 30;

    private final Db db;
    private final ModelProfiles profiles;
    private final AiGateway gateway;
    private final KnowledgeIndex index;
    private final KilnmarkConfig config;

    public KnowledgeSearch(Db db, ModelProfiles profiles, AiGateway gateway, KnowledgeIndex index,
                           KilnmarkConfig config) {
        this.db = db;
        this.profiles = profiles;
        this.gateway = gateway;
        this.index = index;
        this.config = config;
    }

    /** One section found, with its full text. */
    public record Hit(String domain, String sectionId, String page, String titlePath, String body, double score,
                      List<String> matchedBy) {

        /** The anchor within the page, or null for the page's own section. */
        public String slug() {
            int slash = sectionId.indexOf('/');
            return slash < 0 ? null : sectionId.substring(slash + 1);
        }

        /** The in-app viewer's link to this section. */
        public String href() {
            return DocSections.href(page, slug());
        }
    }

    /** {@code mode}: {@code hybrid} when meaning was used, else {@code keyword}; {@code note} says why, or null. */
    public record Result(String mode, String note, List<Hit> hits) {
    }

    /**
     * The best sections for {@code query}, at most {@code limit}. A blank query with a page lists
     * that page's sections in order.
     *
     * @param useMeaning false for words only (no model is called)
     */
    public Result search(String domain, String query, String pageHint, int limit, String caller, boolean useMeaning) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return new Result("keyword", null, pageHint == null ? List.of() : page(domain, pageHint, limit));
        }
        Map<String, Double> words = words(domain, q, CANDIDATES);
        Map<String, Double> meaning = Map.of();
        String note = null;
        if (useMeaning) {
            Optional<ModelProfile> p = profiles.route(AiFeature.EMBED);
            if (p.isEmpty()) {
                note = "Search by meaning is off: no embedding model is set. Sections are matched by their words.";
            } else {
                try {
                    float[] v = gateway.embedWith(p.get(), AiFeature.EMBED.id(), null, List.of(q), caller, () -> false,
                        AiGateway.Budget.INTERACTIVE).answer().vectors().get(0);
                    meaning = nearest(domain, p.get(), v, CANDIDATES);
                    index.embedIfMissing(p.get(), domain);
                    if (meaning.isEmpty()) {
                        note = "The documentation has not been embedded for search by meaning yet, so sections are "
                            + "matched by their words.";
                    }
                } catch (AiException e) {
                    note = "Search by meaning is unavailable right now, so sections are matched by their words. "
                        + (e.kind() == AiException.Kind.REFUSED ? e.publicMessage()
                            : "An admin can see why under AI usage.");
                } catch (Db.DbException e) {
                    LOG.warnf("Searching the documentation by meaning failed; matching words only: %s", e.getMessage());
                    note = "Search by meaning is unavailable right now, so sections are matched by their words.";
                }
            }
        }
        List<KnowledgeRanking.Scored> ranked = KnowledgeRanking.fuse(words, meaning, pageHint);
        List<KnowledgeRanking.Scored> top = ranked.subList(0, Math.min(limit, ranked.size()));
        Map<String, String[]> rows = rows(domain, top.stream().map(KnowledgeRanking.Scored::id).toList());
        List<Hit> hits = new ArrayList<>();
        for (KnowledgeRanking.Scored s : top) {
            String[] r = rows.get(s.id());
            if (r != null) {
                hits.add(new Hit(domain, s.id(), r[0], r[1], r[2], s.score(), s.matchedBy()));
            }
        }
        return new Result(meaning.isEmpty() ? "keyword" : "hybrid", note, hits);
    }

    /**
     * Full-text ranking: the question's words (stemmed, common words dropped) are matched with OR,
     * each weighted by how rare it is among the domain's sections (inverse document frequency), so
     * a question that names "postcode" finds the sections about postcodes rather than every section
     * that says "rule". Titles count more than text.
     */
    private Map<String, Double> words(String domain, String q, int limit) {
        return db.tx(c -> {
            Map<String, Double> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("""
                WITH q AS (
                    SELECT DISTINCT lexeme FROM unnest(to_tsvector('english', ?))
                ), t AS (
                    SELECT lexeme, ('''' || replace(replace(lexeme, '\\', '\\\\'), '''', '''''') || '''')::tsquery AS tq
                    FROM q
                ), n AS (
                    SELECT count(*)::float8 AS total FROM kilnmark.knowledge_section WHERE domain = ?
                ), df AS (
                    SELECT t.lexeme, count(s.section_id)::float8 AS df
                    FROM t LEFT JOIN kilnmark.knowledge_section s ON s.domain = ? AND s.search @@ t.tq
                    GROUP BY t.lexeme
                ), w AS (
                    SELECT t.tq, ln(1 + (n.total - df.df + 0.5) / (df.df + 0.5)) AS idf
                    FROM t JOIN df USING (lexeme) CROSS JOIN n
                )
                SELECT s.section_id, sum(w.idf * ts_rank(s.search, w.tq, 1)) AS score
                FROM kilnmark.knowledge_section s JOIN w ON s.search @@ w.tq
                WHERE s.domain = ?
                GROUP BY s.section_id
                ORDER BY score DESC, s.section_id
                LIMIT ?""")) {
                ps.setString(1, q);
                ps.setString(2, domain);
                ps.setString(3, domain);
                ps.setString(4, domain);
                ps.setInt(5, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getDouble(2));
                    }
                }
            }
            return out;
        });
    }

    /**
     * The sections nearest to {@code v} with vectors from the same model, at least
     * {@code kilnmark.ai.min-similarity} alike, best first. The dimensions go into the SQL as a
     * number; there are few sections, so this scans them.
     */
    private Map<String, Double> nearest(String domain, ModelProfile p, float[] v, int k) {
        int dims = p.dimensions();
        String cast = Embeddings.cast(dims);
        String literal = Embeddings.literal(v);
        double min = config.ai().minSimilarity();
        return db.tx(c -> {
            Map<String, Double> out = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT section_id, 1 - (embedding::" + cast + " <=> ?::"
                + cast + ") FROM kilnmark.knowledge_section WHERE domain = ? AND embed_key = ? AND embed_dimensions = "
                + dims + " AND embedding IS NOT NULL ORDER BY embedding::" + cast + " <=> ?::" + cast + " LIMIT " + k)) {
                ps.setString(1, literal);
                ps.setString(2, domain);
                ps.setBytes(3, KnowledgeIndex.vectorKey(p));
                ps.setString(4, literal);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getDouble(2) >= min) {
                            out.put(rs.getString(1), rs.getDouble(2));
                        }
                    }
                }
            }
            return out;
        });
    }

    /** Page, title path and text of each section. */
    private Map<String, String[]> rows(String domain, List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return db.tx(c -> {
            Map<String, String[]> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT section_id, page, title_path, body FROM "
                + "kilnmark.knowledge_section WHERE domain = ? AND section_id = ANY (?)")) {
                ps.setString(1, domain);
                ps.setArray(2, c.createArrayOf("text", ids.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                    }
                }
            }
            return out;
        });
    }

    /** A page's sections in order. */
    private List<Hit> page(String domain, String page, int limit) {
        return db.tx(c -> {
            List<Hit> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT section_id, page, title_path, body FROM "
                + "kilnmark.knowledge_section WHERE domain = ? AND page = ? ORDER BY position LIMIT ?")) {
                ps.setString(1, domain);
                ps.setString(2, page);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Hit(domain, rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), 0,
                            List.of("page")));
                    }
                }
            }
            return out;
        });
    }

    /** Sections by id, in the order given, skipping ids that do not exist. */
    public List<Hit> byIds(String domain, List<String> ids) {
        Map<String, String[]> rows = rows(domain, ids);
        List<Hit> out = new ArrayList<>();
        for (String id : ids) {
            String[] r = rows.get(id);
            if (r != null) {
                out.add(new Hit(domain, id, r[0], r[1], r[2], 0, List.of()));
            }
        }
        return out;
    }
}
