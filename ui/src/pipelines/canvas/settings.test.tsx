import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { PipelineProblem, PipelineValidation, Source, NodeType, NodeTypeSetting } from "../../api";
import { arrivingColumns, defaultText, problemSetting, shownSettings, nodeSummary } from "./insight";
import type { GraphNode } from "./model";
import { SettingControl, type ControlContext } from "./SettingControls";
import { categoryOf, Palette } from "./Palette";

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
  { name: "first name", type: "utf8" },
];
const SOURCES = [
  { name: "sales-db", displayName: "Sales database", kind: "postgresql", settings: {} },
  { name: "warehouse", displayName: "warehouse", kind: "s3", settings: { allow_writing: "true" } },
] as unknown as Source[];
const ctx = (over: Partial<ControlContext> = {}): ControlContext => ({
  columns: { main: COLS, lookup: [{ name: "id", type: "int64" }] },
  side: null,
  sources: SOURCES,
  writing: false,
  assets: ["crm.customers", "crm.orders"],
  nodeType: "filter",
  ...over,
});
const html = (s: NodeTypeSetting, value: unknown, over: Partial<ControlContext> = {}, problems: string[] = []) =>
  renderToStaticMarkup(
    <SettingControl spec={s} id="t" value={value as never} onChange={() => {}} readOnly={false} problems={problems} context={ctx(over)} />,
  );

describe("each setting kind gets its control, with help and default", () => {
  test("BOOLEAN: a switch, showing the default when unset", () => {
    const out = html(spec("strict", "boolean", { defaultValue: false }), undefined);
    expect(out).toContain('role="switch"');
    expect(out).toContain('aria-checked="false"');
    expect(out).toContain("Default: off");
    expect(out).toContain("Help for strict");
    expect(html(spec("keep_grants", "boolean", { defaultValue: true }), false)).toContain('aria-checked="false"');
  });

  test("NUMBER: a number field with the default as a placeholder", () => {
    const out = html(spec("memory_limit_mb", "number", { defaultValue: 256 }), undefined);
    expect(out).toMatch(/<input[^>]*inputMode="decimal"/);
    expect(out).toContain('placeholder="256"');
    expect(out).toContain("Default: 256.");
  });

  test("CHOICE: a select whose empty option names the default", () => {
    const out = html(spec("mode", "choice", { defaultValue: "create", choices: ["create", "append", "replace"] }), "replace");
    expect(out).toContain("<select");
    expect(out).toContain("Default (create)");
    expect(out).toMatch(/<option value="replace" selected="">replace<\/option>/);
  });

  test("SOURCE: the saved sources; for a write, those that are read-only say so", () => {
    const out = html(spec("source", "source", { required: true }), "sales-db", { writing: true });
    expect(out).toContain("Sales database (sales-db), read-only");
    expect(out).toMatch(/<option value="warehouse">warehouse<\/option>/);
    expect(out).toContain("(required)");
    expect(html(spec("source", "source"), "gone")).toContain("gone (not a saved source)");
  });

  test("ASSET: a text field suggesting the source's tables and files", () => {
    const out = html(spec("asset", "asset"), "crm.customers");
    expect(out).toContain('list="t-assets"');
    expect(out).toContain('<option value="crm.orders">');
  });

  test("EXPRESSION: an editor with the arriving columns to insert, and its problems", () => {
    const out = html(spec("condition", "expression"), "statuss = 1", {}, ["unknown column 'statuss' at 1; did you mean status?"]);
    expect(out).toContain("<textarea");
    expect(out).toContain('aria-invalid="true"');
    expect(out).toContain('aria-describedby="t-help t-problems"');
    expect(out).toContain("unknown column &#x27;statuss&#x27;");
    expect(out).toContain('aria-label="Insert a column into condition"');
    expect(out).toContain(">customer_id</button>");
  });

  test("COLUMN and COLUMNS: chosen from the columns arriving", () => {
    expect(html(spec("key", "column"), "status")).toMatch(/<option value="status" selected="">status \(utf8\)<\/option>/);
    const many = html(spec("key", "columns"), ["status"]);
    expect(many).toContain('aria-label="Remove status from key"');
    expect(many).toContain("Add a column…");
    expect(many).not.toMatch(/<option value="status">/); // already chosen
    // Unknown columns: typed instead.
    expect(html(spec("key", "columns"), [], { columns: { main: null, lookup: null } })).toContain('aria-label="Column to add to key"');
    // A lookup's keep names the lookup side's columns.
    expect(html(spec("keep", "columns"), [], { side: "lookup", nodeType: "lookup" })).toMatch(/<option value="id">id \(int64\)<\/option>/);
  });

  test("A read node's query: SQL in a monospace box over several lines; other text stays one line", () => {
    const out = html(spec("query", "text"), "SELECT 1\nFROM t", { nodeType: "read" });
    expect(out).toMatch(/<textarea[^>]*class="mono cv-sql"/);
    expect(out).toContain("SELECT 1\nFROM t");
    expect(html(spec("query", "text"), undefined, { nodeType: "filter" })).toMatch(/<input/);
  });

  test("A read node's COLUMNS: a box per column of the table, ticked in the order chosen", () => {
    const none = html(spec("columns", "columns"), undefined, { nodeType: "read" });
    expect(none).toContain("None ticked: every column is read.");
    expect((none.match(/type="checkbox"/g) ?? []).length).toBe(3);
    const two = html(spec("columns", "columns"), ["status", "customer_id"], { nodeType: "read" });
    expect(two).toContain("2 of 3 read, in this order: status, customer_id.");
    expect((two.match(/checked=""/g) ?? []).length).toBe(2);
    expect(two).toContain("Read every column");
    // A column the table does not have stays visible, to be unticked.
    expect(html(spec("columns", "columns"), ["gone"], { nodeType: "read" })).toContain("not a column of the table");
    // The table's columns not known yet: the plain list, typed.
    expect(html(spec("columns", "columns"), [], { nodeType: "read", columns: { main: null, lookup: null } })).toContain(
      'aria-label="Column to add to columns"',
    );
  });

  test("EXPRESSION: the pipeline's parameters are offered as ${name}", () => {
    const out = html(spec("where", "expression"), "status = ", { nodeType: "read", parameters: ["status_wanted"] });
    expect(out).toContain('aria-label="Insert a parameter into where"');
    expect(out).toContain("${status_wanted}");
    expect(html(spec("where", "expression"), "", { nodeType: "read" })).not.toContain("Insert a parameter");
  });

  test("COLUMN_MAP: pairs; a lookup's keys pick from main and lookup columns", () => {
    const out = html(spec("on", "column_map"), { customer_id: "id" }, { nodeType: "lookup" });
    expect(out).toContain("Main column 1");
    expect(out).toContain("Lookup column 1");
    expect(out).toMatch(/<option value="id" selected="">id \(int64\)<\/option>/);
    const rename = html(spec("rename", "column_map"), { status: "state" }, { nodeType: "select" });
    expect(rename).toContain("New name 1");
    expect(rename).toContain('value="state"');
  });

  test("EXPRESSIONS and CONDITIONS: names and expressions in order", () => {
    const out = html(spec("columns", "expressions"), { full_name: "a || b", email: "lower(email)" }, { nodeType: "compute" });
    expect(out.indexOf("full_name")).toBeLessThan(out.indexOf("email"));
    expect(out).toContain("Expression 2");
    expect(out).toContain("Add a column");
    const routes = html(spec("routes", "conditions"), { large: "amount > 1000" }, { nodeType: "route" });
    expect(routes).toContain("Port 1");
    expect(routes).toContain("Condition 1");
    expect(routes).toContain("Add a route");
  });

  test("TEXT: keeps what was written", () => {
    const out = renderToStaticMarkup(
      <SettingControl spec={spec("label", "text")} id="t" value={7} text="007" onChange={() => {}} readOnly problems={[]} context={ctx()} />,
    );
    expect(out).toContain('value="007"');
    expect(out).toContain("readOnly");
  });
});

