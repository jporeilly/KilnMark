package io.kilnmark.server.ai;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Search that matches words and meaning (ADR 0016). The keyword ranking ({@link GraphStore#search})
 * and the nearest assets by embedding are merged by reciprocal rank fusion, so an asset found both
 * ways rises to the top. Without an embedding model, or when the model cannot be used, it is the
 * keyword search alone, with a note saying why.
 */
@ApplicationScoped
public class HybridSearch {

    /** The usual constant for reciprocal rank fusion: rank 1 scores 1/61. */
    static final int RRF_K = 60;
    private static final int CANDIDATES = 100;

    private final GraphStore store;
    private final Db db;
    private final AiGateway gateway;
    private final ModelProfiles profiles;
    private final KilnmarkConfig config;

    public HybridSearch(GraphStore store, Db db, AiGateway gateway, ModelProfiles profiles, KilnmarkConfig config) {
        this.store = store;
        this.db = db;
        this.gateway = gateway;
        this.profiles = profiles;
        this.config = config;
    }

    /** What to narrow a search to. Empty lists mean no restriction. Kinds and field patterns are lower case. */
    public record Filters(List<String> kinds, List<String> sources, List<String> fields) {
        public static final Filters NONE = new Filters(List.of(), List.of(), List.of());

        public Filters {
            kinds = List.copyOf(kinds);
            sources = List.copyOf(sources);
            fields = List.copyOf(fields);
        }

        boolean any() {
            return !kinds.isEmpty() || !sources.isEmpty() || !fields.isEmpty();
        }
    }

    /** One result. {@code matchedBy} holds {@code words}, {@code meaning} or both. */
    public record Hit(String urn, String source, String kind, String container, String name, String description,
                      Long rowCount, List<String> matchedFields, double score, List<String> matchedBy) {
    }

    /** {@code mode} is {@code hybrid} when meaning was used, else {@code keyword}; {@code note} says why, or null. */
    public record Result(String mode, String note, List<Hit> hits) {
    }

    /**
     * Searches for {@code text} (may be blank when filters are given), narrowed by {@code filters}.
     *
     * @param useMeaning false for keyword search only
     */
    public Result search(String text, Filters filters, int limit, String caller, boolean useMeaning) {
        String q = text == null ? "" : text.strip();
        if (q.isEmpty()) {
            return new Result("keyword", null, listing(filters, limit));
        }
        List<Records.SearchHit> keyword = store.search(q, filters.any() ? CANDIDATES * 2 : CANDIDATES);
        List<String> keywordUrns = keyword.stream().map(Records.SearchHit::urn).toList();
        Map<String, Records.SearchHit> keywordHits = new HashMap<>();
        keyword.forEach(h -> keywordHits.put(h.urn(), h));

        String note = null;
        List<String> vectorUrns = List.of();
        boolean meaning = false;
        if (useMeaning) {
            Optional<ModelProfile> p = profiles.route(AiFeature.EMBED);
            if (p.isEmpty()) {
                note = "Search by meaning is off: no embedding model is set. Results match words only.";
            } else {
                try {
                    float[] v = gateway.embedWith(p.get(), AiFeature.EMBED.id(), null, List.of(q), caller, () -> false)
                        .answer().vectors().get(0);
                    if (v.length != p.get().dimensions()) {
                        note = "Search by meaning is unavailable: the embedding model returns " + v.length
                            + " numbers per text, but its profile says " + p.get().dimensions()
                            + ". An admin can fix the profile. Results match words only.";
                    } else {
                        Nearest n = nearest(p.get(), v);
                        vectorUrns = n.urns();
                        meaning = true;
                        if (n.stored() == 0) {
                            note = "No assets have been embedded with " + p.get().name() + " yet, so results match "
                                + "words only. Scan a source to embed its assets.";
                        }
                    }
                } catch (AiException e) {
                    note = "Search by meaning is unavailable: " + e.getMessage() + " Results match words only.";
                }
            }
        }
        List<String> order;
        Map<String, Double> scores = new HashMap<>();
        if (meaning) {
            List<Fused> fused = fuse(keywordUrns, vectorUrns);
            fused.forEach(f -> scores.put(f.urn(), f.score()));
            order = fused.stream().map(Fused::urn).toList();
        } else {
            keyword.forEach(h -> scores.put(h.urn(), h.score()));
            order = keywordUrns;
        }
        Map<String, Row> rows = hydrate(order, filters);
        List<Hit> hits = new ArrayList<>();
        java.util.Set<String> byMeaning = new java.util.HashSet<>(vectorUrns);
        for (String urn : order) {
            Row r = rows.get(urn);
            if (r == null) {
                continue;
            }
            Records.SearchHit k = keywordHits.get(urn);
            List<String> by = new ArrayList<>();
            if (k != null) {
                by.add("words");
            }
            if (byMeaning.contains(urn)) {
                by.add("meaning");
            }
            List<String> matched = k != null && !k.matchedFields().isEmpty() ? k.matchedFields() : r.matchedFields();
            double score = Math.round(scores.getOrDefault(urn, 0.0) * 10000) / 10000.0;
            hits.add(new Hit(urn, r.source(), r.kind(), r.container(), r.name(), r.description(), r.rowCount(), matched,
                score, by));
            if (hits.size() >= limit) {
                break;
            }
        }
        return new Result(meaning ? "hybrid" : "keyword", note, hits);
    }

    /** An asset's place in the fused ranking. */
    record Fused(String urn, double score) {
    }

    /**
     * Reciprocal rank fusion: each list gives an asset 1 / (60 + rank). Ties keep the keyword order,
     * then the meaning order.
     */
    static List<Fused> fuse(List<String> keyword, List<String> meaning) {
        Map<String, double[]> s = new LinkedHashMap<>(); // score, keyword rank, meaning rank
        for (int i = 0; i < keyword.size(); i++) {
            s.computeIfAbsent(keyword.get(i), u -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            double[] a = s.get(keyword.get(i));
            if (a[1] == Integer.MAX_VALUE) {
                a[0] += 1.0 / (RRF_K + i + 1);
                a[1] = i;
            }
        }
        for (int i = 0; i < meaning.size(); i++) {
            s.computeIfAbsent(meaning.get(i), u -> new double[] {0, Integer.MAX_VALUE, Integer.MAX_VALUE});
            double[] a = s.get(meaning.get(i));
            if (a[2] == Integer.MAX_VALUE) {
                a[0] += 1.0 / (RRF_K + i + 1);
                a[2] = i;
            }
        }
        List<Map.Entry<String, double[]>> e = new ArrayList<>(s.entrySet());
        e.sort((x, y) -> {
            int c = Double.compare(y.getValue()[0], x.getValue()[0]);
            if (c != 0) {
                return c;
            }
            c = Double.compare(x.getValue()[1], y.getValue()[1]);
            return c != 0 ? c : Double.compare(x.getValue()[2], y.getValue()[2]);
        });
        return e.stream().map(x -> new Fused(x.getKey(), x.getValue()[0])).toList();
    }

    private record Nearest(List<String> urns, long stored) {
    }

    /** The assets nearest to {@code v}, at least {@code kilnmark.ai.min-similarity} alike, best first. */
    private Nearest nearest(ModelProfile p, float[] v) {
        String cast = Embeddings.cast(p.dimensions());
        String literal = Embeddings.literal(v);
        double min = config.ai().minSimilarity();
        return db.tx(c -> {
            long stored;
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM (SELECT 1 FROM kilnmark.asset_embedding WHERE profile_id = ? LIMIT 1) x")) {
                ps.setLong(1, p.id());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    stored = rs.getLong(1);
                }
            }
            List<String> out = new ArrayList<>();
            // The cast matches the profile's partial index (Embeddings.ensureIndex), so the index is used.
            try (PreparedStatement ps = c.prepareStatement("SELECT asset_urn, 1 - (embedding::" + cast + " <=> ?::"
                + cast + ") FROM kilnmark.asset_embedding WHERE profile_id = ? ORDER BY embedding::" + cast
                + " <=> ?::" + cast + " LIMIT " + CANDIDATES)) {
                ps.setString(1, literal);
                ps.setLong(2, p.id());
                ps.setString(3, literal);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (rs.getDouble(2) >= min) {
                            out.add(rs.getString(1));
                        }
                    }
                }
            }
            return new Nearest(out, stored);
        });
    }

    private record Row(String source, String kind, String container, String name, String description, Long rowCount,
                       List<String> matchedFields) {
    }

    private static final String SELECT = """
        SELECT a.urn, s.name, a.kind, a.container, a.name, a.description, a.row_count,
               CASE WHEN cardinality(?::text[]) = 0 THEN '{}'::text[]
                    ELSE coalesce((SELECT array_agg(f.name ORDER BY f.position) FROM kilnmark.field f
                                   WHERE f.asset_urn = a.urn AND lower(f.name) LIKE ANY (?::text[])), '{}') END
        FROM kilnmark.asset a JOIN kilnmark.data_source s ON s.id = a.source_id
        WHERE a.removed_at IS NULL
          AND (cardinality(?::text[]) = 0 OR lower(a.kind) = ANY (?::text[]))
          AND (cardinality(?::text[]) = 0 OR s.name = ANY (?::text[]))
          AND (cardinality(?::text[]) = 0 OR EXISTS (SELECT 1 FROM kilnmark.field f
                   WHERE f.asset_urn = a.urn AND lower(f.name) LIKE ANY (?::text[])))
        """;

    private static int bindFilters(Connection c, PreparedStatement ps, Filters f) throws SQLException {
        Array patterns = c.createArrayOf("text", f.fields().stream().map(HybridSearch::pattern).toArray());
        Array kinds = c.createArrayOf("text", f.kinds().toArray());
        Array sources = c.createArrayOf("text", f.sources().toArray());
        ps.setArray(1, patterns);
        ps.setArray(2, patterns);
        ps.setArray(3, kinds);
        ps.setArray(4, kinds);
        ps.setArray(5, sources);
        ps.setArray(6, sources);
        ps.setArray(7, patterns);
        ps.setArray(8, patterns);
        return 9;
    }

    /** A field name as a LIKE pattern matching it anywhere, with LIKE's own characters escaped. */
    static String pattern(String field) {
        return "%" + field.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private Map<String, Row> hydrate(List<String> urns, Filters f) {
        if (urns.isEmpty()) {
            return Map.of();
        }
        return db.tx(c -> {
            Map<String, Row> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + " AND a.urn = ANY (?::text[])")) {
                int next = bindFilters(c, ps, f);
                ps.setArray(next, c.createArrayOf("text", urns.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), row(rs));
                    }
                }
            }
            return out;
        });
    }

    /** Assets matching the filters alone, by source and name: for a question that names no words to search. */
    private List<Hit> listing(Filters f, int limit) {
        if (!f.any()) {
            return List.of();
        }
        return db.tx(c -> {
            List<Hit> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT + " ORDER BY s.name, a.container, a.name LIMIT ?")) {
                int next = bindFilters(c, ps, f);
                ps.setInt(next, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Row r = row(rs);
                        out.add(new Hit(rs.getString(1), r.source(), r.kind(), r.container(), r.name(), r.description(),
                            r.rowCount(), r.matchedFields(), 0, List.of("filters")));
                    }
                }
            }
            return out;
        });
    }

    private static Row row(ResultSet rs) throws SQLException {
        long rows = rs.getLong(7);
        Long rowCount = rs.wasNull() ? null : rows;
        Array fields = rs.getArray(8);
        List<String> matched = fields == null ? List.of()
            : Arrays.stream((Object[]) fields.getArray()).map(String::valueOf).toList();
        return new Row(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rowCount,
            matched);
    }
}
