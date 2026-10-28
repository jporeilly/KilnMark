// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot } from "react-dom/client";
import { renderToStaticMarkup } from "react-dom/server";
import { ReactFlowProvider } from "@xyflow/react";
import type { StepType, StepTypeSetting } from "../../api";
import {
  changedDimensions,
  dimensionRows,
  effectWords,
  gapWords,
  pointsWords,
  predictedWords,
  rowsWords,
  type Advice,
  type PredictedQuality,
  type Suggestion,
} from "../prediction";
import { catalogueOf, insertSteps, readGraph, type Change, type GraphStep } from "./model";
import { PredictedSection } from "./PredictedSection";
import { StepNode, type StepData } from "./StepNode";
import SuggestionsPanel from "./SuggestionsPanel";

const ok = (c: Change) => {
  if ("error" in c) throw new Error(c.error);
  return c.text;
};

const S = (key: string, kind: string): StepTypeSetting => ({ key, label: key, kind, required: false, defaultValue: null, help: "", choices: [] });
const T = (type: string, kind: string, inputs: string[], outputs: string[], settings: StepTypeSetting[] = []): StepType => ({
  type,
  kind,
  label: type,
  help: "",
  inputs,
  manyInputs: [],
  outputs,
  settings,
});
const CAT = catalogueOf([
  T("read", "source", [], ["out"], [S("source", "source"), S("asset", "asset")]),
  T("compute", "transform", ["in"], ["out"], [S("columns", "expressions")]),
  T("lookup", "transform", ["main", "lookup"], ["out", "rejected"], [S("on", "column_map"), S("keep", "columns"), S("unmatched", "choice")]),
  T("gate", "gate", ["in"], ["out", "quarantine"], [S("on_fail", "choice"), S("asset_rules", "boolean"), S("rules", "rules")]),
  T("write", "sink", ["in"], [], [S("source", "source"), S("table", "text")]),
]);

const PIPELINE = `kilnmark: pipeline/v1
name: people-out
steps:
  - id: people
    type: read
    source: lab-db       # synthetic people
    asset: crm.people

  - id: out
    type: write
    input: people
    source: warehouse
    table: crm.people_out

# Where the canvas draws each step. Kilnmark ignores this when it runs the pipeline.
layout:
  people: {x: 40, y: 120}
  out: {x: 360, y: 120}
`;

const PREDICTED: PredictedQuality = {
  step: "out",
  role: "output",
  asset: "urn:kilnmark:warehouse:crm.people_out",
  overall: 0.912,
  dimensions: { completeness: 0.98, consistency: 0.85 },
  reasons: [],
  unknown: ["label as tidy computes it"],
  assumptions: ["the first 10 rows are like the rest"],
  rows: 1000,
  gate: null,
};

const TRIM: Suggestion = {
  id: "trim-out",
  kind: "trim",
  title: "Trim spaces from name before out",
  why: "Some values start or end with spaces.",
  source: "rules",
  steps: [{ id: "trim", type: "compute", settings: { columns: { name: "trim(name)" } }, inputs: { in: "people" } }],
  placement: { before: "out", port: "in", from: "people", through: "trim" },
  effect: {
    output: "out",
    before: 0.89,
    after: 0.94,
    dimensionsBefore: { completeness: 0.98, consistency: 0.8 },
    dimensionsAfter: { completeness: 0.98, consistency: 0.95 },
    rowsBefore: 1000,
    rowsAfter: 1000,
    note: null,
  },
};

