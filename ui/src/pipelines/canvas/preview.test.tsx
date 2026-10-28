// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act, useRef, useState } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { StepPreview } from "../../api";
import { StepMenu, type StepMenuItem } from "./StepMenu";
import { StepPreviewPanel, previewSummary, secondsWords, type PreviewAsk, type PreviewResult } from "./StepPreviewPanel";

const YAML = `kilnmark: pipeline/v1
name: people-tidy
steps:
  - {id: people, type: read, source: lab-db, asset: lab.people}
  - {id: tidy, type: compute, input: people, columns: {shout: "upper(name)"}}
`;

const VIEW: StepPreview = {
  step: "tidy",
  port: "out",
  asset: "tidy.out",
  columns: [
    { name: "id", kind: "int64", sourceType: null },
    { name: "name", kind: "text", sourceType: null },
    { name: "shout", kind: "text", sourceType: null },
  ],
  rows: [
    ["1", "••••", "••••"],
    ["2", "••••", null],
  ],
  rowCount: 2,
  truncated: false,
  details: {},
  complete: false,
  millis: 420,
  masked: ["name", "shout"],
  waits: ["Step spend (aggregate) reads every row it is given before it passes any on, so every row before it was read."],
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  host?.remove();
  root = null;
  vi.unstubAllGlobals();
});

