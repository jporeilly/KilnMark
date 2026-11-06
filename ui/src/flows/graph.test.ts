import { describe, expect, test } from "vitest";
import { bowOffsets, connectionSentence, priorityWords, readFlowGraph, TYPE_WORDS, whenWords } from "./graph";

const FLOW = `kilnmark: flow/v1
name: nightly-customers
nodes:
  - id: clean
    type: run-pipeline
    pipeline: demo-customers-clean
    version: 3
  - id: spend
    type: run-pipeline
    pipeline: demo-customer-spend
  - id: tell
    type: notify
    title: Too many set aside
  - id: later
    type: wait
    for: 10m
  - id: empty
    type: sql
    source: warehouse
    statements:
      - TRUNCATE staging.orders
      - DELETE FROM staging.log
  - id: halt
    type: stop
    status: succeeded
connections:
  - { from: clean, to: spend, when: "clean.quarantined_share < limit_share and clean.score >= good" }
  - { from: clean, to: tell, when: failure }
  - { from: tell, to: later }
  - { from: later, to: empty, when: always }
  - { from: empty, to: halt }
  - { from: empty, to: nowhere }
layout:
  clean: { x: 10, y: 20 }
  spend: { x: 300, y: 20 }
  gone: { x: 1, y: 1 }
`;

describe("readFlowGraph", () => {
  test("reads nodes with what each does, connections with how they are taken, and positions", () => {
    const r = readFlowGraph(FLOW);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.graph.nodes.map((n) => [n.id, n.type, n.summary])).toEqual([
      ["clean", "run-pipeline", "demo-customers-clean, version 3"],
      ["spend", "run-pipeline", "demo-customer-spend"],
      ["tell", "notify", "Too many set aside"],
      ["later", "wait", "Waits 10m"],
      ["empty", "sql", "2 SQL statements on warehouse"],
      ["halt", "stop", "The run counts as succeeded"],
    ]);
    expect(r.graph.connections.map((c) => [c.from, c.to, c.kind])).toEqual([
      ["clean", "spend", "condition"],
      ["clean", "tell", "failure"],
      ["tell", "later", "success"],
      ["later", "empty", "always"],
      ["empty", "halt", "success"],
    ]);
    // Each keeps its place in the YAML list, which the canvas changes it by.
    expect(r.graph.connections.map((c) => c.index)).toEqual([0, 1, 2, 3, 4]);
    // A connection to a node that is not there, and a position for one, are left out.
    expect(r.graph.positions).toEqual({ clean: { x: 10, y: 20 }, spend: { x: 300, y: 20 } });
  });

  test("reads flows written before the rename (steps and paths)", () => {
    const r = readFlowGraph(`kilnmark: flow/v1
name: old
steps:
  - { id: a, type: run-pipeline, pipeline: p }
  - { id: b, type: stop }
paths:
  - { from: a, to: b, when: failure }
`);
    expect(r.ok && r.graph.nodes.map((n) => n.id)).toEqual(["a", "b"]);
    expect(r.ok && r.graph.connections).toEqual([{ from: "a", to: "b", kind: "failure", when: "failure", index: 0 }]);
    expect(r.ok && r.graph.nodes[1].summary).toBe("The run counts as failed");
  });

  test("says why there is nothing to draw", () => {
    expect(readFlowGraph("nodes: [a, : b")).toEqual({ ok: false, error: "The YAML cannot be read." });
    expect(readFlowGraph("- a\n- b\n")).toEqual({ ok: false, error: "The YAML is not a flow." });
    expect(readFlowGraph("kilnmark: flow/v1\nname: x\n")).toEqual({ ok: false, error: "The flow has no nodes yet." });
  });

  test("reads when as the server does, in any case; a position needs x and y", () => {
    const r = readFlowGraph(`nodes:
  - { id: a, type: wait, for: 1m }
  - { id: b, type: stop }
connections:
  - { from: a, to: b, when: " Failure " }
  - { from: a, to: b, when: ALWAYS }
layout:
  a: { x: 5 }
  b: { x: 1, y: 2 }
`);
    expect(r.ok && r.graph.connections.map((c) => c.kind)).toEqual(["failure", "always"]);
    expect(r.ok && r.graph.positions).toEqual({ b: { x: 1, y: 2 } });
  });

  test("keeps the first of two nodes with one id", () => {
    const r = readFlowGraph("nodes:\n  - { id: a, type: wait, for: 1m }\n  - { id: a, type: stop }\n");
    expect(r.ok && r.graph.nodes).toEqual([{ id: "a", type: "wait", summary: "Waits 1m", settings: { for: "1m" } }]);
  });
});

