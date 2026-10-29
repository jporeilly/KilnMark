package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.yaml.PipelineDefinition;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import jakarta.ws.rs.WebApplicationException;

/**
 * Preview at a node without a database: which nodes run, what is refused, the limits, masking of
 * columns made from columns marked sensitive, and the run itself over the in-memory test connector
 * (synthetic people).
 */
class NodePreviewTest {

    static final String PEOPLE = "urn:kilnmark:lab-db:lab.people";

    private final FakeSources sources = new FakeSources()
        .add("lab-db", Map.of("rows", "1000000000"), "s3cret-pass")
        .add("small", Map.of("rows", "40"), null)
        .add("warehouse", Map.of("allow_writing", "true"), null);
    private final NodeFactories factories = NodeFactories.discover().register(new ReadNodeFactory(sources))
        .register(new WriteNodeFactory(sources)).register(new GateNodeFactory(sources));

    private PipelinePreviews previews(Duration timeout, int perMinute, long memoryBytes) {
        return new PipelinePreviews(factories, null, sources, new PipelinePreviews.Limits(memoryBytes, 4, timeout,
            perMinute, 4, 256));
    }

    private PipelinePreviews previews() {
        return previews(Duration.ofSeconds(30), 100, 64L << 20);
    }

    /** A viewer who may or may not see sensitive columns, with these marks on lab.people. */
    static PipelinePreviews.Viewer viewer(boolean admin, String... marks) {
        return new PipelinePreviews.Viewer() {
            @Override
            public boolean mayUnmask() {
                return admin;
            }

            @Override
            public Set<String> marks(String urn) {
                return urn.equals(PEOPLE) ? Set.of(marks) : Set.of();
            }
        };
    }

    static final PipelinePreviews.Viewer STEWARD = viewer(false);

    private static int status(WebApplicationException e) {
        return e.getResponse().getStatus();
    }

    private static String error(WebApplicationException e) {
        return ((ApiError) e.getResponse().getEntity()).error();
    }

    static String yaml(String nodes) {
        return "kilnmark: pipeline/v1\nname: people-tidy\nnodes:\n" + nodes;
    }

    static final String MAIN = yaml("""
          - id: people
            type: read
            source: lab-db
            asset: lab.people
          - id: others
            type: read
            source: small
            asset: lab.others
          - id: tidy
            type: compute
            input: people
            columns:
              shout: "upper(name)"
              next_id: "id + 1"
          - id: some
            type: filter
            input: tidy
            condition: "id > 10"
          - id: out
            type: write
            input: some
            source: warehouse
            table: lab.people_tidy
          - id: broken
            type: compute
            input: others
            columns:
              x: "nonsense(("
        """);

    // ---- which nodes run ----

