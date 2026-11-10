package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Notes on the canvas (notes:) and on a node (note:): read in pipelines and flows, refused past
 * their limits with a clear problem, and never part of what runs.
 */
class CanvasNotesTest {

    private static final PipelineCompiler COMPILER = new PipelineCompiler(ToyFactories.all());

    private static final String PIPELINE = """
        kilnmark: pipeline/v1
        name: toy
        nodes:
          - id: a
            type: numbers
            batches: 4
          - id: keep
            type: filter
            input: a
            condition: "n > 0"
          - id: out
            type: sink
            input: keep
            path: out.csv
        """;

    private static final String NOTES = """

        # Notes on the canvas, for people. Kilnmark ignores this list when it runs the pipeline.
        notes:
          - text: |-
              **What it does:** keeps the positive numbers.

              - Read [the guide](https://example.com/guide) first.
            node: a
            x: 0
            y: -160
            width: 260
            height: 140
            colour: blue
          - text: Free-standing, with every key left to its default.
        """;

    private static String withNodeNote(String yaml, String id, String note) {
        String at = "  - id: " + id + "\n";
        assertTrue(yaml.contains(at), "test bug: " + id);
        return yaml.replace(at, at + "    note: " + note + "\n");
    }

    private static List<String> messages(List<Problem> problems) {
        return problems.stream().map(Problem::message).toList();
    }

    /** What a pipeline's nodes do: everything but where each is written. */
    private static String runs(Compiled c) {
        return c.definition().nodes().stream()
            .map(n -> n.id() + " " + n.type() + " " + n.config() + " "
                + n.inputs().stream().map(i -> i.port() + "<-" + i.from()).toList())
            .toList() + " " + c.schemas() + " " + c.edges() + " " + c.configs();
    }

    @Test
    void aPipelineWithNotesReadsAndRunsTheSame() {
        Compiled plain = COMPILER.compile(PIPELINE);
        assertTrue(plain.ok(), plain.problems().toString());
        String noted = withNodeNote(PIPELINE, "keep", "\"Drops zero and below: see the note above\"") + NOTES;
        Compiled c = COMPILER.compile(noted);
        assertTrue(c.ok(), c.problems().toString());
        assertEquals(runs(plain), runs(c));
        assertEquals(plain.notes(), c.notes());
        // A node's note is not one of its settings, so no node type is asked about it.
        assertFalse(c.definition().nodes().get(1).config().containsKey("note"));
        // A note on every node, of every kind here, is never "a key the node does not take".
        String everywhere = withNodeNote(withNodeNote(withNodeNote(PIPELINE, "a", "reads"), "keep", "keeps"), "out",
            "writes");
        assertTrue(COMPILER.compile(everywhere).ok(), COMPILER.compile(everywhere).problems().toString());
        // An empty list is no notes.
        assertTrue(COMPILER.compile(PIPELINE + "notes: []\n").ok());
        assertTrue(COMPILER.compile(PIPELINE + "notes:\n").ok());
    }

    @Test
    void aFlowWithNotesReadsAndRunsTheSame() {
        FlowDefinition.Parsed plain = FlowDefinition.parse(FlowDefinitionTest.GOOD);
        assertTrue(plain.valid(), plain.problems().toString());
        String noted = withNodeNote(withNodeNote(FlowDefinitionTest.GOOD, "clean", "Runs first, every night"), "halt",
            "Ends the run as failed") + NOTES.replace("node: a", "node: clean");
        FlowDefinition.Parsed p = FlowDefinition.parse(noted);
        assertTrue(p.valid(), p.problems().toString());
        FlowDefinition a = plain.definition();
        FlowDefinition b = p.definition();
        assertEquals(a.nodes().size(), b.nodes().size());
        for (int i = 0; i < a.nodes().size(); i++) {
            FlowDefinition.Node x = a.nodes().get(i);
            FlowDefinition.Node y = b.nodes().get(i);
            assertEquals(List.of(x.id(), x.type(), String.valueOf(x.pipeline()), String.valueOf(x.version()),
                    String.valueOf(x.status()), String.valueOf(x.message()), x.with().toString()),
                List.of(y.id(), y.type(), String.valueOf(y.pipeline()), String.valueOf(y.version()),
                    String.valueOf(y.status()), String.valueOf(y.message()), y.with().toString()));
        }
        assertEquals(a.connections().stream().map(c -> c.from() + c.to() + c.when()).toList(),
            b.connections().stream().map(c -> c.from() + c.to() + c.when()).toList());
        assertEquals(a.parameters(), b.parameters());
        // Every flow node type takes a note.
        for (String type : FlowDefinition.TYPES) {
            assertTrue(FlowDefinition.TYPE_KEYS.containsKey(type), type);
        }
        String wait = "kilnmark: flow/v1\nname: w\nnodes:\n  - id: pause\n    type: wait\n    for: 1m\n    note: A pause\n";
        assertTrue(FlowDefinition.parse(wait).valid(), FlowDefinition.parse(wait).problems().toString());
    }

