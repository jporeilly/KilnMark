import { describe, expect, test } from "vitest";
import type { NodeType } from "../../api";
import {
  addNode,
  catalogueOf,
  connect,
  connectProblem,
  disconnect,
  duplicateNode,
  freshId,
  possibleConnections,
  readGraph,
  removeNode,
  renameNode,
  setPositions,
  setSetting,
  parseRef,
  portsOf,
  type Change,
  type Graph,
} from "./model";
import { emptyYaml as emptyPipeline, withoutLayout } from "../template";

const t = (type: string, kind: string, inputs: string[], outputs: string[], many: string[] = []): NodeType => ({
  type,
  kind,
  label: type,
  help: "",
  inputs,
  manyInputs: many,
  outputs,
  settings: [],
});
const CAT = catalogueOf([
  t("read", "source", [], ["out"]),
  t("filter", "transform", ["in"], ["out", "rejected"]),
  t("compute", "transform", ["in"], ["out"]),
  t("lookup", "transform", ["main", "lookup"], ["out", "rejected"]),
  t("union", "transform", ["in"], ["out"], ["in"]),
  t("route", "transform", ["in"], ["out"]),
  t("write", "sink", ["in"], []),
]);

const BILLING = `kilnmark: pipeline/v1
name: customer-billing
description: Monthly spend per customer, with orphan transactions set aside.
nodes:
  # Two reads from the same source.
  - id: customers
    type: read
    source: sales-db
    asset: crm.customers

  - id: transactions
    type: read
    source: sales-db
    asset: "2024.10"          # quoted: stays text

  - id: tidy
    type: compute
    input: customers
    columns:
      full_name: "trim(first_name) || ' ' || trim(last_name)"
      email: "lower(trim(email))"

  - id: joined
    type: lookup
    inputs: { main: transactions, lookup: tidy }
    on: { customer_id: customer_id }
    keep: [full_name, email]
    unmatched: rejected        # rows with no customer go to the 'rejected' port
    memory_limit_mb: 256
    future_setting: { nested: [1, 2, { deep: yes }] }

  - id: orphans
    type: write
    input: joined.rejected
    source: file-share
    path: quarantine/orphan-spend.csv
    format: csv
    code: 007

  - id: out
    type: write
    input: joined
    source: warehouse
    table: billing.customer_spend
    mode: replace              # create | append | replace | upsert (with key)
`;

const ok = (c: Change): string => {
  if ("error" in c) throw new Error(c.error);
  return c.text;
};
const graph = (text: string): Graph => {
  const r = readGraph(text, CAT);
  if (!r.ok) throw new Error(r.error);
  return r.graph;
};
/** Every node's id, type, settings and inputs: what a change must keep. */
const meaning = (g: Graph) => g.nodes.map((s) => ({ id: s.id, type: s.type, settings: s.settings, inputs: s.inputs }));

