import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { NodeType, NodeTypeSetting, Source } from "../../api";
import { catalogueOf, type GraphNode } from "./model";
import { SettingControl, type ControlContext } from "./SettingControls";
import { SettingsPanel } from "./SettingsPanel";
import { DomainList } from "../../pages/EmailSettingsPage";

// The style sheets as text, read from disk: a test is given an empty sheet for a CSS import, and
// the UI has no Node types, so fs is reached through the running Node itself.
type Fs = { readFileSync(path: URL, encoding: "utf8"): string };
const fs = (globalThis as unknown as { process: { getBuiltinModule(id: "node:fs"): Fs } }).process.getBuiltinModule("node:fs");
const dockCss = fs.readFileSync(new URL("../pipelines.css", import.meta.url), "utf8");
const canvasCss = fs.readFileSync(new URL("./canvas.css", import.meta.url), "utf8");

// The layout of fields, as markup: which settings take the panel's whole row, and the order of a
// field's parts (name, box, what is chosen, help), so the boxes of a row line up.

const spec = (key: string, kind: string, extra: Partial<NodeTypeSetting> = {}): NodeTypeSetting => ({
  key,
  label: key.replace(/_/g, " "),
  kind,
  required: false,
  defaultValue: null,
  help: `Help for ${key}`,
  choices: [],
  ...extra,
});
const COLS = [
  { name: "customer_id", type: "int32" },
  { name: "status", type: "utf8" },
];
const ctx = (over: Partial<ControlContext> = {}): ControlContext => ({
  columns: { main: COLS, lookup: [{ name: "id", type: "int64" }] },
  side: null,
  sources: [] as Source[],
  writing: false,
  assets: [],
  nodeType: "filter",
  ...over,
});
const html = (s: NodeTypeSetting, value: unknown, over: Partial<ControlContext> = {}) =>
  renderToStaticMarkup(
    <SettingControl spec={s} id="t" value={value as never} onChange={() => {}} readOnly={false} problems={[]} context={ctx(over)} />,
  );
const wide = (out: string) => /class="cv-field cv-field-wide"/.test(out);

