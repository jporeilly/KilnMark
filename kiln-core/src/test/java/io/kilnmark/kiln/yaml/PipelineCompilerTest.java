package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;

class PipelineCompilerTest {

    private static final PipelineCompiler COMPILER = new PipelineCompiler(ToyFactories.all());

    private static final String GOOD = """
        kilnmark: pipeline/v1
        name: toy
        description: Numbers, filtered, joined and written.
        steps:
          - id: a
            type: numbers
            batches: 4
          - id: b
            type: numbers
            start: 100
          - id: keep
            type: filter
            input: a
            condition: "n > 0"
            mode: drop
          - id: joined
            type: lookup
            inputs: { main: keep, lookup: b }
            keep: [n]
          - id: all
            type: union
            input: [joined, keep.rejected]
          - id: out
            type: sink
            input: all
            path: out.csv
        """;

    private static Compiled compile(String yaml) {
        return COMPILER.compile(yaml);
    }

    /** The one problem, checking it is where it should be. */
    private static Problem only(Compiled c, int line) {
        assertEquals(1, c.problems().size(), c.problems().toString());
        Problem p = c.problems().getFirst();
        assertEquals(line, p.line(), p.toString());
        return p;
    }

    private static void contains(Problem p, String... parts) {
        for (String part : parts) {
            assertTrue(p.message().contains(part), "'" + p.message() + "' should mention '" + part + "'");
        }
    }

    private static List<String> names(Schema s) {
        return s.getFields().stream().map(Field::getName).toList();
    }

    @Test
    void aGoodPipelineChecksWithoutCreatingStepsAndRuns() throws Exception {
        int before = ToyFactories.CREATED.get();
        Compiled c = compile(GOOD);
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(before, ToyFactories.CREATED.get(), "checking created no steps");
        assertEquals("toy", c.definition().name());
        assertEquals(List.of("n", "lookup_n"), names(c.schemas().get("joined").get("out")));
        assertEquals(List.of("n"), names(c.schemas().get("keep").get("rejected")));
        assertEquals(List.of("n", "lookup_n"), names(c.schemas().get("all").get("out")));
        assertTrue(c.notes().stream().noneMatch(n -> n.message().contains("cannot be known")), c.notes().toString());

        PipelineGraph g = c.graph();
        assertEquals(before + 6, ToyFactories.CREATED.get());
        assertTrue(g.check().isEmpty());
        assertEquals("all.out -> out.in", g.into("out").getFirst().toString());
        assertEquals(List.of("joined.out -> all.in", "keep.rejected -> all.in"),
            g.into("all").stream().map(Object::toString).toList());
        assertEquals(Map.of("batches", 4), g.step("a").config());

        RunResult r = new KilnGraphRunner(64L << 20, 2).run(g, e -> { });
        assertTrue(r.succeeded(), r.error());
        assertEquals(40, r.step("a").rowsOut());
        assertEquals(40 + 30, r.step("all").rowsIn(), "the lookup toy passes both sides on; nothing is rejected");
    }

