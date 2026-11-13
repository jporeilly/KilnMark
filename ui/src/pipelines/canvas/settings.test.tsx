import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { PipelineProblem, PipelineValidation, Source, NodeType, NodeTypeSetting } from "../../api";
import { arrivingColumns, defaultText, problemSetting, shownSettings, nodeSummary } from "./insight";
import type { GraphNode } from "./model";
import { SettingControl, orderTermText, parseOrderTerm, type ControlContext } from "./SettingControls";
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

describe("each setting's box sits under its name, its help under the box", () => {
  // So the boxes of a row line up whatever the length of each one's help (the node settings panel).
  const order = (out: string, box: RegExp) => {
    const at = out.search(box);
    const help = out.indexOf('class="hint"');
    expect(at).toBeGreaterThan(-1);
    expect(help).toBeGreaterThan(at);
  };

  test("a text, a choice and a source", () => {
    order(html(spec("label", "text"), "x"), /<input/);
    order(html(spec("mode", "choice", { choices: ["create", "replace"] }), "replace"), /<select/);
    order(html(spec("source", "source"), "crm-db"), /<select|<input/);
  });

  test("a list of columns: the box to add one first, then what is chosen", () => {
    const out = html(spec("key", "columns"), []);
    order(out, /<input|<select/);
    expect(out.indexOf("None chosen")).toBeGreaterThan(out.search(/<input|<select/));
  });
});

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

  test("a join's kinds and a comparing union's settings, as the node catalogue gives them", () => {
    const how = html(spec("how", "choice", { defaultValue: "inner", choices: ["inner", "left", "right", "full", "semi", "anti"] }), "anti", { nodeType: "join" });
    expect(how).toContain("Default (inner)");
    expect(how).toMatch(/<option value="semi">semi<\/option>/);
    expect(how).toMatch(/<option value="anti" selected="">anti<\/option>/);
    const mode = html(spec("mode", "choice", { defaultValue: "all", choices: ["all", "except", "intersect"] }), "except", { nodeType: "union" });
    expect(mode).toContain("Default (all)");
    expect(mode).toMatch(/<option value="except" selected="">except<\/option>/);
    expect(mode).toMatch(/<option value="intersect">intersect<\/option>/);
    // compare_on picks from the columns arriving at the union
    const on = html(spec("compare_on", "columns"), ["customer_id"], { nodeType: "union" });
    expect(on).toContain('aria-label="Remove customer_id from compare on"');
    expect(on).toMatch(/<option value="status">status \(utf8\)<\/option>/);
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

  test("ORDER: columns in order, each with its direction and where empty values go", () => {
    const out = html(spec("by", "order"), ["status desc", "customer_id nulls first"], { nodeType: "sort" });
    expect(out).toMatch(/<ol class="cv-order"/);
    expect(out.indexOf(">status<")).toBeLessThan(out.indexOf(">customer_id<"));
    expect(out).toMatch(/aria-label="Direction for status"[^>]*>.*?<option value="desc" selected="">Largest first<\/option>/);
    expect(out).toMatch(/aria-label="Empty values in customer_id"[^>]*>.*?<option value="first" selected="">Empty first<\/option>/);
    expect(out).toContain('aria-label="Move customer_id up"');
    expect(out).not.toContain('aria-label="Move status up"'); // the first cannot move up
    expect(out).toContain('aria-label="Remove status from by"');
    // only columns not chosen yet can be added
    expect(out).toMatch(/<option value="first name">first name \(utf8\)<\/option>/);
    expect(out).not.toMatch(/<option value="status">/);
    expect(html(spec("by", "order"), [], { columns: { main: null, lookup: null } })).toContain('aria-label="Column to add to by"');
  });

  test("ORDER entries are read and written as the sort node reads them", () => {
    expect(parseOrderTerm("amount")).toEqual({ column: "amount", descending: false, nullsFirst: false });
    expect(parseOrderTerm(" first name DESC NULLS FIRST ")).toEqual({ column: "first name", descending: true, nullsFirst: true });
    expect(parseOrderTerm("x asc nulls last")).toEqual({ column: "x", descending: false, nullsFirst: false });
    expect(orderTermText({ column: "amount", descending: true, nullsFirst: false })).toBe("amount desc");
    expect(orderTermText({ column: "amount", descending: false, nullsFirst: true })).toBe("amount nulls first");
    expect(orderTermText(parseOrderTerm("a desc nulls first"))).toBe("a desc nulls first");
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
    const join = html(spec("on", "column_map"), { customer_id: "id" }, { nodeType: "join" });
    expect(join).toContain("Left column 1");
    expect(join).toContain("Right column 1");
    expect(join).toMatch(/<option value="id" selected="">id \(int64\)<\/option>/); // from the right input
    const rename = html(spec("rename", "column_map"), { status: "state" }, { nodeType: "select" });
    expect(rename).toContain("New name 1");
    expect(rename).toContain('value="state"');
  });

  test("EXPRESSIONS and CONDITIONS: names and expressions in order", () => {
    const out = html(spec("columns", "expressions"), { full_name: "a || b", email: "lower(email)" }, { nodeType: "compute" });
    expect(out.indexOf("full_name")).toBeLessThan(out.indexOf("email"));
    expect(out).toContain("Expression 2");
    expect(out).toContain("Add a column");
    const window = html(spec("columns", "expressions"), { balance: "sum(amount)" }, { nodeType: "window" });
    expect(window).toContain("Function 1");
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
    expect(nodeSummary(node({ type: "lookup", settings: { on: { a: "b" } } }))).toBe("on a = b · first match");
    expect(nodeSummary(node({ type: "sort", settings: { by: ["region", "amount desc"] } }))).toBe("by region, amount desc");
    expect(nodeSummary(node({ type: "window", settings: { partition_by: ["account_id"], order_by: ["txn_at"], columns: { balance: "sum(amount)", n: "row_number()" } } }))).toBe(
      "balance, n · per account_id",
    );
    expect(nodeSummary(node({ type: "window", settings: { order_by: ["txn_at"], columns: { n: "row_number()" } } }))).toBe("n");
    expect(nodeSummary(node({ type: "join", settings: { on: { customer_id: "id" }, how: "left" } }))).toBe("left on customer_id = id");
    expect(nodeSummary(node({ type: "join", settings: { on: { a: "b" } } }))).toBe("inner on a = b");
    expect(nodeSummary(node({ type: "join", settings: { on: { email: "email" }, how: "anti" } }))).toBe("anti on email = email");
    const two = [
      { port: null, from: "archived", fromPort: "out" },
      { port: null, from: "current", fromPort: "out" },
    ];
    expect(nodeSummary(node({ type: "union", inputs: two }))).toBe("2 inputs");
    expect(nodeSummary(node({ type: "union", inputs: two, settings: { mode: "all" } }))).toBe("2 inputs");
    expect(nodeSummary(node({ type: "union", inputs: two, settings: { mode: "except" } }))).toBe("except: rows of archived in no other input");
    expect(nodeSummary(node({ type: "union", inputs: two, settings: { mode: "intersect", compare_on: ["id", "email"] } }))).toBe(
      "intersect on id, email: rows of archived in every other input",
    );
    expect(nodeSummary(node({ type: "write", settings: { source: "w", path: "x.csv", mode: "replace" } }))).toBe("w · x.csv · replace");
    // which row of a repeated key wins is always in sight, the default too
    expect(nodeSummary(node({ type: "write", settings: { source: "w", table: "a.b", mode: "upsert", key: ["id"] } }))).toBe("w · a.b · upsert · last kept");
    expect(nodeSummary(node({ type: "dedupe", settings: { key: ["id"] } }))).toBe("key id · first kept");
    expect(nodeSummary(node({ type: "dedupe" }))).toBe("whole rows · first kept");
    expect(nodeSummary(node({ type: "dedupe", settings: { key: ["id"], duplicates: "latest", duplicates_by: "updated_at" } }))).toBe(
      "key id · latest by updated_at kept",
    );
    expect(nodeSummary(node({ type: "lookup", settings: { on: { a: "b" }, duplicates: "error" } }))).toBe("on a = b · error on a repeated key");
    expect(nodeSummary(node({ type: "dedupe", settings: { duplicates: "latest" } }))).toBe("whole rows · latest kept");
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
      settings: [spec("source", "source"), spec("target", "asset"), spec("table", "text"), spec("path", "text"), spec("commit_every", "number"), spec("overwrite", "boolean")],
      writerSettings: { postgresql: ["commit_every"], s3: ["overwrite"] },
    } as unknown as NodeType;
    const keys = (kind: string | null, settings = {}) => shownSettings(write, node({ type: "write", settings }), kind).specs.map((s) => s.key);
    expect(keys("s3")).toEqual(["source", "target", "overwrite"]);
    expect(keys("postgresql")).toEqual(["source", "target", "commit_every"]);
    expect(keys(null)).toEqual(["source", "target"]);
    expect(shownSettings(write, node({ type: "write" }), null).writerHidden).toBe(2);
    // A setting the YAML has is always shown, so it can be seen and removed; `table` (or `path`) in
    // the YAML names where the write goes, so the empty `target` box beside it is not shown.
    expect(keys("s3", { table: "a.b", commit_every: 5 })).toEqual(["source", "table", "commit_every", "overwrite"]);
    expect(keys(null, { path: "out/a.csv" })).toEqual(["source", "path"]);
    // A target the YAML has stays, with a table beside it, so both can be seen and one removed.
    expect(keys(null, { target: "a.b", table: "a.c" })).toEqual(["source", "target", "table"]);
  });

  test("which row wins is offered where it applies", () => {
    const write = {
      type: "write",
      kind: "sink",
      settings: [spec("source", "source"), spec("target", "asset"), spec("mode", "choice"), spec("duplicates", "choice"), spec("duplicates_by", "column")],
      writerSettings: {},
    } as unknown as NodeType;
    const keys = (settings = {}) => shownSettings(write, node({ type: "write", settings }), null).specs.map((s) => s.key);
    expect(keys()).toEqual(["source", "target", "mode"]);
    expect(keys({ mode: "upsert" })).toEqual(["source", "target", "mode", "duplicates"]);
    expect(keys({ mode: "upsert", duplicates: "latest" })).toEqual(["source", "target", "mode", "duplicates", "duplicates_by"]);
    // set in the YAML: shown, so it can be seen and removed
    expect(keys({ mode: "append", duplicates: "first" })).toEqual(["source", "target", "mode", "duplicates"]);
    const dedupe = { type: "dedupe", kind: "transform", settings: [spec("key", "columns"), spec("duplicates", "choice"), spec("duplicates_by", "column")] } as unknown as NodeType;
    const d = (settings = {}) => shownSettings(dedupe, node({ type: "dedupe", settings }), null).specs.map((s) => s.key);
    expect(d()).toEqual(["key", "duplicates"]);
    expect(d({ duplicates: "earliest" })).toEqual(["key", "duplicates", "duplicates_by"]);
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
