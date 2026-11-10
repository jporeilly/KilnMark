// @vitest-environment jsdom
import { afterEach, describe, expect, test } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { NodeTypeSetting, PipelineNodeOutline } from "../../api";
import { SettingControl, type ControlContext } from "./SettingControls";
import { EXAMPLES, OPERATOR_GROUPS } from "./ExpressionHelp";
import { FOLD_ABOVE, PortColumns } from "./PortColumns";
import type { Value } from "./model";

// Beside a condition: operator chips that go in at the caret, as the column chips do, and a short
// reference with worked examples (each checked by the server's ExpressionHelpTest) and the guide.

const spec = (key: string, kind: string): NodeTypeSetting => ({
  key,
  label: key === "condition" ? "Condition" : key,
  kind,
  // As the node types say: a filter's condition and a route's routes are required, a read's where is not.
  required: key === "condition" || key === "routes",
  defaultValue: null,
  help: "Rows meeting this condition",
  choices: [],
});
const ctx = (nodeType: string): ControlContext => ({
  columns: { main: [{ name: "amount", type: "decimal(12,2)" }], lookup: null },
  side: null,
  sources: [],
  writing: false,
  assets: null,
  nodeType,
});

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  host?.remove();
  root = null;
});

async function mount(node: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

/** A setting kept as the canvas keeps it, so each change shows in the box. */
function Kept({ s, start, nodeType, readOnly = false }: { s: NodeTypeSetting; start: Value; nodeType: string; readOnly?: boolean }) {
  const [v, setV] = useState<Value | undefined>(start);
  return (
    <>
      <SettingControl spec={s} id="c" value={v} onChange={setV} readOnly={readOnly} problems={[]} context={ctx(nodeType)} />
      <output id="kept">{JSON.stringify(v ?? null)}</output>
    </>
  );
}

const chip = (el: HTMLElement, text: string) =>
  [...el.querySelectorAll<HTMLButtonElement>("button.cv-chip-op")].find((b) => b.textContent === text)!;

describe("operators go into a condition at the caret", () => {
  test("every operator the reference names has a chip", () => {
    const ops = OPERATOR_GROUPS.flatMap((g) => g.ops.map((o) => o.text));
    for (const op of ["=", "<>", "<", "<=", ">", ">=", "in (…)", "between … and …", "like", "is null", "is not null", "and", "or", "not", "(", ")", "+", "-", "*", "/", "%"]) {
      expect(ops).toContain(op);
    }
    expect(OPERATOR_GROUPS.map((g) => g.label)).toEqual(["Compare", "Test", "Combine", "Arithmetic"]);
  });

  test("a filter's chip puts the operator where the caret is", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="amount 0" nodeType="filter" />);
    const box = el.querySelector<HTMLTextAreaElement>("#c")!;
    box.focus();
    box.setSelectionRange(7, 7); // after "amount "
    await act(async () => chip(el, ">=").click());
    expect(JSON.parse(el.querySelector("#kept")!.textContent!)).toBe("amount >= 0");
  });

  test("brackets for nesting close without a space before them", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="a or (b and c" nodeType="filter" />);
    const box = el.querySelector<HTMLTextAreaElement>("#c")!;
    box.focus();
    box.setSelectionRange(box.value.length, box.value.length);
    await act(async () => chip(el, ")").click());
    expect(JSON.parse(el.querySelector("#kept")!.textContent!)).toBe("a or (b and c) ");
  });

  test("a route's chip goes into the last condition used", async () => {
    const el = await mount(<Kept s={spec("routes", "conditions")} start={{ big: "amount", small: "amount < 10" }} nodeType="route" />);
    const first = el.querySelector<HTMLTextAreaElement>("#c-0-v")!;
    await act(async () => first.focus());
    first.setSelectionRange(first.value.length, first.value.length);
    await act(async () => chip(el, ">").click());
    expect(JSON.parse(el.querySelector("#kept")!.textContent!)).toEqual({ big: "amount > ", small: "amount < 10" });
  });

  test("read-only, the reference shows but no chips", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="amount > 0" nodeType="filter" readOnly />);
    expect(el.querySelectorAll("button.cv-chip-op").length).toBe(0);
    expect(el.querySelector(".cv-expr-help")).not.toBeNull();
  });

  test("a compute's columns get the reference; an aggregate's do not", async () => {
    let el = await mount(<Kept s={spec("columns", "expressions")} start={{ total: "price * qty" }} nodeType="compute" />);
    expect(el.querySelector(".cv-expr-help")).not.toBeNull();
    await act(async () => root!.unmount());
    root = null;
    host?.remove();
    el = await mount(<Kept s={spec("values", "expressions")} start={{ n: "count(*)" }} nodeType="aggregate" />);
    expect(el.querySelector(".cv-expr-help")).toBeNull();
  });
});

