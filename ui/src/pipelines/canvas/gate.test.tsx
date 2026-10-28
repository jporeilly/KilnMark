import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { ReactFlowProvider } from "@xyflow/react";
import type { GateOutcome, PipelineStepQuality, StepType, StepTypeSetting } from "../../api";
import { belowThreshold, gateWords, percent, scoreWords, stepQualityWords } from "../quality";
import { stepSummary } from "./insight";
import { catalogueOf, portsOf, readGraph, setSetting, type Change, type GraphStep } from "./model";
import { SettingControl, type ControlContext } from "./SettingControls";
import { StepNode, type StepData } from "./StepNode";

const ok = (c: Change) => {
  if ("error" in c) throw new Error(c.error);
  return c.text;
};

const GATE: StepType = {
  type: "gate",
  kind: "gate",
  label: "Quality gate",
  help: "Checks rows against quality rules.",
  inputs: ["in"],
  manyInputs: [],
  outputs: ["out", "quarantine"],
  settings: [],
  category: "Quality",
};
const CAT = catalogueOf([
  { type: "read", kind: "source", label: "read", help: "", inputs: [], manyInputs: [], outputs: ["out"], settings: [] },
  GATE,
  { type: "write", kind: "sink", label: "write", help: "", inputs: ["in"], manyInputs: [], outputs: [], settings: [] },
]);

const PIPELINE = `kilnmark: pipeline/v1
name: people-gate
quality:
  min_score: 0.95   # tell followers below 95%
steps:
  - id: people
    type: read
    source: lab-db
    asset: crm.people

  - id: check
    type: gate
    input: people
    on_fail: quarantine   # set failing rows aside

  - id: good
    type: write
    input: check
    source: warehouse
    table: crm.people_ok

  - id: aside
    type: write
    input: check.quarantine
    source: warehouse
    table: crm.people_aside
`;

const graphOf = (text: string) => {
  const r = readGraph(text, CAT);
  if (!r.ok) throw new Error(r.error);
  return r.graph;
};

const outcome = (over: Partial<GateOutcome> = {}): GateOutcome => ({
  onFail: "quarantine",
  decision: "quarantined",
  asset: "urn:kilnmark:lab-db:crm.people",
  rowsIn: 1000,
  rowsPassed: 900,
  rowsFailing: 100,
  rowsQuarantined: 100,
  minScore: null,
  maxFailingShare: null,
  stopReason: null,
  rules: [
    { name: "email not null", type: "not_null", field: "email", dimension: "completeness", origin: "inline", status: "fail", failing: 100, total: 1000, message: null },
  ],
  ...over,
});

describe("the gate on the canvas", () => {
  test("its ports: out and quarantine, and the quarantine edge reads from that port", () => {
    const g = graphOf(PIPELINE);
    const check = g.steps.find((s) => s.id === "check")!;
    expect(portsOf(check, CAT)).toMatchObject({ inputs: ["in"], outputs: ["out", "quarantine"] });
    expect(g.edges).toContainEqual({ from: "check", fromPort: "quarantine", to: "aside", toPort: "in" });
  });

  test("its one-line summary says what happens to failing rows and what it checks", () => {
    const g = graphOf(PIPELINE);
    expect(stepSummary(g.steps.find((s) => s.id === "check")!)).toBe("quarantine · asset's rules");
    const withRules = graphOf(
      ok(setSetting(PIPELINE, "check", "rules", [{ type: "not_null", field: "email" }, { type: "unique", field: "id" }], "rules")),
    );
    expect(stepSummary(withRules.steps.find((s) => s.id === "check")!)).toBe("quarantine · asset's rules + 2 rules");
    const own = graphOf(ok(setSetting(PIPELINE, "check", "asset_rules", false, "boolean")));
    expect(stepSummary(own.steps.find((s) => s.id === "check")!)).toBe("quarantine");
  });

  test("rules are written one a line, touching nothing else in the YAML", () => {
    const text = ok(
      setSetting(PIPELINE, "check", "rules", [{ type: "not_null", field: "email" }, { name: "known country", type: "allowed_values", field: "country", values: ["GB", "IE"] }], "rules"),
    );
    expect(text).toContain(
      "    rules:\n      - {type: not_null, field: email}\n      - {name: known country, type: allowed_values, field: country, values: [GB, IE]}\n",
    );
    expect(text).toContain("on_fail: quarantine   # set failing rows aside");
    expect(text).toContain("  min_score: 0.95   # tell followers below 95%");
    // Changed again in place, then removed.
    const again = ok(setSetting(text, "check", "rules", [{ type: "not_null", field: "phone" }], "rules"));
    expect(again).toContain("    rules:\n      - {type: not_null, field: phone}\n");
    expect(again).not.toContain("known country");
    expect(graphOf(again).steps.find((s) => s.id === "check")!.settings.rules).toEqual([{ type: "not_null", field: "phone" }]);
    const gone = ok(setSetting(again, "check", "rules", undefined, "rules"));
    expect(gone).toBe(PIPELINE);
  });
});

