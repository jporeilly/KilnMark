import { describe, expect, test } from "vitest";
import { parseDocument } from "yaml";
import { readGraph, removeNode, renameNode, setPositions } from "./model";
import { addNote, detachNotes, MAX_NOTES, NOTE_SIZE, readNotes, removeNote, setNodeNote, updateNote, withoutCanvas, type Change } from "./notes";
import { readFlowGraph } from "../../flows/graph";
import { removeNode as removeFlowNode } from "../../flows/edit";

const COMMENT = " Notes on the canvas, for people. Kilnmark ignores this list when it runs the pipeline.";

// Comments, odd spacing and quoting the canvas must leave exactly as they are.
const PIPELINE = `# A pipeline (a Kilnmark sample).
kilnmark: pipeline/v1
name: tidy-people
description: "People, tidied"   # quoted on purpose

nodes:
  - id: people
    type: read
    source: lab-db       # aligned
    asset: crm.people

  - id: out
    type: write
    input: people
    path: out.csv
`;

const ok = (c: Change) => {
  if ("error" in c) throw new Error(c.error);
  expect(parseDocument(c.text).errors).toEqual([]);
  return c.text;
};
const notesOf = (text: string) => readNotes(parseDocument(text));
const note = (over: Partial<Parameters<typeof addNote>[1]> = {}) => ({ text: "Hello", node: null, x: 10, y: 20, ...NOTE_SIZE, colour: "yellow" as const, ...over });

