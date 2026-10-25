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
        assertEquals(1.5, c.get("f"));
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
}
