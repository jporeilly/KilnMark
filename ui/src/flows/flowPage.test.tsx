// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { FlowRun } from "../api";
import { RoleContext } from "../components/role";
import { startInView } from "./FlowDiagram";
import { FlowPage } from "./FlowPage";

// The flow's page: Run now shows the run whichever tab was showing, and a saved change is no longer
// said or offered to undo.

const YAML = `kilnmark: flow/v1
name: nightly
nodes:
  - id: load
    type: run-pipeline
    pipeline: orders
  - id: done
    type: notify
    title: Loaded
    message: The orders are loaded.
connections:
  - { from: load, to: done }
`;
const AT = "2026-11-10T10:00:00Z";
const run = (id: number, status: FlowRun["status"]): FlowRun => ({
  id,
  flow: "nightly",
  version: 2,
  status,
  requestedBy: "jp",
  requestedRole: "admin",
  requestedAt: AT,
  startedAt: AT,
  finishedAt: status === "running" ? null : AT,
  cancelRequested: false,
  error: null,
  parameters: null,
});
const version = (n: number, yaml: string) => ({ n, yaml, message: "Tidy", author: "jp", createdAt: AT, valid: true, problems: [], parameters: [] });

/** The server, as far as the page asks it. */
function server() {
  const state = { runs: [run(7, "succeeded")] as FlowRun[], saved: [] as string[] };
  const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
  const detail = (latest: number, yaml: string) => ({
    name: "nightly", displayName: "Nightly", description: null, latestVersion: latest, createdBy: "jp", createdAt: AT, updatedBy: "jp", updatedAt: AT,
    latest: version(latest, yaml), lastRun: state.runs[0], library: null, sample: null,
  });
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      const u = String(url);
      const method = init?.method ?? "GET";
      if (u.includes("/schedules")) return json([]);
      if (u.includes("/owners")) return json({ owners: [], none: true, note: null });
      if (u.endsWith("validate")) return json({ valid: true, name: "nightly", problems: [], parameters: [] });
      if (u.endsWith("/flows/nightly/runs") && method === "POST") {
        const r = run(8, "running");
        state.runs = [r, ...state.runs];
        return json(r);
      }
      if (u.includes("/flows/nightly/runs")) return json(state.runs);
      if (u.endsWith("/flows/nightly/versions")) return json([version(2, YAML)]);
      if (u.endsWith("/flows/nightly") && method !== "GET") {
        const yaml = (JSON.parse(String(init?.body)) as { yaml: string }).yaml;
        state.saved.push(yaml);
        return json(detail(3, yaml));
      }
      if (u.endsWith("/flows/nightly")) return json(detail(2, YAML));
      const m = /flow-runs\/(\d+)$/.exec(u);
      if (m) return json({ run: state.runs.find((r) => r.id === Number(m[1])) ?? state.runs[0], nodes: [], tries: [] });
      if (u.endsWith("/api/v1/pipelines")) return json([]);
      return json({ error: "Not here" }, 404);
    }),
  );
  vi.stubGlobal("ResizeObserver", class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  vi.stubGlobal("CSS", { escape: (s: string) => s });
  // jsdom's dialog has no close(): Run now closes its values dialog (never opened here) before it reports the run.
  const dialog = HTMLDialogElement.prototype as { close?: () => void };
  dialog.close ??= () => {};
  vi.stubGlobal("DOMMatrixReadOnly", class {
    m22 = 1;
  });
  return state;
}

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

const wait = (ms: number) => act(async () => new Promise((r) => setTimeout(r, ms)));
const tab = (name: string) => [...host.querySelectorAll<HTMLButtonElement>('.pl-dock [role="tab"]')].find((t) => t.textContent === name)!;
const selectedTab = () => host.querySelector<HTMLButtonElement>('.pl-dock [role="tab"][aria-selected="true"]')!;
const button = (name: string) => [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent === name)!;

async function show() {
  root = createRoot(host);
  await act(async () =>
    root!.render(
      <RoleContext.Provider value="admin">
        <FlowPage name="nightly" version={null} role="admin" />
      </RoleContext.Provider>,
    ),
  );
  // Until the flow, its runs and its diagram are on the page (the diagram is loaded on its own).
  const drawn = () => !!tab("Schedules") && !!host.querySelector('.react-flow__node[data-id="load"]') && !!button("Add a note");
  for (let i = 0; i < 500 && !drawn(); i++) await wait(20);
  if (!drawn()) throw new Error("the flow's page did not draw within 10 s");
}

describe("the flow's page", { timeout: 20_000 }, () => {
  test("Run now pressed while the Schedules tab shows goes to the Run tab, where the run is", async () => {
    const state = server();
    await show();
    await act(async () => tab("Schedules").click());
    expect(selectedTab().textContent).toBe("Schedules");
    const runNow = host.querySelector<HTMLButtonElement>(".pl-head-actions button")!;
    expect(runNow.textContent).toContain("Run");
    await act(async () => runNow.click());
    for (let i = 0; i < 100 && (state.runs.length < 2 || selectedTab().textContent !== "Run"); i++) await wait(20);
    expect(state.runs[0].id).toBe(8);
    expect(selectedTab().textContent).toBe("Run");
    expect(host.querySelector(".pl-dock-panel")!.textContent).toContain("Run 8 of version 2");
  });

  test("once a change made on the diagram is saved, it is no longer said, nor offered to undo", async () => {
    const state = server();
    await show();
    await act(async () => button("Add a note").click());
    const said = () => host.querySelector("#fd-note")!.textContent;
    expect(said()).toContain("Added note 1");
    expect(button("Undo")).toBeTruthy();

    const message = host.querySelector<HTMLInputElement>("#fl-message")!;
    await act(async () => {
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!.call(message, "A note for the night shift");
      message.dispatchEvent(new Event("input", { bubbles: true }));
    });
    await act(async () => host.querySelector<HTMLFormElement>("form.pl-save")!.requestSubmit());
    for (let i = 0; i < 100 && state.saved.length < 1; i++) await wait(20);
    await wait(20);
    expect(state.saved).toHaveLength(1);
    expect(host.textContent).toContain("Saved as version 3.");
    expect(said()).toBe("");
    expect(button("Undo")).toBeUndefined();
  });
});

describe("where a flow's diagram starts", () => {
  test("a flow too wide to fit, centred with its start off the left, is moved so its start shows", () => {
    // Fitted at the least zoom, 0.5, the flow's left edge (x 16 in the flow) is drawn at -109.
    expect(startInView({ x: -117, y: 40, zoom: 0.5 }, 16)).toEqual({ x: 48, y: 40, zoom: 0.5 });
    // Its start is then 56px in: clear of the zoom buttons in the corner.
    expect(16 * 0.5 + 48).toBe(56);
  });

  test("a flow that fits is left as fitted", () => {
    const fitted = { x: 120, y: 40, zoom: 0.9 };
    expect(startInView(fitted, 16)).toBe(fitted);
    expect(startInView({ x: 0, y: 0, zoom: 1 }, 0)).toEqual({ x: 0, y: 0, zoom: 1 });
  });
});