describe("words for predictions", () => {
  test("every prediction says it is an estimate", () => {
    expect(predictedWords(PREDICTED)).toBe("Predicted 91% (estimate)");
    expect(predictedWords({ overall: null })).toBe("Predicted: unknown (estimate)");
    expect(predictedWords(null)).toBeNull();
    expect(rowsWords(1234)).toBe("about 1,234 rows (estimate)");
    expect(rowsWords(null)).toBe("rows unknown");
  });

  test("effects in points, with the dimensions that change", () => {
    expect(pointsWords(0.05)).toBe("+5 points");
    expect(pointsWords(-0.01)).toBe("−1 point");
    expect(pointsWords(0.001)).toBe("no change");
    expect(effectWords(TRIM.effect)).toBe("Predicted 89% → 94% (+5 points, estimate)");
    expect(effectWords(null)).toBe("Effect unknown (estimate)");
    expect(changedDimensions(TRIM.effect)).toEqual(["Consistency +15 points"]);
  });

  test("measured beside predicted, and what the gap says", () => {
    const measured = { overall: 0.8, dimensions: { completeness: 0.7, validity: 0.9 }, reasons: [] };
    const predicted = { overall: 0.9, dimensions: { completeness: 0.95, consistency: 0.9 }, reasons: [] };
    expect(dimensionRows(measured, predicted)).toEqual([
      { dimension: "completeness", measured: 0.7, predicted: 0.95, gap: 0.7 - 0.95 },
      { dimension: "validity", measured: 0.9, predicted: null, gap: null },
      { dimension: "consistency", measured: null, predicted: 0.9, gap: null },
    ]);
    expect(gapWords(measured, predicted)).toBe(
      "Measured 25 points below the estimate; the widest gap is in completeness (−25 points). Check the metadata of what this step reads, or the steps before it.",
    );
    expect(gapWords({ ...measured, dimensions: { completeness: 0.9 } }, { ...predicted, dimensions: { completeness: 0.91 } })).toBe(
      "Measured as predicted: the catalogue's metadata matched the rows.",
    );
    // Only the dimensions both have count: a dimension only measured makes no gap.
    expect(gapWords({ overall: 0.5, dimensions: { completeness: 0.9, validity: 0.1 }, reasons: [] }, { overall: 0.9, dimensions: { completeness: 0.9 }, reasons: [] })).toBe(
      "Measured as predicted: the catalogue's metadata matched the rows.",
    );
    expect(gapWords({ ...measured, dimensions: { validity: 0.9 } }, { ...predicted, dimensions: { completeness: 0.9 } })).toBeNull();
    expect(gapWords(null, predicted)).toBeNull();
  });
});

describe("adding a suggestion through the YAML", () => {
  test("one change: the step after where rows come from, the write rewired, every other byte kept", () => {
    const text = ok(insertSteps(PIPELINE, CAT, TRIM.steps, TRIM.placement));
    expect(text).toContain(`  - id: trim
    type: compute
    input: people
    columns: {name: "trim(name)"}
`);
    expect(text).toContain("    input: trim\n    source: warehouse");
    expect(text).toContain("source: lab-db       # synthetic people");
    expect(text.indexOf("id: trim")).toBeGreaterThan(text.indexOf("id: people"));
    expect(text.indexOf("id: trim")).toBeLessThan(text.indexOf("id: out"));
    const g = readGraph(text, CAT);
    expect(g.ok && g.graph.edges).toEqual([
      { from: "people", fromPort: "out", to: "trim", toPort: "in" },
      { from: "trim", fromPort: "out", to: "out", toPort: "in" },
    ]);
    // Removing the change gives the text back exactly.
    expect(text.replace(/\n {2}- id: trim\n[\s\S]*?\n\n/, "\n").replace("input: trim", "input: people")).toBe(PIPELINE);
  });

  test("a lookup with its own read, and a gate with its rules, are written as the guides write them", () => {
    const lookup = ok(
      insertSteps(
        PIPELINE,
        CAT,
        [
          { id: "read-countries", type: "read", settings: { source: "lab-db", asset: "ref.countries" }, inputs: {} },
          { id: "match-countries", type: "lookup", settings: { on: { country: "code" }, keep: [], unmatched: "drop" }, inputs: { main: "people", lookup: "read-countries" } },
        ],
        { before: "out", port: "in", from: "people", through: "match-countries" },
        { "read-countries": { x: 200, y: 300 } },
      ),
    );
    expect(lookup).toContain("inputs: {main: people, lookup: read-countries}\n    on: {country: code}\n    keep: []\n    unmatched: drop");
    expect(lookup).toContain("read-countries: {x: 200, y: 300}");
    const g = readGraph(lookup, CAT);
    expect(g.ok && g.graph.edges.filter((e) => e.to === "out")).toEqual([{ from: "match-countries", fromPort: "out", to: "out", toPort: "in" }]);
    const gate = ok(
      insertSteps(
        PIPELINE,
        CAT,
        [{ id: "check-email", type: "gate", settings: { on_fail: "quarantine", asset_rules: false, rules: [{ type: "not_null", field: "email" }] }, inputs: { in: "people" } }],
        { before: "out", port: "in", from: "people", through: "check-email" },
      ),
    );
    expect(gate).toContain("    on_fail: quarantine\n    asset_rules: false\n    rules: [{type: not_null, field: email}]\n");
  });

  test("keys and values with colons, hashes and line breaks read back exactly", () => {
    const columns = { "a: b": "x || ' # not a comment'", "c#d": "concat(first, ': ', last)", e: "line one\nline two" };
    const text = ok(
      insertSteps(PIPELINE, CAT, [{ id: "odd", type: "compute", settings: { columns }, inputs: { in: "people" } }], {
        before: "out",
        port: "in",
        from: "people",
        through: "odd",
      }),
    );
    const g = readGraph(text, CAT);
    expect(g.ok).toBe(true);
    const step = g.ok ? g.graph.steps.find((s) => s.id === "odd") : undefined;
    expect(step?.settings.columns).toEqual(columns);
    expect(g.ok && g.graph.steps.find((s) => s.id === "out")?.inputs).toEqual([{ port: null, from: "odd", fromPort: "out" }]);
  });

  test("refused when the pipeline has changed since the suggestion was made", () => {
    const taken = PIPELINE.replace("  - id: out", "  - id: trim\n    type: compute\n    input: people\n\n  - id: out");
    expect(insertSteps(taken, CAT, TRIM.steps, TRIM.placement)).toEqual({
      error: "The pipeline already has a step called trim. Ask for the suggestions again.",
    });
    const rewired = PIPELINE.replace("    input: people\n    source: warehouse", "    input: elsewhere\n    source: warehouse");
    expect("error" in insertSteps(rewired, CAT, TRIM.steps, TRIM.placement)).toBe(true);
  });
});

