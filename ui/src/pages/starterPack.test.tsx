// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { STARTER_PACK_CHANGED, StarterPackBanner } from "../shell/StarterPackBanner";
import { CONFIRM, StarterPackPage, Steps, stepLabel } from "./StarterPackPage";
import { parse } from "../router";
import type { StarterPackDetails } from "../api";

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

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

/** A server whose Starter Pack is on or off, answering every other call with an error. */
function server(answers: Record<string, () => Response>) {
  const fetch = vi.fn(async (url: string, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${url}`;
    const answer = answers[key];
    return answer ? answer() : json({ error: `not expected: ${key}` }, 500);
  });
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

const details: StarterPackDetails = {
  on: false,
  since: null,
  by: null,
  production: false,
  ready: true,
  notReady: null,
  database: "kilnmark_samples",
  files: "/srv/files/starter-pack",
  tablesRun: null,
  samples: 0,
  people: { allowed: false, why: "Not managed here: the example people need KILNMARK_STARTER=allowed", present: [], presentError: null },
};

describe("the Starter Pack banner", () => {
  test("shows for everyone while the server says the pack is on", async () => {
    server({ "GET /api/v1/starter-pack": () => json({ on: true, since: "2026-11-06T10:00:00Z", by: "admin" }) });
    await show(<StarterPackBanner />);
    const banner = host.querySelector(".km-starter-banner");
    expect(banner?.textContent).toBe("Starter Pack: example data and accounts, not for production.");
    expect(banner?.getAttribute("role")).toBe("status");
  });

  test("is not there while the pack is off, or when the server cannot be asked", async () => {
    server({ "GET /api/v1/starter-pack": () => json({ on: false, since: null, by: null }) });
    await show(<StarterPackBanner />);
    expect(host.querySelector(".km-starter-banner")).toBeNull();
    server({});
    await act(async () => window.dispatchEvent(new Event(STARTER_PACK_CHANGED)));
    expect(host.querySelector(".km-starter-banner")).toBeNull();
  });

  test("follows the pack when an admin turns it on", async () => {
    let on = false;
    server({ "GET /api/v1/starter-pack": () => json({ on, since: null, by: null }) });
    await show(<StarterPackBanner />);
    expect(host.querySelector(".km-starter-banner")).toBeNull();
    on = true;
    await act(async () => window.dispatchEvent(new Event(STARTER_PACK_CHANGED)));
    expect(host.querySelector(".km-starter-banner")).not.toBeNull();
  });
});

describe("Settings, Starter Pack", () => {
  test("lives under Settings", () => {
    expect(parse("#/settings/starter-pack")).toEqual({ page: "starter-pack" });
  });

  test("cannot be turned on on an install marked production, and says why", async () => {
    server({ "GET /api/v1/starter-pack/details": () => json({ ...details, production: true }) });
    await show(<StarterPackPage />);
    const on = [...host.querySelectorAll("button")].find((b) => b.textContent === "Turn on")!;
    expect(on.disabled).toBe(true);
    expect(host.textContent).toContain("marked as production");
  });

  test("turns off only once DELETE is typed", async () => {
    const fetch = server({
      "GET /api/v1/starter-pack/details": () => json({ ...details, on: true }),
      "DELETE /api/v1/starter-pack": () => json({ on: false, steps: [], tablesRun: null, people: [], peoplePassword: null }),
      "GET /api/v1/starter-pack": () => json({ on: false, since: null, by: null }),
    });
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.open = true;
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.open = false;
    };
    await show(<StarterPackPage />);
    const go = [...host.querySelectorAll("button")].find((b) => b.textContent === "Turn off and remove")!;
    expect(go.disabled).toBe(true);
    const input = host.querySelector<HTMLInputElement>("dialog input")!;
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value")!.set!;
    await act(async () => {
      setValue.call(input, "delete");
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(go.disabled).toBe(true);
    await act(async () => {
      setValue.call(input, CONFIRM);
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(go.disabled).toBe(false);
    await act(async () => go.click());
    const call = fetch.mock.calls.find(([, init]) => init?.method === "DELETE")!;
    expect(JSON.parse(String(call[1]!.body))).toEqual({ confirm: "DELETE" });
  });

  test("shows each step, and the people's password once", async () => {
    await show(
      <Steps
        result={{
          on: true,
          steps: [
            { step: "database source", outcome: "done", detail: "added" },
            { step: "example people", outcome: "skipped", detail: "Not managed here" },
          ],
          tablesRun: null,
          people: [],
          peoplePassword: "abc-123_XYZ-456_def-789x",
        }}
      />,
    );
    expect(host.textContent).toContain("Database source");
    expect(host.textContent).toContain("Skipped");
    expect(host.textContent).toContain("abc-123_XYZ-456_def-789x");
    expect(host.textContent).toContain("Kilnmark keeps it nowhere");
    expect(stepLabel("sample tables")).toBe("Sample tables");
  });
});
