// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import { act } from "react";
import { parseDocument } from "yaml";
import type { NodeTypeSetting } from "../../api";
import { RoleContext } from "../../components/role";
import { readGraph, removeParameter, setParameter, type Change, type DeclaredParameter, type ParameterSpec } from "./model";
import { namesIn, parameterProblems, parameterSummary, specOf, variableSummary } from "./parameters";
import { forgetVariables } from "./ParameterChip";
import { ParametersPanel } from "./ParametersPanel";
import { SettingControl, type ControlContext } from "./SettingControls";

const PIPELINE = `# Parameters: values given for each run (a Kilnmark sample).
kilnmark: pipeline/v1
name: sample-orders-by-status
description: Shop orders in the statuses asked for.

parameters:
  statuses: { type: text list, default: [delivered], description: The order statuses to keep }
  min_fee: { type: number, default: 0, description: Only orders with at least this shipping fee }
  label: { type: text, default: delivered, description: Names the file written }

nodes:
  - id: orders
    type: read
    source: shop-db
    asset: shop.orders
    where: "status in \${statuses} and shipping_fee >= \${min_fee}"

  - id: out
    type: write
    input: orders
    source: files
    path: samples/parameters/orders_\${label}.csv
`;

const ok = (c: Change) => {
  if ("error" in c) throw new Error(c.error);
  expect(parseDocument(c.text).errors).toEqual([]);
  return c.text;
};
const declared = (text: string): DeclaredParameter[] => {
  const r = readGraph(text);
  if (!r.ok) throw new Error(r.error);
  return r.graph.declared;
};
const spec = (over: Partial<ParameterSpec> = {}): ParameterSpec => ({
  name: "region",
  kind: "text",
  defaultValue: "EMEA",
  description: "The region to report on",
  ...over,
});
/** The lines that differ between two texts, as [before, after]. */
function changed(a: string, b: string): [string[], string[]] {
  const x = a.split("\n");
  const y = b.split("\n");
  return [x.filter((l) => !y.includes(l)), y.filter((l) => !x.includes(l))];
}

describe("reading the parameters a pipeline declares", () => {
  test("each with its type, default and description, as written", () => {
    const d = declared(PIPELINE);
    expect(d.map((p) => [p.name, p.kind, p.defaultText, p.description])).toEqual([
      ["statuses", "text list", "delivered", "The order statuses to keep"],
      ["min_fee", "number", "0", "Only orders with at least this shipping fee"],
      ["label", "text", "delivered", "Names the file written"],
    ]);
    expect(d[0].line).toBe(7);
  });

  test("a default that is a server variable is named as one; a type is read as the server reads it", () => {
    const d = declared('parameters:\n  label: { type: " Text ", default: "${vars.default_label}" }\n  n: { type: Number  List, default: [1, 2] }\n');
    expect(d[0].kind).toBe("text");
    expect(d[0].defaultVariable).toBe("default_label");
    expect(d[1].kind).toBe("number list");
    expect(d[1].defaultText).toBe("1, 2");
    expect(declared("parameters:\n  x: { type: date, default: 1 }\n")[0].kind).toBeNull();
  });
});

describe("what a ${name} chip says", () => {
  test("a parameter's type, default and description", () => {
    const [statuses, minFee] = declared(PIPELINE);
    expect(parameterSummary(statuses)).toBe("statuses: text list, default delivered. The order statuses to keep.");
    expect(parameterSummary(minFee)).toBe("min_fee: number, default 0. Only orders with at least this shipping fee.");
    const [label] = declared('parameters:\n  label: { type: text, default: "${vars.default_label}" }\n');
    expect(parameterSummary(label)).toBe("label: text, default the server variable default_label.");
  });

  test("a server variable: what it is, and its value only for those who may see it", () => {
    expect(variableSummary("region", { state: "hidden" })).toContain("Stewards and above see its value.");
    expect(variableSummary("region", { state: "hidden" })).not.toContain("EMEA");
    expect(variableSummary("region", { state: "known", value: "EMEA" })).toContain("Its value now: EMEA.");
    expect(variableSummary("region", { state: "unset" })).toContain("not set on this server");
  });

  test("the names a setting puts in, each once; $${ is a plain ${", () => {
    expect(namesIn("status in ${statuses} and fee >= ${ min_fee } or ${statuses}")).toEqual(["statuses", "min_fee"]);
    expect(namesIn("a $${literal} and ${vars.region}")).toEqual(["vars.region"]);
  });
});

