// @vitest-environment jsdom
import { act } from "react";
import { createRoot } from "react-dom/client";
import { afterEach, describe, expect, test, vi } from "vitest";
import type { PipelineProgressSeries, PipelineRunDetail } from "../api";

/** Each run's points, as the server keeps them: run 21 read orders, run 22 read sales. */
const SERIES: Record<number, PipelineProgressSeries> = {
  21: { nodes: ["orders", "named"], stride: 1, points: [{ t: 1000, r: [100, 90], q: [0, 0] }, { t: 2000, r: [120, 95], q: [0, 0] }] },
  22: { nodes: ["sales", "valued"], stride: 1, points: [{ t: 1000, r: [500, 400], q: [0, 0] }, { t: 3000, r: [600, 450], q: [0, 0] }] },
};

vi.mock("../api", async (original) => {
  const real = await original<typeof import("../api")>();
  return {
    ...real,
    pipelinesApi: {
      ...real.pipelinesApi,
      // Only the points after `after`, as the server answers a later look.
      series: vi.fn(async (id: number, after?: number) => {
        const s = SERIES[id];
        return { ...s, points: s.points.filter((p) => after == null || p.t > after) };
      }),
      events: vi.fn(async () => ({ events: [], next: 0, more: false })),
    },
  };
});

const { RunRates } = await import("./RunRates");

const T0 = Date.parse("2026-10-30T10:00:00Z");
const at = (ms: number) => new Date(T0 + ms).toISOString();

const detail = (id: number): PipelineRunDetail =>
  ({
    run: {
      id,
      pipeline: "p",
      version: 1,
      status: "succeeded",
      requestedBy: "jo",
      requestedAt: at(0),
      startedAt: at(0),
      finishedAt: at(4000),
      cancelRequested: false,
      error: null,
      rowsWritten: 1,
      qualityScore: null,
      qualityThreshold: null,
    },
    nodes: [],
    written: [],
    read: [],
    lineage: [],
    notes: [],
    progress: { at: at(4000), workerStartedAt: at(0), workerFinishedAt: at(4000), slowest: null, nodes: [], edges: [] },
  }) as unknown as PipelineRunDetail;

(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
const host = document.createElement("div");
document.body.appendChild(host);
const root = createRoot(host);

afterEach(() => act(() => root.render(<></>)));

const settle = () => act(async () => {
  await new Promise((r) => setTimeout(r, 0));
});

describe("rows a second through the run", () => {
  test("going on to another run shows that run's nodes, not the last one's", async () => {
    await act(async () => root.render(<RunRates detail={detail(21)} tick={0} />));
    await settle();
    expect(host.textContent).toContain("orders");
    await act(async () => root.render(<RunRates detail={detail(22)} tick={0} />));
    await settle();
    expect(host.textContent).toContain("sales");
    expect(host.textContent).toContain("valued");
    expect(host.textContent).not.toContain("orders");
    expect(host.textContent).not.toContain("named");
  });
});