describe("the rules control", () => {
  const spec: StepTypeSetting = { key: "rules", label: "Rules", kind: "rules", required: false, defaultValue: null, help: "Rules for this pipeline only", choices: [] };
  const context: ControlContext = {
    columns: { main: [{ name: "email", type: "text" }, { name: "id", type: "int64" }], lookup: null },
    side: null,
    sources: null,
    writing: false,
    assets: null,
    stepType: "gate",
  };
  const html = (value: unknown, readOnly = false) =>
    renderToStaticMarkup(<SettingControl spec={spec} id="r" value={value as never} onChange={() => {}} readOnly={readOnly} problems={[]} context={context} />);

  test("each rule has a labelled check, column and its own settings", () => {
    const out = html([{ type: "in_range", field: "id", min: 0, max: 2000, name: "early ids" }]);
    expect(out).toContain("<legend");
    expect(out).toContain('<label class="cv-sub" for="r-0-type">Rule 1: check</label>');
    expect(out).toMatch(/<option value="in_range" selected="">Between a minimum and maximum<\/option>/);
    expect(out).toContain('for="r-0-field"');
    expect(out).toMatch(/<option value="id" selected="">id \(int64\)<\/option>/);
    expect(out).toContain("Rule 1: Minimum");
    expect(out).toMatch(/id="r-0-max"[^>]*value="2000"/);
    expect(out).toMatch(/id="r-0-name"[^>]*value="early ids"/);
    expect(out).toContain('aria-label="Remove rule 1 (early ids)"');
    expect(out).toContain("Add a rule");
  });

  test("a check of the whole data has no column; nothing written says so", () => {
    expect(html([{ type: "row_count", min: 1 }])).not.toContain('for="r-0-field"');
    expect(html(undefined)).toContain("No rules written here.");
    expect(html([{ type: "not_null", field: "email" }], true)).not.toContain("Add a rule");
  });
});

describe("measured quality in words", () => {
  test("scores are a percentage and a word, never 100% unless they are", () => {
    expect(percent(0.9833)).toBe("98.3%");
    expect(percent(0.29)).toBe("29%");
    expect(percent(0.9999)).toBe("99.9%");
    expect(percent(1)).toBe("100%");
    expect(percent(0.0042)).toBe("0.42%");
    expect(scoreWords(0.97)).toBe("Quality 97% (Good)");
    expect(scoreWords(0.85)).toBe("Quality 85% (Fair)");
    expect(scoreWords(0.5)).toBe("Quality 50% (Poor)");
  });

  test("what a gate did with the rows", () => {
    expect(gateWords(outcome())).toBe("900 passed · 100 quarantined");
    expect(gateWords(outcome({ onFail: "warn", decision: "warned", rowsQuarantined: 0 }))).toBe("900 passed · 100 failed, let through");
    expect(gateWords(outcome({ decision: "passed", rowsPassed: 1000, rowsFailing: 0, rowsQuarantined: 0 }))).toBe("1,000 passed");
    expect(gateWords(outcome({ decision: "stopped", rowsIn: 2500 }))).toBe("Stopped the run after 2,500 rows");
  });

  test("a step's badge: its score and, for a gate, its rows", () => {
    const gate: PipelineStepQuality = { step: "check", role: "gate", asset: null, measured: { overall: 0.9, dimensions: { completeness: 0.9 }, reasons: [] }, predicted: null, note: null, gate: outcome() };
    expect(stepQualityWords(gate)).toBe("Quality 90% (Fair) · 900 passed · 100 quarantined");
    expect(stepQualityWords({ ...gate, role: "output", gate: null, measured: { overall: 1, dimensions: {}, reasons: [] } })).toBe("Quality 100% (Good)");
    expect(stepQualityWords({ ...gate, role: "output", gate: null, measured: null, note: "appended" })).toBe("Quality not measured");
    expect(stepQualityWords(null)).toBeNull();
    expect(belowThreshold(0.94, 0.95)).toBe(true);
    expect(belowThreshold(0.95, 0.95)).toBe(false);
    expect(belowThreshold(null, 0.95)).toBe(false);
  });

  test("the node shows the badge beside its rows, as text", () => {
    const step: GraphStep = graphOf(PIPELINE).steps.find((s) => s.id === "check")!;
    const data: StepData = {
      step,
      kind: "gate",
      label: "Quality gate",
      summary: "quarantine",
      inputs: ["in"],
      outputs: ["out", "quarantine"],
      problems: 0,
      columns: "id, email",
      run: { rowsIn: 1000, rowsOut: 1000, outcome: "finished", error: null },
      quality: { step: "check", role: "gate", asset: null, measured: { overall: 0.9, dimensions: {}, reasons: [] }, predicted: null, note: null, gate: outcome() },
      connectable: false,
    };
    const out = renderToStaticMarkup(
      <ReactFlowProvider>
        <StepNode
          id="check"
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
    expect(out).toContain("1,000 rows out · Finished");
    expect(out).toContain('class="cv-badge cv-quality cv-quality-fair"');
    expect(out).toContain("Quality 90% (Fair) · 900 passed · 100 quarantined");
    expect(out).toMatch(/cv-port-out">quarantine/);
  });
});
