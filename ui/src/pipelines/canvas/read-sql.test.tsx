// @vitest-environment jsdom
import { afterEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import type { AccessRole, NodeTypeSetting, ReadSql } from "../../api";
import { RoleContext, UnmaskContext } from "../../components/role";
import { mayWriteQuery, SettingControl, type ControlContext } from "./SettingControls";
import { ReadSqlShown } from "./ReadSqlShown";

// A read's Query box and the SQL shown under it. The query is a person's own SQL (ADR 0028): the
// server saves one only from a designer whose token may see unmasked values, so the box is editable
// for them alone. The SQL shown is the server's, never rebuilt in the browser.

const QUERY: NodeTypeSetting = {
  key: "query",
  label: "Query",
  kind: "text",
  required: false,
  defaultValue: null,
  help: "One SELECT run as written, in place of a table (PostgreSQL sources with Allow SQL on). Its columns are known when it runs",
  choices: [],
};
const ctx: ControlContext = { columns: { main: null, lookup: null }, side: null, sources: [], writing: false, assets: null, nodeType: "read" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let root: Root | null = null;
let host: HTMLElement | null = null;
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  host?.remove();
  root = null;
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

async function mount(node: React.ReactNode) {
  host = document.createElement("div");
  document.body.append(host);
  root = createRoot(host);
  await act(async () => root!.render(node));
  return host;
}

function queryBox(role: AccessRole, unmask: boolean, onChange: (v: unknown) => void = () => {}, readOnly = false) {
  return (
    <RoleContext.Provider value={role}>
      <UnmaskContext.Provider value={unmask}>
        <SettingControl spec={QUERY} id="q" value="SELECT id FROM crm.customers" onChange={onChange} readOnly={readOnly} problems={[]} context={ctx} />
      </UnmaskContext.Provider>
    </RoleContext.Provider>
  );
}

describe("who may write a read's query", () => {
  test("only a designer whose token may see unmasked values, as the server checks on saving", () => {
    expect(mayWriteQuery("designer", true)).toBe(true);
    expect(mayWriteQuery("designer", false)).toBe(false);
    // Admins see values masked (ADR 0027), so the server refuses their queries too.
    expect(mayWriteQuery("admin", false)).toBe(false);
    expect(mayWriteQuery("steward", true)).toBe(false);
    expect(mayWriteQuery("viewer", false)).toBe(false);
  });

  test("the box is editable for that designer, with no note", async () => {
    const el = await mount(queryBox("designer", true));
    const box = el.querySelector("textarea")!;
    expect(box.readOnly).toBe(false);
    expect(box.placeholder).toContain("SELECT");
    expect(el.textContent).not.toContain("Read-only for you");
  });

  test.each([
    ["viewer", false],
    ["steward", false],
    ["designer", false],
    ["admin", false],
  ] as [AccessRole, boolean][])("a %s sees the query read-only, with why", async (role, unmask) => {
    const changes: unknown[] = [];
    const el = await mount(queryBox(role, unmask, (v: unknown) => changes.push(v)));
    const box = el.querySelector("textarea")!;
    expect(box.readOnly).toBe(true);
    expect(box.value).toBe("SELECT id FROM crm.customers");
    expect(box.placeholder).toBe("");
    const why = el.querySelector("#q-who")!;
    expect(why.textContent).toMatch(/only a designer whose token may see unmasked values writes or changes a query/i);
    expect(box.getAttribute("aria-describedby")).toContain("q-who");
    expect(changes).toEqual([]);
  });

  test("a designer who may not write one is told they cannot save the pipeline with it, and may take it out", async () => {
    const changes: unknown[] = [];
    const el = await mount(queryBox("designer", false, (v: unknown) => changes.push(v)));
    expect(el.querySelector("#q-who")!.textContent).toContain("only they can save or preview the pipeline");
    const remove = [...el.querySelectorAll("button")].find((b) => b.textContent === "Remove the query")!;
    await act(async () => remove.click());
    expect(changes).toEqual([undefined]);
  });

  test("on a read-only canvas the note says only why, with nothing to press", async () => {
    const el = await mount(queryBox("viewer", false, () => {}, true));
    expect(el.querySelector("#q-who")!.textContent).toMatch(/^Read-only: only a designer/);
    expect([...el.querySelectorAll("button")].length).toBe(0);
  });

  test("the help still says a query runs only on PostgreSQL sources with Allow SQL on", async () => {
    const el = await mount(queryBox("viewer", false));
    expect(el.querySelector("#q-help")!.textContent).toContain("PostgreSQL sources with Allow SQL on");
  });
});

describe("the SQL a read runs, as the server works it out", () => {
  const YAML = "kilnmark: pipeline/v1\nname: p\nnodes:\n  - {id: people, type: read, source: wh, asset: crm.customers}\n";

  test("is asked of the server with the YAML as it stands, and shown read-only with its bound values", async () => {
    const answer: ReadSql = {
      node: "people",
      sql: 'SELECT "id", "status" FROM "crm"."customers" WHERE "status" = ? LIMIT 10',
      values: 1,
      note: null,
    };
    const fetch = vi.fn(async () => json(answer));
    vi.stubGlobal("fetch", fetch);
    vi.useFakeTimers();
    const el = await mount(<ReadSqlShown id="s" yaml={YAML} node="people" query={false} />);
    expect(el.textContent).toContain("Working out the SQL");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    const call = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(call[0]).toBe("/api/v1/pipelines/read-sql");
    expect(JSON.parse(String(call[1].body))).toEqual({ yaml: YAML, node: "people" });
    const pre = el.querySelector("pre")!;
    expect(pre.textContent).toBe(answer.sql);
    expect(el.querySelector("#s-help")!.textContent).toContain("Each ? is a value from Where");
    expect(el.textContent).toContain("Read-only");
  });

  test("says why when there is none", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => json({ node: "people", sql: null, values: 0, note: "Shown once the table has been scanned: the SQL names its columns." })));
    vi.useFakeTimers();
    const el = await mount(<ReadSqlShown id="s" yaml={YAML} node="people" query={false} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(el.querySelector("pre")).toBeNull();
    expect(el.textContent).toContain("Shown once the table has been scanned");
  });

  test("keeps the last SQL in view while the next is worked out, so the settings under it stay put", async () => {
    let n = 0;
    vi.stubGlobal("fetch", vi.fn(async () => json({ node: "people", sql: `SELECT ${++n}`, values: 0, note: null })));
    vi.useFakeTimers();
    const el = await mount(<ReadSqlShown id="s" yaml={YAML} node="people" query={false} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(el.querySelector("pre")!.textContent).toBe("SELECT 1");
    await act(async () => root!.render(<ReadSqlShown id="s" yaml={YAML + "#"} node="people" query={false} />));
    expect(el.querySelector("pre")!.textContent).toBe("SELECT 1");
    expect(el.textContent).not.toContain("Working out");
    expect(el.querySelector("[data-setting=sql-shown]")!.getAttribute("aria-busy")).toBe("true");
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(el.querySelector("pre")!.textContent).toBe("SELECT 2");
    expect(el.querySelector("[data-setting=sql-shown]")!.hasAttribute("aria-busy")).toBe(false);
  });

  test("asks once the YAML has stayed still, not on every key", async () => {
    const fetch = vi.fn(async () => json({ node: "people", sql: "SELECT * FROM x", values: 0, note: null }));
    vi.stubGlobal("fetch", fetch);
    vi.useFakeTimers();
    await mount(<ReadSqlShown id="s" yaml={YAML} node="people" query={false} />);
    await act(async () => root!.render(<ReadSqlShown id="s" yaml={YAML + "#1"} node="people" query={false} />));
    await act(async () => root!.render(<ReadSqlShown id="s" yaml={YAML + "#12"} node="people" query={false} />));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
