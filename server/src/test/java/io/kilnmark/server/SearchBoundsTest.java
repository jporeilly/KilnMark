package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.graph.store.JdbcGraphStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Keyword search stays bounded on a large catalogue (container test, run in CI): with 20,000
 * assets, 100,000 fields and 20,000 document chunks that all hold the words searched for, the
 * plan the search runs (EXPLAIN ANALYZE of exactly its statement) touches a few thousand rows of
 * each table, where ranking everything before the limit touched them all. Exact names still come
 * first.
 */
@QuarkusTest
class SearchBoundsTest {

    private static final String SOURCE = "search-bounds";
    private static final Set<String> TABLES = Set.of("asset", "field", "document_chunk");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject
    AgroalDataSource db;

    /** The search's own statement; package-private in the graph module, so reached by reflection. */
    private static final java.lang.reflect.Method PREPARE;

    static {
        try {
            PREPARE = JdbcGraphStore.class.getDeclaredMethod("prepareKeywordSearch", Connection.class, String.class,
                String.class, int.class);
            PREPARE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private void load() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO kilnmark.data_source (name, display_name, kind) VALUES ('" + SOURCE
                + "', 'Search bounds', 'postgresql')");
            s.execute("""
                INSERT INTO kilnmark.asset (urn, source_id, kind, container, name, description)
                SELECT 'urn:kilnmark:search-bounds:bulk.customer_' || w.a[1 + n % 5] || '_' || n,
                       (SELECT id FROM kilnmark.data_source WHERE name = 'search-bounds'),
                       CASE WHEN n % 20 = 0 THEN 'document' ELSE 'table' END, 'bulk',
                       'customer_' || w.a[1 + n % 5] || '_' || n, 'The customer records for the ' || w.a[1 + n % 5]
                FROM generate_series(1, 20000) n,
                     (SELECT ARRAY['order', 'invoice', 'account', 'payment', 'contact'] AS a) w""");
            s.execute("""
                INSERT INTO kilnmark.asset (urn, source_id, kind, container, name)
                SELECT 'urn:kilnmark:search-bounds:crm.customer', id, 'table', 'crm', 'customer'
                FROM kilnmark.data_source WHERE name = 'search-bounds'""");
            s.execute("""
                INSERT INTO kilnmark.field (asset_urn, name, position, source_type, kind, nullable)
                SELECT a.urn, f.a[i], i, 'text', 'text', true
                FROM kilnmark.asset a, generate_series(1, 5) i,
                     (SELECT ARRAY['id', 'name', 'customer_id', 'created_at', 'the_notes'] AS a) f
                WHERE a.urn LIKE 'urn:kilnmark:search-bounds:%'""");
            s.execute("""
                INSERT INTO kilnmark.document_chunk (asset_urn, seq, text, start_offset, end_offset)
                SELECT a.urn, k, 'The customer agreed the terms of the order on page ' || k
                                 || '; the invoice follows the shipment.', 0, 80
                FROM kilnmark.asset a, generate_series(0, 19) k
                WHERE a.urn LIKE 'urn:kilnmark:search-bounds:%' AND a.kind = 'document'""");
        }
        // As autovacuum would, in time: statistics, and GIN pending lists merged (outside a transaction).
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(true);
            s.execute("VACUUM (ANALYZE) kilnmark.asset, kilnmark.field, kilnmark.document_chunk");
        }
    }

    @AfterEach
    void drop() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM kilnmark.data_source WHERE name = '" + SOURCE + "'"); // assets and the rest cascade
        }
    }

    /** Rows each table's scans touched, from EXPLAIN ANALYZE of the search's own statement. */
    private Map<String, Long> touched(String q) throws Exception {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = (PreparedStatement) PREPARE.invoke(null, c, "EXPLAIN (ANALYZE, FORMAT JSON) ",
                     q, 100); ResultSet rs = ps.executeQuery()) {
                rs.next();
                Map<String, Long> out = new HashMap<>();
                count(JSON.readTree(rs.getString(1)).get(0).get("Plan"), out);
                return out;
            } finally {
                c.rollback();
            }
        }
    }

    private static void count(JsonNode node, Map<String, Long> out) {
        String table = node.path("Relation Name").asText("");
        if (TABLES.contains(table)) {
            long rows = node.path("Actual Rows").asLong() + node.path("Rows Removed by Filter").asLong()
                + node.path("Rows Removed by Index Recheck").asLong();
            out.merge(table, rows * Math.max(1, node.path("Actual Loops").asLong()), Long::sum);
        }
        node.path("Plans").forEach(p -> count(p, out));
    }

    @Test
    void commonWordsTouchAFewThousandRowsNotTheWholeCatalogue() throws Exception {
        load();
        // Every bulk asset is named and described with "customer"; every field set has "id" and
        // "the_notes"; every chunk has "the" and "customer".
        for (String q : new String[] {"customer", "id", "the", "zebra"}) {
            Map<String, Long> t = touched(q);
            assertTrue(t.getOrDefault("asset", 0L) <= 5_000, q + ": " + t);
            // candidates (about 1,000 at most) times their 5 columns, plus the field candidates
            assertTrue(t.getOrDefault("field", 0L) <= 20_000, q + ": " + t);
            assertTrue(t.getOrDefault("document_chunk", 0L) <= 5_000, q + ": " + t);
        }
        // Exact names first, however many others match as well.
        admin().queryParam("q", "customer").queryParam("mode", "keyword").get("/api/v1/search").then()
            .statusCode(200).body("hits[0].urn", equalTo("urn:kilnmark:search-bounds:crm.customer"));
        admin().queryParam("q", "crm.customer").queryParam("mode", "keyword").get("/api/v1/search").then()
            .statusCode(200).body("hits[0].urn", equalTo("urn:kilnmark:search-bounds:crm.customer"));
    }
}