describe("reading the graph", () => {
  test("a pipeline saved with the old steps key reads the same, and the canvas writes nodes:", () => {
    const old = BILLING.replace(/^nodes:/m, "steps:");
    expect(graph(old).nodes.map((s) => s.id)).toEqual(graph(BILLING).nodes.map((s) => s.id));
    const text = ok(addNode(old, "filter", CAT, { node: "tidy" }));
    expect(graph(text).nodes.map((s) => s.id)).toContain("filter");
    expect(text).toMatch(/^nodes:/m);
    expect(text).not.toMatch(/^steps:/m);
  });

  test("nodes, settings, ports and edges", () => {
    const g = graph(BILLING);
    expect(g.name).toBe("customer-billing");
    expect(g.nodes.map((s) => s.id)).toEqual(["customers", "transactions", "tidy", "joined", "orphans", "out"]);
    expect(g.edges).toEqual([
      { from: "customers", fromPort: "out", to: "tidy", toPort: "in" },
      { from: "transactions", fromPort: "out", to: "joined", toPort: "main" },
      { from: "tidy", fromPort: "out", to: "joined", toPort: "lookup" },
      { from: "joined", fromPort: "rejected", to: "orphans", toPort: "in" },
      { from: "joined", fromPort: "out", to: "out", toPort: "in" },
    ]);
    const tidy = g.nodes.find((s) => s.id === "tidy")!;
    expect(tidy.settings.columns).toEqual({ full_name: "trim(first_name) || ' ' || trim(last_name)", email: "lower(trim(email))" });
    expect(tidy.line).toBe(16);
    expect(tidy.keyLines.columns).toBe(19);
  });

  test("text keeps what was written", () => {
    const g = graph(BILLING);
    expect(g.nodes.find((s) => s.id === "transactions")!.texts.asset).toBe("2024.10");
    expect(g.nodes.find((s) => s.id === "orphans")!.texts.code).toBe("007");
  });

  test("a union lists its inputs; route adds a port per route", () => {
    const g = graph(`kilnmark: pipeline/v1
name: x
nodes:
  - { id: a, type: read }
  - { id: b, type: read }
  - { id: all, type: union, input: [a, b] }
  - id: split
    type: route
    input: all
    routes: { large: "amount > 1000", small: "amount < 10" }
  - { id: big, type: write, input: split.large }
`);
    expect(g.edges.filter((e) => e.to === "all").map((e) => e.from)).toEqual(["a", "b"]);
    expect(g.edges.at(-1)).toEqual({ from: "split", fromPort: "large", to: "big", toPort: "in" });
  });

  test("YAML that cannot be read is not a graph, with its line", () => {
    const r = readGraph("kilnmark: pipeline/v1\nname: x\nnodes:\n  - id: a\n   type: read\n", CAT);
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.line).toBeGreaterThan(0);
    const dup = readGraph("kilnmark: pipeline/v1\nname: x\nname: y\nnodes: []\n", CAT);
    expect(dup.ok).toBe(false);
  });

  test("positions come from the layout block", () => {
    const g = graph(BILLING + "layout:\n  customers: { x: 40, y: 120 }\n  nobody: { x: 1, y: 2 }\n");
    expect(g.positions.customers).toEqual({ x: 40, y: 120 });
  });
});

