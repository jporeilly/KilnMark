package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

/** Rules on a file are evaluated by Kiln while the file streams through, all in one pass. */
class FileRulesStreamTest {

    @TempDir
    Path dir;

    @Test
    void aCsvFilesRulesAreCheckedInOneStreamingPass() throws Exception {
        StringBuilder csv = new StringBuilder("id,email,postcode,amount\n");
        for (int i = 1; i <= 5000; i++) {
            String email = i % 100 == 0 ? "" : i % 250 == 0 ? "not an email" : "buyer" + i + "@example.com";
            String postcode = i % 500 == 0 ? "unknown" : (i % 2 == 0 ? "SW1A 1AA" : "M1 1AE");
            int id = i == 5000 ? 4999 : i; // one duplicate id
            csv.append(id).append(',').append(email).append(',').append(postcode).append(',')
                .append(i % 1000 == 0 ? -5 : i % 97).append('\n');
        }
        Files.writeString(dir.resolve("orders.csv"), csv);
        Connector files = Connectors.load(new ConnectorContext(Map.of("kilnmark.files.roots", dir.toString())))
            .get("files").orElseThrow();
        SourceConfig source = new SourceConfig("drop", "files", Map.of("path", dir.toString()), null);
        AssetInfo asset = files.catalog(source).stream().filter(a -> a.path().equals("orders.csv")).findFirst()
            .orElseThrow();
        Map<String, String> kinds = new LinkedHashMap<>();
        asset.fields().forEach(f -> kinds.put(f.name(), f.kind()));
        assertEquals("int64", kinds.get("id"));

        String urn = "urn:kilnmark:drop:orders.csv";
        RuleDraft.Context ctx = new RuleDraft.Context() {
            @Override
            public Map<String, String> fields() {
                return kinds;
            }

            @Override
            public String sourceKind() {
                return "files";
            }

            @Override
            public boolean sql() {
                return false;
            }

            @Override
            public Optional<Map<String, String>> fieldsOf(String u) {
                return Optional.empty();
            }

            @Override
            public List<String> webhookAllowList() {
                return List.of();
            }
        };
        List<Rule> rules = List.of(
            new RuleDraft(urn, "email", null, "not_null", null, Facts.JSON.readTree("{\"blankIsMissing\":true}"), null,
                null, null, null, null).check(ctx, "t"),
            new RuleDraft(urn, "postcode", null, "matches_pattern", null,
                Facts.JSON.readTree("{\"regex\":\"^[A-Z]{1,2}[0-9][A-Z0-9]? [0-9][A-Z]{2}$\"}"), "share", 0.0, 0.01,
                null, null).check(ctx, "t"),
            new RuleDraft(urn, "id", null, "unique", null, null, null, null, null, null, null).check(ctx, "t"),
            new RuleDraft(urn, "amount", null, "in_range", null, Facts.JSON.readTree("{\"min\":0}"), null, null, null,
                null, null).check(ctx, "t"),
            new RuleDraft(urn, null, null, "row_count", null, Facts.JSON.readTree("{\"min\":1000}"), null, null, null,
                null, null).check(ctx, "t"),
            new RuleDraft(urn, null, null, "row_count_change", null, Facts.JSON.readTree("{\"maxChange\":0.1}"), null,
                null, null, null, null).check(ctx, "t"));

        RuleRunner runner = new RuleRunner(Duration.ofSeconds(10), 3, 64L << 20);
        List<RowChecks.Evaluation> e = runner.run(rules, new RuleRunner.Target(files, source, asset, kinds, 4000L,
            u -> null), Instant.now());

        RowChecks.Evaluation notNull = e.get(0);
        assertNull(notNull.error(), notNull.error());
        assertEquals("stream", notNull.method());
        assertEquals(5000, notNull.total());
        assertEquals(50, notNull.failing(), "every hundredth email is empty");
        assertEquals(3, notNull.failingRows().rows().size(), "a few failing rows kept");
        assertEquals(List.of("id", "email", "postcode", "amount"), notNull.failingRows().columns());

        assertEquals(10, e.get(1).failing(), "every five-hundredth postcode is 'unknown'");
        assertEquals(Rule.Status.WARN, rules.get(1).status(e.get(1).failing(), e.get(1).total()),
            "0.2% is over the 0% warning level and under the 1% failure level");
        assertEquals(2, e.get(2).failing(), "id 4999 twice");
        assertEquals(5, e.get(3).failing(), "five negative amounts");
        assertEquals(0, e.get(4).failing(), "5,000 rows, at least 1,000");
        assertEquals(1, e.get(5).failing(), "25% more rows than last time");
        assertNotNull(e.get(5).message());
        assertTrue(e.get(5).message().contains("25%"), e.get(5).message());
    }
}