describe("checking a parameter as the server does", () => {
  const draft = (over: Partial<Parameters<typeof parameterProblems>[0]> = {}) => ({
    name: "region",
    kind: "text",
    defaultText: "EMEA",
    description: "",
    ...over,
  });

  test("names: a small letter first, small letters, digits and _, not a taken word, not twice", () => {
    expect(parameterProblems(draft(), [])).toEqual({});
    expect(parameterProblems(draft({ name: "" }), []).name).toContain("Give the parameter a name");
    expect(parameterProblems(draft({ name: "Region" }), []).name).toContain("starts with a small letter");
    expect(parameterProblems(draft({ name: "1st" }), []).name).toContain("starts with a small letter");
    expect(parameterProblems(draft({ name: "my-region" }), []).name).toContain("small letters, digits and _");
    expect(parameterProblems(draft({ name: "a".repeat(65) }), []).name).toBeTruthy();
    expect(parameterProblems(draft({ name: "and" }), []).name).toContain("conditions read it as a word");
    expect(parameterProblems(draft({ name: "good" }), []).name).toContain("conditions read it as a word");
    expect(parameterProblems(draft(), ["region"]).name).toBe("There is already a parameter called region.");
  });

  test("kinds: the five the server knows", () => {
    expect(parameterProblems(draft({ kind: "date" }), []).kind).toContain("text, number, boolean, text list, number list");
    expect(parameterProblems(draft({ kind: "boolean list", defaultText: "true" }), []).kind).toBeTruthy();
  });

  test("defaults: needed, and of the kind, or a server variable", () => {
    expect(parameterProblems(draft({ defaultText: " " }), []).defaultText).toContain("Give a default");
    expect(parameterProblems(draft({ kind: "number", defaultText: "ten" }), []).defaultText).toContain("a number");
    expect(parameterProblems(draft({ kind: "number", defaultText: "0.02" }), [])).toEqual({});
    expect(parameterProblems(draft({ kind: "number", defaultText: "1".repeat(16) }), []).defaultText).toContain("15 digits");
    expect(parameterProblems(draft({ kind: "boolean", defaultText: "yes" }), []).defaultText).toContain("true or false");
    expect(parameterProblems(draft({ kind: "number list", defaultText: "1, x" }), []).defaultText).toContain("Each value is a number");
    expect(parameterProblems(draft({ kind: "text list", defaultText: " , " }), []).defaultText).toContain("1 to 200 values");
    expect(parameterProblems(draft({ kind: "text", defaultText: "x".repeat(1001) }), []).defaultText).toContain("1,000 characters");
    expect(parameterProblems(draft({ kind: "number", defaultText: "${vars.min_fee}" }), [])).toEqual({});
    expect(parameterProblems(draft({ defaultText: "${vars.Region}" }), []).defaultText).toContain("not a server variable's name");
  });

  test("what is written: the default of its kind", () => {
    expect(specOf(draft({ kind: "text list", defaultText: "shipped, delivered" })).defaultValue).toEqual(["shipped", "delivered"]);
    expect(specOf(draft({ kind: "number", defaultText: "0.5" })).defaultValue).toBe(0.5);
    expect(specOf(draft({ kind: "boolean", defaultText: "false" })).defaultValue).toBe(false);
    expect(specOf(draft({ defaultText: "${vars.region}" })).defaultValue).toBe("${vars.region}");
    expect(specOf(draft({ description: "  " })).description).toBeNull();
  });
});

