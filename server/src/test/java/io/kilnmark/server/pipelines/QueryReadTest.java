package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.MySqlConnector;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.server.quality.QualityStore;

/**
 * A read that runs a query (ADR 0028): checked as custom SQL rules are, PostgreSQL sources with
 * Allow SQL on only, never with columns or where, and every column it reads marked.
 */
class QueryReadTest {

    private final FakeSources sources = new FakeSources()
        .add("warehouse", "postgresql", Map.of("url", "jdbc:postgresql:" + "/" + "/h/db", "allow_sql", "true"), "pw")
        .add("closed", "postgresql", Map.of("url", "jdbc:postgresql:" + "/" + "/h/db"), "pw")
        .add("lab-db", Map.of(), null);
    private final ReadNodeFactory read;

    QueryReadTest() {
        sources.connectors.put("postgresql", new PostgresConnector());
        read = new ReadNodeFactory(sources);
    }

    private static Map<String, Object> query(String source, String sql) {
        return Map.of("source", source, "query", sql);
    }

    @Test
    void aQueryOnAPostgresqlSourceThatAllowsSqlIsFine() {
        assertEquals(List.of(), read.check(query("warehouse", "SELECT id, total FROM sales.orders WHERE total > 10;")));
        assertTrue(ReadNodeFactory.isQuery(query("warehouse", "SELECT 1")));
        assertFalse(ReadNodeFactory.isQuery(Map.of("source", "warehouse", "asset", "sales.orders")));
    }

    @Test
    void theSourceMustAllowSqlAndBePostgresql() {
        List<String> closed = read.check(query("closed", "SELECT 1"));
        assertEquals(1, closed.size(), closed.toString());
        assertTrue(closed.get(0).startsWith("query: closed does not allow SQL"), closed.toString());
        List<String> memory = read.check(query("lab-db", "SELECT 1"));
        assertTrue(memory.contains("query: a query reads only from PostgreSQL sources for now"), memory.toString());
    }

    @Test
    void aQueryIsOneSelectThatOnlyReads() {
        assertTrue(read.check(query("warehouse", "DELETE FROM sales.orders")).get(0)
            .startsWith("query: must start with SELECT or WITH"));
        assertTrue(read.check(query("warehouse", "SELECT 1; SELECT 2")).get(0).startsWith("query: "));
        assertTrue(read.check(query("warehouse", "SELECT pg_read_file('x')")).get(0).startsWith("query: "));
        assertTrue(read.check(query("warehouse", "SELECT * FROM t WHERE id = ${id}")).get(0)
            .startsWith("query: parameters such as ${name} are not filled"));
        assertEquals("query: one SELECT, or leave query out to read a table or file",
            read.check(query("warehouse", "  ")).get(0));
        IllegalArgumentException long_ = assertThrows(IllegalArgumentException.class,
            () -> ReadNodeFactory.checkQuery("SELECT " + "1,".repeat(6000) + "1"));
        assertTrue(long_.getMessage().startsWith("query: one SELECT, at most"), long_.getMessage());
        assertEquals("SELECT 1", ReadNodeFactory.checkQuery("SELECT 1;;"));
        // A JDBC escape, which the driver would rewrite after the check: refused, and a rule's ${table} too
        for (String escape : List.of("SELECT * {fn xINTO leak} FROM t", "SELECT {d '2024-01-01'}", "SELECT {fn user()}",
            "SELECT * FROM {oj t LEFT OUTER JOIN u ON t.a = u.a}")) {
            assertTrue(read.check(query("warehouse", escape)).get(0).startsWith("query: '{' and '}' are not allowed "
                + "outside quotes"), escape + ": " + read.check(query("warehouse", escape)));
        }
        assertTrue(read.check(query("warehouse", "SELECT * FROM ${table}")).get(0).startsWith("query: "));
        assertEquals("SELECT '{1,2}'::int[] AS a", ReadNodeFactory.checkQuery("SELECT '{1,2}'::int[] AS a"));
    }

    @Test
    void aReadTakesATableOrAQueryNotBoth() {
        Map<String, Object> both = Map.of("source", "warehouse", "asset", "sales.orders", "query", "SELECT 1");
        assertTrue(read.check(both).contains("query: a read takes a table or file, or a query, not both"));
        Map<String, Object> shaped = Map.of("source", "warehouse", "query", "SELECT 1", "where", "id > 1");
        assertTrue(read.check(shaped).get(0).startsWith("query: columns and where do not apply to a query"));
        assertTrue(read.check(Map.of("source", "warehouse")).get(0).startsWith("asset: one table or file to read"));
    }

