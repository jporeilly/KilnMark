import { describe, expect, test } from "vitest";
import { addNode, clearLayout, connect, connectProblem, disconnect, removeNode, setPositions, setWhen, type Change } from "./edit";
import { readFlowGraph } from "./graph";

const FLOW = `kilnmark: flow/v1
name: nightly
# The nodes, in the order they were written.
nodes:
  - id: clean
    type: run-pipeline
    pipeline: demo-customers-clean   # the cleaning pipeline

  - id: tell
    type: notify
    title: "Clean failed"

  - id: halt
    type: stop

connections:
  - { from: clean, to: tell, when: failure }
  - { from: tell, to: halt }
`;

const ok = (c: Change) => {
  if ("error" in c) throw new Error(c.error);
  return c.text;
};

const graphOf = (text: string) => {
  const r = readFlowGraph(text);
  if (!r.ok) throw new Error(r.error);
  return r.graph;
};

describe("adding and removing nodes", () => {
  test("adds a node after the others, as they are written, with a fresh id", () => {
    const c = addNode(FLOW, "wait");
    expect("id" in c && c.id).toBe("wait");
    const text = ok(c);
    expect(text).toContain(`  - id: halt
    type: stop

  - id: wait
    type: wait
    for: 10m

connections:`);
    // Nothing else moved.
    expect(text.replace("\n  - id: wait\n    type: wait\n    for: 10m\n", "")).toBe(FLOW);
    expect(ok(addNode(text, "wait")).includes("id: wait-2")).toBe(true);
  });

  test("names a pipeline node after its pipeline", () => {
    const text = ok(addNode(FLOW, "run-pipeline", { pipeline: "demo-customer-spend" }));
    expect(text).toContain("  - id: demo-customer-spend\n    type: run-pipeline\n    pipeline: demo-customer-spend\n");
  });

  test("places the new node only when the diagram is placed by hand", () => {
    expect(ok(addNode(FLOW, "stop", { at: { x: 5, y: 6 } }))).not.toContain("layout:");
    const placed = ok(setPositions(FLOW, { clean: { x: 0, y: 0 } }));
    expect(graphOf(ok(addNode(placed, "stop", { at: { x: 5.4, y: 6 } }))).positions["stop"]).toEqual({ x: 5, y: 6 });
  });

  test("removes a node with its connections and position, and keeps the comments", () => {
    const placed = ok(setPositions(FLOW, { clean: { x: 0, y: 0 }, tell: { x: 300, y: 0 } }));
    const text = ok(removeNode(placed, "tell"));
    const g = graphOf(text);
    expect(g.nodes.map((n) => n.id)).toEqual(["clean", "halt"]);
    expect(g.connections).toEqual([]);
    expect(text).toContain("connections: []");
    expect(Object.keys(g.positions)).toEqual(["clean"]);
    expect(text).toContain("# the cleaning pipeline");
    expect(text).toContain("# The nodes, in the order they were written.");
  });

  test("keeps the last node, and refuses YAML it cannot read", () => {
    expect(removeNode("kilnmark: flow/v1\nnodes:\n  - { id: a, type: stop }\n", "a")).toEqual({
      error: "A flow needs at least one node, so the last one stays.",
    });
    expect("error" in removeNode("nodes: [", "a")).toBe(true);
    expect(removeNode(FLOW, "nope")).toEqual({ error: "There is no node nope." });
  });
});