describe("the canvas writes parameters as a person would", () => {
  test("a change rewrites only that parameter's line, spaced as it was", () => {
    const out = ok(setParameter(PIPELINE, "min_fee", spec({ name: "min_fee", kind: "number", defaultValue: 5, description: "At least this fee" })));
    expect(changed(PIPELINE, out)).toEqual([
      ["  min_fee: { type: number, default: 0, description: Only orders with at least this shipping fee }"],
      ["  min_fee: { type: number, default: 5, description: At least this fee }"],
    ]);
  });

  test("a rename changes the declaration only", () => {
    const out = ok(setParameter(PIPELINE, "label", spec({ name: "file_label", defaultValue: "delivered", description: "Names the file written" })));
    expect(changed(PIPELINE, out)).toEqual([
      ["  label: { type: text, default: delivered, description: Names the file written }"],
      ["  file_label: { type: text, default: delivered, description: Names the file written }"],
    ]);
    expect("error" in setParameter(PIPELINE, "label", spec({ name: "statuses" }))).toBe(true);
  });

  test("a new one goes after the others; values YAML would misread are quoted", () => {
    const out = ok(setParameter(PIPELINE, null, spec({ description: "Where, as in: EMEA, APAC" })));
    expect(out).toContain('  label: { type: text, default: delivered, description: Names the file written }\n  region: { type: text, default: EMEA, description: "Where, as in: EMEA, APAC" }\n\nnodes:');
    const v = ok(setParameter(PIPELINE, null, spec({ defaultValue: "${vars.region}", description: null })));
    expect(declared(v).find((p) => p.name === "region")?.defaultVariable).toBe("region");
    const n = ok(setParameter(PIPELINE, null, spec({ defaultValue: "007", description: null })));
    expect(declared(n).find((p) => p.name === "region")?.defaultText).toBe("007");
  });

  test("the first one starts a parameters block after the description", () => {
    const plain = "kilnmark: pipeline/v1\nname: p\ndescription: Orders.\n\nnodes:\n  - id: a\n    type: read\n";
    const out = ok(setParameter(plain, null, spec({ kind: "text list", defaultValue: ["a", "b"], description: null })));
    expect(out).toBe(
      "kilnmark: pipeline/v1\nname: p\ndescription: Orders.\n\nparameters:\n  region: { type: text list, default: [ a, b ] }\n\nnodes:\n  - id: a\n    type: read\n",
    );
  });

  test("one written over several lines keeps its lines", () => {
    const text = "name: p\nparameters:\n  region:\n    type: text\n    default: EMEA # the usual\nnodes: []\n";
    const out = ok(setParameter(text, "region", spec({ defaultValue: "APAC", description: "Where" })));
    expect(out).toBe("name: p\nparameters:\n  region:\n    type: text\n    default: APAC # the usual\n    description: Where\nnodes: []\n");
  });

  test("removing one removes its line; removing the last removes the block", () => {
    const one = ok(removeParameter(PIPELINE, "min_fee"));
    expect(changed(PIPELINE, one)[0]).toEqual(["  min_fee: { type: number, default: 0, description: Only orders with at least this shipping fee }"]);
    expect(changed(PIPELINE, one)[1]).toEqual([]);
    const none = ok(removeParameter(ok(removeParameter(one, "statuses")), "label"));
    expect(none).toContain("description: Shop orders in the statuses asked for.\n\nnodes:");
    expect(none).not.toContain("parameters:");
    expect("error" in removeParameter(PIPELINE, "nothing")).toBe(true);
  });
});

// ---- on the page ----

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  act(() => root?.unmount());
  host?.remove();
  root = null;
  vi.unstubAllGlobals();
  forgetVariables();
});
beforeEach(() => {
  (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
});

async function show(ui: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(ui));
}
const button = (name: string) =>
  [...host!.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === name || b.getAttribute("aria-label") === name)!;
/** An element's accessible description: the text of the elements its aria-describedby names. */
const description = (el: Element) =>
  (el.getAttribute("aria-describedby") ?? "")
    .split(" ")
    .map((id) => document.getElementById(id)?.textContent ?? "")
    .join(" ");

