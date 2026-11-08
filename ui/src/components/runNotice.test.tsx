// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { PipelineRun, PipelineSummary } from "../api";
import { noticeText, statusIn } from "./runNotice";

const run = (status: PipelineRun["status"]): PipelineRun =>
  ({
    id: 12,
    pipeline: "orders",
    version: 1,
    status,
    requestedBy: "dana",
    requestedAt: "2026-11-08T17:00:00Z",
    startedAt: null,
    finishedAt: null,
    cancelRequested: false,
    error: null,
    rowsWritten: null,
  }) as PipelineRun;

let status: PipelineRun["status"] = "queued";
const summary = (): PipelineSummary => ({
  name: "orders",
  displayName: "Orders",
  description: null,
  latestVersion: 1,
  valid: true,
  updatedBy: "dana",
  updatedAt: "2026-11-05T10:00:00Z",
  lastRun: run(status),
  library: null,
});

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    pipelinesApi: { ...real.pipelinesApi, list: vi.fn(async () => [summary()]), run: vi.fn(async () => run("queued")) },
  };
});

const { PipelinesPage } = await import("../pipelines/PipelinesPage");

const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);
afterEach(() => {
  vi.useRealTimers();
  act(() => root.render(<></>));
});

describe("the notice after starting a run follows the run", () => {
  test("in words, by status", () => {
    const n = { run: 12, of: "version 1" };
    expect(noticeText(n, "queued")).toBe("Run 12 of version 1 is queued.");
    expect(noticeText(n, "running")).toBe("Run 12 of version 1 is running.");
    expect(noticeText(n, "succeeded")).toBe("Run 12 of version 1 succeeded.");
    expect(noticeText(n, "failed")).toBe("Run 12 of version 1 failed.");
    expect(noticeText(n, undefined)).toBe("Run 12 of version 1 is queued.");
    expect(noticeText("Saved.", "failed")).toBe("Saved.");
    expect(statusIn([run("running")], 12)).toBe("running");
  });

  test("the page says the run succeeded once it has, without a reload", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    status = "queued";
    await act(async () => root.render(<PipelinesPage role="steward" />));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    const button = [...host.querySelectorAll("button")].find((b) => b.getAttribute("aria-label") === "Run Orders now")!;
    await act(async () => button.click());
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    const notice = () => host.querySelector('p[role="status"].notice')!.textContent;
    expect(notice()).toBe("Run 12 of Orders is queued.");
    // The page looks again every few seconds while a run is going.
    status = "succeeded";
    await act(async () => {
      vi.advanceTimersByTime(4100);
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(notice()).toBe("Run 12 of Orders succeeded.");
  });
});
