import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { ReactFlowProvider } from "@xyflow/react";
import type { GateOutcome, PipelineNodeQuality } from "../../api";
import type { PredictedQuality } from "../prediction";
import { nodeLabel } from "./insight";
import { BADGE_ROW, GAP_Y, NODE_WIDTH, NO_BADGES, autoLayout, labelWidth, makeRoom, nodeHeight, type EdgeLabel } from "./layout";
import { readGraph } from "./model";
import { NodeCard, badgeRows, nodeChips, type NodeData } from "./NodeCard";

// Jp's lab demo after a run: customers → tidy → check (gate) → clean, and check.quarantine → aside.
const gate = (over: Partial<GateOutcome> = {}): GateOutcome => ({
  onFail: "quarantine",
  decision: "quarantined",
  asset: "urn:kilnmark:lab-db:crm.customers",
  rowsIn: 5000,
  rowsPassed: 4771,
  rowsFailing: 229,
  rowsQuarantined: 229,
  minScore: null,
  maxFailingShare: null,
  stopReason: null,
  rules: [],
  ...over,
});
const measured = (overall: number, g: GateOutcome | null = null): PipelineNodeQuality => ({
  node: "check",
  role: g ? "gate" : "output",
  asset: null,
  measured: { overall, dimensions: {}, reasons: [] },
  predicted: null,
  note: null,
  gate: g,
});
const predicted = (overall: number | null, unknown: string[] = []): PredictedQuality => ({
  node: "check",
  role: "gate",
  asset: null,
  overall,
  dimensions: {},
  reasons: [],
  unknown,
  assumptions: [],
  rows: 5000,
  gate: null,
});
const finished = { rowsIn: 5000, rowsOut: 5000, outcome: "finished", error: null };

