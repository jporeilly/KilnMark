// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useRef, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { NodeType } from "../../api";
import { RoleContext } from "../../components/role";
import type { DockControl } from "../PipelineDock";
import CanvasView from "./CanvasView";
import { NoteText, safeHref } from "./NoteText";
import { readNotes } from "./notes";
import { parseDocument } from "yaml";
import FlowDiagram from "../../flows/FlowDiagram";
import { readFlowGraph } from "../../flows/graph";

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
  vi.unstubAllGlobals();
});

async function show(node: React.ReactNode) {
  root = createRoot(host);
  await act(async () => root!.render(node));
}

const wait = (ms: number) => act(async () => new Promise((r) => setTimeout(r, ms)));
const key = (el: Element, k: string, opts: KeyboardEventInit = {}) =>
  act(async () => {
    el.dispatchEvent(new KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true, ...opts }));
  });

describe("a note's text is shown safely", () => {
  const render = async (text: string) => {
    await show(<NoteText text={text} />);
    return host;
  };

  test("bold, italic, lists and https links become elements; nothing else does", async () => {
    const el = await render("**Bold** and *italic*.\n\n- one\n- two\n\n1. first\n2. second\n\nSee [the guide](https://example.com/guide) or https://example.org.");
    expect(el.querySelector("strong")?.textContent).toBe("Bold");
    expect(el.querySelector("em")?.textContent).toBe("italic");
    expect([...el.querySelectorAll("ul li")].map((li) => li.textContent)).toEqual(["one", "two"]);
    expect([...el.querySelectorAll("ol li")].map((li) => li.textContent)).toEqual(["first", "second"]);
    const links = [...el.querySelectorAll("a")];
    expect(links.map((a) => a.getAttribute("href"))).toEqual(["https://example.com/guide", "https://example.org"]);
    for (const a of links) {
      expect(a.getAttribute("rel")).toBe("noopener noreferrer");
      expect(a.getAttribute("target")).toBe("_blank");
    }
  });

  test("raw HTML, scripts and images are shown as text, never as elements", async () => {
    const el = await render('<b>not bold</b> <script>alert(1)</script> <img src=x onerror="alert(1)"> ![a picture](https://example.com/p.png) <iframe src="https://example.com"></iframe>');
    for (const tag of ["b", "script", "img", "iframe"]) expect(el.querySelector(tag)).toBeNull();
    expect(el.textContent).toContain("<script>alert(1)</script>");
    expect(el.textContent).toContain('<img src=x onerror="alert(1)">');
    expect(el.textContent).toContain("![a picture](https://example.com/p.png)");
    expect(el.querySelectorAll("[onerror], [onclick], [src]")).toHaveLength(0);
  });

  test("a javascript:, data:, relative or disguised link is shown as text, never as a link", async () => {
    const el = await render(
      "[a](javascript:alert(1)) [b](JavaScript:alert(1)) [c](data:text/html,<script>alert(1)</script>) [d](/api/v1/tokens) " +
        "[e](jav&#x61;script:alert(1)) [f](  javascript:alert(1)) [g](//evil.example) [h](vbscript:x) [i](https:\\\\evil.example) <javascript:alert(1)>",
    );
    expect(el.querySelectorAll("a")).toHaveLength(0);
    expect(el.textContent).toContain("[a](javascript:alert(1))");
    expect(el.textContent).toContain("[c](data:text/html,<script>alert(1)</script>)");
    expect(el.querySelector("script")).toBeNull();
  });

  test("safeHref allows http and https addresses only", () => {
    expect(safeHref("https://example.com/a?b=javascript:x")).toBe("https://example.com/a?b=javascript:x");
    expect(safeHref("HTTP://EXAMPLE.COM")).toBe("HTTP://EXAMPLE.COM");
    for (const bad of ["javascript:alert(1)", " https://x.y", "https://x y", "https:\\\\x.y", "ftp://x.y", "mailto:a@b.c", "/x", "data:,x", "https://"]) {
      expect(safeHref(bad)).toBeNull();
    }
  });
});

// ---- notes on a flow's diagram: the same notes ----

