package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;

/**
 * The SQL a read node sends to its database, shown on the canvas before it runs: worked out with
 * the same options and the same reader as a run, from the table as last scanned, without
 * connecting. A query read shows the query as it is sent; a file or an unscanned table says why
 * there is none.
 */
class ReadSqlTest {

    private static final String URL = "jdbc:postgresql:" + "/" + "/h/db";
    private final FakeSources sources = new FakeSources()
        .add("warehouse", "postgresql", Map.of("url", URL, "allow_sql", "true"), "pw")
        .add("sampled", "postgresql", Map.of("url", URL, "table_sample_above_rows", "1000000"), "pw")
        .add("lab-db", Map.of(), null)
        .add("odd", "no-such-kind", Map.of(), null);
    private final ReadNodeFactory read;

    ReadSqlTest() {
        sources.connectors.put("postgresql", new PostgresConnector());
        for (String source : List.of("warehouse", "sampled")) {
            sources.assets.put("urn:kilnmark:" + source + ":crm.customers", table(source, "table"));
            sources.assets.put("urn:kilnmark:" + source + ":crm.active_customers", table(source, "view"));
        }
        read = new ReadNodeFactory(sources);
    }

    private static Records.AssetDetail table(String source, String kind) {
        String name = kind.equals("view") ? "active_customers" : "customers";
        List<Records.FieldDetail> fields = List.of(
            new Records.FieldDetail("id", 1, "int8", "int64", false, null, null, null),
            new Records.FieldDetail("status", 2, "text", "text", true, null, null, null),
            new Records.FieldDetail("amount", 3, "numeric(12,2)", "decimal", true, null, null, null),
            new Records.FieldDetail("Joined On", 4, "date", "date", true, null, null, null));
        return new Records.AssetDetail("urn:kilnmark:" + source + ":crm." + name, source, kind, "crm", name, null,
            List.of("id"), null, null, Instant.now(), Instant.now(), null, fields);
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aWholeTableIsReadWithStar() {
        ReadNodeFactory.SqlShown s = read.statement(config("source", "warehouse", "asset", "crm.customers"));
        assertEquals("SELECT * FROM \"crm\".\"customers\"", s.sql());
        assertEquals(0, s.values());
        assertNull(s.note());
    }

    @Test
    void chosenColumnsAndTheRowLimitGoIntoTheStatement() {
        ReadNodeFactory.SqlShown s = read.statement(config("source", "warehouse", "asset", "crm.customers",
            "columns", List.of("status", "id"), "row_limit", 10));
        // The table's own order: the read puts the chosen order back as rows arrive.
        assertEquals("SELECT \"id\", \"status\" FROM \"crm\".\"customers\" LIMIT 10", s.sql());
        assertEquals(0, s.values());
    }

    @Test
    void aConditionTheDatabaseKeepsExactlyKeepsTheLimit() {
        // No text compared: the database keeps exactly the rows Kilnmark keeps, so it stops at the limit.
        ReadNodeFactory.SqlShown s = read.statement(config("source", "warehouse", "asset", "crm.customers",
            "where", "id > 5", "row_limit", 10));
        assertEquals("SELECT * FROM \"crm\".\"customers\" WHERE \"id\" > ? LIMIT 10", s.sql());
        assertEquals(1, s.values());
    }

    @Test
    void whereGoesInWithEachValueBound() {
        // The columns where needs are read too. Text is compared here, which the database may compare
        // otherwise than Kilnmark, so Kilnmark counts the rows it keeps and the limit is not sent.
        ReadNodeFactory.SqlShown s = read.statement(config("source", "warehouse", "asset", "crm.customers",
            "columns", List.of("status", "id"), "where", "status = 'active' and amount > 0", "row_limit", 10));
        assertEquals("SELECT \"id\", \"status\", \"amount\" FROM \"crm\".\"customers\" WHERE (\"status\" = ? AND "
            + "\"amount\" > ?)", s.sql());
        assertEquals(2, s.values());
    }

    @Test
    void whatTheDatabaseCannotApplyIsLeftToKilnmark() {
        // The arithmetic stays with Kilnmark as rows arrive; the database applies the rest.
        ReadNodeFactory.SqlShown s = read.statement(config("source", "warehouse", "asset", "crm.customers",
            "where", "status = 'active' and amount * 2 > 10"));
        assertEquals("SELECT * FROM \"crm\".\"customers\" WHERE \"status\" = ?", s.sql());
        assertEquals(1, s.values());
    }

    @Test
    void aSampledSourceSaysSoForATableButNotAView() {
        ReadNodeFactory.SqlShown table = read.statement(config("source", "sampled", "asset", "crm.customers"));
        assertEquals("SELECT * FROM \"crm\".\"customers\"", table.sql());
        assertTrue(table.note().contains("more than 1000000 rows"), table.note());
        ReadNodeFactory.SqlShown view = read.statement(config("source", "sampled", "asset", "crm.active_customers"));
        assertEquals("SELECT * FROM \"crm\".\"active_customers\"", view.sql());
        assertNull(view.note());
    }

    @Test
    void aQueryIsShownAsItIsSent() {
        assertEquals("SELECT id FROM crm.customers", read.statement(config("source", "warehouse",
            "query", "SELECT id FROM crm.customers;")).sql());
        assertEquals("SELECT * FROM (\nSELECT id FROM crm.customers\n) AS kilnmark_query LIMIT 5",
            read.statement(config("source", "warehouse", "query", "SELECT id FROM crm.customers", "row_limit", 5)).sql());
        ReadNodeFactory.SqlShown refused = read.statement(config("source", "sampled", "query", "SELECT 1"));
        assertNull(refused.sql());
        assertTrue(refused.note().startsWith("Fix the problems shown with the query"), refused.note());
        assertNull(read.statement(config("source", "warehouse", "query", "DELETE FROM crm.customers")).sql());
    }

    @Test
    void whenThereIsNoSqlTheNoteSaysWhy() {
        assertEquals("Choose a source to see the SQL this read runs.", read.statement(config()).note());
        assertEquals("There is no saved source by that name.", read.statement(config("source", "gone",
            "asset", "crm.customers")).note());
        assertEquals("Kilnmark reads this source itself, without SQL.", read.statement(config("source", "lab-db",
            "asset", "crm.customers")).note());
        assertTrue(read.statement(config("source", "odd", "asset", "crm.customers")).note()
            .startsWith("This server has no connector"));
        assertEquals("Choose a table to see the SQL this read runs.", read.statement(config("source",
            "warehouse")).note());
        assertTrue(read.statement(config("source", "warehouse", "asset", "crm.orders")).note()
            .startsWith("Shown once the table has been scanned"));
        ReadNodeFactory.SqlShown bad = read.statement(config("source", "warehouse", "asset", "crm.customers",
            "where", "no_such_column = 1"));
        assertNull(bad.sql());
        assertTrue(bad.note().startsWith("Fix the problems shown"), bad.note());
    }

    @Test
    void theQuerysHelpSaysWhereAQueryRuns() {
        // The canvas shows this under the Query box, editable or not.
        String help = read.settings().stream().filter(s -> s.key().equals("query")).findFirst().orElseThrow().help();
        assertTrue(help.contains("PostgreSQL sources with Allow SQL on"), help);
    }

    @Test
    void theCataloguedTableIsDescribedAsTheConnectorDescribesIt() {
        AssetInfo t = ReadNodeFactory.catalogued(table("warehouse", "table"));
        assertEquals("crm.customers", t.path());
        assertEquals(Map.of("schema", "crm", "table", "customers"), t.hints());
        assertFalse(t.bounded());
        assertTrue(ReadNodeFactory.catalogued(table("warehouse", "view")).bounded());
        assertEquals(List.of("id", "status", "amount", "Joined On"), t.fields().stream().map(AssetInfo.FieldInfo::name)
            .toList());
    }
}
