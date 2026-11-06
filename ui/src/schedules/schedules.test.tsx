// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeAll, beforeEach, describe, expect, test, vi } from "vitest";
import type { AccessRole, Schedule, SchedulePreview } from "../api";

const schedule = (s: Partial<Schedule>): Schedule => ({
  id: 3,
  pipeline: "orders",
  flow: null,
  cron: "0 2 * * *",
  zone: "UTC",
  parameters: { region: "EMEA" },
  runAs: "steward",
  enabled: true,
  description: "Nightly load",
  createdBy: "dana",
  createdAt: "2026-11-01T10:00:00Z",
  updatedBy: "dana",
  updatedAt: "2026-11-01T10:00:00Z",
  nextAt: "2026-11-07T02:00:00Z",
  lastAt: "2026-11-06T02:00:00Z",
  lastOutcome: "started",
  lastPipelineRun: 41,
  lastFlowRun: null,
  ...s,
});

// Typing and waiting for the preview take a few seconds on a slow machine.
const SLOW = { timeout: 30_000 };

let listed: Schedule[] = [];
const api = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  preview: vi.fn(),
  replace: vi.fn(),
  remove: vi.fn(),
  create: vi.fn(),
  getPipeline: vi.fn(),
}));

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    schedulesApi: { ...real.schedulesApi, list: api.list, get: api.get, preview: api.preview, replace: api.replace, remove: api.remove, create: api.create },
    pipelinesApi: { ...real.pipelinesApi, get: api.getPipeline },
  };
});

const { SchedulesSection } = await import("./SchedulesSection");
const { useSchedules } = await import("./SchedulesTable");

beforeAll(() => {
  // jsdom has no modal dialogs: open and close them as attributes.
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute("open");
  };
});

beforeEach(() => {
  listed = [
    schedule({}),
    schedule({ id: 4, cron: "*/15 * * * *", enabled: false, nextAt: null, lastOutcome: "failed: Version 2 cannot run", lastPipelineRun: null, parameters: {} }),
  ];
  api.list.mockReset().mockImplementation(async () => listed);
  api.get.mockReset().mockImplementation(async (id: number) => listed.find((s) => s.id === id));
  api.replace.mockReset().mockImplementation(async (id: number, body: { enabled: boolean }) => schedule({ id, enabled: body.enabled }));
  api.remove.mockReset().mockResolvedValue(null);
  api.create.mockReset().mockImplementation(async () => schedule({ id: 9 }));
  api.preview.mockReset().mockImplementation(
    async (cron: string, zone: string): Promise<SchedulePreview> =>
      cron.startsWith("nonsense")
        ? { valid: false, problem: "cron: five fields, such as 0 2 * * *", cron, zone, next: [] }
        : { valid: true, problem: null, cron, zone, next: ["2026-11-09T02:00:00Z", "2026-11-10T02:00:00Z"] },
  );
  api.getPipeline.mockReset().mockResolvedValue({
    name: "orders",
    latest: { yaml: "kilnmark: pipeline/v1\nname: orders\nparameters:\n  region: { type: text, default: EMEA }\nnodes: []\n" },
  });
});

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => {
  act(() => root.render(<></>));
  vi.restoreAllMocks();
});

const settle = (ms = 0) =>
  act(async () => {
    await new Promise((r) => setTimeout(r, ms));
  });

function Harness({ role }: { role: AccessRole }) {
  const data = useSchedules({ pipeline: "orders" });
  return <SchedulesSection target={{ pipeline: "orders" }} role={role} data={data} />;
}

async function show(role: AccessRole) {
  await act(async () => root.render(<Harness role={role} />));
  await settle();
}

const headers = () => [...host.querySelectorAll("thead th")].map((th) => th.textContent?.trim());
const button = (name: string) =>
  [...document.querySelectorAll("button")].find((b) => (b.getAttribute("aria-label") ?? b.textContent?.trim()) === name) as
    | HTMLButtonElement
    | undefined;