const node = (over: Partial<GraphNode>): GraphNode => ({ id: "s", type: "filter", settings: {}, texts: {}, inputs: [], line: 1, keyLines: {}, ...over });

describe("what the canvas says about nodes", () => {
  test("summaries", () => {
    expect(nodeSummary(node({ type: "read", settings: { source: "sales-db", asset: "crm.customers" } }))).toBe("sales-db · crm.customers");
    expect(nodeSummary(node({ type: "filter", settings: { condition: "a > 1" } }))).toBe("a > 1");
    expect(nodeSummary(node({ type: "compute", settings: { columns: { x: "1", y: "2" } } }))).toBe("x, y");
    expect(nodeSummary(node({ type: "lookup", settings: { on: { a: "b" } } }))).toBe("on a = b");
    expect(nodeSummary(node({ type: "write", settings: { source: "w", path: "x.csv", mode: "replace" } }))).toBe("w · x.csv · replace");
    expect(nodeSummary(node({ type: "read", settings: { source: "db", asset: 2024.1 }, texts: { asset: "2024.10" } }))).toBe("db · 2024.10");
  });

  test("problems belong to their setting", () => {
    const s = node({ id: "f", settings: { condition: "x" }, keyLines: { condition: 9, mode: 10 } });
    const p = (message: string, line = 0): PipelineProblem => ({ line, column: 1, node: "f", message });
    expect(problemSetting(p("Node f: condition: unknown column 'x' at 1"), s)).toBe("condition");
    expect(problemSetting(p("Setting 'rows' of node f must be a number"), s)).toBe("rows");
    expect(problemSetting(p("Node f (filter) needs the setting 'condition' (Condition)"), s)).toBe("condition");
    expect(problemSetting(p("Node f (filter) has no setting 'colour': its settings are condition"), s)).toBe("colour");
    expect(problemSetting(p("Something on line ten", 10), s)).toBe("mode");
    expect(problemSetting(p("Node f (filter) needs an input on in"), s)).toBeNull();
  });

  test("columns arriving at a port", () => {
    const check = {
      nodes: [
        { id: "a", type: "read", kind: "source", line: 1, inputs: [], outputs: ["out"], columns: { out: COLS } },
        { id: "b", type: "read", kind: "source", line: 2, inputs: [], outputs: ["out"], columns: { out: null } },
        { id: "j", type: "lookup", kind: "transform", line: 3, inputs: ["main", "lookup"], outputs: ["out"], columns: {} },
      ],
      edges: [
        { fromNode: "a", fromPort: "out", toNode: "j", toPort: "main" },
        { fromNode: "b", fromPort: "out", toNode: "j", toPort: "lookup" },
      ],
    } as unknown as PipelineValidation;
    expect(arrivingColumns(check, "j")?.map((c) => c.name)).toEqual(["customer_id", "status", "first name"]);
    expect(arrivingColumns(check, "j", "lookup")).toBeNull();
    expect(arrivingColumns(null, "j")).toBeNull();
  });

  test("a write shows the writer settings of its source's kind only", () => {
    const write = {
      type: "write",
      kind: "sink",
      settings: [spec("source", "source"), spec("target", "asset"), spec("table", "text"), spec("commit_every", "number"), spec("overwrite", "boolean")],
      writerSettings: { postgresql: ["commit_every"], s3: ["overwrite"] },
    } as unknown as NodeType;
    const keys = (kind: string | null, settings = {}) => shownSettings(write, node({ type: "write", settings }), kind).specs.map((s) => s.key);
    expect(keys("s3")).toEqual(["source", "target", "overwrite"]);
    expect(keys("postgresql")).toEqual(["source", "target", "commit_every"]);
    expect(keys(null)).toEqual(["source", "target"]);
    expect(shownSettings(write, node({ type: "write" }), null).writerHidden).toBe(2);
    // A setting the YAML has is always shown, so it can be seen and removed.
    expect(keys("s3", { table: "a.b", commit_every: 5 })).toEqual(["source", "target", "table", "commit_every", "overwrite"]);
  });

  test("defaults in words", () => {
    expect(defaultText(spec("x", "boolean", { defaultValue: true }))).toBe("on");
    expect(defaultText(spec("x", "number", { defaultValue: 0 }))).toBe("0");
    expect(defaultText(spec("x", "text"))).toBeNull();
  });
});

