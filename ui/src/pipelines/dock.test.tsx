// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { FlowParameter, NodeType, PipelineNodeRun, PipelineRun, PipelineRunDetail, PipelineRunProgress, SampleAbout } from "../api";
import { useTabsId } from "../components/ui";
import { RoleContext } from "../components/role";
import { DOCK_OPEN_KEY, PipelineDock, useDock, type DockTab } from "./PipelineDock";
import { PipelinePage } from "./PipelinePage";
import { runStatusSentence } from "./RunSummary";

const TABS: DockTab[] = ["settings", "preview", "run", "suggestions", "nodes", "runs", "versions"];

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

// The panel's own tabs (the page also has the Canvas and YAML tabs).
const tabs = () => [...host.querySelectorAll<HTMLButtonElement>('.pl-dock [role="tab"]')];
const tabNames = () => tabs().map((t) => t.textContent);
const tab = (name: string) => tabs().find((t) => t.textContent === name)!;
const selectedTab = () => host.querySelector<HTMLButtonElement>('.pl-dock [role="tab"][aria-selected="true"]')!;
const panel = () => host.querySelector<HTMLElement>('.pl-dock [role="tabpanel"]')!;
const key = (el: Element, k: string) =>
  act(async () => {
    el.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true }));
  });

describe("the panel under the canvas", () => {
  test("its tabs follow the tabs pattern, in order, and switch on a click", async () => {
    await show(<Panel />);
    expect(tabNames()).toEqual(["Settings", "Preview", "Run", "Suggestions", "Nodes", "History", "Versions"]);
    expect(host.querySelector('[role="tablist"]')?.getAttribute("aria-label")).toBe("Panels under the canvas");
    // One tab in the Tab order: the selected one.
    expect(tabs().map((t) => t.getAttribute("tabindex"))).toEqual(["0", "-1", "-1", "-1", "-1", "-1", "-1"]);
    expect(tabs().every((t) => t.getAttribute("aria-controls") === panel().id)).toBe(true);
    expect(panel().getAttribute("aria-labelledby")).toBe(tab("Settings").id);

    await act(async () => tab("History").click());
    expect(selectedTab().textContent).toBe("History");
    expect(tab("History").getAttribute("tabindex")).toBe("0");
    expect(panel().getAttribute("aria-labelledby")).toBe(tab("History").id);
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


/** A panel whose node tabs come and go, with a button inside that takes them away (as removing the last node does). */
function Coming() {
  const id = useTabsId();
  const dock = useDock(id);
  const [node, setNode] = useState(true);
  const tabs: DockTab[] = [...(node ? (["settings", "preview"] as const) : []), "run", "nodes"];
  const shown = tabs.includes(dock.tab) ? dock.tab : "nodes";
  return (
    <PipelineDock idBase={id} tabs={tabs} tab={shown} open={dock.open} onTab={dock.show} onOpen={dock.setOpen}>
      {shown === "settings" ? (
        <button type="button" onClick={() => setNode(false)}>
          Delete node
        </button>
      ) : (
        <p>Showing {shown}</p>
      )}
    </PipelineDock>
  );
}

describe("tabs that come and go", () => {
  test("when the tab shown goes with the element holding the focus, the focus moves to the tab shown instead", async () => {
    await show(<Coming />);
    const del = [...host.querySelectorAll("button")].find((b) => b.textContent === "Delete node")!;
    del.focus();
    await act(async () => del.click());
    expect(tabNames()).toEqual(["Run", "Nodes"]);
    expect(selectedTab().textContent).toBe("Nodes");
    expect(document.activeElement).toBe(tab("Nodes"));
    expect(tab("Nodes").getAttribute("tabindex")).toBe("0");
    expect(tab("Run").getAttribute("tabindex")).toBe("-1");
    expect(panel().getAttribute("aria-labelledby")).toBe(tab("Nodes").id);
  });
});

// ---- the pipeline page: the canvas, the panel under it and the Run tab ----

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
const AT = "2026-10-30T10:00:00Z";
const run = (id: number, status: PipelineRun["status"], extra: Partial<PipelineRun> = {}): PipelineRun => ({
  id,
  pipeline: "people-out",
  version: 3,
  status,
  requestedBy: "jp",
  requestedAt: AT,
  startedAt: status === "queued" ? null : AT,
  finishedAt: status === "queued" || status === "running" ? null : AT,
  cancelRequested: false,
  error: null,
  rowsWritten: status === "succeeded" ? 1200 : null,
  ...extra,
});
const nodeRun = (node: string, type: string, rowsIn: number | null, rowsOut: number | null, error: string | null = null): PipelineNodeRun => ({
  node,
  type,
  position: 0,
  rowsIn,
  rowsOut,
  ports: null,
  peakMemory: 2048,
  durationMs: 1500,
  error,
  outcome: error ? "failed" : "finished",
});
const runDetail = (r: PipelineRun, nodes: PipelineNodeRun[] = [], progress: PipelineRunProgress | null = null): PipelineRunDetail => ({
  run: r,
  nodes,
  written: [],
  read: [],
  lineage: [],
  notes: [],
  quality: [],
  progress,
});
const VERSION = { n: 3, yaml: YAML, message: "Tidy", author: "jp", createdAt: AT, valid: true, problems: 0 };
const VALIDATION = {
  valid: true,
  name: "people-out",
  description: null,
  problems: [],
  notes: [],
  nodes: [
    { id: "people", type: "read", kind: "source", line: 4, inputs: [], outputs: ["out"], columns: {} },
    { id: "out", type: "write", kind: "sink", line: 5, inputs: ["in"], outputs: [], columns: {} },
  ],
  edges: [{ fromNode: "people", fromPort: "out", toNode: "out", toPort: "in" }],
};

/** The server, as far as the page asks it: `runs` and `details` can be changed by a test. */
function server() {
  const state = {
    runs: [run(7, "succeeded")] as PipelineRun[],
    details: new Map<number, PipelineRunDetail[]>([[7, [runDetail(run(7, "succeeded"), [nodeRun("people", "read", null, 1200), nodeRun("out", "write", 1200, null)])]]]),
    asked: [] as string[],
    /** The parameters the saved version declares, and what the sample it came from explains. */
    parameters: [] as FlowParameter[],
    sample: null as SampleAbout | null,
    /** The bodies of the runs asked for, and the server's refusal of the next, if any. */
    runBodies: [] as unknown[],
    refuse: null as string | null,
  };
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const u = String(url);
    const method = init?.method ?? "GET";
    state.asked.push(`${method} ${u}`);
    const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    if (u.endsWith("/node-types")) return json(TYPES);
    if (u.includes("/sources")) return json([]);
    if (u.includes("/schedules")) return json([]);
    if (u.endsWith("/validate")) return json(VALIDATION);
    if (u.endsWith("/pipelines/people-out/runs") && method === "POST") {
      state.runBodies.push(JSON.parse(String(init?.body)));
      if (state.refuse) return json({ error: state.refuse }, 400);
      const r = run(8, "queued");
      state.runs = [r, ...state.runs];
      return json(r);
    }
    if (u.includes("/pipelines/people-out/runs")) return json(state.runs);
    if (u.endsWith("/pipelines/people-out/versions")) return json([VERSION]);
    if (u.endsWith("/pipelines/people-out"))
      return json({ name: "people-out", displayName: "People out", description: null, latestVersion: 3, createdBy: "jp", createdAt: AT, updatedBy: "jp", updatedAt: AT, latest: { ...VERSION, parameters: state.parameters }, lastRun: state.runs[0], library: state.sample ? "sample" : null, sample: state.sample });
    const m = /pipeline-runs\/(\d+)$/.exec(u);
    if (m) {
      const list = state.details.get(Number(m[1])) ?? [];
      // Each look takes the next answer; the last one stays.
      const d = list.length > 1 ? list.shift()! : list[0];
      return d ? json(d) : json({ error: "No such run" }, 404);
    }
    return json({ error: "Not a steward" }, 403);
  });
  vi.stubGlobal("fetch", fetch);
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  );
  vi.stubGlobal("CSS", { escape: (s: string) => s });
  vi.stubGlobal(
    "DOMMatrixReadOnly",
    class {
      m22 = 1;
    },
  );
  return state;
}

const page = () => (
  <RoleContext.Provider value="admin">
    <PipelinePage name="people-out" version={null} role="admin" />
  </RoleContext.Provider>
);
const nodeEl = (id: string) => host.querySelector<HTMLElement>(`.react-flow__node[data-id="${id}"]`)!;
const wait = (ms: number) => act(async () => new Promise((r) => setTimeout(r, ms)));
/** Waits (in real time) until the canvas has drawn its nodes and the run is shown. */
async function ready() {
  for (let i = 0; i < 100 && !(nodeEl("out") && host.querySelector(".cv-run-legend") && host.textContent?.includes("No problems")); i++) await wait(20);
  await wait(20);
}
const click = (el: Element, type = "click") => act(async () => void el.dispatchEvent(new MouseEvent(type, { bubbles: true })));

describe("the pipeline page's panel", () => {
  test("a click on a node shows its Settings without moving the focus; the node's tabs go when nothing is selected", async () => {
    server();
    await show(page());
    await ready();
    // Nothing selected: the pipeline's tabs (Settings its own), and Nodes shown.
    expect(tabNames()).toEqual(["Nodes", "Settings", "Run", "Suggestions", "History", "Schedules", "Versions"]);
    expect(selectedTab().textContent).toBe("Nodes");
    expect(host.querySelector(".cv-drawer")).toBeNull();

    const before = document.activeElement;
    await click(nodeEl("people"));
    expect(tabNames()).toEqual(["Nodes", "Settings", "Preview", "Run", "Suggestions", "History", "Schedules", "Versions"]);
    expect(selectedTab().textContent).toBe("Settings");
    expect(panel().querySelector("h3")?.textContent).toBe("people");
    expect(document.activeElement).toBe(before);
    expect(panel().contains(document.activeElement)).toBe(false);

    // The arrow keys select too: from Runs, Right moves to the next node and shows its settings.
    await act(async () => tab("History").click());
    nodeEl("people").focus();
    await key(nodeEl("people"), "ArrowRight");
    expect(selectedTab().textContent).toBe("Settings");
    expect(panel().querySelector("h3")?.textContent).toBe("out");

    // Escape clears the selection: Preview goes, Nodes is shown, the focus stays on the node.
    nodeEl("out").focus();
    await key(nodeEl("out"), "Escape");
    expect(tabNames()).toEqual(["Nodes", "Settings", "Run", "Suggestions", "History", "Schedules", "Versions"]);
    expect(selectedTab().textContent).toBe("Nodes");
    expect(document.activeElement).toBe(nodeEl("out"));
    expect(tabs().every((t) => t.getAttribute("aria-controls") === panel().id)).toBe(true);
    expect(tabs().filter((t) => t.getAttribute("tabindex") === "0")).toEqual([tab("Nodes")]);
  });

  test("Enter and Space on a node show its settings and move to the first field", async () => {
    server();
    await show(page());
    await ready();
    nodeEl("people").focus();
    await key(nodeEl("people"), " ");
    await wait(60);
    expect(selectedTab().textContent).toBe("Settings");
    expect((document.activeElement as HTMLInputElement).value).toBe("people");
  });

  test("the Run tab shows the run the canvas shows, node by node, linked to its page", async () => {
    server();
    await show(page());
    await ready();
    expect(host.querySelector(".cv-run-legend")?.textContent).toContain("Showing run 7");
    await act(async () => tab("Run").click());
    expect(panel().querySelector("h3")?.textContent).toBe("Run 7 of version 3");
    expect(panel().querySelector<HTMLAnchorElement>('a[href="#/pipeline-runs/7"]')).toBeTruthy();
    const facts = panel().querySelector(".pl-facts")!.textContent!;
    expect(facts).toContain("Rows read1,200");
    expect(facts).toContain("Rows written1,200");
    expect(panel().querySelector('[aria-label="Nodes of the run 7"]')?.textContent).toContain("people");
    // With Run the last of the pipeline's tabs shown, it is the one a deselection falls back to.
    await click(nodeEl("people"));
    expect(selectedTab().textContent).toBe("Settings");
    nodeEl("people").focus();
    await key(nodeEl("people"), "Escape");
    expect(selectedTab().textContent).toBe("Run");
  });

  test("Run now opens the Run tab and follows the run every two seconds until it ends", async () => {
    vi.useFakeTimers();
    try {
      window.localStorage.setItem(DOCK_OPEN_KEY, "collapsed");
      const state = server();
      const progress: PipelineRunProgress = {
        at: AT,
        slowest: "people",
        nodes: [
          { node: "people", state: "running", rowsIn: 0, rowsOut: 400, rowsPerSecond: 200 },
          { node: "out", state: "waiting", rowsIn: 400, rowsOut: 0, rowsPerSecond: 0 },
        ],
        edges: [],
      } as unknown as PipelineRunProgress;
      state.details.set(8, [
        runDetail(run(8, "queued")),
        runDetail(run(8, "running"), [], progress),
        runDetail(run(8, "failed", { error: "The write could not reach warehouse." }), [nodeRun("people", "read", null, 900), nodeRun("out", "write", 900, null, "Connection refused")]),
      ]);
      await show(page());
      await act(async () => vi.advanceTimersByTimeAsync(1000));
      expect(panel().hidden).toBe(true);

      const runNow = [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "Run now")!;
      await act(async () => runNow.click());
      await act(async () => vi.advanceTimersByTimeAsync(10));
      expect(selectedTab().textContent).toBe("Run");
      expect(panel().hidden).toBe(false);
      expect(panel().querySelector("h3")?.textContent).toContain("Run 8 of version 3");
      expect(panel().textContent).toContain("Queued");

      await act(async () => vi.advanceTimersByTimeAsync(2000));
      expect(panel().textContent).toContain("So far");
      expect(panel().querySelector('.pl-dock-run [role="status"]')?.textContent).toBe("Run 8 is running.");
      expect(panel().querySelector('[aria-label="Nodes of the run so far 8"]')?.textContent).toContain("Slowest");

      await act(async () => vi.advanceTimersByTimeAsync(2000));
      expect(panel().textContent).toContain("Why it failed: The write could not reach warehouse.");
      expect(panel().querySelector('[aria-label="Nodes of the run 8"]')?.textContent).toContain("Connection refused");
      expect(panel().textContent).toContain("Run 8 failed.");
      expect(panel().textContent).not.toContain("is now");

      const looks = () => state.asked.filter((a) => a === "GET /api/v1/pipeline-runs/8").length;
      const ended = looks();
      expect(ended).toBe(3);
      await act(async () => vi.advanceTimersByTimeAsync(10000));
      expect(looks()).toBe(ended);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe("the pipeline's own settings and the values a run is given", () => {
  const PARAMS: FlowParameter[] = [
    { name: "statuses", type: "text list", defaultValue: ["delivered"], defaultVariable: null, required: false, description: "The order statuses to keep" },
    { name: "min_fee", type: "number", defaultValue: 0, defaultVariable: null, required: false, description: null },
    { name: "label", type: "text", defaultValue: null, defaultVariable: "default_label", required: false, description: null },
  ];
  const SAMPLE: SampleAbout = {
    file: "07-parameters/orders-by-status.yaml",
    explanation: { title: "Parameters", shows: "Reads the shop orders in the statuses asked for.", why: "One pipeline replaces many near-copies.", lookFor: "the note under the read node.", needs: "shop-db and files." },
    changed: false,
  };
  const field = (name: string) => document.getElementById(`pl-run-values-${name}`) as HTMLInputElement;
  function type(el: HTMLInputElement, value: string) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(el, value);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  }

  test("with no node selected, Settings shows what the sample explains, then the parameters", async () => {
    const state = server();
    state.sample = { ...SAMPLE, changed: true };
    await show(page());
    await ready();
    // On the page, from the server's sample registry, with the guide.
    const about = host.querySelector<HTMLElement>('section[aria-labelledby="pl-sample-about"]')!;
    expect(about.querySelector("h2")?.textContent).toBe("About this sample" + "Based on a sample; changed since");
    expect([...about.querySelectorAll("dt")].map((d) => d.textContent)).toEqual(["What it shows", "Why it matters", "Look for when it runs", "Needs"]);
    expect(about.querySelector("dd")?.textContent).toBe("Reads the shop orders in the statuses asked for.");
    expect(about.querySelector<HTMLAnchorElement>('a[href="#/docs/library/samples"]')).toBeTruthy();

    await act(async () => tab("Settings").click());
    expect(selectedTab().textContent).toBe("Settings");
    expect(panel().querySelector("#cv-sample-about")?.textContent).toContain("About this sample");
    expect(panel().querySelector("#cv-params-h")?.textContent).toBe("Parameters");
    expect(panel().textContent).toContain("This pipeline declares no parameters.");
    expect(panel().querySelector("#cv-params-add")).toBeTruthy();
    // A node chosen shows its settings; deselected, the panel goes back to Nodes.
    await click(nodeEl("people"));
    expect(panel().querySelector("h3")?.textContent).toBe("people");
    nodeEl("people").focus();
    await key(nodeEl("people"), "Escape");
    expect(selectedTab().textContent).toBe("Nodes");
  });

  test("Run now asks for the values in the Run tab, filled with the defaults, and sends what was changed", async () => {
    const state = server();
    state.parameters = PARAMS;
    state.details.set(8, [runDetail(run(8, "queued"))]);
    await show(page());
    await ready();
    const runNow = [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "Run now")!;
    await act(async () => runNow.click());
    await wait(10);
    expect(selectedTab().textContent).toBe("Run");
    expect(state.runBodies).toEqual([]);
    expect(field("statuses").value).toBe("delivered");
    expect(field("min_fee").value).toBe("0");
    expect(field("label").value).toBe("");
    expect(document.activeElement).toBe(field("statuses"));

    // A value of the wrong kind is caught before anything is sent.
    await act(async () => type(field("min_fee"), "five"));
    const submit = [...panel().querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "Run with these values")!;
    await act(async () => submit.click());
    expect(state.runBodies).toEqual([]);
    expect(field("min_fee").getAttribute("aria-invalid")).toBe("true");

    // The server's refusal is shown beside the values.
    await act(async () => type(field("min_fee"), "5"));
    await act(async () => type(field("statuses"), "shipped, delivered"));
    state.refuse = "parameters: min_fee must be a number of at most 15 digits before the point and 30 in all";
    await act(async () => submit.click());
    await wait(10);
    expect(panel().querySelector('[role="alert"]')?.textContent).toBe(state.refuse);

    // Exactly what was changed, and nothing else: the label is left to its server variable.
    state.refuse = null;
    await act(async () => submit.click());
    await wait(10);
    expect(state.runBodies.at(-1)).toEqual({ version: null, parameters: { statuses: ["shipped", "delivered"], min_fee: 5 } });
    expect(panel().querySelector("h3")?.textContent).toContain("Values for the next run");
    expect(panel().textContent).toContain("Run 8 of version 3");
  });

  test("a pipeline without parameters runs at once, sending none", async () => {
    const state = server();
    await show(page());
    await ready();
    const runNow = [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === "Run now")!;
    await act(async () => runNow.click());
    await wait(10);
    expect(state.runBodies).toEqual([{ version: null }]);
    expect(panel().querySelector(".pl-run-values")).toBeNull();
  });
});

test("a run's status reads as a sentence in each state", () => {
  expect(["queued", "running", "succeeded", "failed", "cancelled"].map((st) => runStatusSentence(8, st))).toEqual([
    "Run 8 is queued.",
    "Run 8 is running.",
    "Run 8 succeeded.",
    "Run 8 failed.",
    "Run 8 was cancelled.",
  ]);
});
