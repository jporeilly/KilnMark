// @vitest-environment jsdom
import { afterEach, describe, expect, test } from "vitest";
import { useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import { flushSync } from "react-dom";
import userEvent from "@testing-library/user-event";
import type { NodeTypeSetting } from "../../api";
import type { Value } from "./model";
import { SettingControl, type ControlContext } from "./SettingControls";

const spec: NodeTypeSetting = { key: "rules", label: "Rules", kind: "rules", required: false, defaultValue: null, help: "Rules", choices: [] };
const context: ControlContext = {
  columns: { main: [{ name: "email", type: "text" }, { name: "amount", type: "decimal" }], lookup: null },
  side: null,
  sources: null,
  writing: false,
  assets: null,
  nodeType: "gate",
};

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(() => {
  root?.unmount();
  host?.remove();
  root = null;
});

/** The rules control as the canvas holds it: the value it writes comes back as its value. */
function mount(initial: Value | undefined) {
  const written: (Value | undefined)[] = [];
  function Holder() {
    const [value, setValue] = useState<Value | undefined>(initial);
    return (
      <SettingControl
        spec={spec}
        id="r"
        value={value}
        onChange={(v) => {
          written.push(v);
          setValue(v);
        }}
        readOnly={false}
        problems={[]}
        context={context}
      />
    );
  }
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  flushSync(() => root!.render(<Holder />));
  return written;
}

const byId = (id: string) => document.getElementById(id) as HTMLInputElement;

describe("typing in the rules editor", () => {
  test("a share typed one key at a time is written once, as typed", async () => {
    const written = mount([{ type: "not_null", field: "email" }]);
    const user = userEvent.setup();
    await user.click(byId("r-0-fail"));
    for (const key of "0.05") await user.keyboard(key);
    expect(byId("r-0-fail").value).toBe("0.05");
    expect(written).toEqual([]); // nothing is written while typing
    await user.tab();
    expect(written).toEqual([[{ type: "not_null", field: "email", fail_above: 0.05 }]]);
    expect(byId("r-0-fail").value).toBe("0.05");
  });

  test("a bound typed key by key keeps its digits, and Enter writes it", async () => {
    const written = mount([{ type: "in_range", field: "amount" }]);
    const user = userEvent.setup();
    await user.click(byId("r-0-max"));
    for (const key of "10.50") await user.keyboard(key);
    await user.keyboard("{Enter}");
    expect(written.at(-1)).toEqual([{ type: "in_range", field: "amount", max: 10.5 }]);
    expect(byId("r-0-max").value).toBe("10.50"); // still as typed while it has the focus
  });

  test("removing a rule moves the focus to the rule in its place, then to Add a rule", async () => {
    mount([{ type: "not_null", field: "email" }, { type: "unique", field: "amount" }]);
    const user = userEvent.setup();
    await user.click(document.querySelector<HTMLButtonElement>('[aria-label="Remove rule 1"]')!);
    await new Promise((r) => setTimeout(r, 50));
    expect(document.activeElement?.id).toBe("r-0-type");
    expect((document.activeElement as HTMLSelectElement).value).toBe("unique");
    await user.click(document.querySelector<HTMLButtonElement>('[aria-label="Remove rule 1"]')!);
    await new Promise((r) => setTimeout(r, 50));
    expect(document.activeElement?.id).toBe("r-add");
  });
});
