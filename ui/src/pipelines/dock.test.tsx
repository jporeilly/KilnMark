// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useMemo, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { NodeType } from "../api";
import { useTabsId } from "../components/ui";
import { RoleContext } from "../components/role";
import { DOCK_OPEN_KEY, PipelineDock, useDock, type DockControl, type DockTab } from "./PipelineDock";
import CanvasView from "./canvas/CanvasView";

const TABS: DockTab[] = ["settings", "preview", "suggestions", "nodes", "runs", "versions"];

let root: Root | null = null;
let host: HTMLDivElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.append(host);
});

afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = null;
  document.body.innerHTML = "";
  window.localStorage.clear();
  vi.unstubAllGlobals();
});

async function show(node: React.ReactNode) {
  root = createRoot(host);
  await act(async () => root!.render(node));
}

/** The page's panel with plain words in its non-canvas tabs. */
function Panel() {
  const id = useTabsId();
  const dock = useDock(id);
  return (
    <PipelineDock idBase={id} tabs={TABS} tab={dock.tab} open={dock.open} onTab={dock.show} onOpen={dock.setOpen}>
      <p>Showing {dock.tab}</p>
    </PipelineDock>
  );
}

const tab = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('[role="tab"]')].find((t) => t.textContent === name)!;
const selectedTab = () => host.querySelector<HTMLButtonElement>('[role="tab"][aria-selected="true"]')!;
const panel = () => host.querySelector<HTMLElement>('[role="tabpanel"]')!;
const key = (el: Element, k: string) =>
  act(async () => {
    el.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true }));
  });

describe("the panel under the canvas", () => {
  test("its tabs follow the tabs pattern, in order, and switch on a click", async () => {
    await show(<Panel />);
    const tabs = [...host.querySelectorAll('[role="tab"]')];
    expect(tabs.map((t) => t.textContent)).toEqual(["Settings", "Preview", "Suggestions", "Nodes", "Runs", "Versions"]);
    expect(host.querySelector('[role="tablist"]')?.getAttribute("aria-label")).toBe("Panels under the canvas");
    // One tab in the Tab order: the selected one.
    expect(tabs.map((t) => t.getAttribute("tabindex"))).toEqual(["0", "-1", "-1", "-1", "-1", "-1"]);
    expect(tabs.every((t) => t.getAttribute("aria-controls") === panel().id)).toBe(true);
    expect(panel().getAttribute("aria-labelledby")).toBe(tab("Settings").id);

    await act(async () => tab("Runs").click());
    expect(selectedTab().textContent).toBe("Runs");
    expect(tab("Runs").getAttribute("tabindex")).toBe("0");
    expect(panel().getAttribute("aria-labelledby")).toBe(tab("Runs").id);
    expect(panel().textContent).toBe("Showing runs");
  });

  test("Left, Right, Home and End move between the tabs, show them and move the focus", async () => {
    await show(<Panel />);
    tab("Settings").focus();
    await key(tab("Settings"), "ArrowRight");
    expect(selectedTab().textContent).toBe("Preview");
    expect(document.activeElement).toBe(tab("Preview"));
    await key(tab("Preview"), "End");
    expect(selectedTab().textContent).toBe("Versions");
    await key(tab("Versions"), "ArrowRight");
    expect(selectedTab().textContent).toBe("Settings");
    await key(tab("Settings"), "ArrowLeft");
    expect(selectedTab().textContent).toBe("Versions");
    await key(tab("Versions"), "Home");
    expect(selectedTab().textContent).toBe("Settings");
    expect(document.activeElement).toBe(tab("Settings"));
    expect(panel().textContent).toBe("Showing settings");
  });

  test("it collapses and expands, remembers that per viewer, and a tab chosen opens it", async () => {
    await show(<Panel />);
    const toggle = () => host.querySelector<HTMLButtonElement>(".pl-dock-toggle")!;
    expect(toggle().getAttribute("aria-expanded")).toBe("true");
    expect(toggle().getAttribute("aria-controls")).toBe(panel().id);
    await act(async () => toggle().click());
    expect(toggle().getAttribute("aria-expanded")).toBe("false");
    expect(panel().hidden).toBe(true);
    expect(window.localStorage.getItem(DOCK_OPEN_KEY)).toBe("collapsed");

    // Shown again collapsed.
    await act(async () => root!.unmount());
    await show(<Panel />);
    expect(panel().hidden).toBe(true);
    expect(toggle().textContent).toContain("Expand");

    await act(async () => tab("Nodes").click());
    expect(panel().hidden).toBe(false);
    expect(panel().textContent).toBe("Showing nodes");
    expect(window.localStorage.getItem(DOCK_OPEN_KEY)).toBe("open");
  });

  test("works without browser storage", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    await show(<Panel />);
    expect(panel().hidden).toBe(false);
    await act(async () => host.querySelector<HTMLButtonElement>(".pl-dock-toggle")!.click());
    expect(panel().hidden).toBe(true);
    vi.restoreAllMocks();
  });
});

// ---- the canvas fills the Settings, Preview and Suggestions tabs ----

