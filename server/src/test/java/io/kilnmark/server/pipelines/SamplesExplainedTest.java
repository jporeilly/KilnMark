package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every sample and template explains itself, in the comment it starts with: what it shows, why it
 * matters, what to look for when it runs, and what it needs. The Library shows these on each sample's and
 * template's card, so one without all three fails the build.
 */
class SamplesExplainedTest {

    private static Path samples() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("samples");
    }

    @Test
    void everySampleAndTemplateExplainsItself() throws Exception {
        List<Path> files;
        try (Stream<Path> all = Files.walk(samples())) {
            files = all.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertTrue(files.size() > 30, files.toString());
        List<String> unexplained = new ArrayList<>();
        for (Path file : files) {
            Samples.Explanation e = Samples.explanation(Files.readString(file));
            if (!e.complete()) {
                unexplained.add(samples().relativize(file) + " " + e);
            }
        }
        assertEquals(List.of(), unexplained, "each starts with a title, what it shows, \"# Why it matters:\", "
            + "\"# Look for:\" and \"# Needs:\"");
        // And as the server bundles them, for the Library.
        for (Samples.Sample s : Samples.bundled()) {
            assertTrue(s.explanation().complete(), s.file());
        }
        for (Samples.Sample s : Samples.templates()) {
            assertTrue(s.explanation().complete(), s.file());
        }
        assertEquals(files.size(), Samples.bundled().size() + Samples.templates().size(), "every file is bundled");
    }

    @Test
    void theCommentIsReadIntoItsParts() {
        Samples.Explanation e = Samples.explanation("""
            # Parameters: values given for each run (a Kilnmark sample).
            # Reads the orders asked for,
            # and names the file. Run it with:
            #   POST /api/v1/pipelines/x/runs
            #   {"parameters": {"label": "both"}}
            # Then open the file.
            # Why it matters: one pipeline
            # replaces many.
            # Look for: the note under
            # the read node.
            #
            # Needs: shop-db and files.
            kilnmark: pipeline/v1
            # not part of it
            name: x
            """);
        assertEquals("Parameters: values given for each run", e.title());
        assertEquals("Reads the orders asked for, and names the file. Run it with:\n"
            + "POST /api/v1/pipelines/x/runs\n{\"parameters\": {\"label\": \"both\"}}\nThen open the file.", e.shows());
        assertEquals("one pipeline replaces many.", e.why());
        assertEquals("the note under the read node.", e.lookFor());
        assertEquals("shop-db and files.", e.needs());
        assertTrue(e.complete());

        Samples.Explanation template = Samples.explanation("# Template: copy a table (a Kilnmark template).\n"
            + "# Copies it.\n# Needs: a database.\nkilnmark: pipeline/v1\n");
        assertEquals("Template: copy a table", template.title());
        assertNull(template.lookFor());
        assertTrue(!template.complete());

        Samples.Explanation none = Samples.explanation("kilnmark: pipeline/v1\nname: x\n");
        assertNull(none.title());
        assertTrue(!none.complete());
        Samples.Explanation blank = Samples.explanation("# A title.\n# Shows.\n# Look for:\n# Needs: x.\n");
        assertNull(blank.lookFor());
        assertTrue(!blank.complete());
    }

    @Test
    void aFlowsCheckedTablesAreRepointedAndOnlySqlNeedsSql() {
        String flow = """
            kilnmark: flow/v1
            name: f
            nodes:
              - id: ready
                type: check-source
                source: crm-db
                tables: [crm.customers, crm.other, shop.orders]
              - id: landed
                type: wait-for
                source: files
                file: samples/x.csv
            """;
        String out = Samples.withTables(flow, "s1");
        assertTrue(out.contains("    tables: [s1.crm_customers, crm.other, s1.shop_orders]\n"), out);
        assertEquals(List.of(), Samples.sqlSources(flow));
        String sql = flow + """
              - id: q
                type: wait-for
                source: shop-db
                query: SELECT count(*) > 0 FROM shop.orders
            """;
        assertEquals(List.of("shop-db"), Samples.sqlSources(sql));
    }
}