    @Test
    void aManyInputPortsSchemasAreKeyedInOrder() {
        ToyFactories.UNION_KEYS.clear();
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: u
            steps:
              - { id: a, type: numbers }
              - { id: b, type: numbers }
              - { id: c, type: numbers }
              - { id: u, type: union, input: [a, b, c] }
            """);
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(List.of(List.of("in[0]", "in[1]", "in[2]")), ToyFactories.UNION_KEYS);
    }

    @Test
    void theFormatLineIsRequired() {
        contains(only(compile("name: x\nsteps:\n  - { id: a, type: numbers }\n"), 1), "kilnmark: pipeline/v1");
        contains(only(compile("kilnmark: pipeline/v9\nname: x\nsteps:\n  - { id: a, type: numbers }\n"), 1),
            "pipeline/v9", "reads pipeline/v1");
    }

    @Test
    void unknownTopLevelKeysAreNamedWithASuggestion() {
        Problem p = only(compile("kilnmark: pipeline/v1\nname: x\ndescripton: y\nsteps:\n  - { id: a, type: numbers }\n"), 3);
        contains(p, "descripton", "did you mean 'description'");
        assertNull(p.stepId());
    }

    @Test
    void theCanvasLayoutIsAcceptedAndIgnored() {
        String steps = "kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a, type: numbers }\n";
        Compiled plain = compile(steps);
        assertTrue(plain.problems().isEmpty(), plain.problems().toString());
        // Whatever the layout holds (positions, unknown steps, odd shapes), it changes nothing.
        for (String layout : List.of("layout:\n  a: { x: 40, y: 120 }\n", "layout:\n  nobody: { x: -1, y: 2.5 }\n",
            "layout: [1, 2]\n", "layout:\n")) {
            Compiled c = compile(steps + layout);
            assertTrue(c.problems().isEmpty(), layout + c.problems());
            assertEquals(plain.schemas(), c.schemas(), layout);
            assertEquals(plain.notes(), c.notes(), layout);
        }
        contains(only(compile(steps + "layuot:\n  a: { x: 1, y: 2 }\n"), 5), "layuot", "did you mean 'layout'");
    }

    @Test
    void thePipelinesQualityThresholdIsAShare() {
        String steps = "steps:\n  - { id: a, type: numbers }\n";
        Compiled none = compile("kilnmark: pipeline/v1\nname: x\n" + steps);
        assertNull(none.definition().quality());
        Compiled c = compile("kilnmark: pipeline/v1\nname: x\nquality:\n  min_score: 0.95\n" + steps);
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(0.95, c.definition().quality().minScore(), 1e-12);
        assertEquals(1.0, compile("kilnmark: pipeline/v1\nname: x\nquality: { min_score: 1 }\n" + steps).definition()
            .quality().minScore());
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nquality:\n  min_score: 95\n" + steps), 4),
            "quality.min_score must be a share from 0 to 1");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nquality:\n  min_score: high\n" + steps), 4),
            "quality.min_score");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nquality:\n  min_scor: 0.9\n" + steps), 4),
            "quality has no setting 'min_scor'", "did you mean 'min_score'");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nquality: 0.9\n" + steps), 3),
            "quality must be a mapping");
        assertNull(compile("kilnmark: pipeline/v1\nname: x\nquality: {}\n" + steps).definition().quality()
            .minScore());
    }

    @Test
    void aRulesSettingIsAListOfMappings() {
        String head = "kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a, type: numbers }\n"
            + "  - id: f\n    type: filter\n    input: a\n    condition: \"n > 0\"\n";
        Compiled c = compile(head + "    checks:\n      - {type: not_null, field: n}\n      - type: unique\n"
            + "        fields: [n]\n");
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(List.of(Map.of("type", "not_null", "field", "n"), Map.of("type", "unique", "fields", List.of("n"))),
            c.configs().get("f").get("checks"));
        contains(only(compile(head + "    checks: not_null\n"), 9), "checks", "must be a list of rules");
        contains(only(compile(head + "    checks: [not_null]\n"), 9), "must be a list of rules");
    }

    @Test
    void stepIdsMustBeWordsAndUnique() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: Bad Id, type: numbers }
              - { id: a, type: numbers }
              - { id: a, type: numbers }
            """);
        assertEquals(2, c.problems().size(), c.problems().toString());
        assertEquals(4, c.problems().get(0).line());
        contains(c.problems().get(0), "Bad Id", "lower-case");
        assertEquals(6, c.problems().get(1).line());
        contains(c.problems().get(1), "used twice", "first on line 5");
    }

    @Test
    void anUnknownStepTypeSuggestsTheClosest() {
        Problem p = only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - id: a\n    type: numbrs\n"), 5);
        contains(p, "numbrs", "did you mean 'numbers'");
        assertEquals("a", p.stepId());
        assertEquals(5, p.column());
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - id: a\n    type: zzzzzzzz\n"), 5),
            "The step types are: filter, lookup");
    }

    @Test
    void inputsMustNameStepsAndPortsThatExist() {
        String head = "kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: src, type: numbers }\n  - { id: f, type: filter, input: src, condition: x }\n";
        contains(only(compile(head + "  - { id: s, type: sink, input: srcc, path: p }\n"), 6),
            "'srcc'", "not a step", "did you mean 'src'");
        contains(only(compile(head + "  - { id: s, type: sink, input: f.rejectd, path: p }\n"), 6),
            "no output 'rejectd'", "did you mean 'rejected'", "out, rejected");
        contains(only(compile(head + "  - { id: s, type: sink, inputs: { inn: f }, path: p }\n"), 6),
            "no input 'inn'", "did you mean 'in'");
        contains(only(compile(head + "  - { id: j, type: lookup, input: f }\n"), 6),
            "has inputs main and lookup", "inputs: { main: …, lookup: … }");
        contains(only(compile(head + "  - { id: n, type: numbers, input: f }\n"), 6), "takes no input");
        contains(only(compile(head + "  - { id: s, type: sink, input: f, path: p }\n  - { id: t, type: sink, input: s, path: p }\n"), 7),
            "has no outputs");
        contains(only(compile(head + "  - { id: s, type: sink, input: s, path: p }\n"), 6), "reads from itself");
    }

    @Test
    void everyInputMustBeConnected() {
        Problem p = only(compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - id: j
                type: lookup
                inputs: { main: a }
            """), 5);
        contains(p, "needs an input on port 'lookup'");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: s, type: sink, path: p }\n"), 4),
            "needs an input on port 'in'", "input: <step id>");
    }

    @Test
    void aSingleInputPortCannotBeFedTwice() {
        Problem p = only(compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: b, type: numbers }
              - id: p
                type: pass
                input: [a, b]
            """), 8);
        contains(p, "takes one input", "2 are connected", "union");
    }

    @Test
    void settingsAreCheckedAgainstTheStepType() {
        String head = "kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a, type: numbers }\n  - id: f\n    type: filter\n    input: a\n";
        contains(only(compile(head + "    condition: x\n    conditon: y\n"), 9),
            "no setting 'conditon'", "did you mean 'condition'", "its settings are condition, mode");
        contains(only(compile(head + "    mode: keep\n"), 5), "needs the setting 'condition' (Condition)");
        contains(only(compile(head + "    condition: x\n    limit: lots\n"), 9), "'limit'", "must be a number");
        contains(only(compile(head + "    condition: x\n    mode: maybe\n"), 9), "must be one of keep, drop");
        contains(only(compile(head + "    condition: x\n    strict: yes\n"), 9), "must be true or false");
        contains(only(compile(head + "    condition: x\n    columns: a\n"), 9), "must be a list of columns");
        contains(only(compile(head + "    condition: x\n    rename: [a]\n"), 9), "mapping of column names");
        contains(only(compile(head + "    condition: [x]\n"), 8), "single value");
        Problem own = only(compile(head + "    mode: drop\n    condition: bad > 1\n"), 9);
        contains(own, "Step f: condition uses a column that does not exist");
        assertEquals("f", own.stepId());
        assertTrue(compile(head + "    condition: x\n    limit: 5\n    strict: false\n    columns: [n]\n    rename: { n: m }\n").ok());
    }

    @Test
    void loopsAreRefused() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: src, type: numbers }
              - { id: m, type: lookup, inputs: { main: src, lookup: q } }
              - { id: p, type: pass, input: m }
              - { id: q, type: pass, input: p }
            """);
        Problem p = only(c, 5);
        contains(p, "m → p → q → m", "loop");
        assertFalse(c.ok());
        assertThrows(IllegalStateException.class, c::graph);
    }

    @Test
    void columnsFlowThroughTheStepsAndProblemsWithThemAreReported() {
        Problem p = only(compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: b, type: numbers }
              - id: j
                type: lookup
                inputs: { main: a, lookup: b }
                keep: [missing]
            """), 6);
        contains(p, "Step j: keep names missing, which the lookup side does not have");
    }

    @Test
    void unknownColumnsSkipLaterChecksWithANote() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: m, type: mystery }
              - id: j
                type: lookup
                inputs: { main: a, lookup: m }
                keep: [anything]
              - { id: p, type: pass, input: j }
            """);
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(1, c.notes().size(), c.notes().toString());
        Problem note = c.notes().getFirst();
        assertEquals(5, note.line());
        contains(note, "step m cannot be known", "steps j, p are not checked");
        assertNull(c.schemas().get("j"));
        assertEquals(List.of("n"), names(c.schemas().get("a").get("out")));
    }

    @Test
    void inputsSharingAStepUpstreamGetANote() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: p, type: pass, input: a }
              - { id: j, type: lookup, inputs: { main: a, lookup: p } }
            """);
        assertTrue(c.ok(), c.problems().toString());
        assertTrue(c.notes().stream().anyMatch(n -> n.line() == 6 && n.message().contains("both come from step a")),
            c.notes().toString());
    }

    @Test
    void theYamlShapeIsChecked() {
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps: nothing\n"), 3), "steps must be a list");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps: []\n"), 3), "at least one step");
        contains(only(compile("kilnmark: pipeline/v1\nsteps:\n  - { id: a, type: numbers }\n"), 1), "needs a name");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { type: numbers }\n"), 4), "needs an id");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a }\n"), 4), "needs a type");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a, type: numbers }\n"
            + "  - { id: p, type: pass, input: a, inputs: { in: a } }\n"), 5), "both input and inputs");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: p, type: pass, input: 5 }\n"), 4),
            "id of the step to read from");
        contains(only(compile("- just\n- a list\n"), 1), "starts with kilnmark");
        contains(only(compile(""), 1), "starts with kilnmark");
    }

    @Test
    void unsafeOrBrokenYamlIsRefusedWithItsPosition() {
        contains(only(compile("kilnmark: pipeline/v1\nname: &n x\nsteps: []\n"), 2), "Anchors");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\nother: *n\n"), 3), "Aliases");
        contains(only(compile("kilnmark: pipeline/v1\nname: !!python/object x\n"), 2), "Tags (!!python/object)");
        contains(only(compile("kilnmark: pipeline/v1\nname: !custom x\n"), 2), "Tags");
        Problem dup = only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - id: a\n    type: numbers\n    type: pass\n"), 6);
        contains(dup, "'type' appears twice", "first on line 5");
        contains(only(compile("kilnmark: pipeline/v1\n---\nname: y\n"), 2), "one YAML document");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\n  bad: indent\n"), 3), "not valid YAML");
        contains(only(compile("x".repeat(YamlReader.MAX_CHARS + 1)), 1), "too large");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\ndeep: " + "[".repeat(100) + "]".repeat(100) + "\n"), 3),
            "more than 64 deep");
        contains(only(compile("kilnmark: pipeline/v1\nname: x\n{a: 1}: y\n"), 3), "key must be a plain word");
    }

    @Test
    void scalarsFollowTheYaml12CoreSchema() {
        PipelineDefinition.Parsed p = PipelineDefinition.parse("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - id: a
                type: t
                i: 12
                big: 12345678901
                hex: 0x1F
                f: 1.5
                inf: -.inf
                yes: yes
                t: true
                quoted: "12"
                nothing: null
                env: ${HOME}
                list: [1, two]
            """);
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        Map<String, Object> c = p.definition().steps().getFirst().config();
        assertEquals(12, c.get("i"));
        assertEquals(12345678901L, c.get("big"));
        assertEquals(31, c.get("hex"));
        assertEquals(new java.math.BigDecimal("1.5"), c.get("f"));
        assertEquals(Double.NEGATIVE_INFINITY, c.get("inf"));
        assertEquals("yes", c.get("yes"), "YAML 1.2: yes is text");
        assertEquals(true, c.get("t"));
        assertEquals("12", c.get("quoted"));
        assertTrue(c.containsKey("nothing"));
        assertNull(c.get("nothing"));
        assertEquals("${HOME}", c.get("env"), "environment variables are never substituted");
        assertEquals(List.of(1, "two"), c.get("list"));
        assertEquals(new Position(7, 5), p.definition().steps().getFirst().at("big"));
    }

    @Test
    void aStepThatCannotBeCreatedClosesTheOthers() {
        io.kilnmark.kiln.StepFactories f = ToyFactories.all().register(new ToyFactories.Pass() {
            @Override
            public String type() {
                return "broken";
            }

            @Override
            public io.kilnmark.kiln.Step create(Map<String, Object> config) throws io.kilnmark.kiln.StepException {
                throw new io.kilnmark.kiln.StepException("no connection");
            }
        });
        Compiled c = new PipelineCompiler(f).compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: b, type: broken, input: a }
            """);
        assertTrue(c.ok(), c.problems().toString());
        io.kilnmark.kiln.StepException e = assertThrows(io.kilnmark.kiln.StepException.class, c::graph);
        assertTrue(e.getMessage().contains("Step b (broken) could not be created: no connection"), e.getMessage());
    }

    private static final String FILTER_HEAD = "kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: a, type: numbers }\n"
        + "  - id: f\n    type: filter\n    input: a\n";

    @Test
    void settingsThatAreTextKeepWhatWasWritten() throws Exception {
        Compiled c = compile(FILTER_HEAD + """
                condition: 007
                mode: drop
                columns: [007, 1.0E-4, 2024.10]
                rename: { 007: 0x10 }
                limit: 0x10
              - { id: s, type: sink, input: f, path: 2024.10 }
            """);
        assertTrue(c.ok(), c.problems().toString());
        Map<String, Object> f = c.configs().get("f");
        assertEquals("007", f.get("condition"));
        assertEquals(List.of("007", "1.0E-4", "2024.10"), f.get("columns"));
        assertEquals(Map.of("007", "0x10"), f.get("rename"));
        assertEquals(16, f.get("limit"), "a number setting is a number");
        assertEquals("2024.10", c.configs().get("s").get("path"));
        assertEquals("2024.10", c.graph().step("s").config().get("path"), "the step receives the text as written");
        assertEquals("007", c.definition().steps().get(1).configText().get("condition"));
        assertEquals(7, c.definition().steps().get(1).config().get("condition"), "the definition keeps the YAML value too");
    }

    @Test
    void numberSettingsAreExactAndFinite() {
        Compiled ok = compile(FILTER_HEAD + "    condition: x\n    limit: 2.50\n");
        assertTrue(ok.ok(), ok.problems().toString());
        assertEquals(new java.math.BigDecimal("2.50"), ok.configs().get("f").get("limit"));
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: .nan\n"), 9), "must be a finite number");
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: -.inf\n"), 9), "must be a finite number");
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: 1e999\n"), 9), "too large");
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: 99999999999999999999\n"), 9), "too large");
    }

    @Test
    void mappingKeysAreTheTextWritten() {
        PipelineDefinition.Parsed p = PipelineDefinition.parse("kilnmark: pipeline/v1\nname: x\nsteps:\n"
            + "  - { id: a, type: t, m: { 007: x, 1.10: y, true: z } }\n");
        assertTrue(p.problems().isEmpty(), p.problems().toString());
        assertEquals(List.of("007", "1.10", "true"),
            List.copyOf(((Map<?, ?>) p.definition().steps().getFirst().config().get("m")).keySet()));
    }

    @Test
    void hugeValuesAreEchoedShort() {
        String type = "x".repeat(999_000);
        Compiled c = compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - id: a\n    type: " + type + "\n");
        Problem p = only(c, 5);
        assertTrue(p.message().length() < 300, p.message().length() + " characters");
        contains(p, "Unknown step type 'xxx", "…'");
        String key = "k".repeat(5_000);
        Problem k = only(compile(FILTER_HEAD + "    condition: x\n    " + key + ": 1\n"), 9);
        assertTrue(k.message().length() < 400, k.message());
        String id = "a".repeat(5_000) + "B";
        Problem i = only(compile("kilnmark: pipeline/v1\nname: x\nsteps:\n  - { id: " + id + ", type: numbers }\n"), 4);
        assertTrue(i.message().length() < 300 && i.stepId().length() <= 80, i.message());
    }

    @Test
    void aPipelineBuiltInCodeGetsTheChecksReadingWouldGive() {
        PipelineDefinition.StepDefinition a = new PipelineDefinition.StepDefinition("a", "numbers", List.of(), Map.of(),
            new Position(1, 1), Map.of());
        PipelineDefinition.StepDefinition again = new PipelineDefinition.StepDefinition("a", "numbers", List.of(),
            Map.of(), new Position(2, 1), Map.of());
        PipelineDefinition.StepDefinition bad = new PipelineDefinition.StepDefinition("Bad", null, List.of(), Map.of(),
            new Position(3, 1), Map.of());
        Compiled c = COMPILER.compile(new PipelineDefinition(" ", null, List.of(a, again, bad)));
        List<String> messages = c.problems().stream().map(Problem::message).toList();
        assertEquals(4, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("needs a name"), messages.toString());
        assertTrue(messages.get(1).contains("used twice (first on line 1)"), messages.toString());
        assertTrue(messages.get(2).contains("Step id 'Bad' must start"), messages.toString());
        assertTrue(messages.get(3).contains("Step Bad needs a type"), messages.toString());
    }

    @Test
    void aDuplicateIdStillLetsTheFirstStepBeChecked() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: p, type: pass }
              - { id: p, type: pass, input: a }
            """);
        List<String> messages = c.problems().stream().map(Problem::message).toList();
        assertEquals(2, messages.size(), messages.toString());
        assertTrue(messages.get(0).contains("needs an input on port 'in'"), messages.toString());
        assertTrue(messages.get(1).contains("used twice"), messages.toString());
    }

    @Test
    void manyInputsSharingAStepUpstreamGetANote() {
        Compiled c = compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: p1, type: pass, input: a }
              - { id: p2, type: pass, input: a }
              - { id: u, type: union, input: [p1, p2] }
            """);
        assertTrue(c.ok(), c.problems().toString());
        assertTrue(c.notes().stream().anyMatch(n -> n.line() == 7 && n.message().contains("in[0] and in[1] both come from step a")),
            c.notes().toString());
    }

    @Test
    void aForbiddenCharacterIsReportedWhereItIs() {
        Problem p = only(compile("kilnmark: pipeline/v1\nname: x\u0007\n"), 2);
        assertEquals(8, p.column());
        contains(p, "U+0007");
    }

    @Test
    void inputColumnsReachTheGraph() throws Exception {
        Compiled c = compile(GOOD);
        assertEquals(List.of("main", "lookup"), List.copyOf(c.inputSchemas().get("joined").keySet()));
        assertEquals(List.of("in[0]", "in[1]"), List.copyOf(c.inputSchemas().get("all").keySet()));
        PipelineGraph g = c.graph();
        assertEquals(List.of("n"), names(g.step("joined").inputSchemas().get("lookup")));
        assertEquals(List.of("n", "lookup_n"), names(g.step("all").inputSchemas().get("in[0]")));
        assertTrue(g.step("a").inputSchemas().isEmpty());
    }

    @Test
    void anErrorWhileCreatingStepsClosesThoseAlreadyMade() {
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        io.kilnmark.kiln.StepFactories f = ToyFactories.all()
            .register(new ToyFactories.Pass() {
                @Override
                public String type() {
                    return "tracked";
                }

                @Override
                public io.kilnmark.kiln.Step create(Map<String, Object> config) {
                    return new io.kilnmark.kiln.Step() {
                        @Override
                        public String type() {
                            return "tracked";
                        }

                        @Override
                        public void init(io.kilnmark.kiln.StepContext context) {
                        }

                        @Override
                        public boolean process(io.kilnmark.kiln.StepContext context) {
                            return false;
                        }

                        @Override
                        public void close() {
                            closed.set(true);
                        }
                    };
                }
            })
            .register(new ToyFactories.Pass() {
                @Override
                public String type() {
                    return "boom";
                }

                @Override
                public io.kilnmark.kiln.Step create(Map<String, Object> config) {
                    throw new LinkageError("boom");
                }
            });
        Compiled c = new PipelineCompiler(f).compile("""
            kilnmark: pipeline/v1
            name: x
            steps:
              - { id: a, type: numbers }
              - { id: t, type: tracked, input: a }
              - { id: b, type: boom, input: t }
            """);
        assertTrue(c.ok(), c.problems().toString());
        assertThrows(LinkageError.class, c::graph);
        assertTrue(closed.get());
    }

    @Test
    void hugeDecimalsAreRefusedAsNumbersAndKeptAsWrittenText() {
        long start = System.nanoTime();
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: 1e-999999999\n"), 9), "too many digits");
        contains(only(compile(FILTER_HEAD + "    condition: x\n    limit: 1" + "0".repeat(1_200) + ".5\n"), 9),
            "too many digits");
        Compiled text = compile(FILTER_HEAD + "    condition: 1e-999999999\n");
        assertTrue(text.ok(), text.problems().toString());
        assertEquals("1e-999999999", text.configs().get("f").get("condition"));
        PipelineDefinition.StepDefinition built = new PipelineDefinition.StepDefinition("a", "numbers", List.of(),
            Map.of("x", new java.math.BigDecimal("1e-999999999")), new Position(1, 1), Map.of());
        assertEquals("1E-999999999", built.configText().get("x"));
        assertTrue(java.time.Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }
}