    @Test
    void onlyTheNodeAndWhatIsUpstreamOfItRun() {
        NodePreview.Plan p = NodePreview.plan(MAIN, "some");
        assertEquals(List.of("people", "tidy", "some"), p.definition().nodes().stream()
            .map(PipelineDefinition.NodeDefinition::id).toList(), "no write, no other branch");
        assertEquals("filter", p.type());
        assertEquals(List.of("people"), NodePreview.plan(MAIN, "people").definition().nodes().stream()
            .map(PipelineDefinition.NodeDefinition::id).toList());
        assertEquals(Set.of("a", "b", "c", "d"), NodePreview.upstreamAndSelf("d", ids(NodePreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: read, source: small, asset: lab.b}
              - {id: c, type: lookup, inputs: {main: a, lookup: b}, on: {id: id}}
              - {id: d, type: filter, input: c.rejected, condition: "id > 1"}
              - {id: e, type: filter, input: d, condition: "id > 1"}
            """), "e").definition())));
    }

    private static Map<String, PipelineDefinition.NodeDefinition> ids(PipelineDefinition d) {
        Map<String, PipelineDefinition.NodeDefinition> out = new java.util.LinkedHashMap<>();
        d.nodes().forEach(s -> out.put(s.id(), s));
        return out;
    }

    @Test
    void writesAndNodesThatCouldChangeAnythingAreRefused() {
        IllegalArgumentException write = assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(MAIN, "out"));
        assertTrue(write.getMessage().contains("writes rows"), write.getMessage());
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: send-email, input: a}
              - {id: c, type: filter, input: b, condition: "id > 1"}
            """), "c"));
        assertTrue(other.getMessage().startsWith("Node b (send-email) cannot run in a preview"), other.getMessage());
        assertTrue(other.getMessage().contains("c needs its rows"), other.getMessage());
        assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(MAIN, "nowhere"));
        assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(MAIN, " "));
        assertThrows(IllegalArgumentException.class, () -> NodePreview.plan("kilnmark: [", "a"));
        assertTrue(NodePreview.PREVIEWABLE.stream().noneMatch(t -> t.equals(WriteNodeFactory.TYPE)));
    }

    @Test
    void problemsElsewhereDoNotStopAPreviewButProblemsInItDo() {
        PipelineViews.NodePreviewView v = previews().preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD,
            STEWARD, () -> false);
        assertEquals(5, v.rowCount());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> previews().preview(MAIN,
            "broken", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertTrue(e.getMessage().startsWith("Fix the pipeline before previewing broken"), e.getMessage());
    }

    @Test
    void portsAreChecked() {
        PipelineViews.NodePreviewView rejected = previews().preview(MAIN, "some", "rejected", 3, "jo",
            AccessRole.STEWARD, STEWARD, () -> false);
        assertEquals("rejected", rejected.port());
        assertEquals(List.of("1", "2", "3"), rejected.rows().stream().map(r -> r.get(0)).toList(),
            "ids 1 to 10 fail id > 10");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> previews().preview(MAIN, "some",
            "elsewhere", 3, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertTrue(e.getMessage().contains("its outputs are out, rejected"), e.getMessage());
    }

    // ---- running ----

    @Test
    void theFirstRowsArriveAndTheNodesBeforeEndEarly() {
        long started = System.nanoTime();
        PipelineViews.NodePreviewView v = previews().preview(MAIN, "tidy", null, null, "jo", AccessRole.STEWARD,
            STEWARD, () -> false);
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(20).toNanos(),
            "a billion rows upstream: the read ended early");
        assertEquals(100, v.rowCount(), "100 rows by default");
        assertFalse(v.complete());
        assertEquals("tidy.out", v.asset());
        assertEquals(List.of("id", "name", "shout", "next_id"), v.columns().stream()
            .map(PipelineViews.PreviewColumn::name).toList());
        assertEquals(List.of("int64", "text", "text", "int64"), v.columns().stream()
            .map(PipelineViews.PreviewColumn::kind).toList());
        assertEquals(List.of("1", "Person 1", "PERSON 1", "2"), v.rows().get(0));
        assertEquals(List.of(), v.masked());
        assertEquals(List.of(), v.waits());

        PipelineViews.NodePreviewView all = previews().preview(yaml("""
              - {id: few, type: read, source: small, asset: lab.few}
            """), "few", null, 1000, "jo", AccessRole.STEWARD, STEWARD, () -> false);
        assertEquals(40, all.rowCount());
        assertTrue(all.complete(), "the node gave fewer rows than asked for: these are all of them");
    }

    @Test
    void rowsAreLimited() {
        for (int rows : new int[] {0, -1, 1001}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> previews().preview(MAIN,
                "tidy", null, rows, "jo", AccessRole.STEWARD, STEWARD, () -> false));
            assertEquals("rows: from 1 to 1000", e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> previews().preview(" ", "tidy", null, 5, "jo",
            AccessRole.STEWARD, STEWARD, () -> false));
    }

    @Test
    void aNodeReadingAllItsRowsIsExplainedAndStoppedAtTheTimeLimit() {
        String yaml = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - id: counts
                type: aggregate
                input: people
                values: {n: "count(*)"}
            """);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews(Duration.ofMillis(800),
            100, 64L << 20).preview(yaml, "counts", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertEquals(422, status(e));
        assertTrue(error(e).startsWith("The preview took longer than 1 second, so it was stopped. Node counts "
            + "(aggregate) reads every row it is given before it passes any on"), error(e));
    }

    @Test
    void aClosedRequestStopsThePreview() {
        String yaml = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: counts, type: aggregate, input: people, values: {n: "count(*)"}}
            """);
        long started = System.nanoTime();
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(yaml, "counts",
            null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> System.nanoTime() - started > 300_000_000L));
        assertEquals(422, status(e));
        assertEquals("The preview was stopped: its request was closed.", error(e));
        assertFalse(Thread.currentThread().isInterrupted(), "the interrupt meant for the preview is cleared");
    }

    @Test
    void thePreviewHasAMemoryLimitOfItsOwn() {
        String yaml = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: names, type: aggregate, input: people, group_by: [name], values: {n: "count(*)"}}
            """);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews(Duration.ofSeconds(30),
            100, 1L << 20).preview(yaml, "names", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertEquals(422, status(e));
        assertTrue(error(e).contains("memory"), error(e));
    }

    @Test
    void onePreviewPerPersonAtATimeAndSoManyAMinute() {
        PipelinePreviews p = previews(Duration.ofSeconds(30), 2, 64L << 20);
        AtomicReference<WebApplicationException> second = new AtomicReference<>();
        p.preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> {
            if (second.get() == null) {
                // Asked while the first is running (the watch asks on its own thread).
                second.set(assertThrows(WebApplicationException.class, () -> p.preview(MAIN, "people", null, 5, "jo",
                    AccessRole.STEWARD, STEWARD, () -> false)));
            }
            return false;
        });
        assertEquals(429, status(second.get()));
        assertTrue(error(second.get()).startsWith("You already have a preview running"));
        p.preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false);
        WebApplicationException third = assertThrows(WebApplicationException.class, () -> p.preview(MAIN, "people",
            null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertEquals(429, status(third));
        assertTrue(error(third).startsWith("You have previewed 2 times in the last minute"), error(third));
        assertEquals(5, p.preview(MAIN, "people", null, 5, "sam", AccessRole.STEWARD, STEWARD, () -> false)
            .rowCount(), "each person has their own count");
    }

    // ---- masking: hidden columns pass through untouched, and nothing may look inside one ----

    @Test
    void sensitiveColumnsPassThroughMaskedForAllButAdmins() {
        String y = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: tidy, type: compute, input: people, columns: {next_id: "id + 1"}}
              - {id: renamed, type: select, input: tidy, rename: {name: who}}
            """);
        PipelineViews.NodePreviewView v = previews().preview(y, "renamed", null, 3, "jo", AccessRole.STEWARD,
            viewer(false, "name"), () -> false);
        assertEquals(List.of("who"), v.masked(), "a renamed hidden column stays hidden");
        assertEquals(List.of("1", Masking.MASK, "2"), v.rows().get(0));

        PipelineViews.NodePreviewView admin = previews().preview(y, "renamed", null, 3, "ann", AccessRole.ADMIN,
            viewer(true, "name"), () -> false);
        assertEquals(List.of(), admin.masked());
        assertEquals("Person 1", admin.rows().get(0).get(1));
    }

    /** The review's proof of concept: a computed column named like a hidden one in another case. */
    @Test
    void aComputedColumnInAnotherCaseNeverUnhidesOne() {
        String y = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: c, type: compute, input: people, columns: {NAME: "'x'"}}
            """);
        PipelineViews.NodePreviewView v = previews().preview(y, "c", null, 3, "jo", AccessRole.STEWARD,
            viewer(false, "name"), () -> false);
        assertTrue(v.masked().contains("name"), v.masked().toString());
        int at = v.columns().stream().map(PipelineViews.PreviewColumn::name).toList().indexOf("name");
        assertTrue(at >= 0);
        assertTrue(v.rows().stream().allMatch(r -> Masking.MASK.equals(r.get(at))), "name's values stay masked");
        assertFalse(v.rows().stream().flatMap(List::stream).anyMatch(x -> x != null && x.startsWith("Person")));
        // And an exact-case overwrite stays masked too.
        PipelineViews.NodePreviewView same = previews().preview(yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: c, type: compute, input: people, columns: {name: "'x'"}}
            """), "c", null, 3, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false);
        assertTrue(same.masked().contains("name"));
    }

    @Test
    void hiddenStaysHiddenThroughRenamesUnionsAndLimits() {
        sources.asset("urn:kilnmark:small:lab.people", "id:int64", "name:text");
        sources.asset("urn:kilnmark:small:lab.other", "id:int64", "name:text");
        NodePreview.Hidden h = hidden("""
              - {id: people, type: read, source: small, asset: lab.people}
              - {id: other, type: read, source: small, asset: lab.other}
              - {id: u, type: union, input: [people, other]}
              - {id: r, type: select, input: u, rename: {name: who}}
              - {id: l, type: limit, input: r, rows: 5}
            """, "l", Set.of("name"));
        assertTrue(h.hides("who") && h.hides("WHO") && h.hides("name"));
        assertFalse(h.hides("id"));
    }

    private NodePreview.Hidden hidden(String nodes, String node, Set<String> marks) {
        PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(NodePreview.plan(yaml(nodes), node)
            .definition(), factories, false);
        assertEquals(List.of(), c.problems());
        Map<String, Set<String>> reads = new java.util.HashMap<>();
        c.nodes().stream().filter(s -> s.type().equals("read") && s.id().equals("people"))
            .forEach(s -> reads.put(s.id(), marks));
        return NodePreview.hidden(c, reads, node);
    }

    /**
     * No node may look inside a hidden column: an expression, a cast, a key, a group or a rule could
     * show its values through the rows, a computed value, or whether the preview succeeds.
     */
    @Test
    void anyNodeLookingInsideASensitiveColumnIsRefusedForAllButAdmins() {
        String byName = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: ps, type: filter, input: people, condition: "name like 'Person 1%'"}
            """);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(byName, "ps",
            null, 5, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false));
        assertEquals(403, status(e));
        assertTrue(error(e).startsWith("Only an admin can preview this: node ps (filter) chooses rows by name, which "
            + "is marked sensitive"), error(e));
        assertEquals(5, previews().preview(byName, "ps", null, 5, "ann", AccessRole.ADMIN, viewer(true, "name"),
            () -> false).rowCount());
        assertEquals(5, previews().preview(byName, "ps", null, 5, "jo", AccessRole.STEWARD, viewer(false, "id"),
            () -> false).rowCount(), "a filter on visible columns is fine");

        for (String node : List.of("""
              - {id: x, type: compute, input: people, columns: {shout: "upper(name)"}}
            """, """
              - {id: x, type: compute, input: people, columns: {n: "cast(name as int64)"}}
            """, """
              - {id: x, type: select, input: people, cast: {name: int64}}
            """, """
              - {id: x, type: dedupe, input: people, key: [name]}
            """, """
              - {id: x, type: dedupe, input: people}
            """, """
              - {id: x, type: route, input: people, routes: {p: "length(name) > 8"}}
            """, """
              - {id: x, type: aggregate, input: people, group_by: [name], values: {n: "count(*)"}}
            """, """
              - {id: x, type: aggregate, input: people, values: {n: "max(length(name))"}}
            """, """
              - {id: y, type: read, source: small, asset: lab.other}
              - {id: x, type: lookup, inputs: {main: people, lookup: y}, on: {name: name}}
            """, """
              - {id: x, type: gate, input: people, on_fail: stop, asset_rules: false, rules: [{type: matches_pattern, field: name, regex: "^Person 1$"}]}
            """, """
              - {id: x, type: gate, input: people, on_fail: warn, asset_rules: false, rules: [{type: not_null, field: name}]}
            """)) {
            String y = yaml("  - {id: people, type: read, source: lab-db, asset: lab.people}\n" + node);
            WebApplicationException r = assertThrows(WebApplicationException.class, () -> previews().preview(y, "x",
                null, 5, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false), node);
            assertEquals(403, status(r), node);
            assertTrue(error(r).startsWith("Only an admin can preview this: node x ("), error(r));
        }
    }

    /** The review's oracle: a gate's success or failure must not tell a hidden value, whatever it does. */
    @Test
    void aGateCheckingAHiddenColumnIsRefusedWhetherItsRulesAreInlineOrTheAssets() {
        String inline = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: one, type: limit, input: people, rows: 1}
              - id: g
                type: gate
                input: one
                asset_rules: false
                max_failing_share: 0
                rules: [{type: matches_pattern, field: name, regex: "^Person 1$"}]
            """);
        for (String regex : List.of("^Person 1$", "^Person 2$")) {
            WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(
                inline.replace("^Person 1$", regex), "g", null, 1, "jo", AccessRole.STEWARD, viewer(false, "name"),
                () -> false));
            assertEquals(403, status(e), "the same answer whatever the value: " + regex);
        }
        sources.rules.put(PEOPLE, GateRules.read(List.of(Map.of("type", "not_null", "field", "name")), null));
        String asset = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: g, type: gate, input: people, on_fail: warn}
            """);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(asset, "g",
            null, 1, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false));
        assertEquals(403, status(e));
        assertTrue(error(e).contains("checks rules on name"), error(e));
        assertEquals(1, previews().preview(asset, "g", null, 1, "jo", AccessRole.STEWARD, viewer(false, "id"),
            () -> false).rowCount(), "rules on visible columns are fine");
    }

    /** Review 2: a unique rule names its columns in params.fields; inline or the asset's, it is an oracle. */
    @Test
    void aGateWithAUniqueRuleOverAHiddenColumnIsRefused() {
        for (String guess : List.of("Person 1", "Person 9")) {
            String y = yaml("""
                  - {id: people, type: read, source: lab-db, asset: lab.people}
                  - {id: a, type: limit, input: people, rows: 1}
                  - {id: g, type: gate, input: a, asset_rules: false, max_failing_share: 0, rules: [{type: unique, fields: [id, name]}]}
                """.replace("Person 1", guess));
            WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(y, "g",
                null, 1, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false));
            assertEquals(403, status(e), guess);
        }
        sources.rules.put(PEOPLE, GateRules.read(List.of(Map.of("type", "unique", "fields", List.of("id", "name"))),
            null));
        WebApplicationException asset = assertThrows(WebApplicationException.class, () -> previews().preview(yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: g, type: gate, input: people, on_fail: warn}
            """), "g", null, 1, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false));
        assertEquals(403, status(asset));
        Set<String> cols = new java.util.HashSet<>();
        PipelinePreviews.ruleColumns(GateRules.read(List.of(Map.of("type", "unique", "fields", List.of("a", "b"))),
            null).get(0), cols);
        assertEquals(Set.of("a", "b"), cols);
    }

    /** Review 2: a union converting a hidden column between types can fail on a value. */
    @Test
    void aUnionConvertingAHiddenColumnIsRefused() {
        sources.asset(PEOPLE, "id:int64", "name:text");
        sources.asset("urn:kilnmark:small:lab.x", "id:int64", "name:text");
        for (int scale : new int[] {36, 37}) {
            String y = yaml("""
                  - {id: people, type: read, source: lab-db, asset: lab.people}
                  - {id: a, type: limit, input: people, rows: 10}
                  - {id: other, type: read, source: small, asset: lab.x}
                  - {id: b, type: compute, input: other, columns: {id: "cast(0 as decimal(38,%d))"}}
                  - {id: u, type: union, input: [a, b]}
                """.formatted(scale));
            WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(y, "u",
                null, 5, "jo", AccessRole.STEWARD, viewer(false, "id"), () -> false));
            assertEquals(403, status(e));
            assertTrue(error(e).contains("(union) converts id"), error(e));
        }
        String same = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: other, type: read, source: small, asset: lab.x}
              - {id: u, type: union, input: [people, other]}
            """);
        PipelineViews.NodePreviewView v = previews().preview(same, "u", null, 5, "jo", AccessRole.STEWARD,
            viewer(false, "id"), () -> false);
        assertEquals(List.of("id"), v.masked(), "the same type on every side: nothing converted");
        String unknown = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: other, type: read, source: small, asset: lab.unscanned}
              - {id: u, type: union, input: [people, other]}
            """);
        assertEquals(403, status(assertThrows(WebApplicationException.class, () -> previews().preview(unknown, "u",
            null, 5, "jo", AccessRole.STEWARD, viewer(false, "id"), () -> false))));
    }

    /** Review 2: any memory_limit_mb, decimal or text, is replaced by the preview's share. */
    @Test
    void theMemoryLimitIsAlwaysTheSharesWhateverWasWritten() {
        for (String v : List.of("1048576", "1048576.0", "\"1048576\"", "1e30", "0")) {
            NodePreview.Plan p = NodePreview.plan(yaml("""
                  - {id: people, type: read, source: lab-db, asset: lab.people}
                  - {id: d, type: dedupe, input: people, key: [id], memory_limit_mb: %s}
                """.formatted(v)), "d");
            assertEquals(256L, p.definition().nodes().get(1).config().get("memory_limit_mb"), v);
            PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(p.definition(), factories, false);
            assertEquals(List.of(), c.problems(), v);
        }
    }

    @Test
    void theRequestsThreadIsNeverLeftInterrupted() {
        Thread.currentThread().interrupt();
        try {
            previews().preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false);
        } catch (WebApplicationException e) {
            // stopped, as it was interrupted: fine
        }
        assertFalse(Thread.currentThread().isInterrupted());
    }

    // ---- sizes and limits ----

    @Test
    void nodesHoldingStateGetAShareOfThePreviewsMemory() {
        NodePreview.Plan p = NodePreview.plan(yaml("""
              - {id: people, type: read, source: small, asset: lab.people}
              - {id: a, type: aggregate, input: people, values: {n: "count(*)"}, memory_limit_mb: 1000000}
              - {id: d, type: dedupe, input: people, memory_limit_mb: 10}
              - {id: u, type: union, input: [a, d]}
            """), "u", new NodePreview.Caps(50, 10, 256));
        Map<String, Object> a = p.definition().nodes().stream().filter(s -> s.id().equals("a")).findFirst().orElseThrow()
            .config();
        Map<String, Object> d = p.definition().nodes().stream().filter(s -> s.id().equals("d")).findFirst().orElseThrow()
            .config();
        Map<String, Object> u = p.definition().nodes().stream().filter(s -> s.id().equals("u")).findFirst().orElseThrow()
            .config();
        assertEquals(85L, a.get("memory_limit_mb"), "a million MB is lowered to a third of 256");
        assertEquals(10L, d.get("memory_limit_mb"), "a lower limit is kept");
        assertEquals(85L, u.get("memory_limit_mb"), "unset: its share");
        PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(p.definition(), factories, false);
        assertEquals(List.of(), c.problems());
        assertEquals("85", String.valueOf(c.configs().get("a").get("memory_limit_mb")));
    }

    @Test
    void aPreviewHasAtMostSoManyNodesAndReads() {
        StringBuilder chain = new StringBuilder("  - {id: s0, type: read, source: small, asset: lab.people}\n");
        for (int i = 1; i <= 5; i++) {
            chain.append("  - {id: s").append(i).append(", type: limit, input: s").append(i - 1).append(", rows: 5}\n");
        }
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(
            yaml(chain.toString()), "s5", new NodePreview.Caps(4, 10, 256)));
        assertTrue(e.getMessage().startsWith("A preview runs at most 4 nodes, and s5 needs 6"), e.getMessage());
        IllegalArgumentException reads = assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: read, source: small, asset: lab.b}
              - {id: u, type: union, input: [a, b]}
            """), "u", new NodePreview.Caps(50, 1, 256)));
        assertTrue(reads.getMessage().startsWith("A preview reads from at most 1 read nodes"), reads.getMessage());
        IllegalArgumentException untyped = assertThrows(IllegalArgumentException.class, () -> NodePreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, input: a}
              - {id: c, type: limit, input: b, rows: 1}
            """), "c"));
        assertTrue(untyped.getMessage().startsWith("Node b needs a type"), untyped.getMessage());
    }

    @Test
    void theTimeLimitHoldsEvenWhenANodeIgnoresInterrupts() throws Exception {
        StuckConnector.release = new java.util.concurrent.CountDownLatch(1);
        sources.connectors.put(StuckConnector.KIND, new StuckConnector());
        sources.add("stuck", StuckConnector.KIND, Map.of(), null);
        // Answered after 0.6 s and 0.2 s of grace; the engine waits 2 s for the stuck node, then lets go.
        PipelinePreviews p = new PipelinePreviews(factories, null, sources, new PipelinePreviews.Limits(64L << 20, 4,
            Duration.ofMillis(600), 1, 1, 256, 50, 10, Duration.ofMillis(200), Duration.ofSeconds(2)));
        String y = yaml("  - {id: s, type: read, source: stuck, asset: lab.stuck}\n");
        try {
            long started = System.nanoTime();
            WebApplicationException e = assertThrows(WebApplicationException.class, () -> p.preview(y, "s", null, 5,
                "jo", AccessRole.STEWARD, STEWARD, () -> false));
            long took = (System.nanoTime() - started) / 1_000_000;
            assertEquals(422, status(e));
            assertTrue(error(e).startsWith("The preview took longer than 1 second"), error(e));
            assertTrue(took < 1_800, "answered after the limit and a short grace, not when the node let go: " + took);
            assertEquals(1, p.stillStopping());
            // Its slots are held until it has ended: the same person's, and (one at a time here) anyone's.
            WebApplicationException mine = assertThrows(WebApplicationException.class, () -> p.preview(MAIN, "people",
                null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
            assertTrue(error(mine).startsWith("You already have a preview running"));
            WebApplicationException full = assertThrows(WebApplicationException.class, () -> p.preview(MAIN, "people",
                null, 5, "sam", AccessRole.STEWARD, STEWARD, () -> false));
            assertTrue(error(full).startsWith("Kilnmark is running as many previews"), error(full));
            // Once the stuck node lets go, the preview ends and its slots are free.
            StuckConnector.release.countDown();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (p.stillStopping() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(0, p.stillStopping());
            PipelineViews.NodePreviewView later = null;
            for (int i = 0; i < 100 && later == null; i++) {
                later = sam(p);
                if (later == null) {
                    Thread.sleep(50);
                }
            }
            assertEquals(5, later == null ? -1 : later.rowCount(),
                "the slot is free again, and the refusal used none of sam's one preview a minute");
        } finally {
            StuckConnector.release.countDown();
        }
    }

    /** Review 2: a node the engine stopped waiting for still holds the preview's slot and is counted. */
    @Test
    void nodesTheEngineStoppedWaitingForStillCount() throws Exception {
        StuckConnector.release = new java.util.concurrent.CountDownLatch(1);
        sources.connectors.put(StuckConnector.KIND, new StuckConnector());
        sources.add("stuck", StuckConnector.KIND, Map.of(), null);
        // The engine waits 0.1 s for its nodes, less than the 0.3 s grace.
        PipelinePreviews p = new PipelinePreviews(factories, null, sources, new PipelinePreviews.Limits(64L << 20, 4,
            Duration.ofMillis(500), 100, 1, 256, 50, 10, Duration.ofMillis(300), Duration.ofMillis(100)));
        try {
            assertEquals(422, status(assertThrows(WebApplicationException.class, () -> p.preview(
                yaml("  - {id: s, type: read, source: stuck, asset: lab.stuck}\n"), "s", null, 5, "jo",
                AccessRole.STEWARD, STEWARD, () -> false))));
            Thread.sleep(500);
            assertEquals(1, p.stillStopping(), "the stuck node is still counted");
            assertTrue(error(assertThrows(WebApplicationException.class, () -> p.preview(MAIN, "people", null, 5,
                "sam", AccessRole.STEWARD, STEWARD, () -> false))).startsWith("Kilnmark is running as many previews"));
        } finally {
            StuckConnector.release.countDown();
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (p.stillStopping() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(0, p.stillStopping());
    }

    private PipelineViews.NodePreviewView sam(PipelinePreviews p) {
        try {
            return p.preview(MAIN, "people", null, 5, "sam", AccessRole.STEWARD, STEWARD, () -> false);
        } catch (WebApplicationException e) {
            return null;
        }
    }

    @Test
    void runningOutOfTimeWhileLookingUpMarksIsATimeoutNotAServerError() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PipelinePreviews.Viewer slow = new PipelinePreviews.Viewer() {
            @Override
            public boolean mayUnmask() {
                return false;
            }

            @Override
            public Set<String> marks(String urn) {
                if (calls.incrementAndGet() > 1) {
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return Set.of();
            }
        };
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews(Duration.ofMillis(500),
            100, 64L << 20).preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD, slow, () -> false));
        assertEquals(422, status(e));
        assertTrue(error(e).startsWith("The preview took longer than"), error(e));
    }

    @Test
    void aFailureIsHeldBackWhenWhatWasReadHasSensitiveColumns() {
        String yaml = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: bad, type: compute, input: people, columns: {n: "cast(name as int64)"}}
            """);
        WebApplicationException held = assertThrows(WebApplicationException.class, () -> previews().preview(yaml, "bad",
            null, 5, "jo", AccessRole.STEWARD, viewer(false, "id"), () -> false));
        assertEquals(422, status(held));
        assertEquals("The preview stopped at node bad. " + PipelineService.FOR_ADMINS, error(held));
        WebApplicationException shown = assertThrows(WebApplicationException.class, () -> previews().preview(yaml,
            "bad", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertTrue(error(shown).contains("bad"), error(shown));
        assertFalse(error(shown).contains("s3cret-pass"));
    }

    @Test
    void waitsSayWhichNodesReadAllTheirRowsFirst() {
        PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(NodePreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: read, source: small, asset: lab.b}
              - {id: j, type: lookup, inputs: {main: a, lookup: b}, on: {id: id}}
              - {id: s, type: aggregate, input: j, values: {n: "count(*)"}}
            """), "s").definition(), factories, false);
        assertEquals(List.of("Node j (lookup) reads all of its lookup side (from b) before it passes any rows on.",
            "Node s (aggregate) reads every row it is given before it passes any on, so every row before it was "
                + "read."), NodePreview.waits(c));
    }
}