const TYPES: NodeType[] = [
  { type: "read", kind: "source", label: "Read", help: "", inputs: [], manyInputs: [], outputs: ["out"], settings: [] },
  { type: "write", kind: "sink", label: "Write", help: "", inputs: ["in"], manyInputs: [], outputs: [], settings: [] },
];
const YAML = `kilnmark: pipeline/v1
name: people-out
nodes:
  - {id: people, type: read, source: lab-db, asset: crm.people}
  - {id: out, type: write, input: people, source: warehouse, table: crm.people_out}
`;

/** The page's part: the panel, with the canvas drawing into it. */
function Page() {
  const id = useTabsId();
  const dock = useDock(id);
  const [hostEl, setHostEl] = useState<HTMLDivElement | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const control: DockControl = useMemo(
    () => ({ tab: dock.tab, open: dock.open, show: dock.show, host: hostEl, panel: dock.panel }),
    [dock.tab, dock.open, dock.show, hostEl, dock.panel],
  );
  return (
    <RoleContext.Provider value="designer">
      <CanvasView
        yaml={YAML}
        onEdit={() => {}}
        onUndo={() => null}
        onRedo={() => null}
        canUndo={false}
        canRedo={false}
        readOnly={false}
        check={null}
        selected={selected}
        onSelect={setSelected}
        focusRequest={null}
        run={null}
        title="people-out"
        memory={{}}
        dock={control}
      />
      <PipelineDock idBase={id} tabs={TABS} tab={dock.tab} open={dock.open} onTab={dock.show} onOpen={dock.setOpen}>
        <div ref={setHostEl} className="pl-dock-host" hidden={!["settings", "preview", "suggestions"].includes(dock.tab)} />
        {dock.tab === "runs" && <p>The runs</p>}
      </PipelineDock>
    </RoleContext.Provider>
  );
}

function stubBrowser() {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal(
    "DOMMatrixReadOnly",
    class {
      m22 = 1;
    },
  );
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const u = String(url);
      const body = u.includes("node-types") ? TYPES : u.includes("sources") ? [] : null;
      return new Response(JSON.stringify(body ?? { error: "Not a steward" }), { status: body ? 200 : 403, headers: { "Content-Type": "application/json" } });
    }),
  );
}

const nodeEl = (id: string) => host.querySelector<HTMLElement>(`.react-flow__node[data-id="${id}"]`)!;
const wait = (ms: number) => act(async () => new Promise((r) => setTimeout(r, ms)));

describe("the canvas and the panel under it", () => {
  test("Enter on a node shows its settings and moves to its first field; nothing selected says how", async () => {
    stubBrowser();
    await show(<Page />);
    await wait(30);
    expect(nodeEl("people")).toBeTruthy();
    // Nothing covers the canvas: no drawer.
    expect(host.querySelector(".cv-drawer")).toBeNull();
    expect(panel().textContent).toContain("No node is selected");

    await act(async () => tab("Runs").click());
    expect(panel().textContent).toContain("The runs");

    nodeEl("people").focus();
    await key(nodeEl("people"), "Enter");
    await wait(60);
    expect(selectedTab().textContent).toBe("Settings");
    expect(panel().querySelector("h3")?.textContent).toBe("people");
    expect(panel().contains(document.activeElement)).toBe(true);
    expect((document.activeElement as HTMLInputElement).value).toBe("people");
  });

  test("a double-click shows the settings even when the panel was collapsed; a plain click keeps the tab", async () => {
    stubBrowser();
    window.localStorage.setItem(DOCK_OPEN_KEY, "collapsed");
    await show(<Page />);
    await wait(30);
    expect(panel().hidden).toBe(true);
    await act(async () => tab("Versions").click());
    await act(async () => host.querySelector<HTMLButtonElement>(".pl-dock-toggle")!.click());
    expect(panel().hidden).toBe(true);

    await act(async () => nodeEl("out").dispatchEvent(new MouseEvent("click", { bubbles: true })));
    expect(selectedTab().textContent).toBe("Versions");

    await act(async () => nodeEl("out").dispatchEvent(new MouseEvent("dblclick", { bubbles: true })));
    expect(selectedTab().textContent).toBe("Settings");
    expect(panel().hidden).toBe(false);
    expect(panel().querySelector("h3")?.textContent).toBe("out");
  });

  test("Suggestions on the toolbar shows its tab; Shift+F10 then Preview rows shows the preview", async () => {
    stubBrowser();
    await show(<Page />);
    await wait(30);
    const suggest = [...host.querySelectorAll<HTMLButtonElement>(".cv-tool")].find((b) => b.textContent?.includes("Suggestions"))!;
    await act(async () => suggest.click());
    await wait(60);
    expect(selectedTab().textContent).toBe("Suggestions");
    expect(panel().textContent).toContain("Nodes that would raise the predicted quality");
    expect(document.activeElement?.id).toBe("cv-suggest-h");

    nodeEl("people").focus();
    await act(async () => {
      nodeEl("people").dispatchEvent(new KeyboardEvent("keydown", { key: "F10", shiftKey: true, bubbles: true, cancelable: true }));
    });
    const item = [...document.querySelectorAll<HTMLElement>('[role="menuitem"]')].find((b) => b.textContent?.includes("Preview rows"));
    expect(item).toBeTruthy();
    await act(async () => item!.click());
    await wait(60);
    expect(selectedTab().textContent).toBe("Preview");
    expect(panel().textContent).toContain("Preview of people");
    expect(document.activeElement).toBe(panel());
  });
});