    @Test
    void aQuerysColumnsAreKnownWhenItRuns() throws Exception {
        assertNull(read.outputSchema("out", Map.of(), query("warehouse", "SELECT 1 AS one")));
        assertEquals(List.of(), read.notes(Map.of(), query("warehouse", "SELECT 1 AS one")));
        assertNull(ReadNodeFactory.urn(query("warehouse", "SELECT 1")));
    }

    @Test
    void theTablesAQueryReadsComeFromItsPlan() {
        String plan = """
            [{"Plan": {"Node Type": "Hash Join", "Plans": [
              {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "sales", "Alias": "o"},
              {"Node Type": "Hash", "Plans": [
                {"Node Type": "Index Scan", "Relation Name": "customers", "Schema": "crm", "Alias": "c"}]},
              {"Node Type": "CTE Scan", "CTE Name": "recent"},
              {"Node Type": "Seq Scan", "Relation Name": "orders", "Schema": "sales", "Alias": "o2"}]}}]
            """;
        assertEquals(Set.of("sales.orders", "crm.customers"), ReadNodeFactory.relations(plan));
        assertEquals(List.of("sales.orders", "crm.customers"), List.copyOf(ReadNodeFactory.relations(plan)));
        assertEquals(Set.of(), ReadNodeFactory.relations("not json"));
    }

    @Test
    void theTablesAQueryReadIsWhatWritesFromItCameFrom() {
        PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check("""
            kilnmark: pipeline/v1
            name: by-query
            nodes:
              - id: q
                type: read
                source: warehouse
                query: SELECT id FROM sales.orders
            """, io.kilnmark.kiln.NodeFactories.discover().register(read), Map.of(), Map.of());
        RunContext run = new RunContext("by-query", 1, "jo", io.kilnmark.server.auth.AccessRole.DESIGNER);
        assertEquals(Map.of("q", List.of()), PipelineExecution.queryReads(c, run));
        run.queryRead("warehouse", "SELECT id FROM sales.orders", List.of("urn:kilnmark:warehouse:sales.orders"));
        assertEquals(Map.of("q", List.of("urn:kilnmark:warehouse:sales.orders")), PipelineExecution.queryReads(c, run));
        assertTrue(run.reads().contains("urn:kilnmark:warehouse:sales.orders"));
        assertEquals(Map.of(), PipelineExecution.queryReads(c, null));
    }

    @Test
    void loginRefusalsAreWordedForAQuery() {
        String r = ReadNodeFactory.forQuery("Custom SQL does not run under this source's login: it is a superuser");
        assertTrue(r.startsWith("The query does not run under"), r);
    }

    @Test
    void theReaderRunsTheQueryAsWrittenWithinTheRowLimit() {
        SourceConfig config = new SourceConfig("warehouse", "postgresql",
            Map.of("url", "jdbc:postgresql:" + "/" + "/h/db", "username", "reader"), "pw");
        PostgresConnector pg = new PostgresConnector();
        assertEquals("SELECT * FROM (\nSELECT 1\n) AS kilnmark_query LIMIT 5",
            pg.queryReader(config, "SELECT 1", "query", 5, 100).settings().get("query"));
        assertEquals("SELECT 1", pg.queryReader(config, "SELECT 1", "query", 0, 100).settings().get("query"));
        assertThrows(IllegalStateException.class,
            () -> new MySqlConnector().queryReader(config, "SELECT 1", "query", 0, 100));
    }

    @Test
    void everyColumnAQueryReadsIsMarked() {
        PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check("""
            kilnmark: pipeline/v1
            name: by-query
            nodes:
              - id: q
                type: read
                source: warehouse
                query: SELECT id, email FROM crm.customers
            """, io.kilnmark.kiln.NodeFactories.discover().register(read), Map.of(), Map.of());
        assertTrue(PipelinePreviews.hasQueryRead(c));
        // Whatever the catalogue says: the query may read tables of other schemas or sources.
        assertEquals(Set.of(QualityStore.EVERY_COLUMN), PipelinePreviews.readMarks(c, null, urn -> Set.of()).get("q"));
        assertTrue(NodePreview.marked(c, Map.of("q", Set.of(QualityStore.EVERY_COLUMN)), "q").all());
    }
}