describe("the chips under a node", () => {
  test("each kind in its own language: rows neutral, measured toned, predicted violet, gate text", () => {
    const [first, score, estimate, counts] = nodeChips({ problems: 0, run: finished, quality: measured(0.99, gate()), predicted: predicted(0.99) });
    expect(first).toEqual([{ kind: "rows", tone: "neutral", text: "5,000 rows out · Finished" }]);
    expect(score).toMatchObject([{ kind: "measured", tone: "good", text: "Quality 99% (Good)" }]);
    expect(estimate).toMatchObject([{ kind: "predicted", tone: "violet", text: "Predicted ≈ 99%", hidden: " (estimate)" }]);
    expect(estimate[0].title).toMatch(/\(estimate\)/);
    expect(counts).toEqual([{ kind: "gate", tone: "neutral", text: "4,771 passed · 229 quarantined" }]);
  });

  test("the score's tone follows its word; predicted stays violet whatever its score", () => {
    expect(nodeChips({ problems: 0, run: null, quality: measured(0.85), predicted: predicted(0.5) })[1][0]).toMatchObject({ tone: "warn", text: "Quality 85% (Fair)" });
    expect(nodeChips({ problems: 0, run: null, quality: measured(0.5), predicted: null })[1][0]).toMatchObject({ tone: "bad", text: "Quality 50% (Poor)" });
    expect(nodeChips({ problems: 0, run: null, quality: null, predicted: predicted(0.5) })[2][0].tone).toBe("violet");
    expect(nodeChips({ problems: 0, run: null, quality: null, predicted: predicted(null) })[2][0].text).toBe("Predicted: unknown");
    const unknown = nodeChips({ problems: 0, run: null, quality: null, predicted: predicted(0.8, ["x"]) })[2][0];
    expect(unknown).toMatchObject({ text: "Predicted ≈ 80%", more: " · 1 unknown" });
    expect(unknown.title).toMatch(/1 thing it could not tell/);
  });

  test("a run's outcome takes a status tone only when it is not a success", () => {
    const failed = nodeChips({ problems: 0, run: { rowsIn: 10, rowsOut: null, outcome: "failed", error: "boom" }, quality: null, predicted: null })[0];
    expect(failed).toEqual([
      { kind: "rows", tone: "neutral", text: "10 rows in" },
      { kind: "outcome", tone: "bad", text: "Failed", title: "boom" },
    ]);
    const early = nodeChips({ problems: 2, run: { rowsIn: null, rowsOut: 3, outcome: "ended_early", error: null }, quality: null, predicted: null })[0];
    expect(early.map((c) => [c.kind, c.tone, c.text])).toEqual([
      ["problems", "bad", "2 problems"],
      ["rows", "neutral", "3 rows out"],
      ["outcome", "warn", "Ended early"],
    ]);
  });

  test("a gate that stopped the run: its score and counts say so", () => {
    const [, score, , counts] = nodeChips({ problems: 0, run: null, quality: measured(0.97, gate({ decision: "stopped" })), predicted: null });
    expect(score[0].tone).toBe("bad");
    expect(counts[0]).toMatchObject({ tone: "bad", text: "Stopped the run after 5,000 rows" });
  });

  test("rendered: each chip's kind, words and look, the estimate said for screen readers", () => {
    const yaml = "kilnmark: pipeline/v1\nname: x\nnodes:\n  - id: check\n    type: gate\n    on_fail: quarantine\n";
    const r = readGraph(yaml);
    if (!r.ok) throw new Error("unreadable");
    const data: NodeData = {
      node: r.graph.nodes[0],
      kind: "gate",
      label: "Quality gate",
      summary: "quarantine",
      inputs: ["in"],
      outputs: ["out", "quarantine"],
      problems: 0,
      columns: "",
      run: finished,
      quality: measured(0.99, gate()),
      predicted: predicted(0.99),
      connectable: false,
    };
    const html = renderToStaticMarkup(
      <ReactFlowProvider>
        <NodeCard id="check" data={data} type="node" selected={false} dragging={false} zIndex={0} isConnectable={false} positionAbsoluteX={0} positionAbsoluteY={0} selectable deletable draggable />
      </ReactFlowProvider>,
    );
    expect(html).toMatch(/class="km-chip km-chip--neutral cv-node-chip" data-kind="rows">.*5,000 rows out · Finished/);
    expect(html).toMatch(/class="km-chip km-chip--good cv-node-chip cv-quality" data-kind="measured">.*Quality 99% \(Good\)/);
    expect(html).toMatch(
      /class="km-chip km-chip--violet km-chip--outline cv-node-chip cv-predicted-badge"[^>]*data-kind="predicted">.*Predicted ≈ 99%<span class="visually-hidden"> \(estimate\)<\/span>/,
    );
    expect(html).toMatch(/class="cv-node-gate" data-kind="gate">.*4,771 passed · 229 quarantined/);
    // Only the estimate is outlined; each kind is on a row of its own.
    expect(html.match(/km-chip--outline/g)).toHaveLength(1);
    expect(html.match(/class="cv-node-chips"/g)).toHaveLength(4);
  });
});

describe("a node's height", () => {
  const gatePorts = { inputs: ["in"], outputs: ["out", "quarantine"] };

  test("grows by a fixed row per kind of chip shown", () => {
    const bare = nodeHeight(gatePorts);
    const all = badgeRows({ problems: 0, run: finished, quality: measured(0.99, gate()), predicted: predicted(0.99) });
    expect(all).toEqual({ first: true, measured: true, predicted: true, gate: true });
    const after = nodeHeight(gatePorts, all);
    expect(after).toBeGreaterThan(bare + 4 * BADGE_ROW - 1);
    expect(nodeHeight(gatePorts, { ...NO_BADGES, predicted: true })).toBe(nodeHeight(gatePorts, { ...NO_BADGES, first: true }));
    expect(nodeHeight(gatePorts, NO_BADGES)).toBe(bare);
  });

  test("a write before a run with only an estimate: one row", () => {
    const rows = badgeRows({ problems: 0, run: null, quality: null, predicted: predicted(0.9) });
    expect(rows).toEqual({ first: false, measured: false, predicted: true, gate: false });
  });
});