describe("a pipeline's schedules", () => {
  test("viewers see when, who and what happened, without the values or any way to change them", async () => {
    listed = listed.map((s) => ({ ...s, parameters: null, lastOutcome: s.lastOutcome?.startsWith("failed") ? "failed" : s.lastOutcome }));
    await show("viewer");
    expect(api.list).toHaveBeenCalledWith({ pipeline: "orders" });
    const text = host.textContent ?? "";
    expect(text).toContain("Every day at 02:00");
    expect(text).toContain("Every 15 minutes");
    expect(text).toContain("dana");
    expect(text).toContain("Runs as steward");
    expect(text).toContain("Paused");
    expect(text).toContain("Failed");
    expect(headers()).toEqual(["Schedule", "Set by", "State and next run", "Last time it was due"]);
    expect(text).not.toContain("Values:");
    expect(text).not.toContain("region");
    expect(button("Add a schedule")).toBeUndefined();
    expect(button("Edit schedule 3")).toBeUndefined();
    expect(button("Pause schedule 3")).toBeUndefined();
    expect(button("Delete schedule 3")).toBeUndefined();
  });

  test("stewards read the values too, and still change nothing", async () => {
    await show("steward");
    expect(host.textContent).toContain("Values: region = EMEA");
    expect(host.textContent).toContain("The defaults");
    expect(button("Add a schedule")).toBeUndefined();
    expect(button("Edit schedule 3")).toBeUndefined();
  });

  test("a last run links to the run, and an outcome is in words as well as colour", async () => {
    await show("steward");
    const link = [...host.querySelectorAll("a")].find((a) => a.textContent === "Pipeline run 41");
    expect(link?.getAttribute("href")).toBe("#/pipeline-runs/41");
    const chips = [...host.querySelectorAll('[data-kind="outcome"]')].map((c) => c.textContent);
    expect(chips).toEqual(["Started", "Failed"]);
    expect(host.textContent).toContain("Version 2 cannot run");
  });

  test("designers pause, resume and delete, each sending its call", SLOW, async () => {
    await show("designer");
    expect(button("Add a schedule")).toBeDefined();
    // Someone changed the description since the list was read: pausing keeps their change.
    listed = listed.map((s) => (s.id === 3 ? { ...s, description: "Changed since" } : s));
    await act(async () => button("Pause schedule 3")!.click());
    expect(api.get).toHaveBeenCalledWith(3);
    expect(api.replace).toHaveBeenCalledWith(3, expect.objectContaining({ description: "Changed since" }));
    expect(host.textContent).toContain("Schedule 3 is paused, and is now set by you.");
    expect(api.replace).toHaveBeenCalledWith(3, expect.objectContaining({ pipeline: "orders", cron: "0 2 * * *", enabled: false, parameters: { region: "EMEA" } }));
    await act(async () => button("Resume schedule 4")!.click());
    expect(api.replace).toHaveBeenCalledWith(4, expect.objectContaining({ cron: "*/15 * * * *", enabled: true }));

    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false).mockReturnValueOnce(true);
    await act(async () => button("Delete schedule 3")!.click());
    expect(api.remove).not.toHaveBeenCalled();
    await act(async () => button("Delete schedule 3")!.click());
    expect(confirm).toHaveBeenCalledTimes(2);
    expect(api.remove).toHaveBeenCalledWith(3);
    expect(host.textContent).toContain("Schedule 3 is deleted.");
    await settle();
    expect(document.activeElement?.textContent).toBe("Schedule 3 is deleted.");
  });
});