    @Test
    void theLimitsAreRefusedWithAClearProblem() {
        // Note text: at most 2,000 characters.
        String fits = PIPELINE + "notes:\n  - text: " + "x".repeat(CanvasNotes.MAX_TEXT) + "\n";
        assertTrue(COMPILER.compile(fits).ok());
        List<String> m = messages(COMPILER.compile(PIPELINE + "notes:\n  - text: " + "x".repeat(CanvasNotes.MAX_TEXT + 1)
            + "\n").problems());
        assertEquals(List.of("Note 1's text is 2001 characters long: at most 2000 fit. Shorten it, or split it into "
            + "two notes."), m);

        // At most 50 notes.
        StringBuilder many = new StringBuilder(PIPELINE + "notes:\n");
        for (int i = 0; i < CanvasNotes.MAX_NOTES; i++) {
            many.append("  - text: note ").append(i).append('\n');
        }
        assertTrue(COMPILER.compile(many.toString()).ok());
        many.append("  - text: one too many\n");
        m = messages(COMPILER.compile(many.toString()).problems());
        assertEquals(1, m.size(), m.toString());
        assertTrue(m.getFirst().startsWith("A canvas has at most 50 notes; this one has 51."), m.toString());
        StringBuilder flowMany = new StringBuilder(FlowDefinitionTest.GOOD + "notes:\n");
        for (int i = 0; i <= CanvasNotes.MAX_NOTES; i++) {
            flowMany.append("  - text: note ").append(i).append('\n');
        }
        assertTrue(messages(FlowDefinition.parse(flowMany.toString()).problems()).getFirst()
            .startsWith("A canvas has at most 50 notes"));

        // A node's note: at most 280 characters, on pipeline and flow nodes alike.
        assertTrue(COMPILER.compile(withNodeNote(PIPELINE, "a", "y".repeat(CanvasNotes.MAX_NODE_NOTE))).ok());
        Problem p = COMPILER.compile(withNodeNote(PIPELINE, "a", "y".repeat(CanvasNotes.MAX_NODE_NOTE + 1))).problems()
            .getFirst();
        assertEquals("a", p.nodeId());
        assertEquals("Node a's note is 281 characters long: at most 280 fit. Shorten it, or say the rest in a note on "
            + "the canvas.", p.message());
        assertEquals(5, p.line());
        assertTrue(messages(FlowDefinition.parse(withNodeNote(FlowDefinitionTest.GOOD, "halt",
            "z".repeat(CanvasNotes.MAX_NODE_NOTE + 1))).problems()).getFirst().startsWith("Node halt's note is 281"));
    }

    @Test
    void aNoteOfTheWrongShapeIsAProblem() {
        String[][] cases = {
            {"notes: hello\n", "notes must be a list"},
            {"notes:\n  - just text\n", "Note 1 must be a mapping"},
            {"notes:\n  - colour: blue\n", "Note 1 needs text"},
            {"notes:\n  - text: [a, b]\n", "Note 1's text must be text"},
            {"notes:\n  - text: x\n    colour: purple\n", "Note 1's colour is one of yellow, blue, green, pink, grey"},
            {"notes:\n  - text: x\n    width: 10\n", "Note 1's width is a number from 120 to 1200"},
            {"notes:\n  - text: x\n    x: big\n", "Note 1's x is a number from -100000 to 100000"},
            {"notes:\n  - text: x\n    node: [a]\n", "Note 1's node must be the id of the node"},
            {"notes:\n  - text: x\n    color: blue\n", "Note 1 has no key 'color'"},
            {"notes:\n  - text: x\n    html: <b>x</b>\n", "Note 1 has no key 'html'"},
        };
        for (String[] c : cases) {
            List<String> m = messages(COMPILER.compile(PIPELINE + c[0]).problems());
            assertEquals(1, m.size(), c[0] + m);
            assertTrue(m.getFirst().startsWith(c[1]), c[0] + m);
            List<String> f = messages(FlowDefinition.parse(FlowDefinitionTest.GOOD + c[0]).problems());
            assertTrue(f.size() == 1 && f.getFirst().startsWith(c[1]), c[0] + f);
        }
        assertTrue(messages(COMPILER.compile(withNodeNote(PIPELINE, "a", "[1, 2]")).problems()).getFirst()
            .startsWith("Node a's note must be text"));
        // A note attached to a node the pipeline does not have is drawn from the origin: no problem.
        assertTrue(COMPILER.compile(PIPELINE + "notes:\n  - text: x\n    node: gone\n").ok());
        assertTrue(messages(COMPILER.compile(PIPELINE + "nots:\n  - text: x\n").problems()).getFirst()
            .contains("did you mean 'notes'"));
    }