async function mount(node: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

/** The panel with the canvas's state around it, as CanvasView keeps it. */
function Harness(props: {
  role?: "viewer" | "steward" | "admin";
  kind?: string | null;
  outputs?: string[];
  ask?: PreviewAsk | null;
  yaml?: string;
  onAsked?: () => void;
}) {
  const [last, setLast] = useState<PreviewResult | null>(null);
  return (
    <StepPreviewPanel
      yaml={props.yaml ?? YAML}
      step="tidy"
      outputs={props.outputs ?? ["out"]}
      kind={props.kind ?? "transform"}
      role={props.role ?? "steward"}
      ask={props.ask ?? null}
      last={last}
      onResult={setLast}
      onAsked={props.onAsked}
    />
  );
}

describe("previewSummary and secondsWords", () => {
  test("say how many rows, from where and how long, in words", () => {
    expect(secondsWords(420)).toBe("0.4 seconds");
    expect(secondsWords(1000)).toBe("1 second");
    expect(secondsWords(12_400)).toBe("12 seconds");
    expect(previewSummary(VIEW)).toBe("The first 2 rows leaving tidy, in 0.4 seconds.");
    expect(previewSummary({ ...VIEW, complete: true, port: "rejected" })).toBe("All 2 rows leaving tidy (rejected), in 0.4 seconds.");
    expect(previewSummary({ ...VIEW, rowCount: 0, rows: [] })).toBe("No rows left tidy. It took 0.4 seconds.");
    expect(previewSummary({ ...VIEW, truncated: true })).toContain("stopped at its size limit");
  });
});

describe("StepPreviewPanel", () => {
  test("previews the YAML as it stands and shows the rows, what was masked and why it waited", async () => {
    const fetch = vi.fn(async () => json(VIEW));
    vi.stubGlobal("fetch", fetch);
    const el = await mount(<Harness />);
    const go = [...el.querySelectorAll("button")].find((b) => b.textContent?.includes("Preview rows"))!;
    await act(async () => go.click());
    expect(fetch).toHaveBeenCalledTimes(1);
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe("/api/v1/pipelines/preview");
    expect(JSON.parse(init.body as string)).toEqual({ yaml: YAML, step: "tidy", port: "out", rows: 100 });
    expect(el.textContent).toContain("The first 2 rows leaving tidy, in 0.4 seconds.");
    expect(el.textContent).toContain("Values of name, shout are hidden");
    expect(el.textContent).toContain("Step spend (aggregate) reads every row");
    const table = el.querySelector("table.preview-table")!;
    expect([...table.querySelectorAll("th")].map((t) => t.textContent)).toEqual(["id int64", "name text", "shout text"]);
    expect(table.querySelector("caption")?.textContent).toContain("tidy.out");
    expect(el.textContent).toContain("Read live when you asked, and not kept.");
    expect(el.textContent).toContain("Preview again");
  });

  test("asks for the output chosen and the rows chosen", async () => {
    const fetch = vi.fn(async () => json({ ...VIEW, port: "rejected" }));
    vi.stubGlobal("fetch", fetch);
    const el = await mount(<Harness outputs={["out", "rejected"]} />);
    const [output, rows] = [...el.querySelectorAll("select")];
    expect([...output.options].map((o) => o.value)).toEqual(["out", "rejected"]);
    await act(async () => {
      output.value = "rejected";
      output.dispatchEvent(new Event("change", { bubbles: true }));
      rows.value = "10";
      rows.dispatchEvent(new Event("change", { bubbles: true }));
    });
    await act(async () => [...el.querySelectorAll("button")].find((b) => b.textContent?.includes("Preview rows"))!.click());
    expect(JSON.parse((fetch.mock.calls[0] as unknown as [string, RequestInit])[1].body as string)).toMatchObject({ port: "rejected", rows: 10 });
  });

  test("runs at once when the context menu asks, and says when the pipeline has changed since", async () => {
    const fetch = vi.fn(async () => json(VIEW));
    vi.stubGlobal("fetch", fetch);
    const el = await mount(<Harness ask={{ step: "tidy", n: 1 }} />);
    await act(async () => {});
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(el.textContent).not.toContain("has changed since");
    await act(async () => root!.render(<Harness ask={{ step: "tidy", n: 1 }} yaml={YAML + "# edited\n"} />));
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(el.textContent).toContain("The pipeline has changed since this preview.");
  });

  test("tells the canvas once it has acted on an ask, so switching tabs or steps does not run it again", async () => {
    const fetch = vi.fn(async () => json(VIEW));
    vi.stubGlobal("fetch", fetch);
    const asked = vi.fn();
    // As the canvas does: the ask is cleared once used, so a fresh panel (another tab, then back) has none.
    function Canvas() {
      const [ask, setAsk] = useState<PreviewAsk | null>({ step: "tidy", n: 1 });
      const [tab, setTab] = useState("preview");
      return (
        <>
          <button type="button" onClick={() => setTab(tab === "preview" ? "settings" : "preview")}>switch</button>
          {tab === "preview" && (
            <Harness
              ask={ask}
              onAsked={() => {
                asked();
                setAsk(null);
              }}
            />
          )}
        </>
      );
    }
    const el = await mount(<Canvas />);
    await act(async () => {});
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(asked).toHaveBeenCalledTimes(1);
    const sw = [...el.querySelectorAll("button")].find((b) => b.textContent === "switch")!;
    await act(async () => sw.click());
    await act(async () => sw.click());
    await act(async () => {});
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  test("a second Preview rows from the menu runs again after the first ask was cleared", async () => {
    const fetch = vi.fn(async () => json(VIEW));
    vi.stubGlobal("fetch", fetch);
    let ask: (() => void) | null = null;
    function Canvas() {
      const count = useRef(0);
      const [a, setA] = useState<PreviewAsk | null>(null);
      ask = () => {
        count.current += 1;
        setA({ step: "tidy", n: count.current });
      };
      return <Harness ask={a} onAsked={() => setA(null)} />;
    }
    await mount(<Canvas />);
    await act(async () => ask!());
    await act(async () => {});
    await act(async () => ask!());
    await act(async () => {});
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  test("shows the server's refusal as an alert", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ error: "Only an admin can preview this: step ps (filter) chooses rows by name" }, 403)));
    const el = await mount(<Harness />);
    await act(async () => [...el.querySelectorAll("button")].find((b) => b.textContent?.includes("Preview rows"))!.click());
    expect(el.querySelector("[role=alert]")?.textContent).toContain("Only an admin can preview this");
  });

  test("viewers and writes get words, not a button", async () => {
    const fetch = vi.fn();
    vi.stubGlobal("fetch", fetch);
    let el = await mount(<Harness role="viewer" />);
    expect(el.textContent).toContain("for stewards and admins");
    expect(el.querySelector("button")).toBeNull();
    await act(async () => root!.render(<Harness kind="sink" outputs={[]} />));
    el = host!;
    expect(el.textContent).toContain("nothing to preview here");
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("StepMenu", () => {
  const items = (picked: string[]): StepMenuItem[] => [
    { id: "preview", label: "Preview rows", onSelect: () => picked.push("preview") },
    { id: "settings", label: "Settings", onSelect: () => picked.push("settings") },
    { id: "remove", label: "Remove", onSelect: () => picked.push("remove"), disabled: true, note: "Read-only here" },
  ];

  test("is a menu the keyboard can use: focus on the first item, arrows, Home and End, Escape", async () => {
    const picked: string[] = [];
    const closed: boolean[] = [];
    const el = await mount(<StepMenu step="tidy" at={{ x: 10, y: 10 }} items={items(picked)} onClose={(back) => closed.push(back)} />);
    const menu = el.querySelector("[role=menu]")!;
    expect(menu.getAttribute("aria-label")).toBe("Actions for step tidy");
    const buttons = [...menu.querySelectorAll<HTMLButtonElement>("[role=menuitem]")];
    expect(document.activeElement).toBe(buttons[0]);
    const key = (k: string, shift = false) => act(async () => { menu.dispatchEvent(new KeyboardEvent("keydown", { key: k, shiftKey: shift, bubbles: true })); });
    await key("ArrowDown");
    expect(document.activeElement).toBe(buttons[1]);
    await key("End");
    expect(document.activeElement).toBe(buttons[2]);
    expect(buttons[2].getAttribute("aria-disabled")).toBe("true");
    expect(buttons[2].textContent).toContain("Read-only here");
    await act(async () => buttons[2].click());
    expect(picked).toEqual([]);
    await key("ArrowDown");
    expect(document.activeElement).toBe(buttons[0]); // wraps round
    await key("Home");
    await act(async () => buttons[0].click());
    expect(picked).toEqual(["preview"]);
    await key("Escape");
    expect(closed).toEqual([false, true]);
  });
});
