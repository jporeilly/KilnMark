import { describe, expect, test } from "vitest";
import { connectionSentence, readFlowGraph, whenWords } from "./graph";

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
      ["halt", "stop", "Ends the flow: succeeded"],
    ]);
    expect(r.graph.connections.map((c) => [c.from, c.to, c.kind])).toEqual([
      ["clean", "spend", "condition"],
      ["clean", "tell", "failure"],
      ["tell", "later", "success"],
      ["later", "empty", "always"],
      ["empty", "halt", "success"],
    ]);
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
    expect(r.ok && r.graph.connections).toEqual([{ from: "a", to: "b", kind: "failure", when: "failure" }]);
    expect(r.ok && r.graph.nodes[1].summary).toBe("Ends the flow: failed");
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
    expect(r.ok && r.graph.nodes).toEqual([{ id: "a", type: "wait", summary: "Waits 1m" }]);
  });
});

describe("connection words", () => {
  const c = (kind: "success" | "failure" | "always" | "condition", when: string) => ({ from: "a", to: "b", kind, when });

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