/** Sets an input's value as typing does, so React sees it. */
function type(el: HTMLInputElement | HTMLSelectElement, value: string) {
  const proto = el instanceof HTMLSelectElement ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, "value")!.set!.call(el, value);
  el.dispatchEvent(new Event(el instanceof HTMLSelectElement ? "change" : "input", { bubbles: true }));
}

const WHERE: NodeTypeSetting = { key: "where", label: "Where", kind: "expression", required: false, defaultValue: null, help: "Rows to keep", choices: [] };
const context = (over: Partial<ControlContext> = {}): ControlContext => ({
  columns: { main: null, lookup: null },
  side: null,
  sources: null,
  writing: false,
  assets: null,
  nodeType: "read",
  parameters: ["statuses", "min_fee", "label"],
  declared: declared(PIPELINE),
  ...over,
});

describe("${name} chips", () => {
  test("each says what it is on keyboard focus and on hover, as its description; Escape hides it", async () => {
    await show(
      <SettingControl spec={WHERE} id="w" value="status in ${statuses}" onChange={() => {}} readOnly={false} problems={[]} context={context()} />,
    );
    const chip = button("${statuses}");
    expect(description(chip)).toBe("statuses: text list, default delivered. The order statuses to keep.");
    const tip = document.getElementById(chip.getAttribute("aria-describedby")!)!;
    expect(tip.getAttribute("role")).toBe("tooltip");
    expect(tip.hidden).toBe(true);
    await act(async () => chip.focus());
    expect(tip.hidden).toBe(false);
    await act(async () => void chip.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true })));
    expect(tip.hidden).toBe(true);
    await act(async () => chip.blur());
    const other = button("${min_fee}");
    const otherTip = document.getElementById(other.getAttribute("aria-describedby")!)!;
    await act(async () => void other.parentElement!.dispatchEvent(new MouseEvent("mouseover", { bubbles: true, relatedTarget: document.body })));
    expect(otherTip.hidden).toBe(false);
    expect(otherTip.textContent).toBe("min_fee: number, default 0. Only orders with at least this shipping fee.");
    // No title only: the words are on the page.
    expect(chip.getAttribute("title")).toBeNull();
  });

  test("read-only, the parameters a setting puts in are shown as chips that say what they are", async () => {
    await show(
      <SettingControl
        spec={{ ...WHERE, key: "path", label: "Path", kind: "text" }}
        id="p"
        value="samples/orders_${label}.csv"
        onChange={() => {}}
        readOnly
        problems={[]}
        context={context()}
      />,
    );
    const group = host!.querySelector('[role="group"]')!;
    expect(group.getAttribute("aria-label")).toBe("Parameters and variables Path puts in");
    expect(description(button("${label}"))).toBe("label: text, default delivered. Names the file written.");
  });

  test("a server variable's value is read only for stewards and above", async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ name: "region", type: "text", value: "EMEA" }), { status: 200, headers: { "Content-Type": "application/json" } }));
    vi.stubGlobal("fetch", fetch);
    const control = (
      <SettingControl spec={WHERE} id="v" value="region = ${vars.region}" onChange={() => {}} readOnly problems={[]} context={context()} />
    );
    await show(<RoleContext.Provider value="viewer">{control}</RoleContext.Provider>);
    expect(description(button("${vars.region}"))).toContain("Stewards and above see its value.");
    expect(fetch).not.toHaveBeenCalled();
    act(() => root!.unmount());

    await show(<RoleContext.Provider value="steward">{control}</RoleContext.Provider>);
    await act(async () => new Promise((r) => setTimeout(r, 0)));
    expect(fetch.mock.calls.map((c) => String((c as unknown[])[0]))).toEqual(["/api/v1/variables/region"]);
    expect(description(button("${vars.region}"))).toContain("Its value now: EMEA.");
  });
});