describe("the schedule form", () => {
  test("shows the next runs as the times change, and the problem when they cannot be used", SLOW, async () => {
    const user = userEvent.setup();
    await show("designer");
    await user.click(button("Add a schedule")!);
    await settle(500);
    const dialog = host.querySelector("dialog")!;
    expect(dialog.hasAttribute("open")).toBe(true);
    expect(api.preview).toHaveBeenLastCalledWith("0 2 * * *", expect.any(String), expect.anything());
    expect(dialog.querySelectorAll(".sf-next li")).toHaveLength(2);

    await user.selectOptions(dialog.querySelector("#sf-repeat")!, "weekdays");
    await settle(500);
    expect((dialog.querySelector("#sf-cron") as HTMLInputElement).value).toBe("0 2 * * MON-FRI");
    expect(api.preview).toHaveBeenLastCalledWith("0 2 * * MON-FRI", expect.any(String), expect.anything());

    await user.selectOptions(dialog.querySelector("#sf-repeat")!, "minutes");
    await user.selectOptions(dialog.querySelector("#sf-every")!, "10");
    await settle(500);
    expect(api.preview).toHaveBeenLastCalledWith("*/10 * * * *", expect.any(String), expect.anything());

    await user.selectOptions(dialog.querySelector("#sf-zone")!, "UTC");
    await settle(500);
    expect(api.preview).toHaveBeenLastCalledWith("*/10 * * * *", "UTC", expect.anything());

    const cron = dialog.querySelector("#sf-cron") as HTMLInputElement;
    await user.clear(cron);
    await user.type(cron, "nonsense");
    await settle(500);
    expect((dialog.querySelector("#sf-repeat") as HTMLSelectElement).value).toBe("custom");
    expect(dialog.textContent).toContain("Cannot be used: cron: five fields, such as 0 2 * * *");
    expect(dialog.querySelectorAll(".sf-next li")).toHaveLength(0);
  });

  test("labels every field, offers the pipeline's parameters, and saves what was chosen", SLOW, async () => {
    const user = userEvent.setup();
    await show("designer");
    await user.click(button("Add a schedule")!);
    await settle(500);
    const dialog = host.querySelector("dialog")!;
    for (const field of dialog.querySelectorAll("input, select")) {
      expect(dialog.querySelector(`label[for="${field.id}"]`), field.id).not.toBeNull();
    }
    const region = dialog.querySelector("#sf-p-region") as HTMLInputElement;
    expect(region.value).toBe("EMEA");
    await user.clear(region);
    await user.type(region, "APAC");
    await user.selectOptions(dialog.querySelector("#sf-runas")!, "designer");
    await user.type(dialog.querySelector("#sf-description")!, "Weekly APAC load");
    await user.click(button("Add schedule")!);
    await settle();
    expect(api.create).toHaveBeenCalledWith({
      pipeline: "orders",
      cron: "0 2 * * *",
      zone: expect.any(String),
      parameters: { region: "APAC" },
      runAs: "designer",
      enabled: true,
      description: "Weekly APAC load",
    });
    expect(host.textContent).toContain("Schedule 9 is added.");
  });

  test("a part of a preset that is not right empties the expression rather than keep an older one", SLOW, async () => {
    const user = userEvent.setup();
    await show("designer");
    await user.click(button("Add a schedule")!);
    await settle(500);
    const dialog = host.querySelector("dialog")!;
    await user.selectOptions(dialog.querySelector("#sf-repeat")!, "monthly");
    const day = dialog.querySelector("#sf-day") as HTMLInputElement;
    await user.clear(day);
    await user.type(day, "32");
    await settle(500);
    expect((dialog.querySelector("#sf-cron") as HTMLInputElement).value).toBe("");
    expect(dialog.textContent).toContain("Finish the times above to see when it runs.");
    await user.click(button("Add schedule")!);
    expect(api.create).not.toHaveBeenCalled();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe("Finish the times: a part of them is not right yet.");
  });

  test("cannot save while the parameters are unread, so stored values are never wiped", SLOW, async () => {
    const user = userEvent.setup();
    api.getPipeline.mockRejectedValue(new Error("The server answered 500"));
    await show("designer");
    await user.click(button("Edit schedule 3")!);
    await settle(500);
    const dialog = host.querySelector("dialog")!;
    expect(dialog.textContent).toContain("parameters could not be read");
    expect(button("Save changes")!.disabled).toBe(true);
    expect(api.replace).not.toHaveBeenCalled();
  });

  test("shows the server's refusal in the form", SLOW, async () => {
    const user = userEvent.setup();
    api.replace.mockRejectedValueOnce(new Error("zone: unknown time zone"));
    await show("designer");
    await user.click(button("Edit schedule 3")!);
    await settle(500);
    const dialog = host.querySelector("dialog")!;
    expect((dialog.querySelector("#sf-repeat") as HTMLSelectElement).value).toBe("daily");
    expect((dialog.querySelector("#sf-time") as HTMLInputElement).value).toBe("02:00");
    await user.click(button("Save changes")!);
    await settle();
    expect(dialog.querySelector('[role="alert"]')?.textContent).toBe("zone: unknown time zone");
    expect(dialog.hasAttribute("open")).toBe(true);
  });
});