describe("the node list", () => {
  const t = (type: string, kind: string, category?: string) =>
    ({ type, kind, label: type, help: "", inputs: [], manyInputs: [], outputs: [], settings: [], category }) as unknown as NodeType;
  const types = [t("read", "source", "Read"), t("filter", "transform", "Filter and route"), t("route", "transform", "Filter and route"), t("compute", "transform", "Transform"), t("mystery", "transform")];

  test("folders by category, in order, with counts; open folders show their nodes", () => {
    const out = renderToStaticMarkup(
      <Palette types={types} usedTypes={[]} readOnly={false} expanded onAdd={() => {}} searchRef={{ current: null }} hint="" />,
    );
    const folders = [...out.matchAll(/data-folder="([^"]+)"[^>]*>.*?<span>([^<]+) <span class="cv-count">\((\d+)\)/g)].map((m) => `${m[2]} ${m[3]}`);
    expect(folders).toEqual(["Read 1", "Filter and route 2", "Transform 1", "Other 1"]);
    expect(out).toContain('aria-expanded="false"');
    expect(out).toMatch(/<ul id="cv-folder-read" class="cv-type-list" hidden="">/);
    expect(out).toContain('aria-controls="cv-folder-filter-and-route"');
    // Only the list: no rail of folder buttons beside it.
    expect(out).not.toContain("cv-rail");
    expect(out).not.toContain("show in the node list");
  });

  test("hidden, nothing is drawn", () => {
    const out = renderToStaticMarkup(
      <Palette types={types} usedTypes={[]} readOnly={false} expanded={false} onAdd={() => {}} searchRef={{ current: null }} hint="" />,
    );
    expect(out).toBe("");
  });

  test("a type the server gives no category goes by its kind", () => {
    expect(categoryOf(t("x", "source"))).toBe("Read");
    expect(categoryOf(t("x", "sink"))).toBe("Write");
    expect(categoryOf(t("x", "gate"))).toBe("Quality");
    expect(categoryOf(t("x", "transform"))).toBe("Other");
    expect(categoryOf(t("x", "transform", "AI"))).toBe("AI");
  });
});

describe("number fields", () => {
  test("keep the text typed", () => {
    const out = renderToStaticMarkup(
      <SettingControl spec={spec("rows", "number")} id="t" value={9007199254740993 as never} text="9007199254740993" onChange={() => {}} readOnly={false} problems={[]} context={ctx()} />,
    );
    expect(out).toContain('value="9007199254740993"');
  });
});
