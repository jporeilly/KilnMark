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

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(HybridSearch.class);

    /** The usual constant for reciprocal rank fusion: rank 1 scores 1/61. */
    static final int RRF_K = 60;
    private static final int CANDIDATES = 100;

    private final GraphStore store;
    private final Db db;
    private final AiGateway gateway;
    private final ModelProfiles profiles;
    private final KilnmarkConfig config;

    /** Who is asking: withheld documents' text is matched only for admins (data quality, sensitive columns). */
    @jakarta.inject.Inject
    jakarta.enterprise.inject.Instance<io.kilnmark.server.quality.Sights> sights;

    /** Outside a request (no caller) nothing withheld is matched. */
    private boolean everyText() {
        io.quarkus.arc.ArcContainer arc = io.quarkus.arc.Arc.container();
        return arc != null && sights != null && arc.requestContext().isActive() && sights.get().everyText();
    }

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

    /**
     * One result. {@code matchedBy} holds {@code words}, {@code meaning} or both. {@code snippet} is
     * the best passage from a document's text when its words matched there (ADR 0017), else null;
     * the MCP tools leave it out.
     */
    public record Hit(String urn, String source, String kind, String container, String name, String description,
                      Long rowCount, List<String> matchedFields, double score, List<String> matchedBy,
                      io.kilnmark.server.api.Views.Snippet snippet) {
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
        List<Records.SearchHit> keyword = store.search(q, filters.any() ? CANDIDATES * 2 : CANDIDATES,
            filters.sources(), everyText());
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
                // Notes are shown to every viewer (and to MCP clients): never the model's address or reply.
                try {
                    float[] v = gateway.embedWith(p.get(), AiFeature.EMBED.id(), null, List.of(q), caller, () -> false,
                        AiGateway.Budget.INTERACTIVE).answer().vectors().get(0);
                    Nearest n = nearest(p.get(), v, filters, Math.max(limit, CANDIDATES));
                    vectorUrns = n.urns();
                    meaning = true;
                    if (n.stored() == 0) {
                        note = "No assets have been embedded for search by meaning yet, so results match words only. "
                            + "Scan a source to embed its assets.";
                    }
                } catch (AiException e) {
                    note = "Search by meaning is unavailable right now, so results match words only. "
                        + (e.kind() == AiException.Kind.REFUSED ? e.publicMessage()
                            : "An admin can see why under AI usage.");
                } catch (Db.DbException e) {
                    LOG.warnf("Search by meaning failed in the database; searching words only: %s", e.getMessage());
                    note = "Search by meaning is unavailable right now, so results match words only. "
                        + "An admin can see why in the server log.";
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
                score, by, k == null ? null : io.kilnmark.server.api.Views.Snippet.of(k.snippet())));
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

    /** Whether pgvector can keep scanning an HNSW index until filters are met (0.8 and later); asked once. */
    private volatile Boolean iterativeScan;

    private boolean iterativeScan(Connection c) throws SQLException {
        if (iterativeScan == null) {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT extversion FROM pg_extension WHERE extname = 'vector'"); ResultSet rs = ps.executeQuery()) {
                iterativeScan = rs.next() && atLeast(rs.getString(1), 0, 8);
            }
        }
        return iterativeScan;
    }

    static boolean atLeast(String version, int major, int minor) {
        String[] v = version.split("\\.");
        try {
            int a = Integer.parseInt(v[0]);
            int b = v.length > 1 ? Integer.parseInt(v[1]) : 0;
            return a > major || (a == major && b >= minor);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * The assets nearest to {@code v}, at least {@code kilnmark.ai.min-similarity} alike, best first,
     * within the filters. The profile id and dimensions go into the SQL as numbers, matching the
     * profile's partial index (Embeddings.ensureIndex) even under generic plans; rows of another
     * size are never compared. {@code hnsw.ef_search} is raised to the number wanted, and with
     * filters pgvector keeps scanning the index until enough rows pass (relaxed order, sorted again
     * here).
     */
    private Nearest nearest(ModelProfile p, float[] v, Filters filters, int k) {
        int dims = p.dimensions();
        String cast = Embeddings.cast(dims);
        String literal = Embeddings.literal(v);
        double min = config.ai().minSimilarity();
        String where = "e.profile_id = " + p.id() + " AND e.dimensions = " + dims;
        return db.tx(c -> {
            long stored;
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM (SELECT 1 FROM kilnmark.asset_embedding e "
                + "WHERE " + where + " LIMIT 1) x"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                stored = rs.getLong(1);
            }
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SET LOCAL hnsw.ef_search = " + Math.min(1000, Math.max(40, k)));
                if (filters.any() && iterativeScan(c)) {
                    st.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
                }
            }
            String join = filters.any() ? " JOIN kilnmark.asset a ON a.urn = e.asset_urn JOIN kilnmark.data_source s "
                + "ON s.id = a.source_id" : "";
            String filter = filters.any() ? " " + FILTERS : "";
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("WITH nn AS MATERIALIZED (SELECT e.asset_urn, e.embedding::"
                + cast + " <=> ?::" + cast + " AS d FROM kilnmark.asset_embedding e" + join + " WHERE " + where + filter
                + " ORDER BY e.embedding::" + cast + " <=> ?::" + cast + " LIMIT " + k + ") "
                + "SELECT asset_urn, 1 - d FROM nn ORDER BY d")) {
                ps.setString(1, literal);
                int next = 2;
                if (filters.any()) {
                    next = bindFilterArrays(c, ps, filters, next);
                }
                ps.setString(next, literal);
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

    /** The filters on asset {@code a} and source {@code s}, as in {@link #SELECT}. */
    private static final String FILTERS = """
         AND a.removed_at IS NULL
          AND (cardinality(?::text[]) = 0 OR lower(a.kind) = ANY (?::text[]))
          AND (cardinality(?::text[]) = 0 OR s.name = ANY (?::text[]))
          AND (cardinality(?::text[]) = 0 OR EXISTS (SELECT 1 FROM kilnmark.field f
                   WHERE f.asset_urn = a.urn AND lower(f.name) LIKE ANY (?::text[])))""";

    private static int bindFilterArrays(Connection c, PreparedStatement ps, Filters f, int from) throws SQLException {
        Array patterns = c.createArrayOf("text", f.fields().stream().map(HybridSearch::pattern).toArray());
        Array kinds = c.createArrayOf("text", f.kinds().toArray());
        Array sources = c.createArrayOf("text", f.sources().toArray());
        ps.setArray(from, kinds);
        ps.setArray(from + 1, kinds);
        ps.setArray(from + 2, sources);
        ps.setArray(from + 3, sources);
        ps.setArray(from + 4, patterns);
        ps.setArray(from + 5, patterns);
        return from + 6;
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
                            r.rowCount(), r.matchedFields(), 0, List.of("filters"), null));
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