describe("connections", () => {
  test("adds a connection written as the others are; on success needs no when", () => {
    const text = ok(connect(FLOW, "clean", "halt"));
    expect(text.endsWith("  - { from: tell, to: halt }\n  - { from: clean, to: halt }\n")).toBe(true);
    const cond = ok(connect(FLOW, "clean", "halt", "clean.score < good"));
    expect(cond).toContain(`  - { from: clean, to: halt, when: "clean.score < good" }`);
    expect(graphOf(cond).connections[2]).toMatchObject({ kind: "condition", when: "clean.score < good", index: 2 });
  });

  test("writes the connections list when there is none, after the nodes", () => {
    const bare = "kilnmark: flow/v1\nnodes:\n  - id: a\n    type: wait\n    for: 1m\n  - id: b\n    type: stop\nlayout:\n  a: { x: 0, y: 0 }\n";
    const text = ok(connect(bare, "a", "b", "always"));
    expect(text).toBe(
      "kilnmark: flow/v1\nnodes:\n  - id: a\n    type: wait\n    for: 1m\n  - id: b\n    type: stop\n\nconnections:\n  - { from: a, to: b, when: always }\nlayout:\n  a: { x: 0, y: 0 }\n",
    );
    expect(ok(connect("nodes:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\nconnections: []\n", "a", "b"))).toBe(
      "nodes:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\nconnections:\n  - { from: a, to: b }\n",
    );
  });

  test("writes a block item under block items", () => {
    const block = "nodes:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\n  - { id: c, type: stop }\nconnections:\n  - from: a\n    to: b\n";
    expect(ok(connect(block, "b", "c", "failure"))).toBe(block + "  - from: b\n    to: c\n    when: failure\n");
  });

  test("refuses itself, the same connection twice, a loop, and an empty condition", () => {
    const g = graphOf(FLOW);
    expect(connectProblem(g, "clean", "clean", "success")).toBe("A connection joins two different nodes.");
    expect(connectProblem(g, "clean", "tell", " Failure ")).toBe("clean is already connected to tell that way.");
    expect(connectProblem(g, "clean", "tell", "always")).toBeNull();
    expect(connectProblem(g, "halt", "clean", "success")).toBe("clean already leads to halt, so this connection would make a loop.");
    expect(connectProblem(g, "clean", "halt", "  ")).toBeNull(); // blank is on success
    expect(connectProblem(g, "clean", "halt", "a\nb")).toBe("A condition is one line.");
    expect(connect(FLOW, "halt", "clean")).toEqual({ error: "clean already leads to halt, so this connection would make a loop." });
  });

  test("changes when a connection is taken, and removes when: for on success", () => {
    const failure = ok(setWhen(FLOW, 1, "failure"));
    expect(failure).toContain("  - { from: tell, to: halt, when: failure }");
    const cond = ok(setWhen(failure, 1, "tell.status = 'failed'"));
    expect(cond).toContain(`  - { from: tell, to: halt, when: "tell.status = 'failed'" }`);
    expect(ok(setWhen(cond, 1, "success"))).toBe(FLOW);
    // Changing a connection to what it already is is allowed; to its twin is not.
    expect("text" in setWhen(FLOW, 0, "failure")).toBe(true);
    const twins = ok(connect(FLOW, "clean", "tell", "always"));
    expect(setWhen(twins, 2, "failure")).toEqual({ error: "clean is already connected to tell that way." });
  });

  test("sets a when written first in a block item to success in place", () => {
    const block = "nodes:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\nconnections:\n  - when: failure\n    from: a\n    to: b\n";
    expect(ok(setWhen(block, 0, "success"))).toBe(block.replace("when: failure", "when: success"));
  });

  test("removes a connection by its place in the list", () => {
    expect(ok(disconnect(FLOW, 0))).toBe(FLOW.replace("  - { from: clean, to: tell, when: failure }\n", ""));
    expect(disconnect(FLOW, 7)).toEqual({ error: "That connection is no longer in the YAML." });
  });

  test("refuses a list written in flow style rather than rewriting it", () => {
    const flowStyle = "nodes:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\n  - { id: c, type: stop }\nconnections: [{ from: a, to: b }]\n";
    expect(connect(flowStyle, "b", "c")).toEqual({
      error: "The connections are written in flow style ([ … ]). Make this change in the YAML below.",
    });
  });

  test("keeps the old key names of flows written before the rename", () => {
    const old = "steps:\n  - { id: a, type: stop }\n  - { id: b, type: stop }\npaths:\n  - { from: a, to: b }\n";
    const text = ok(addNode(old, "wait"));
    expect(text).toContain("steps:");
    expect(text).not.toContain("nodes:");
    expect(ok(connect(text, "a", "wait"))).toContain("paths:\n  - { from: a, to: b }\n  - { from: a, to: wait }\n");
  });
});

describe("the layout block", () => {
  test("writes positions under a comment, updates them in place, and drops stale ones", () => {
    const placed = ok(setPositions(FLOW, { clean: { x: 10.4, y: 20 }, tell: { x: 300, y: 20 } }));
    expect(placed.endsWith(
      "\n# Where the diagram draws each node. Kilnmark ignores this when it runs the flow.\nlayout:\n  clean: {x: 10, y: 20}\n  tell: {x: 300, y: 20}\n",
    )).toBe(true);
    const moved = ok(setPositions(placed, { tell: { x: 400, y: 50 }, gone: { x: 1, y: 1 } }));
    expect(graphOf(moved).positions).toEqual({ clean: { x: 10, y: 20 }, tell: { x: 400, y: 50 } });
    expect(moved).not.toContain("gone");
  });

  test("clearing the layout takes the block and its comment away", () => {
    const placed = ok(setPositions(FLOW, { clean: { x: 1, y: 2 } }));
    expect(ok(clearLayout(placed))).toBe(FLOW);
    expect(ok(clearLayout(FLOW))).toBe(FLOW);
  });
});