describe("predictions on the canvas", () => {
  const step: GraphStep = { id: "out", type: "write", settings: {}, texts: {}, inputs: [], line: 1, keyLines: {} };
  const data: StepData = {
    step,
    kind: "sink",
    label: "write",
    summary: "",
    inputs: ["in"],
    outputs: [],
    problems: 0,
    columns: "",
    run: null,
    quality: null,
    predicted: PREDICTED,
    connectable: true,
  };

  test("a write shows its predicted score, labelled as an estimate", () => {
    const html = renderToStaticMarkup(
      <ReactFlowProvider>
        <StepNode
          id="out"
          data={data}
          type="step"
          selected={false}
          dragging={false}
          zIndex={0}
          isConnectable={false}
          positionAbsoluteX={0}
          positionAbsoluteY={0}
          selectable
          deletable
          draggable
        />
      </ReactFlowProvider>,
    );
    expect(html).toContain('Predicted ≈ 91%<span class="visually-hidden"> (estimate)</span>');
    expect(html).toContain("km-chip km-chip--violet km-chip--outline cv-node-chip cv-predicted-badge");
  });

  test("the settings drawer says what the estimate could not tell", () => {
    const html = renderToStaticMarkup(<PredictedSection p={PREDICTED} id="p" />);
    expect(html).toContain("Predicted quality (estimate)");
    expect(html).toContain("no data was read");
    expect(html).toContain("Consistency: 85%");
    expect(html).toContain("label as tidy computes it");
    expect(html).toContain("the first 10 rows are like the rest");
  });
});

describe("the suggestions panel", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = "";
  });

  test("lists suggestions with their effect and adds one", async () => {
    const advice: Advice = { estimate: true, suggestions: [TRIM], ai: { state: "off", message: "No AI model is set.", model: null } };
    const fetch = vi.fn(async () => new Response(JSON.stringify(advice), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    const added: Suggestion[] = [];
    const host = document.createElement("div");
    document.body.append(host);
    const root = createRoot(host);
    await act(async () => {
      root.render(<SuggestionsPanel yaml={PIPELINE} readOnly={false} onAdd={(s) => (added.push(s), null)} onClose={() => {}} />);
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(fetch).toHaveBeenCalledTimes(1);
    const body = JSON.parse((fetch.mock.calls[0] as unknown as [string, RequestInit])[1].body as string);
    expect(body).toEqual({ yaml: PIPELINE, ai: true });
    expect(host.textContent).toContain("Predicted 89% → 94% (+5 points, estimate): Consistency +15 points");
    expect(host.textContent).toContain("Kilnmark's own");
    const add = host.querySelector<HTMLButtonElement>('button[aria-label="Add: Trim spaces from name before out"]')!;
    await act(async () => add.click());
    expect(added).toEqual([TRIM]);
    expect(host.textContent).toContain("Added: Trim spaces from name before out. Undo takes it out again.");
    await act(async () => root.unmount());
  });

  test("viewers see why they get none, and no Add buttons", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: "Not a steward" }), { status: 403, headers: { "Content-Type": "application/json" } })));
    const host = document.createElement("div");
    document.body.append(host);
    const root = createRoot(host);
    await act(async () => {
      root.render(<SuggestionsPanel yaml={PIPELINE} readOnly onAdd={() => null} onClose={() => {}} />);
    });
    await act(async () => {
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(host.querySelector('[role="alert"]')?.textContent).toMatch(/steward|Not a steward/);
    expect(host.querySelector("button[aria-label^='Add']")).toBeNull();
    await act(async () => root.unmount());
  });
});