describe("making room for badges and labels", () => {
  // Jp's saved layout, tight before a run.
  const saved = { customers: { x: 40, y: 120 }, tidy: { x: 330, y: 120 }, check: { x: 620, y: 110 }, aside: { x: 910, y: 0 }, clean: { x: 910, y: 160 } };
  const heights = { customers: 170, tidy: 170, check: 230, aside: 200, clean: 200 };
  const labels: EdgeLabel[] = [
    { from: "customers", to: "tidy", text: "5,000 rows" },
    { from: "tidy", to: "check", text: "5,000 rows" },
    { from: "check", to: "clean", text: "4,771 rows" },
    { from: "check", to: "aside", text: "quarantine · 229 rows" },
  ];
  const overlap = (a: { x: number; y: number }, ha: number, b: { x: number; y: number }, hb: number) =>
    a.x < b.x + NODE_WIDTH && b.x < a.x + NODE_WIDTH && a.y < b.y + hb && b.y < a.y + ha;

  test("no two nodes overlap, and the nodes in a column keep their order", () => {
    const shown = makeRoom(saved, heights, labels);
    const ids = Object.keys(shown);
    for (const a of ids) for (const b of ids) if (a < b) expect(overlap(shown[a], heights[a as keyof typeof heights], shown[b], heights[b as keyof typeof heights]), `${a} and ${b}`).toBe(false);
    expect(shown.clean.y).toBeGreaterThanOrEqual(shown.aside.y + heights.aside + GAP_Y);
    expect(saved.clean).toEqual({ x: 910, y: 160 }); // the saved layout is untouched
  });

  test("each labelled connection's gap fits its label", () => {
    const shown = makeRoom(saved, heights, labels);
    for (const l of labels) expect(shown[l.to].x - (shown[l.from].x + NODE_WIDTH), l.text).toBeGreaterThanOrEqual(labelWidth(l.text));
  });

  test("a roomy layout is left as it is", () => {
    const roomy = { a: { x: 0, y: 0 }, b: { x: 400, y: 0 } };
    expect(makeRoom(roomy, { a: 150, b: 150 }, [{ from: "a", to: "b", text: "10 rows" }])).toEqual(roomy);
  });

  test("auto-layout uses the real heights and the labels' widths", () => {
    const yaml = `kilnmark: pipeline/v1
name: demo
nodes:
  - id: check
    type: gate
  - id: clean
    type: write
    input: check
  - id: aside
    type: write
    input: check
`;
    const r = readGraph(yaml);
    if (!r.ok) throw new Error("unreadable");
    const hs = { check: 260, clean: 240, aside: 240 };
    const at = autoLayout(r.graph, new Map(), { heights: hs, labels: [{ from: "check", to: "aside", text: "quarantine · 229 rows" }] });
    expect(overlap(at.clean, hs.clean, at.aside, hs.aside)).toBe(false);
    expect(Math.abs(at.clean.y - at.aside.y)).toBeGreaterThanOrEqual(240 + GAP_Y - 1);
    expect(at.aside.x - (at.check.x + NODE_WIDTH)).toBeGreaterThanOrEqual(labelWidth("quarantine · 229 rows"));
  });
});

describe("a write's header", () => {
  const w = (settings: Record<string, string>) => ({ type: "write", settings });
  test("says file or table from its own settings", () => {
    expect(nodeLabel(w({ path: "out/x.csv" }), "Write to a table or file")).toBe("Write to a file");
    expect(nodeLabel(w({ table: "crm.x" }), "Write to a table or file")).toBe("Write to a table");
    expect(nodeLabel(w({ target: "demo/customers-clean.csv" }), "Write to a table or file")).toBe("Write to a file");
    expect(nodeLabel(w({ target: "crm.customers_clean" }), "Write to a table or file")).toBe("Write to a table");
    expect(nodeLabel(w({}), "Write to a table or file")).toBe("Write to a table or file");
    expect(nodeLabel({ type: "gate", settings: {} }, "Quality gate")).toBe("Quality gate");
  });
});
