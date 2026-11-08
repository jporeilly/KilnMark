// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test } from "vitest";
import type { Schedule } from "../api";
import { SchedulesTable } from "../schedules/SchedulesTable";
import { whoParts } from "./Who";

/** A stylesheet's text, read from disk: in tests a CSS import is empty. Node's fs, untyped here (no Node types in the UI). */
async function stylesheet(path: string): Promise<string> {
  const fs = await import(/* @vite-ignore */ ["node", "fs"].join(":"));
  // Relative to ui/, where the tests run.
  return fs.readFileSync(path, "utf8");
}

const UUID = "2f6c1d0e-8a4b-4c3d-9e2f-0123456789ab";

describe("who did something reads the same everywhere", () => {
  test("a token is shown by its name, whichever way the server wrote it", () => {
    expect(whoParts("token 1 (KILNMARK_ADMIN_TOKEN)").shown).toBe("KILNMARK_ADMIN_TOKEN");
    expect(whoParts("KILNMARK_ADMIN_TOKEN").shown).toBe("KILNMARK_ADMIN_TOKEN");
    expect(whoParts("token 1 (KILNMARK_ADMIN_TOKEN)").full).toBe("token 1 (KILNMARK_ADMIN_TOKEN)");
  });

  test("a provider sign-in keeps its sso: name and leaves the provider's ID to the tooltip", () => {
    expect(whoParts(`sso:dana.reyes@kilnmark.com (${UUID})`)).toEqual({
      shown: "sso:dana.reyes@kilnmark.com",
      full: `sso:dana.reyes@kilnmark.com (${UUID})`,
    });
    // Without brackets, or a name that only looks like a token's: as it is.
    expect(whoParts("sso:dana").shown).toBe("sso:dana");
    expect(whoParts("dana (finance)").shown).toBe("dana (finance)");
    expect(whoParts(null).shown).toBe("–");
  });
});

const schedule = (s: Partial<Schedule>): Schedule => ({
  id: 3,
  pipeline: "sample-orders-by-status",
  flow: null,
  cron: "15 7 * * 1-5",
  zone: "Europe/London",
  parameters: {},
  runAs: "steward",
  enabled: true,
  description: null,
  createdBy: `sso:dana.reyes@kilnmark.com (${UUID})`,
  createdAt: "2026-11-01T10:00:00Z",
  updatedBy: `sso:dana.reyes@kilnmark.com (${UUID})`,
  updatedAt: "2026-11-01T10:00:00Z",
  nextAt: "2026-11-09T07:15:00Z",
  lastAt: null,
  lastOutcome: null,
  lastPipelineRun: null,
  lastFlowRun: null,
  ...s,
});

let host: HTMLDivElement | null = null;
afterEach(() => {
  host?.remove();
  host = null;
});

describe("the schedules table keeps its last columns in view", () => {
  test("a long sign-in name is shortened, with the full one on hover", async () => {
    host = document.createElement("div");
    document.body.appendChild(host);
    const root = createRoot(host);
    await act(async () =>
      root.render(
        <SchedulesTable schedules={[schedule({})]} role="admin" label="Schedules" names={new Map()} onEdit={() => {}} onToggle={() => {}} onDelete={() => {}} />,
      ),
    );
    const setBy = host.querySelector(".sc-setby .km-who")!;
    expect(setBy.textContent).toBe("sso:dana.reyes@kilnmark.com");
    expect(setBy.getAttribute("title")).toBe(`sso:dana.reyes@kilnmark.com (${UUID})`);
    expect(host.textContent).not.toContain(UUID);
    await act(async () => root.unmount());
  });

  test("names in the table wrap rather than widen it", async () => {
    // jsdom draws nothing, so the rule itself: no name column may refuse to wrap.
    const css = await stylesheet("src/schedules/schedules.css");
    expect(css).toContain(".sc-setby");
    for (const rule of css.matchAll(/([^{}]+)\{([^}]*)\}/g)) {
      const selectors = rule[1].split(",").map((x: string) => x.replace(/\/\*[\s\S]*?\*\//g, "").trim());
      if (selectors.some((x: string) => x === ".sc-setby" || x === ".sc-target")) expect(rule[2]).not.toMatch(/nowrap/);
    }
    expect(await stylesheet("src/app.css")).toMatch(/\.km-who\s*\{[^}]*overflow-wrap:\s*anywhere/);
  });
});