describe("connection words", () => {
  const c = (kind: "success" | "failure" | "always" | "condition", when: string) => ({ from: "a", to: "b", kind, when, index: 0 });

  test("label each kind, shortening a long condition", () => {
    expect(whenWords(c("success", "success"))).toBe("on success");
    expect(whenWords(c("failure", "failure"))).toBe("on failure");
    expect(whenWords(c("always", "always"))).toBe("always");
    expect(whenWords(c("condition", "a.score >= good"))).toBe("a.score >= good");
    const long = whenWords(c("condition", "a.quarantined_share < 0.02 and a.score >= good and region != 'APAC'"));
    expect(long.length).toBeLessThanOrEqual(40);
    expect(long.endsWith("…")).toBe(true);
  });

  test("say in a sentence when the next node runs", () => {
    expect(connectionSentence(c("failure", "failure"))).toBe("b runs when a fails");
    expect(connectionSentence(c("condition", "a.score >= good"))).toBe("b runs when a succeeds and a.score >= good");
  });
});

describe("bowOffsets", () => {
  test("leaves a pair's only connection straight and spreads several either side, whichever way they go", () => {
    const offsets = bowOffsets([
      { from: "a", to: "b" },
      { from: "a", to: "c" },
      { from: "a", to: "b" },
      { from: "b", to: "a" },
    ]);
    expect(offsets[1]).toBe(0);
    // a-b has three: -36, 0, 36 in one frame; b→a is turned round, so its offset changes sign.
    expect([offsets[0], offsets[2], -offsets[3]]).toEqual([-36, 0, 36]);
  });
});

describe("for-each nodes", () => {
  test("say what they run, and over how many items or which parameter", () => {
    const r = readFlowGraph(`kilnmark: flow/v1
name: per-region
nodes:
  - id: regions
    type: for-each
    pipeline: sales-by-region
    items: [EMEA, APAC, AMER]
    as: region
  - id: one
    type: for-each
    pipeline: sales-by-region
    items: [EMEA]
    as: region
  - id: given
    type: for-each
    pipeline: sales-by-year
    items: "\${years}"
    as: year
  - id: bare
    type: for-each
`);
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.graph.nodes.map((n) => n.summary)).toEqual([
      "sales-by-region for each of 3 items",
      "sales-by-region for each of 1 item",
      "sales-by-year for each of years",
      "a pipeline for each item",
    ]);
    expect(TYPE_WORDS["for-each"]).toBe("Runs a pipeline for each item");
  });
});

describe("priority in a node's summary", () => {
  test("says a high or low priority, not a normal one", () => {
    const r = readFlowGraph(`kilnmark: flow/v1
name: f
nodes:
  - { id: a, type: run-pipeline, pipeline: load, priority: high }
  - { id: b, type: run-pipeline, pipeline: load, priority: normal }
  - { id: c, type: for-each, pipeline: load, items: [x, y], as: region, priority: LOW }
  - { id: d, type: run-pipeline, pipeline: load }
`);
    expect(r.ok && r.graph.nodes.map((n) => n.summary)).toEqual([
      "load, high priority",
      "load",
      "load for each of 2 items, low priority",
      "load",
    ]);
    expect(priorityWords(undefined)).toBe("");
  });
});

describe("a check-source node's summary", () => {
  test("says what it looks for, and where", () => {
    const r = readFlowGraph(`kilnmark: flow/v1
name: f
nodes:
  - { id: a, type: check-source, source: warehouse, tables: [sales.orders, sales.customers], files: [in/a.csv] }
  - { id: b, type: check-source, source: landing, files: [in/a.csv] }
  - { id: c, type: check-source, source: warehouse }
`);
    expect(r.ok && r.graph.nodes.map((n) => [n.summary, TYPE_WORDS[n.type]])).toEqual([
      ["2 tables and 1 file on warehouse", "Checks a source"],
      ["1 file on landing", "Checks a source"],
      ["That warehouse can be reached", "Checks a source"],
    ]);
  });
});

describe("a wait-for node's summary", () => {
  test("says what it waits for, where and how often", () => {
    const r = readFlowGraph(`kilnmark: flow/v1
name: f
nodes:
  - { id: a, type: wait-for, source: landing, file: "incoming/*.csv", every: 2m }
  - { id: b, type: wait-for, source: warehouse, query: "select true" }
`);
    expect(r.ok && r.graph.nodes.map((n) => [n.summary, TYPE_WORDS[n.type]])).toEqual([
      ["incoming/*.csv on landing, every 2m", "Waits for a file or a query"],
      ["A query on warehouse, every 5m", "Waits for a file or a query"],
    ]);
  });
});