const FLOW = `kilnmark: flow/v1
name: nightly
nodes:
  - id: load
    type: run-pipeline
    pipeline: people-out
    note: Runs first
  - id: done
    type: notify
    title: Loaded
connections:
  - { from: load, to: done }
notes:
  - text: Runs **every night**.
    node: load
    y: -160
    colour: green
`;

describe("notes on a flow's diagram", { timeout: 20_000 }, () => {
  const draw = async (editable: boolean) => {
    stubServer();
    const g = readFlowGraph(FLOW);
    if (!g.ok) throw new Error(g.error);
    const calls: string[] = [];
    await show(
      <FlowDiagram
        graph={g.graph}
        label="Diagram of the flow"
        editable={editable}
        onNoteUpdate={(i, p, l) => calls.push(`update ${i} ${JSON.stringify(p)} ${l}`)}
        onNoteRemove={(i, l) => calls.push(`remove ${i} ${l}`)}
      />,
    );
    for (let i = 0; i < 300 && !(notes().length && host.querySelector('.react-flow__node[data-id="done"]')); i++) await wait(20);
    return calls;
  };

  test("drawn as notes, not nodes, with the node's own note on its card", async () => {
    const calls = await draw(true);
    expect(host.querySelectorAll(".react-flow__node.react-flow__node-flowNode")).toHaveLength(2);
    const n = noteAt(0);
    expect(n.classList.contains("cv-note-green")).toBe(true);
    expect(n.getAttribute("aria-roledescription")).toBe("note");
    expect(n.querySelector("strong")?.textContent).toBe("every night");
    expect(host.querySelector('.react-flow__node[data-id="load"] .cv-node-note')?.textContent).toBe("Runs first");
    await key(n, "Delete");
    expect(calls).toEqual(["remove 0 Removed note 1."]);
  });

  test("read-only for those who cannot change the flow", async () => {
    const calls = await draw(false);
    const n = noteAt(0);
    await key(n, "Delete");
    await key(n, "Enter");
    expect(document.querySelector(".cv-note-editor")).toBeNull();
    expect(calls).toEqual([]);
  });
});

// ---- notes on the pipeline canvas ----

const TYPES: NodeType[] = [
  { type: "read", kind: "source", label: "Read", help: "", inputs: [], manyInputs: [], outputs: ["out"], settings: [] },
  { type: "write", kind: "sink", label: "Write", help: "", inputs: ["in"], manyInputs: [], outputs: [], settings: [] },
];
const YAML = `kilnmark: pipeline/v1
name: people-out
nodes:
  - id: people
    type: read
    source: lab-db
    asset: crm.people

  - id: out
    type: write
    note: Writes the tidy copy for the steward
    input: people
    source: files
    path: out.csv

notes:
  - text: |-
      **Reads** the people.

      - Then [read the guide](https://example.com/guide).
    node: people
    x: 0
    y: -160
`;

function stubServer() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string) => {
      const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
      const u = String(url);
      if (u.endsWith("/node-types")) return json(TYPES);
      if (u.includes("/sources")) return json([]);
      return json({ error: "Not here" }, 404);
    }),
  );
  vi.stubGlobal("ResizeObserver", class {
    observe() {}
    unobserve() {}
    disconnect() {}
  });
  vi.stubGlobal("CSS", { escape: (s: string) => s });
  vi.stubGlobal("DOMMatrixReadOnly", class {
    m22 = 1;
  });
}