describe("notes on the canvas, as YAML", () => {
  test("the first note starts a notes list at the end, under its comment; the rest of the text is untouched", () => {
    const one = ok(addNote(PIPELINE, note({ text: "**What it does:** tidies people.\n\n- Run it after the scan." }), COMMENT));
    expect(one.startsWith(PIPELINE)).toBe(true);
    expect(one.slice(PIPELINE.length)).toBe(
      "\n# Notes on the canvas, for people. Kilnmark ignores this list when it runs the pipeline.\nnotes:\n" +
        "  - text: |-\n      **What it does:** tidies people.\n\n      - Run it after the scan.\n" +
        "    x: 10\n    y: 20\n    width: 240\n    height: 140\n    colour: yellow\n",
    );
    expect(notesOf(one)).toEqual([
      { index: 0, text: "**What it does:** tidies people.\n\n- Run it after the scan.", node: null, x: 10, y: 20, width: 240, height: 140, colour: "yellow" },
    ]);
    const two = ok(addNote(one, note({ text: "On people", node: "people", y: -160, colour: "blue" }), COMMENT));
    expect(two.startsWith(one)).toBe(true);
    expect(notesOf(two)[1]).toMatchObject({ index: 1, text: "On people", node: "people", y: -160, colour: "blue" });
  });

  test("a change to a note rewrites only that note's lines; every other byte stays", () => {
    const two = ok(addNote(ok(addNote(PIPELINE, note({ text: "First" }), COMMENT)), note({ text: "Second" }), COMMENT));
    const moved = ok(updateNote(two, 1, { x: 300, y: -40 }));
    const before = two.split("\n");
    const after = moved.split("\n");
    expect(after.length).toBe(before.length);
    expect(after.filter((l, i) => l !== before[i])).toEqual(["    x: 300", "    y: -40"]);
    const recoloured = ok(updateNote(moved, 0, { colour: "green", text: "First, now\nover two lines" }));
    expect(recoloured.startsWith(PIPELINE)).toBe(true);
    expect(notesOf(recoloured)[0]).toMatchObject({ text: "First, now\nover two lines", colour: "green" });
    expect(notesOf(recoloured)[1]).toMatchObject({ text: "Second", x: 300, y: -40 });
    // CRLF text keeps its line breaks.
    const crlf = two.replace(/\n/g, "\r\n");
    expect(ok(updateNote(crlf, 0, { width: 400 }))).toBe(ok(updateNote(two, 0, { width: 400 })).replace(/\n/g, "\r\n"));
  });

  test("removing every note gives back the YAML as it was, byte for byte", () => {
    const two = ok(addNote(ok(addNote(PIPELINE, note(), COMMENT)), note({ text: "Two" }), COMMENT));
    const one = ok(removeNote(two, 0, COMMENT));
    expect(notesOf(one).map((n) => n.text)).toEqual(["Two"]);
    expect(ok(removeNote(one, 0, COMMENT))).toBe(PIPELINE);
  });

  test("text that YAML would read as something else is quoted; control characters are dropped", () => {
    const tricky = ok(addNote(PIPELINE, note({ text: "#not a comment: {x}" }), COMMENT));
    expect(notesOf(tricky)[0].text).toBe("#not a comment: {x}");
    const odd = ok(addNote(PIPELINE, note({ text: "  leading space\n\ttab\u0007bell" }), COMMENT));
    expect(notesOf(odd)[0].text).toBe("  leading space\n\ttabbell");
    expect(notesOf(ok(addNote(PIPELINE, note({ text: "x".repeat(3000) }), COMMENT)))[0].text.length).toBe(2000);
  });

  test("at most 50 notes; a note written in flow style is changed whole", () => {
    let text = PIPELINE;
    for (let i = 0; i < MAX_NOTES; i++) text = ok(addNote(text, note({ text: `n${i}` }), COMMENT));
    const c = addNote(text, note(), COMMENT);
    expect("error" in c && c.error).toBe("A canvas has at most 50 notes. Remove one first.");
    const flowItem = PIPELINE + "notes:\n  - {text: hi, x: 1, y: 2}\n";
    expect(notesOf(ok(updateNote(flowItem, 0, { x: 50 })))[0]).toMatchObject({ text: "hi", x: 50, y: 2 });
  });

  test("a node's own note: added after its type, changed in place, removed when blank, one line only", () => {
    const noted = ok(setNodeNote(PIPELINE, "out", "Writes the\ntidy file"));
    expect(noted).toBe(PIPELINE.replace("    type: write\n", "    type: write\n    note: Writes the tidy file\n"));
    const r = readGraph(noted);
    expect(r.ok && r.graph.nodes.find((n) => n.id === "out")?.note).toBe("Writes the tidy file");
    // Not one of the node's settings.
    expect(r.ok && "note" in r.graph.nodes.find((n) => n.id === "out")!.settings).toBe(false);
    expect(ok(setNodeNote(noted, "out", "  "))).toBe(PIPELINE);
    const long = readGraph(ok(setNodeNote(PIPELINE, "out", "y".repeat(400))));
    expect(long.ok && long.graph.nodes[1].note?.length).toBe(280);
  });

  test("notes are not nodes: the graph's nodes, edges and positions are the same with them", () => {
    const plain = readGraph(PIPELINE);
    const noted = readGraph(ok(addNote(ok(setNodeNote(PIPELINE, "people", "Reads")), note({ node: "people" }), COMMENT)));
    if (!plain.ok || !noted.ok) throw new Error("unreadable");
    expect(noted.graph.nodes.map((n) => n.id)).toEqual(plain.graph.nodes.map((n) => n.id));
    expect(noted.graph.edges).toEqual(plain.graph.edges);
    expect(noted.graph.notes).toHaveLength(1);
  });

  test("renaming a node keeps its notes on it; removing it leaves them where they were drawn", () => {
    const on = ok(addNote(PIPELINE, note({ node: "people", x: 0, y: -160 }), COMMENT));
    const renamed = ok(renameNode(on, "people", "everyone"));
    expect(notesOf(renamed)[0].node).toBe("everyone");
    const detached = ok(detachNotes(ok(removeNode(renamed, "everyone", new Map())), "everyone", { x: 100, y: 300 }));
    expect(notesOf(detached)[0]).toMatchObject({ node: null, x: 100, y: 140 });
    // Moving nodes leaves notes alone.
    expect(notesOf(ok(setPositions(on, { people: { x: 5, y: 5 } })))).toEqual(notesOf(on));
  });

  test("a flow's notes read and change as a pipeline's; a removed flow node's notes stay", () => {
    const flow = "kilnmark: flow/v1\nname: f\nnodes:\n  - id: a\n    type: wait\n    for: 1m\n    note: Pauses\n  - id: b\n    type: wait\n    for: 2m\nconnections:\n  - { from: a, to: b }\n";
    const noted = ok(addNote(flow, note({ node: "a", x: 0, y: -100 }), COMMENT));
    const g = readFlowGraph(noted);
    expect(g.ok && g.graph.nodes.length).toBe(2);
    expect(g.ok && g.graph.notes.length).toBe(1);
    expect(g.ok && g.graph.nodes[0].note).toBe("Pauses");
    expect(g.ok && "note" in g.graph.nodes[0].settings).toBe(false);
    const gone = ok(removeFlowNode(noted, "a", { x: 40, y: 200 }));
    expect(notesOf(gone)[0]).toMatchObject({ node: null, x: 40, y: 100 });
  });
});

describe("comparing versions without the canvas", () => {
  test("layout, notes and node notes are left out; what runs is not", () => {
    const bare = withoutCanvas(PIPELINE);
    const noted = ok(addNote(ok(setNodeNote(PIPELINE, "out", "Writes")), note(), COMMENT));
    const laid = ok(setPositions(noted, { people: { x: 0, y: 0 }, out: { x: 300, y: 0 } }));
    expect(withoutCanvas(noted)).toBe(bare);
    expect(withoutCanvas(laid)).toBe(bare);
    expect(withoutCanvas(laid.replace(/\n/g, "\r\n"))).toBe(bare);
    expect(withoutCanvas(PIPELINE.replace("out.csv", "other.csv"))).not.toBe(bare);
    // A node note over several lines, with a blank line in it.
    expect(withoutCanvas(PIPELINE.replace("    type: write\n", "    type: write\n    note: |\n      One\n\n      Two\n"))).toBe(bare);
  });
});