describe("the Parameters panel", () => {
  /** The panel as the canvas holds it: each change goes into the YAML, read back for the table. */
  function Holder({ readOnly, start = PIPELINE, onText }: { readOnly: boolean; start?: string; onText?: (t: string) => void }) {
    const [text, setText] = useState(start);
    const r = readGraph(text);
    const graph = r.ok ? r.graph : null;
    const apply = (c: Change) => {
      if ("error" in c) return c.error;
      setText(c.text);
      onText?.(c.text);
      return null;
    };
    return (
      <ParametersPanel
        idBase="pp"
        declared={graph?.declared ?? []}
        nodes={graph?.nodes ?? []}
        readOnly={readOnly}
        onSave={(was, s) => apply(setParameter(text, was, s))}
        onRemove={(n) => apply(removeParameter(text, n))}
      />
    );
  }
  const rows = () => [...host!.querySelectorAll("tbody tr")].map((tr) => [...tr.children].map((c) => c.textContent));

  test("a table of each parameter: name, type, default, description and the nodes that put it in", async () => {
    await show(<Holder readOnly />);
    expect([...host!.querySelectorAll("thead th")].map((th) => th.textContent)).toEqual(["Name", "Type", "Default", "Description", "Put in by"]);
    expect(rows()).toEqual([
      ["statuses", "text list", "delivered", "The order statuses to keep", "orders"],
      ["min_fee", "number", "0", "Only orders with at least this shipping fee", "orders"],
      ["label", "text", "delivered", "Names the file written", "out"],
    ]);
  });

  test("read-only for everyone but designers and admins: nothing to change", async () => {
    await show(<Holder readOnly />);
    expect(host!.textContent).toContain("Designers and admins change them.");
    expect(host!.querySelector("form")).toBeNull();
    expect([...host!.querySelectorAll("button")].map((b) => b.textContent)).toEqual([]);
  });

  test("a designer adds one, checked first, and changes and removes others, each written to the YAML", async () => {
    let text = PIPELINE;
    await show(<Holder readOnly={false} onText={(t) => (text = t)} />);
    await act(async () => button("Add a parameter").click());
    const name = document.getElementById("pp-name") as HTMLInputElement;
    expect(document.activeElement).toBe(name);
    // Problems first: a taken word and no default.
    await act(async () => type(name, "and"));
    await act(async () => type(document.getElementById("pp-defaultText") as HTMLInputElement, ""));
    await act(async () => button("Add").click());
    expect(name.getAttribute("aria-invalid")).toBe("true");
    expect(description(name)).toContain("conditions read it as a word");
    expect(description(document.getElementById("pp-defaultText")!)).toContain("Give a default");
    expect(document.activeElement).toBe(name);
    expect(text).toBe(PIPELINE);

    await act(async () => type(name, "region"));
    await act(async () => type(document.getElementById("pp-kind") as HTMLSelectElement, "text list"));
    await act(async () => type(document.getElementById("pp-defaultText") as HTMLInputElement, "EMEA, APAC"));
    await act(async () => type(document.getElementById("pp-description") as HTMLInputElement, "Regions to report on"));
    await act(async () => button("Add").click());
    expect(text).toContain("  region: { type: text list, default: [ EMEA, APAC ], description: Regions to report on }\n");
    expect(rows().at(-1)).toEqual(["region", "text list", "EMEA, APAC", "Regions to report on", "No node yet", "ChangeRemove"]);
    expect(host!.querySelector('[role="status"]')?.textContent).toBe("Parameter region added.");
    expect(document.activeElement).toBe(document.getElementById("pp-add"));

    await act(async () => button("Change min_fee").click());
    const fee = document.getElementById("pp-defaultText") as HTMLInputElement;
    expect(fee.value).toBe("0");
    await act(async () => type(fee, "2.5"));
    await act(async () => button("Save the change").click());
    expect(text).toContain("  min_fee: { type: number, default: 2.5, description: Only orders with at least this shipping fee }\n");
    expect(document.activeElement).toBe(document.getElementById("pp-edit-min_fee"));

    await act(async () => button("Remove label").click());
    expect(text).not.toContain("  label:");
    expect(host!.querySelector('[role="status"]')?.textContent).toContain("out still puts in ${label}");
  });
});