describe("round trips keep every setting and comment", () => {
  const settingsBefore = meaning(graph(BILLING));

  test("moving nodes changes only the layout block", () => {
    const text = ok(setPositions(BILLING, { customers: { x: 10.4, y: 20.6 }, out: { x: 500, y: 90 }, ghost: { x: 0, y: 0 } }));
    expect(meaning(graph(text))).toEqual(settingsBefore);
    expect(graph(text).positions).toEqual({ customers: { x: 10, y: 21 }, out: { x: 500, y: 90 } });
    expect(text).toContain("# Two reads from the same source.");
    expect(text).toContain("# rows with no customer go to the 'rejected' port");
    expect(text).toContain("# create | append | replace | upsert (with key)");
    expect(text).toContain("# Where the canvas draws each node. Kilnmark ignores this when it runs the pipeline.");
    expect(text).toContain('asset: "2024.10"');
    expect(text).toContain("code: 007");
    // Moving again replaces the positions in place.
    const again = ok(setPositions(text, { customers: { x: 1, y: 2 } }));
    expect(again.match(/customers: \{/g)?.length).toBe(1);
  });

  test("adding then removing a node gives back the same pipeline", () => {
    const added = addNode(BILLING, "filter", CAT, { node: "tidy" });
    const text = ok(added);
    expect("id" in added && added.id).toBe("filter");
    const g = graph(text);
    expect(g.edges).toContainEqual({ from: "tidy", fromPort: "out", to: "filter", toPort: "in" });
    expect(g.nodes.map((s) => s.id).indexOf("filter")).toBe(3);
    const back = ok(removeNode(text, "filter", CAT));
    expect(meaning(graph(back))).toEqual(settingsBefore);
  });

  test("connecting and disconnecting keeps the other settings", () => {
    const text = ok(addNode(BILLING, "union", CAT));
    const one = ok(connect(text, { from: "customers", fromPort: "out", to: "union", toPort: "in" }, CAT));
    const two = ok(connect(one, { from: "joined", fromPort: "rejected", to: "union", toPort: "in" }, CAT));
    expect(two).toContain("input: [customers, joined.rejected]");
    const back = ok(disconnect(ok(disconnect(two, { from: "customers", fromPort: "out", to: "union", toPort: "in" }, CAT)),
      { from: "joined", fromPort: "rejected", to: "union", toPort: "in" }, CAT));
    expect(back).not.toMatch(/- id: union\n\s+type: union\n\s+input/);
    expect(meaning(graph(back)).filter((s) => s.id !== "union")).toEqual(settingsBefore);
  });

  test("renaming follows every input and the layout", () => {
    const placed = ok(setPositions(BILLING, { joined: { x: 1, y: 1 } }));
    const text = ok(renameNode(placed, "joined", "matched"));
    const g = graph(text);
    expect(g.edges.filter((e) => e.from === "matched").map((e) => `${e.fromPort}->${e.to}`)).toEqual(["rejected->orphans", "out->out"]);
    expect(g.positions.matched).toEqual({ x: 1, y: 1 });
    expect(g.nodes.find((s) => s.id === "matched")!.settings).toEqual(settingsBefore.find((s) => s.id === "joined")!.settings);
    expect("error" in renameNode(text, "tidy", "Bad Id")).toBe(true);
    expect("error" in renameNode(text, "tidy", "out")).toBe(true);
  });

  test("a duplicate copies settings and inputs", () => {
    const c = duplicateNode(BILLING, "tidy");
    const g = graph(ok(c));
    expect("id" in c && c.id).toBe("tidy-copy");
    const copy = g.nodes.find((s) => s.id === "tidy-copy")!;
    expect(copy.settings).toEqual(g.nodes.find((s) => s.id === "tidy")!.settings);
    expect(copy.inputs).toEqual([{ port: null, from: "customers", fromPort: "out" }]);
  });

  test("settings: set, change and remove, by kind", () => {
    let text = ok(setSetting(BILLING, "tidy", "columns", { full_name: "first_name || last_name", n: "1" }, "expressions"));
    expect(text).toContain('full_name: "first_name || last_name"');
    expect(graph(text).nodes.find((s) => s.id === "tidy")!.settings.columns).toEqual({ full_name: "first_name || last_name", n: "1" });
    text = ok(setSetting(text, "out", "mode", "append", "choice"));
    expect(text).toMatch(/mode: append {14}# create \| append \| replace \| upsert \(with key\)/);
    text = ok(setSetting(text, "out", "key", ["customer_id", "month"], "columns"));
    expect(text).toContain("key: [customer_id, month]");
    text = ok(setSetting(text, "out", "commit_every", 5000, "number"));
    text = ok(setSetting(text, "out", "keep_grants", false, "boolean"));
    text = ok(setSetting(text, "out", "label", "007", "text"));
    const out = graph(text).nodes.find((s) => s.id === "out")!;
    expect(out.settings).toMatchObject({ mode: "append", key: ["customer_id", "month"], commit_every: 5000, keep_grants: false, label: "007" });
    text = ok(setSetting(text, "out", "key", [], "columns"));
    text = ok(setSetting(text, "out", "label", undefined));
    expect(graph(text).nodes.find((s) => s.id === "out")!.settings).not.toHaveProperty("key");
    expect(graph(text).nodes.find((s) => s.id === "out")!.settings).not.toHaveProperty("label");
    // The settings the canvas does not know, and the other nodes, are untouched.
    const joined = graph(text).nodes.find((s) => s.id === "joined")!;
    expect(joined.settings.future_setting).toEqual({ nested: [1, 2, { deep: "yes" }] });
    expect(meaning(graph(text)).filter((s) => s.id !== "tidy" && s.id !== "out")).toEqual(
      settingsBefore.filter((s) => s.id !== "tidy" && s.id !== "out"),
    );
  });

  test("every change round-trips: what is read again is what was written", () => {
    let text = BILLING;
    const nodes: ((x: string) => Change)[] = [
      (x) => addNode(x, "filter", CAT, { node: "tidy" }),
      (x) => setSetting(x, "filter", "condition", "status = 'active'", "expression"),
      (x) => disconnect(x, { from: "tidy", fromPort: "out", to: "joined", toPort: "lookup" }, CAT),
      (x) => connect(x, { from: "filter", fromPort: "out", to: "joined", toPort: "lookup" }, CAT),
      (x) => setPositions(x, { filter: { x: 300, y: 40 } }),
      (x) => duplicateNode(x, "out"),
      (x) => removeNode(x, "out-copy", CAT),
    ];
    for (const s of nodes) {
      text = ok(s(text));
      const g = graph(text);
      expect(graph(ok(setPositions(text, g.positions)))).toEqual(g);
    }
    const g = graph(text);
    expect(g.edges).toContainEqual({ from: "filter", fromPort: "out", to: "joined", toPort: "lookup" });
    expect(text).toContain(`condition: "status = 'active'"`);
    expect(text).toContain("inputs: {main: transactions, lookup: filter}");
    for (const s of settingsBefore) {
      expect(g.nodes.find((x) => x.id === s.id)!.settings).toEqual(s.settings);
    }
  });
});

describe("connections", () => {
  test("refused with a reason", () => {
    const g = graph(BILLING);
    const why = (from: string, fromPort: string, to: string, toPort: string) => connectProblem(g, { from, fromPort, to, toPort }, CAT);
    expect(why("tidy", "out", "tidy", "in")).toMatch(/cannot read from itself/);
    expect(why("tidy", "out", "customers", "in")).toMatch(/where rows start/);
    expect(why("customers", "out", "tidy", "in")).toMatch(/already feeds/);
    expect(why("transactions", "out", "tidy", "in")).toMatch(/already reads from customers.*union/);
    expect(why("out", "out", "tidy", "in")).toMatch(/no output called out/);
    expect(why("customers", "out", "joined", "side")).toMatch(/no input called side/);
    const text = ok(addNode(BILLING, "filter", CAT));
    const looped = ok(connect(text, { from: "joined", fromPort: "out", to: "filter", toPort: "in" }, CAT));
    const c = connect(ok(addNode(looped, "union", CAT)), { from: "filter", fromPort: "out", to: "union", toPort: "in" }, CAT);
    const withUnion = ok(c);
    const toLoop = connect(withUnion, { from: "union", fromPort: "out", to: "filter", toPort: "in" }, CAT);
    expect("error" in toLoop && toLoop.error).toMatch(/already reads from joined/);
    const g2 = graph(withUnion);
    expect(connectProblem(g2, { from: "filter", fromPort: "out", to: "union", toPort: "in" }, CAT)).toMatch(/feeds/);
  });

  test("loops are refused", () => {
    const text = `kilnmark: pipeline/v1
name: x
nodes:
  - { id: a, type: read }
  - { id: u, type: union, input: [a] }
  - { id: f, type: filter, input: u }
`;
    const c = connect(text, { from: "f", fromPort: "out", to: "u", toPort: "in" }, CAT);
    expect("error" in c && c.error).toMatch(/loop/);
  });

  test("a union takes many inputs, written as a list", () => {
    let text = ok(addNode(emptyPipeline("x"), "read", CAT));
    text = ok(addNode(text, "read", CAT));
    text = ok(addNode(text, "union", CAT));
    text = ok(connect(text, { from: "read", fromPort: "out", to: "union", toPort: "in" }, CAT));
    text = ok(connect(text, { from: "read-2", fromPort: "out", to: "union", toPort: "in" }, CAT));
    expect(text).toContain("input: [read, read-2]");
    text = ok(disconnect(text, { from: "read", fromPort: "out", to: "union", toPort: "in" }, CAT));
    expect(text).toContain("input: read-2");
  });

  test("the Connect to menu offers only what is allowed", () => {
    const g = graph(ok(addNode(BILLING, "filter", CAT)));
    const offers = possibleConnections(g, "filter", CAT).map((e) => `${e.from}.${e.fromPort}->${e.to}.${e.toPort}`);
    // Into its free input from any node's output; out of it into no port, since every input is fed.
    expect(offers).toEqual(expect.arrayContaining(["customers.out->filter.in", "joined.rejected->filter.in"]));
    expect(offers.every((o) => o.endsWith("->filter.in"))).toBe(true);
    expect(offers).not.toContain("out.out->filter.in");
  });
});

describe("new pipelines", () => {
  test("an empty canvas grows one node at a time", () => {
    let text = emptyPipeline("my-pipeline");
    expect(graph(text).nodes).toEqual([]);
    text = ok(addNode(text, "read", CAT, null, { x: 0, y: 0 }));
    text = ok(addNode(text, "filter", CAT, { node: "read" }));
    text = ok(addNode(text, "compute", CAT, { node: "filter" }));
    text = ok(addNode(text, "write", CAT, { node: "compute" }));
    expect(text).toMatch(/^nodes:\n {2}- id: read\n {4}type: read\n\n {2}- id: filter\n {4}type: filter\n {4}input: read\n/m);
    expect(graph(text).edges.map((e) => `${e.from}->${e.to}`)).toEqual(["read->filter", "filter->compute", "compute->write"]);
  });

  test("fresh ids", () => {
    expect(freshId("filter", [])).toBe("filter");
    expect(freshId("filter", ["filter", "filter-2"])).toBe("filter-3");
    expect(freshId("9 Odd Name!", [])).toBe("odd-name-");
  });
});

const TRICKY = `# Header comment
kilnmark: pipeline/v1   # format
name: tricky
description: >
  Folded description
  over two lines.
nodes:
- id: src            # unindented sequence
  type: read
  source: 'sales-db'
  asset: 2024.10
  row_limit: 007
  flag: yes
- id: f
  type: filter
  input: src
  condition: "name = 'a#b' and note != \\"x: y\\""   # quotes, hash, colon
  sql: |
    select *
      from t   -- keep indent
- { id: c, type: compute, input: f.rejected, columns: { a: "x || ':' || y" } }
- id: w
  type: write
  input: c
  mode: replace              # create | append
# trailing comment
layout:
  src: { x: 10, y: 20 }
  w: {x: 300, y: 20}
`;

/** The lines of `b` that are not in `a`, and those of `a` not in `b`. */
function changed(a: string, b: string) {
  const al = a.split("\n");
  const bl = b.split("\n");
  return { added: bl.filter((l) => !al.includes(l)), removed: al.filter((l) => !bl.includes(l)) };
}

describe("only what changes is rewritten", () => {
  test("moving a node rewrites its position and nothing else", () => {
    const out = ok(setPositions(TRICKY, { src: { x: 5, y: 6 } }));
    expect(changed(TRICKY, out)).toEqual({ added: ["  src: { x: 5, y: 6 }"], removed: ["  src: { x: 10, y: 20 }"] });
  });

  test("a setting is changed in place, keeping its comment's alignment", () => {
    const out = ok(setSetting(TRICKY, "w", "mode", "append", "choice"));
    expect(changed(TRICKY, out)).toEqual({
      added: ["  mode: append              # create | append"],
      removed: ["  mode: replace              # create | append"],
    });
    const cond = ok(setSetting(TRICKY, "f", "condition", 'a # b: "c"', "expression"));
    expect(changed(TRICKY, cond).added).toEqual(['  condition: "a # b: \\"c\\""   # quotes, hash, colon']);
  });

  test("renaming changes the id, the inputs naming it and its position", () => {
    const out = ok(renameNode(TRICKY, "f", "g"));
    expect(changed(TRICKY, out)).toEqual({
      added: ["- id: g", '- { id: c, type: compute, input: g.rejected, columns: { a: "x || \':\' || y" } }'],
      removed: ["- id: f", '- { id: c, type: compute, input: f.rejected, columns: { a: "x || \':\' || y" } }'],
    });
  });

  test("adding, removing and duplicating leave the other nodes alone", () => {
    const added = ok(addNode(TRICKY, "compute", CAT, { node: "f" }, { x: 1, y: 1 }));
    expect(changed(TRICKY, added)).toEqual({ added: ["- id: compute", "  type: compute", "  input: f", "  compute: {x: 1, y: 1}"], removed: [] });
    const removed = ok(removeNode(TRICKY, "w", CAT));
    expect(changed(TRICKY, removed).added).toEqual([]);
    expect(changed(TRICKY, removed).removed).toEqual(["- id: w", "  type: write", "  input: c", "  mode: replace              # create | append", "  w: {x: 300, y: 20}"]);
    const dup = ok(duplicateNode(TRICKY, "w"));
    expect(changed(TRICKY, dup).removed).toEqual([]);
    expect(graph(dup).nodes.find((x) => x.id === "w-copy")!.settings).toEqual({ mode: "replace" });
  });

  test("line endings and a byte order mark are kept", () => {
    const crlf = "kilnmark: pipeline/v1\r\nname: x\r\nnodes:\r\n  - id: a\r\n    type: read   # c\r\n";
    const out = ok(setPositions(crlf, { a: { x: 1, y: 1 } }));
    expect(out.startsWith(crlf)).toBe(true);
    expect(out.slice(crlf.length).replace(/\r\n/g, "")).not.toContain("\n");
    const bom = "\ufeffkilnmark: pipeline/v1\nname: x\nnodes:\n  - id: a\n    type: read\n";
    expect(ok(setPositions(bom, { a: { x: 1, y: 1 } })).startsWith(bom)).toBe(true);
  });

  test("a number setting is written as typed, without losing digits", () => {
    const out = ok(setSetting(TRICKY, "src", "row_limit", "9007199254740993", "number"));
    expect(out).toContain("  row_limit: 9007199254740993\n");
    const added = ok(setSetting(TRICKY, "w", "commit_every", "12345678901234567890", "number"));
    expect(added).toContain("  commit_every: 12345678901234567890\n");
  });

  test("the layout is what Run and Save may ignore", () => {
    expect(withoutLayout(ok(setPositions(BILLING, { tidy: { x: 1, y: 2 } })))).toBe(BILLING);
    expect(withoutLayout(TRICKY)).toBe(TRICKY.slice(0, TRICKY.indexOf("layout:")));
  });
});

describe("the canvas follows the compiler's rules", () => {
  test("a stale position never becomes a second key", () => {
    const stale = "kilnmark: pipeline/v1\nname: s\nnodes:\n  - { id: a, type: read }\nlayout:\n  a: {x: 1, y: 1}\n  c: {x: 3, y: 3}\n";
    const out = ok(renameNode(stale, "a", "c"));
    expect(out.match(/^ {2}c: /gm)?.length).toBe(1);
    expect(graph(out).positions).toEqual({ c: { x: 1, y: 1 } });
    const fresh = ok(addNode(stale.replace("c: {x: 3", "read: {x: 3"), "read", CAT));
    expect(fresh).not.toMatch(/read: \{x: 3/);
  });

  test("a node with both input and inputs is refused, not rewritten", () => {
    const both = "kilnmark: pipeline/v1\nname: b\nnodes:\n  - { id: a, type: read }\n  - { id: e, type: write, input: a, inputs: { in: a } }\n";
    const c = disconnect(both, { from: "a", fromPort: "out", to: "e", toPort: "in" }, CAT);
    expect("error" in c && c.error).toMatch(/both input and inputs/);
    const r = removeNode(both, "a", CAT);
    expect("error" in r && r.error).toMatch(/both input and inputs/);
  });

  test("two ports of one node may both feed a union; the same edge twice may not", () => {
    let text = ok(addNode(BILLING, "union", CAT));
    text = ok(connect(text, { from: "joined", fromPort: "out", to: "union", toPort: "in" }, CAT));
    text = ok(connect(text, { from: "joined", fromPort: "rejected", to: "union", toPort: "in" }, CAT));
    expect(text).toContain("input: [joined, joined.rejected]");
    const again = connect(text, { from: "joined", fromPort: "rejected", to: "union", toPort: "in" }, CAT);
    expect("error" in again && again.error).toMatch(/already feeds/);
  });

  test("references split at the last dot; routes list their ports first", () => {
    expect(parseRef("a.b.c")).toEqual({ from: "a.b", fromPort: "c" });
    expect(parseRef("tidy")).toEqual({ from: "tidy", fromPort: "out" });
    const route = { type: "route", settings: { routes: { large: "a > 1", small: "a < 1" } }, inputs: [] };
    expect(portsOf(route, CAT).outputs).toEqual(["large", "small", "out"]);
  });
});
