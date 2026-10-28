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

import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.yaml.PipelineDefinition;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import jakarta.ws.rs.WebApplicationException;

/**
 * Preview at a step without a database: which steps run, what is refused, the limits, masking of
 * columns made from columns marked sensitive, and the run itself over the in-memory test connector
 * (synthetic people).
 */
class StepPreviewTest {

    static final String PEOPLE = "urn:kilnmark:lab-db:lab.people";

    private final FakeSources sources = new FakeSources()
        .add("lab-db", Map.of("rows", "1000000000"), "s3cret-pass")
        .add("small", Map.of("rows", "40"), null)
        .add("warehouse", Map.of("allow_writing", "true"), null);
    private final StepFactories factories = StepFactories.discover().register(new ReadStepFactory(sources))
        .register(new WriteStepFactory(sources)).register(new GateStepFactory(sources));

    private PipelinePreviews previews(Duration timeout, int perMinute, long memoryBytes) {
        return new PipelinePreviews(factories, null, new PipelinePreviews.Limits(memoryBytes, 4, timeout, perMinute, 4,
            256));
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

    static String yaml(String steps) {
        return "kilnmark: pipeline/v1\nname: people-tidy\nsteps:\n" + steps;
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

    // ---- which steps run ----

    @Test
    void onlyTheStepAndWhatIsUpstreamOfItRun() {
        StepPreview.Plan p = StepPreview.plan(MAIN, "some");
        assertEquals(List.of("people", "tidy", "some"), p.definition().steps().stream()
            .map(PipelineDefinition.StepDefinition::id).toList(), "no write, no other branch");
        assertEquals("filter", p.type());
        assertEquals(List.of("people"), StepPreview.plan(MAIN, "people").definition().steps().stream()
            .map(PipelineDefinition.StepDefinition::id).toList());
        assertEquals(Set.of("a", "b", "c", "d"), StepPreview.upstreamAndSelf("d", ids(StepPreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: read, source: small, asset: lab.b}
              - {id: c, type: lookup, inputs: {main: a, lookup: b}, on: {id: id}}
              - {id: d, type: filter, input: c.rejected, condition: "id > 1"}
              - {id: e, type: filter, input: d, condition: "id > 1"}
            """), "e").definition())));
    }

    private static Map<String, PipelineDefinition.StepDefinition> ids(PipelineDefinition d) {
        Map<String, PipelineDefinition.StepDefinition> out = new java.util.LinkedHashMap<>();
        d.steps().forEach(s -> out.put(s.id(), s));
        return out;
    }

    @Test
    void writesAndStepsThatCouldChangeAnythingAreRefused() {
        IllegalArgumentException write = assertThrows(IllegalArgumentException.class, () -> StepPreview.plan(MAIN, "out"));
        assertTrue(write.getMessage().contains("writes rows"), write.getMessage());
        IllegalArgumentException other = assertThrows(IllegalArgumentException.class, () -> StepPreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: send-email, input: a}
              - {id: c, type: filter, input: b, condition: "id > 1"}
            """), "c"));
        assertTrue(other.getMessage().startsWith("Step b (send-email) cannot run in a preview"), other.getMessage());
        assertTrue(other.getMessage().contains("c needs its rows"), other.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StepPreview.plan(MAIN, "nowhere"));
        assertThrows(IllegalArgumentException.class, () -> StepPreview.plan(MAIN, " "));
        assertThrows(IllegalArgumentException.class, () -> StepPreview.plan("kilnmark: [", "a"));
        assertTrue(StepPreview.PREVIEWABLE.stream().noneMatch(t -> t.equals(WriteStepFactory.TYPE)));
    }

    @Test
    void problemsElsewhereDoNotStopAPreviewButProblemsInItDo() {
        PipelineViews.StepPreviewView v = previews().preview(MAIN, "people", null, 5, "jo", AccessRole.STEWARD,
            STEWARD, () -> false);
        assertEquals(5, v.rowCount());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> previews().preview(MAIN,
            "broken", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertTrue(e.getMessage().startsWith("Fix the pipeline before previewing broken"), e.getMessage());
    }

    @Test
    void portsAreChecked() {
        PipelineViews.StepPreviewView rejected = previews().preview(MAIN, "some", "rejected", 3, "jo",
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
    void theFirstRowsArriveAndTheStepsBeforeEndEarly() {
        long started = System.nanoTime();
        PipelineViews.StepPreviewView v = previews().preview(MAIN, "tidy", null, null, "jo", AccessRole.STEWARD,
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

        PipelineViews.StepPreviewView all = previews().preview(yaml("""
              - {id: few, type: read, source: small, asset: lab.few}
            """), "few", null, 1000, "jo", AccessRole.STEWARD, STEWARD, () -> false);
        assertEquals(40, all.rowCount());
        assertTrue(all.complete(), "the step gave fewer rows than asked for: these are all of them");
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
    void aStepReadingAllItsRowsIsExplainedAndStoppedAtTheTimeLimit() {
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
        assertTrue(error(e).startsWith("The preview took longer than 1 second, so it was stopped. Step counts "
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

    // ---- masking ----

    @Test
    void columnsMadeFromSensitiveColumnsAreMaskedForAllButAdmins() {
        PipelineViews.StepPreviewView v = previews().preview(MAIN, "tidy", null, 3, "jo", AccessRole.STEWARD,
            viewer(false, "name"), () -> false);
        assertEquals(List.of("name", "shout"), v.masked(), "upper(name) is made from name");
        assertEquals(List.of("1", Masking.MASK, Masking.MASK, "2"), v.rows().get(0));

        PipelineViews.StepPreviewView admin = previews().preview(MAIN, "tidy", null, 3, "ann", AccessRole.ADMIN,
            viewer(true, "name"), () -> false);
        assertEquals(List.of(), admin.masked());
        assertEquals("PERSON 1", admin.rows().get(0).get(2));
    }

    @Test
    void hiddenColumnsFollowRenamesComputesAndAggregates() {
        StepPreview.Hidden h = hidden("""
              - {id: people, type: read, source: small, asset: lab.people}
              - {id: renamed, type: select, input: people, rename: {name: who}}
              - id: c
                type: compute
                input: renamed
                columns:
                  initial: "substring(who, 1, 1)"
                  name: "'anon'"
                  plain: "id * 2"
                  later: "initial || '.'"
            """, "c", Set.of("name"));
        assertTrue(h.hides("who") && h.hides("WHO") && h.hides("initial") && h.hides("later"));
        assertFalse(h.hides("name"), "replaced by a value made from no sensitive column");
        assertFalse(h.hides("plain") || h.hides("id"));

        StepPreview.Hidden agg = hidden("""
              - {id: people, type: read, source: small, asset: lab.people}
              - id: a
                type: aggregate
                input: people
                group_by: [name]
                values: {n: "count(*)", longest: "max(length(name))", top: "max(id)"}
            """, "a", Set.of("name"));
        assertTrue(agg.hides("name") && agg.hides("longest"));
        assertFalse(agg.hides("n") || agg.hides("top"));

        StepPreview.Hidden union = hidden("""
              - {id: people, type: read, source: small, asset: lab.people}
              - {id: other, type: read, source: small, asset: lab.other}
              - {id: u, type: union, input: [people, other]}
              - {id: l, type: limit, input: u, rows: 5}
            """, "l", Set.of("name"));
        assertTrue(union.hides("name"));
        assertFalse(union.hides("id"));
    }

    private StepPreview.Hidden hidden(String steps, String step, Set<String> marks) {
        PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(StepPreview.plan(yaml(steps), step)
            .definition(), factories, false);
        assertEquals(List.of(), c.problems());
        Map<String, Set<String>> reads = new java.util.HashMap<>();
        c.steps().stream().filter(s -> s.type().equals("read") && s.id().equals("people"))
            .forEach(s -> reads.put(s.id(), marks));
        return StepPreview.hidden(c, reads, step);
    }

    @Test
    void rowsChosenByASensitiveColumnAreRefusedForAllButAdmins() {
        String byName = yaml("""
              - {id: people, type: read, source: lab-db, asset: lab.people}
              - {id: ps, type: filter, input: people, condition: "name like 'Person 1%'"}
            """);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> previews().preview(byName, "ps",
            null, 5, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false));
        assertEquals(403, status(e));
        assertTrue(error(e).startsWith("Only an admin can preview this: step ps (filter) chooses rows by name, which "
            + "is marked sensitive"), error(e));
        assertEquals(5, previews().preview(byName, "ps", null, 5, "ann", AccessRole.ADMIN, viewer(true, "name"),
            () -> false).rowCount());
        assertEquals(5, previews().preview(byName, "ps", null, 5, "jo", AccessRole.STEWARD, viewer(false, "id"),
            () -> false).rowCount(), "a filter on visible columns is fine");

        for (String step : List.of("""
              - {id: x, type: dedupe, input: people, key: [name]}
            """, """
              - {id: x, type: dedupe, input: people}
            """, """
              - {id: x, type: route, input: people, routes: {p: "length(name) > 8"}}
            """, """
              - {id: x, type: gate, input: people, on_fail: quarantine, asset_rules: false, rules: [{type: not_null, field: id}]}
            """)) {
            String y = yaml("  - {id: people, type: read, source: lab-db, asset: lab.people}\n" + step);
            WebApplicationException r = assertThrows(WebApplicationException.class, () -> previews().preview(y, "x",
                null, 5, "jo", AccessRole.STEWARD, viewer(false, "name"), () -> false), step);
            assertEquals(403, status(r), step);
        }
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
        assertEquals("The preview stopped at step bad. " + PipelineService.FOR_ADMINS, error(held));
        WebApplicationException shown = assertThrows(WebApplicationException.class, () -> previews().preview(yaml,
            "bad", null, 5, "jo", AccessRole.STEWARD, STEWARD, () -> false));
        assertTrue(error(shown).contains("bad"), error(shown));
        assertFalse(error(shown).contains("s3cret-pass"));
    }

    @Test
    void waitsSayWhichStepsReadAllTheirRowsFirst() {
        PipelineCompilerPort.Compilation c = KilnPipelineCompiler.compile(StepPreview.plan(yaml("""
              - {id: a, type: read, source: small, asset: lab.a}
              - {id: b, type: read, source: small, asset: lab.b}
              - {id: j, type: lookup, inputs: {main: a, lookup: b}, on: {id: id}}
              - {id: s, type: aggregate, input: j, values: {n: "count(*)"}}
            """), "s").definition(), factories, false);
        assertEquals(List.of("Step j (lookup) reads all of its lookup side (from b) before it passes any rows on.",
            "Step s (aggregate) reads every row it is given before it passes any on, so every row before it was "
                + "read."), StepPreview.waits(c));
    }
}