    @Test
    void withoutCanvasLeavesOutTheLayoutAndTheNotes() {
        String bare = CanvasNotes.withoutCanvas(PIPELINE);
        String layout = "\n# Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.\n"
            + "layout:\n  a: {x: 0, y: 0}\n  keep: {x: 240, y: 0}\n";
        assertEquals(bare, CanvasNotes.withoutCanvas(PIPELINE + NOTES));
        assertEquals(bare, CanvasNotes.withoutCanvas(PIPELINE + layout + NOTES));
        assertEquals(bare, CanvasNotes.withoutCanvas(PIPELINE + NOTES + layout));
        assertEquals(bare, CanvasNotes.withoutCanvas((PIPELINE + NOTES + layout).replace("\n", "\r\n")));
        // A node's note on lines of its own, even over several, with blank lines inside.
        String multi = PIPELINE.replace("    batches: 4\n", "    note: |\n      Reads numbers.\n\n      In four batches.\n"
            + "    batches: 4\n");
        assertEquals(bare, CanvasNotes.withoutCanvas(multi));
        assertEquals(bare, CanvasNotes.withoutCanvas(withNodeNote(PIPELINE, "out", "Writes the file")));
        // The blank line between nodes stays, so a note on a node's last line changes nothing either.
        assertEquals(bare, CanvasNotes.withoutCanvas(PIPELINE.replace("    batches: 4\n", "    batches: 4\n    note: x\n")));
        // What runs still counts.
        assertFalse(bare.equals(CanvasNotes.withoutCanvas(PIPELINE.replace("batches: 4", "batches: 5") + NOTES)));
        assertFalse(bare.equals(CanvasNotes.withoutCanvas(PIPELINE + NOTES + "run: {retries: 1}\n")));
        // A setting called note inside another setting is not a node's note.
        String nested = PIPELINE.replace("    path: out.csv\n", "    path: out.csv\n    options:\n      note: x\n");
        assertFalse(CanvasNotes.withoutCanvas(nested).equals(bare));
        // YAML that cannot be read is compared as written.
        assertEquals("a: [\n", CanvasNotes.withoutCanvas("a: [\n"));
        // A root in braces may go on over lines starting at column 1: compared as written, so a change
        // after notes: or layout: is never hidden.
        String braces = "{kilnmark: pipeline/v1, name: t,\nnotes: [], nodes: [{id: a, type: read, path: one.csv}]}\n";
        assertFalse(CanvasNotes.withoutCanvas(braces).equals(CanvasNotes.withoutCanvas(braces.replace("one.csv", "two.csv"))));
        String braces2 = "{\nkilnmark: pipeline/v1,\nlayout: {},\nrun: {retries: 1}}\n";
        assertFalse(CanvasNotes.withoutCanvas(braces2).equals(CanvasNotes.withoutCanvas(braces2.replace("1}}", "2}}"))));
        // A node in braces over several lines: its note is left in, and so is what shares its line.
        String braced = PIPELINE.replace("  - id: out\n    type: sink\n    input: keep\n    path: out.csv\n",
            "  - {id: out, type: sink, input: keep,\n     note: hi, path: out.csv}\n");
        assertFalse(CanvasNotes.withoutCanvas(braced).equals(CanvasNotes.withoutCanvas(braced.replace("out.csv", "two.csv"))));
        // A lone CR ends a line, as the parser counts lines: the note's line goes, the next stays.
        String cr = PIPELINE.replace("name: toy\n", "name: toy\r").replace("    batches: 4\n", "    note: x\n    batches: 4\n");
        assertEquals(bare, CanvasNotes.withoutCanvas(cr));
        assertFalse(bare.equals(CanvasNotes.withoutCanvas(cr.replace("batches: 4", "batches: 5"))));
        // A top-level key after the notes, whatever odd character its line holds, is kept.
        String odd = PIPELINE + NOTES + "run: {retries: 1}   # \u2028 \u0085\n";
        assertTrue(CanvasNotes.withoutCanvas(odd).contains("run: {retries: 1}"), CanvasNotes.withoutCanvas(odd));
    }
}