describe("a setting's width follows its kind of control, never what it holds", () => {
  test("a list of columns keeps one column's width, empty or not", () => {
    // It used to take the whole row once a column was chosen, moving the settings after it.
    expect(wide(html(spec("drop", "columns"), []))).toBe(false);
    expect(wide(html(spec("keep", "columns"), ["customer_id", "status"]))).toBe(false);
  });

  test("a read node's columns take the whole row whether or not the table's columns are known yet", () => {
    expect(wide(html(spec("columns", "columns"), [], { nodeType: "read", columns: { main: null, lookup: null } }))).toBe(true);
    expect(wide(html(spec("columns", "columns"), [], { nodeType: "read" }))).toBe(true);
  });

  test("a chosen column never makes its chip wider than the setting", () => {
    expect(canvasCss).toMatch(/\.cv-chip-row \{[^}]*max-width: 100%;\s*min-width: 0;/);
    expect(canvasCss).toMatch(/\.cv-chip-row > \.mono \{\s*min-width: 0;\s*overflow-wrap: anywhere;/);
  });

  test("the dock spans a setting by its class, not by what is inside it", () => {
    const css = dockCss;
    const rule = css.match(/([^{}]*)\{\s*grid-column: 1 \/ -1;\s*\}/)?.[1] ?? "";
    expect(rule).toContain(".cv-set-main > .cv-field-wide");
    expect(rule).not.toContain(":has(");
  });

  test("SQL, an expression, pairs, rules and a read's checklist take the whole row, even empty", () => {
    expect(wide(html(spec("condition", "expression"), ""))).toBe(true);
    expect(wide(html(spec("columns", "expressions"), undefined))).toBe(true);
    expect(wide(html(spec("columns", "expressions"), { total: "sum(amount)" }))).toBe(true);
    expect(wide(html(spec("on", "column_map"), undefined, { nodeType: "lookup" }))).toBe(true);
    expect(wide(html(spec("rules", "rules"), undefined, { nodeType: "gate" }))).toBe(true);
    expect(wide(html(spec("query", "text"), "", { nodeType: "read" }))).toBe(true);
    expect(wide(html(spec("columns", "columns"), [], { nodeType: "read" }))).toBe(true);
  });

  test("a single box is one column", () => {
    expect(wide(html(spec("label", "text"), "x"))).toBe(false);
    expect(wide(html(spec("limit", "number"), 5))).toBe(false);
  });
});

describe("the parts of a field, in order", () => {
  test("a read's columns: the boxes, then what is read, in words", () => {
    const out = html(spec("columns", "columns"), [], { nodeType: "read" });
    expect(out.indexOf("None ticked: every column is read.")).toBeGreaterThan(out.indexOf('class="cv-checklist"'));
  });

  test("the default follows the help after a space it can wrap at", () => {
    const out = html(spec("memory_limit_mb", "number", { defaultValue: 256 }), undefined);
    expect(out).toContain('Help for memory_limit_mb. <span class="cv-default">Default: 256.</span>');
  });

  test("a node's id: the box under its name, the help under the box", () => {
    const type: NodeType = { type: "filter", kind: "transform", label: "Filter rows", help: "", inputs: ["in"], outputs: ["out"], settings: [] } as unknown as NodeType;
    const node: GraphNode = { id: "keep", type: "filter", settings: {}, texts: {}, inputs: [], line: 1, keyLines: {} };
    const out = renderToStaticMarkup(
      <SettingsPanel
        node={node}
        type={type}
        catalogue={catalogueOf([type])}
        check={null}
        problems={[]}
        sources={[]}
        readOnly={false}
        run={null}
        onSetting={() => {}}
        onRename={() => null}
        onDisconnect={() => {}}
        onConnectMenu={() => {}}
        onDelete={() => {}}
        onDuplicate={() => {}}
      />,
    );
    const label = out.indexOf(">Node id</label>");
    const box = out.search(/<input id="cv-set-keep-id"/);
    const help = out.indexOf("Small letters, digits");
    expect(label).toBeGreaterThan(-1);
    expect(box).toBeGreaterThan(label);
    expect(help).toBeGreaterThan(box);
  });

  test("the allowed email domains: the box to add one, what is listed, then the help", () => {
    const out = renderToStaticMarkup(<DomainList value={[]} onChange={() => {}} />);
    const box = out.indexOf('id="domain-new"');
    const listed = out.indexOf("No domains listed");
    const help = out.indexOf('id="domains-hint"');
    expect(box).toBeGreaterThan(out.indexOf("Allowed email domains"));
    expect(listed).toBeGreaterThan(box);
    expect(help).toBeGreaterThan(listed);
  });
});

describe("an on/off setting lines up with the settings beside it", () => {
  // The switch row's list padding put its name 11 px below the names of the row (measured at
  // 1900, 1280 and 1024 px), and its name was 14 px where the others' are 13 px.
  test("in a field, the switch row has no padding or rule, and its name is a label's size", () => {
    const css = canvasCss;
    expect(css).toMatch(/\.cv-field > \.km-switch-row \{\s*padding: 0;\s*border-bottom: 0;\s*\}/);
    expect(css).toMatch(/\.cv-field > \.km-switch-row \.km-switch-label \{\s*font-size: var\(--km-text-sm\);/);
    const out = html(spec("strict", "boolean", { defaultValue: false }), undefined);
    expect(out).toMatch(/^<div class="cv-field" data-setting="strict"><div class="km-switch-row">/);
  });
});

describe("the panel under the canvas fits a node's settings and leaves the canvas usable", () => {
  // The owner, on 0.1.2: a read's Where, Row limit and the note under them needed scrolling to
  // reach. Worked out at a laptop's 900 px from the rules themselves, so a change to either is seen.
  const px = (css: string, re: RegExp) => (re.exec(css) ?? []).slice(1).map(Number);
  test("taller by default, never squeezing the canvas below its least height, and to its content on a phone", () => {
    const [lo, vh, hi] = px(dockCss, /--pl-dock-h: clamp\((\d+)px, (\d+)vh, (\d+)px\);/);
    const dock = Math.min(hi, Math.max(lo, (900 * vh) / 100));
    expect(dock).toBeGreaterThanOrEqual(340); // was 288 (32vh)
    const [least] = px(canvasCss, /\.cv-stage \{[^}]*height: max\((\d+)px, calc\(100vh - var\(--km-topbar-h\) - 150px - var\(--cv-below, 0px\)\)\);/);
    expect(least).toBeGreaterThanOrEqual(300);
    // At 900 px with the 72 px top bar, the page is at most a little taller than the screen.
    expect(72 + 150 + least + 56 + dock).toBeLessThanOrEqual(930);
    expect(dockCss).toMatch(/@media \(max-width: 700px\) \{\s*\.pl-dock-panel \{\s*height: auto;\s*max-height: 70vh;/);
  });
});
