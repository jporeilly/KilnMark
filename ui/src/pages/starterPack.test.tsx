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
  mode: "starter-pack",
  ready: true,
  notReady: null,
  database: "kilnmark_samples",
  files: "/srv/files/starter-pack",
  tablesRun: null,
  samples: 0,
  people: {
    add: "kilnmark.sh starter people   (Windows: kilnmark.cmd starter people)",
    remove: "kilnmark.sh starter people --remove   (Windows: kilnmark.cmd starter people -Remove)",
    signedIn: [],
  },
};

describe("the Starter Pack marking", () => {
  test("shows for everyone on a Starter Pack install, and says when the example data is on", async () => {
    server({
      "GET /api/v1/starter-pack": () =>
        json({ mode: "starter-pack", on: true, since: "2026-11-06T10:00:00Z", by: null }),
    });
    await show(<StarterPackBanner />);
    const banner = host.querySelector(".km-starter-banner");
    expect(banner?.textContent).toBe(
      "Starter Pack: training and development only. Its example data and accounts are on.",
    );
    expect(banner?.getAttribute("role")).toBe("status");
  });

  test("shows on a Starter Pack install while the pack is off too", async () => {
    server({ "GET /api/v1/starter-pack": () => json({ mode: "starter-pack", on: false, since: null, by: null }) });
    await show(<StarterPackBanner />);
    expect(host.querySelector(".km-starter-banner")?.textContent).toBe("Starter Pack: training and development only.");
  });

  test("is never on a production install, or when the server cannot be asked", async () => {
    server({ "GET /api/v1/starter-pack": () => json({ mode: "production", on: false, since: null, by: null }) });
    await show(<StarterPackBanner />);
    expect(host.querySelector(".km-starter-banner")).toBeNull();
    server({});
    await act(async () => window.dispatchEvent(new Event(STARTER_PACK_CHANGED)));
    expect(host.querySelector(".km-starter-banner")).toBeNull();
  });

  test("follows the pack when an admin turns it on", async () => {
    let on = false;
    server({ "GET /api/v1/starter-pack": () => json({ mode: "starter-pack", on, since: null, by: null }) });
    await show(<StarterPackBanner />);
    expect(host.querySelector(".km-starter-banner")?.textContent).not.toContain("are on");
    on = true;
    await act(async () => window.dispatchEvent(new Event(STARTER_PACK_CHANGED)));
    expect(host.querySelector(".km-starter-banner")?.textContent).toContain("are on");
  });
});

describe("Settings, Starter Pack", () => {
  test("shows the control script's commands for the example people, and who of them has signed in", async () => {
    server({
      "GET /api/v1/starter-pack/details": () =>
        json({ ...details, people: { ...details.people, signedIn: ["sso:alex.morgan@kilnmark.com (3f2a)"] } }),
    });
    await show(<StarterPackPage />);
    expect(host.textContent).toContain("kilnmark.sh starter people --remove");
    expect(host.textContent).toContain("Example accounts have signed in here: sso:alex.morgan@kilnmark.com (3f2a)");
    expect(host.textContent).toContain("turning the pack off here does not remove them");
    expect(host.textContent).not.toMatch(/password: /i);
  });

  test("lives under Settings", () => {
    expect(parse("#/settings/starter-pack")).toEqual({ page: "starter-pack" });
  });

  test("offers nothing on a production install, and says why", async () => {
    server({ "GET /api/v1/starter-pack/details": () => json({ ...details, mode: "production" }) });
    await show(<StarterPackPage />);
    expect([...host.querySelectorAll("button")].find((b) => b.textContent === "Turn on")).toBeUndefined();
    expect(host.textContent).toContain("This is a production install");
    expect(host.textContent).toContain("The templates are here");
    expect(host.textContent).not.toContain("Example people");
  });

  test("turns off only once DELETE is typed", async () => {
    const fetch = server({
      "GET /api/v1/starter-pack/details": () => json({ ...details, on: true }),
      "DELETE /api/v1/starter-pack": () => json({ on: false, steps: [], tablesRun: null }),
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

  test("shows each step, and never a password", async () => {
    await show(
      <Steps
        result={{
          on: true,
          steps: [
            { step: "database source", outcome: "done", detail: "added" },
            { step: "files source", outcome: "skipped", detail: "Not needed" },
          ],
          tablesRun: null,
        }}
      />,
    );
    expect(host.textContent).toContain("Database source");
    expect(host.textContent).toContain("Skipped");
    expect(host.textContent).not.toMatch(/password/i);
    expect(stepLabel("sample tables")).toBe("Sample tables");
  });
});