describe("the reference beside a condition", () => {
  test("names nesting and the operators, gives worked examples and links the guide", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="" nodeType="filter" />);
    const ref = el.querySelector<HTMLDetailsElement>(".cv-expr-help")!;
    expect(ref.open).toBe(true); // open while a required condition is empty
    expect(ref.textContent).toContain("and binds tighter than or");
    expect(ref.textContent).toContain("a or (b and c)");
    const shown = [...ref.querySelectorAll(".cv-expr-examples code")].map((c) => c.textContent);
    expect(shown).toEqual(EXAMPLES.map((e) => e.text));
    expect(shown).toContain("is_active and (loyalty_tier = 'gold' or (loyalty_tier = 'silver' and amount >= 500))");
    expect(shown).toContain("price * qty > 1000");
    expect([...ref.querySelectorAll("a")].map((a) => a.getAttribute("href"))).toContain("#/docs/expressions/operators");
  });

  test("stays open as the first words go in, so it can be read while writing", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="" nodeType="filter" />);
    await act(async () => chip(el, "not").click());
    expect(JSON.parse(el.querySelector("#kept")!.textContent!)).toBe("not ");
    expect(el.querySelector<HTMLDetailsElement>(".cv-expr-help")!.open).toBe(true);
  });

  test("starts folded under a read's where, which is optional, so the panel stays short", async () => {
    const el = await mount(<Kept s={spec("where", "expression")} start="" nodeType="read" />);
    expect(el.querySelector<HTMLDetailsElement>(".cv-expr-help")!.open).toBe(false);
  });

  test("starts folded once the box has a condition", async () => {
    const el = await mount(<Kept s={spec("condition", "expression")} start="amount > 0" nodeType="filter" />);
    expect(el.querySelector<HTMLDetailsElement>(".cv-expr-help")!.open).toBe(false);
  });
});

describe("the columns leaving a node", () => {
  const outline = (columns: PipelineNodeOutline["columns"]): PipelineNodeOutline => ({
    id: "f",
    type: "filter",
    kind: "transform",
    line: 1,
    inputs: ["in"],
    outputs: Object.keys(columns),
    columns,
  });

  test("are listed by port, each column with its type", async () => {
    const el = await mount(
      <PortColumns
        label="Columns leaving f"
        outline={outline({
          out: [
            { name: "id", type: "int64" },
            { name: "status", type: "utf8" },
          ],
          rejected: [{ name: "id", type: "int64" }],
          errors: null,
        })}
      />,
    );
    const ports = [...el.querySelectorAll(".cv-port-cols")];
    expect(ports.map((p) => p.querySelector(".cv-port-name")!.textContent)).toEqual(["out", "rejected", "errors"]);
    expect([...ports[0].querySelectorAll(".cv-col-list li")].map((li) => li.textContent)).toEqual(["id int64", "status utf8"]);
    expect(ports[0].querySelector("summary")!.textContent).toContain("2 columns");
    expect(ports[2].textContent).toContain("columns known when it runs");
  });

  test("a port with many columns starts folded", async () => {
    const many = Array.from({ length: FOLD_ABOVE + 1 }, (_, i) => ({ name: `c${i}`, type: "utf8" }));
    const el = await mount(<PortColumns label="Columns" outline={outline({ out: many, rejected: many.slice(0, 2) })} />);
    const [out, rejected] = [...el.querySelectorAll("details")];
    expect(out.open).toBe(false);
    expect(rejected.open).toBe(true);
  });
});