/** The canvas with the text, undo and redo the page keeps for it. */
const state = { yaml: "", labels: [] as string[] };
function Harness({ initial, role }: { initial: string; role: "admin" | "viewer" }) {
  const [yaml, setYaml] = useState(initial);
  const undos = useRef<{ text: string; label: string }[]>([]);
  const redos = useRef<{ text: string; label: string }[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [dockHost, setDockHost] = useState<HTMLElement | null>(null);
  const memory = useRef({});
  state.yaml = yaml;
  const dock: DockControl = { tab: "settings", open: true, show: () => {}, host: dockHost, panel: () => dockHost };
  return (
    <RoleContext.Provider value={role}>
      <CanvasView
        yaml={yaml}
        onEdit={(next, label) => {
          undos.current.push({ text: yaml, label });
          redos.current = [];
          state.labels.push(label);
          setYaml(next);
        }}
        onUndo={() => {
          const u = undos.current.pop();
          if (!u) return null;
          redos.current.push({ text: yaml, label: u.label });
          setYaml(u.text);
          return u.label;
        }}
        onRedo={() => {
          const r = redos.current.pop();
          if (!r) return null;
          undos.current.push({ text: yaml, label: r.label });
          setYaml(r.text);
          return r.label;
        }}
        canUndo={undos.current.length > 0}
        canRedo={redos.current.length > 0}
        readOnly={role === "viewer"}
        check={null}
        selected={selected}
        onSelect={setSelected}
        focusRequest={null}
        run={null}
        title="people-out"
        memory={memory.current}
        dock={dock}
      />
      <div ref={setDockHost} className="dock-host" />
    </RoleContext.Provider>
  );
}

const notes = () => [...host.querySelectorAll<HTMLElement>(".cv-note")];
const noteAt = (i: number) => host.querySelector<HTMLElement>(`.cv-note[data-note-index="${i}"]`)!;
const notesInYaml = () => readNotes(parseDocument(state.yaml));
const button = (words: string) => [...host.querySelectorAll<HTMLButtonElement>("button")].find((b) => b.textContent?.trim() === words)!;
async function ready() {
  for (let i = 0; i < 300 && !(host.querySelector('.react-flow__node[data-id="out"]') && notes().length > 0); i++) await wait(20);
  if (!notes().length) throw new Error("the canvas did not draw its notes");
}
/** Types into a controlled field, as a person would. */
async function type(el: HTMLTextAreaElement, text: string) {
  await act(async () => {
    const set = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")!.set!;
    set.call(el, text);
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
}

describe("notes on the pipeline canvas", { timeout: 20_000 }, () => {
  test("drawn as notes, never as nodes: their own role and words, not counted, with no ports", async () => {
    stubServer();
    state.labels = [];
    await show(<Harness initial={YAML} role="admin" />);
    await ready();
    expect(host.querySelectorAll(".react-flow__node")).toHaveLength(2);
    const n = noteAt(0);
    expect(n.getAttribute("aria-roledescription")).toBe("note");
    expect(n.getAttribute("aria-label")).toBe("Note 1, on node people: Reads the people. - Then read the guide.");
    expect(n.getAttribute("tabindex")).toBe("0");
    expect(n.querySelector(".react-flow__handle")).toBeNull();
    expect(n.closest(".react-flow__node")).toBeNull();
    expect(n.querySelector("strong")?.textContent).toBe("Reads");
    expect(n.querySelector("a")?.getAttribute("rel")).toBe("noopener noreferrer");
    const region = host.querySelector<HTMLElement>(".cv-flow")!;
    expect(region.getAttribute("aria-label")).toContain("2 nodes and 1 note.");
    // A node's own note: on its card, all of it in the title, and in what its card says.
    const card = host.querySelector<HTMLElement>('.react-flow__node[data-id="out"]')!;
    expect(card.querySelector(".cv-node-note")?.textContent).toBe("Writes the tidy copy for the steward");
    expect(card.querySelector(".cv-node-note")?.getAttribute("title")).toBe("Writes the tidy copy for the steward");
    expect(card.getAttribute("aria-label")).toContain("Note: Writes the tidy copy for the steward");
  });

  test("added, written, moved and removed from the keyboard, each one undo step", async () => {
    stubServer();
    state.labels = [];
    await show(<Harness initial={YAML} role="admin" />);
    await ready();
    const before = state.yaml;

    // Add a note: its editor opens with the focus in it.
    await act(async () => button("Add a note").click());
    await wait(60);
    expect(notes()).toHaveLength(2);
    expect(notesInYaml()[1]).toMatchObject({ text: "", node: null });
    const area = document.querySelector<HTMLTextAreaElement>(".cv-note-editor textarea")!;
    expect(document.activeElement).toBe(area);
    await type(area, "Check the **totals** first");
    await key(area, "Escape");
    expect(document.querySelector(".cv-note-editor")).toBeNull();
    expect(notesInYaml()[1].text).toBe("Check the **totals** first");
    expect(noteAt(1).querySelector("strong")?.textContent).toBe("totals");
    expect(document.activeElement).toBe(noteAt(1));
    expect(state.labels.slice(-2)).toEqual(["Added note 2. Type what it says.", "Changed the text of note 2."]);

    // Enter edits again; Escape stops editing.
    await key(noteAt(1), "Enter");
    expect(document.querySelector(".cv-note-editor textarea")).toBeTruthy();
    await key(document.querySelector(".cv-note-editor textarea")!, "Escape");

    // Shift and the arrow keys move it: one change once the keys are still.
    const at = notesInYaml()[1];
    await key(noteAt(1), "ArrowRight", { shiftKey: true });
    await key(noteAt(1), "ArrowRight", { shiftKey: true });
    await key(noteAt(1), "ArrowDown", { shiftKey: true });
    await wait(500);
    expect(notesInYaml()[1]).toMatchObject({ x: at.x + 40, y: at.y + 20 });
    expect(state.labels.at(-1)).toBe("Moved note 2.");
    // With Ctrl too, it is resized.
    await key(noteAt(1), "ArrowRight", { shiftKey: true, ctrlKey: true });
    await wait(500);
    expect(notesInYaml()[1].width).toBe(at.width + 20);

    // Delete removes it; Undo puts it back, and undoes each change in turn.
    await key(noteAt(1), "Delete");
    expect(notes()).toHaveLength(1);
    expect(state.labels.at(-1)).toBe("Removed note 2.");
    await act(async () => button("Undo").click());
    expect(notes()).toHaveLength(2);
    for (let i = 0; i < 4; i++) await act(async () => button("Undo").click());
    expect(state.yaml).toBe(before);
    // No problems with notes: the YAML still holds the same nodes.
    expect(host.querySelectorAll(".react-flow__node")).toHaveLength(2);
  });

  test("a note is dragged to a new place in one change", async () => {
    stubServer();
    state.labels = [];
    await show(<Harness initial={YAML} role="admin" />);
    await ready();
    const n = noteAt(0);
    const fire = (type: string, x: number, y: number) =>
      act(async () => void n.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, clientX: x, clientY: y, button: 0 })));
    await fire("pointerdown", 100, 100);
    await fire("pointermove", 130, 150);
    await fire("pointermove", 160, 190);
    await fire("pointerup", 160, 190);
    expect(notesInYaml()[0]).toMatchObject({ node: "people", x: 60, y: -70 });
    expect(state.labels).toEqual(["Moved note 1."]);
  });

  test("a viewer reads the notes and can change none of them", async () => {
    stubServer();
    state.labels = [];
    await show(<Harness initial={YAML} role="viewer" />);
    await ready();
    expect(button("Add a note").disabled).toBe(true);
    const n = noteAt(0);
    expect(n.classList.contains("is-editable")).toBe(false);
    expect(n.querySelector(".cv-note-resize")).toBeNull();
    // Still focusable, to be read; links still work.
    expect(n.getAttribute("tabindex")).toBe("0");
    expect(n.querySelector("a")?.getAttribute("href")).toBe("https://example.com/guide");
    await key(n, "Enter");
    await act(async () => void n.dispatchEvent(new MouseEvent("dblclick", { bubbles: true })));
    expect(document.querySelector(".cv-note-editor")).toBeNull();
    await key(n, "Delete");
    await key(n, "ArrowRight", { shiftKey: true });
    await key(host.querySelector(".cv-flow")!, "n");
    await wait(500);
    await act(async () => void n.dispatchEvent(new MouseEvent("pointerdown", { bubbles: true, clientX: 0, clientY: 0, button: 0 })));
    await act(async () => void n.dispatchEvent(new MouseEvent("pointermove", { bubbles: true, clientX: 90, clientY: 90 })));
    await act(async () => void n.dispatchEvent(new MouseEvent("pointerup", { bubbles: true, clientX: 90, clientY: 90 })));
    expect(state.yaml).toBe(YAML);
    expect(state.labels).toEqual([]);
  });
});
