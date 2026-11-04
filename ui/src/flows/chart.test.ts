import { describe, expect, test } from "vitest";
import type { FlowNodeRun, FlowRunDetail } from "../api";
import { chartRowWords, flowChart } from "./chart";

const T0 = Date.parse("2026-11-04T10:00:00Z");
const at = (s: number) => new Date(T0 + s * 1000).toISOString();

const node = (n: Partial<FlowNodeRun> & { node: string; position: number }): FlowNodeRun => ({
  type: "run-pipeline",
  status: "pending",
  startedAt: null,
  finishedAt: null,
  pipelineRunId: null,
  pipeline: null,
  message: null,
  reported: null,
  ...n,
});

const detail = (nodes: FlowNodeRun[], run: Partial<FlowRunDetail["run"]> = {}): FlowRunDetail => ({
  run: {
    id: 7,
    flow: "nightly",
    version: 2,
    status: "running",
    requestedBy: "admin",
    requestedRole: "admin",
    requestedAt: at(-5),
    startedAt: at(0),
    finishedAt: null,
    cancelRequested: false,
    error: null,
    parameters: null,
    ...run,
  },
  nodes,
});

describe("flowChart", () => {
  test("places each node from the run's start, in the version's order, running bars to now", () => {
    const chart = flowChart(
      detail([
        node({ node: "spend", position: 1, status: "running", startedAt: at(30), pipelineRunId: 12 }),
        node({ node: "clean", position: 0, status: "succeeded", startedAt: at(0), finishedAt: at(30), pipelineRunId: 11 }),
        node({ node: "tell", position: 2, status: "skipped" }),
        node({ node: "halt", position: 3 }),
      ]),
      T0 + 45_000,
    );
    expect(chart?.span).toBe(45_000);
    expect(chart?.rows.map((r) => [r.node, r.start, r.end, r.live, r.pipelineRunId])).toEqual([
      ["clean", 0, 30_000, false, 11],
      ["spend", 30_000, 45_000, true, 12],
      ["tell", null, null, false, null],
      ["halt", null, null, false, null],
    ]);
  });

  test("a finished run spans to its end, however long its nodes took", () => {
    const chart = flowChart(
      detail([node({ node: "a", position: 0, status: "succeeded", startedAt: at(1), finishedAt: at(4) })], {
        status: "succeeded",
        finishedAt: at(10),
      }),
      T0 + 999_000,
    );
    expect(chart?.span).toBe(10_000);
    expect(chart?.rows[0]).toMatchObject({ start: 1_000, end: 4_000, live: false });
  });

  test("a running bar never ends before it starts, if the browser's clock is behind", () => {
    const chart = flowChart(
      detail([node({ node: "a", position: 0, status: "running", startedAt: at(20) })]),
      T0 + 5_000,
    );
    expect(chart?.rows[0]).toMatchObject({ start: 20_000, end: 20_000, live: true });
  });

  test("nothing to draw before the run starts", () => {
    expect(flowChart(detail([node({ node: "a", position: 0 })], { status: "queued", startedAt: null }))).toBeNull();
  });
});

describe("chartRowWords", () => {
  test("says what each row shows", () => {
    const row = { node: "clean", type: "run-pipeline", pipelineRunId: null };
    expect(chartRowWords({ ...row, status: "succeeded", start: 0, end: 30_000, live: false })).toBe(
      "clean: started at 0 s, succeeded after 30.0 s",
    );
    expect(chartRowWords({ ...row, status: "running", start: 1_500, end: 4_000, live: true })).toBe(
      "clean: started at 1.5 s, running for 2.5 s",
    );
    expect(chartRowWords({ ...row, status: "skipped", start: null, end: null, live: false })).toBe("clean: skipped");
    expect(chartRowWords({ ...row, status: "pending", start: null, end: null, live: false })).toBe("clean: not started");
  });
});
