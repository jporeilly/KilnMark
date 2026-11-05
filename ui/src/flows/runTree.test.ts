import { describe, expect, test } from "vitest";
import type { FlowNodeRun, FlowRunDetail, PipelineRunDetail } from "../api";
import { openAtFirst, runTree, runsWanted, stepStatus, treeRowWords } from "./runTree";

const T0 = Date.parse("2026-11-05T10:00:00Z");
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

const flowRun = (nodes: FlowNodeRun[], status = "succeeded", finished: number | null = 9): FlowRunDetail => ({
  run: {
    id: 7,
    flow: "nightly",
    version: 2,
    status: status as FlowRunDetail["run"]["status"],
    requestedBy: "admin",
    requestedRole: "admin",
    requestedAt: at(-1),
    startedAt: at(0),
    finishedAt: finished == null ? null : at(finished),
    cancelRequested: false,
    error: null,
    parameters: null,
  },
  nodes,
});

/** A pipeline run that started at `start` seconds, with steps [name, from, to] in seconds from its own start. */
const pipelineRun = (id: number, start: number, steps: [string, number, number | null][], status = "succeeded") =>
  ({
    run: { id, pipeline: "orders", status, startedAt: at(start), finishedAt: null },
    nodes: steps.map(([n, a, b], i) => ({
      node: n,
      type: i === 0 ? "read" : "write",
      position: i,
      rowsIn: null,
      rowsOut: 10,
      ports: null,
      peakMemory: null,
      durationMs: b == null ? null : (b - a) * 1000,
      error: null,
      outcome: b == null ? null : "finished",
      startedAt: at(start + a),
      firstRowAt: null,
      lastRowAt: null,
      finishedAt: b == null ? null : at(start + b),
    })),
    written: [],
    read: [],
    lineage: [],
    notes: [],
  }) as unknown as PipelineRunDetail;

describe("runTree", () => {
  const detail = flowRun([
    node({ node: "orders", position: 0, status: "succeeded", startedAt: at(1), finishedAt: at(5), pipelineRunId: 11, pipeline: "orders" }),
    node({
      node: "regions",
      position: 1,
      type: "for-each",
      status: "succeeded",
      startedAt: at(5),
      finishedAt: at(9),
      pipeline: "by-region",
      items: [
        { item: 0, value: "EMEA", status: "succeeded", pipelineRunId: 12, startedAt: at(5), finishedAt: at(7) },
        { item: 1, value: "APAC", status: "succeeded", pipelineRunId: 13, startedAt: at(6), finishedAt: at(9) },
      ],
    }),
    node({ node: "tell", position: 2, type: "notify", status: "skipped" }),
  ]);
  const runs = new Map([
    [11, pipelineRun(11, 2, [["read", 0, 2], ["write", 0.5, 3]])],
    [12, pipelineRun(12, 5, [["read", 0, 1]])],
  ]);

  test("opens each node's pipeline run and items at first, and asks only for the runs open rows show", () => {
    const open = openAtFirst(detail);
    expect([...open]).toEqual(["n:orders", "n:regions"]);
    expect(runsWanted(detail, open)).toEqual([11]);
    expect(runsWanted(detail, new Set([...open, "n:regions/i:0"]))).toEqual([11, 12]);
  });

  test("nests the flow, its nodes, the items and each pipeline run's nodes, on the flow's time axis", () => {
    const tree = runTree(detail, runs, new Set([...openAtFirst(detail), "n:regions/i:0"]));
    expect(tree?.span).toBe(9000);
    expect(tree?.rows.map((r) => [r.id, r.depth, r.badge, r.start, r.end, r.status])).toEqual([
      ["flow", 0, null, 0, 9000, "succeeded"],
      ["n:orders", 1, "orders", 1000, 5000, "succeeded"],
      // Placed from when the pipeline run started (2 s), not the node.
      ["n:orders/s:read", 2, null, 2000, 4000, "succeeded"],
      ["n:orders/s:write", 2, null, 2500, 5000, "succeeded"],
      ["n:regions", 1, "by-region", 5000, 9000, "succeeded"],
      ["n:regions/i:0", 2, "EMEA", 5000, 7000, "succeeded"],
      ["n:regions/i:0/s:read", 3, null, 5000, 6000, "succeeded"],
      ["n:regions/i:1", 2, "APAC", 6000, 9000, "succeeded"],
      ["n:tell", 1, null, null, null, "skipped"],
    ]);
  });

  test("a closed row hides what is under it; an open one whose run is not read yet says it is loading", () => {
    const closed = runTree(detail, runs, new Set());
    expect(closed?.rows.map((r) => r.id)).toEqual(["flow", "n:orders", "n:regions", "n:tell"]);
    const waiting = runTree(detail, new Map(), new Set(["n:orders"]));
    expect(waiting?.rows.find((r) => r.id === "n:orders")?.loading).toBe(true);
    expect(waiting?.rows.some((r) => r.kind === "step")).toBe(false);
  });

  test("an open row whose pipeline run has no node times says so, with nothing under it", () => {
    const stopped = new Map([[11, pipelineRun(11, 2, [])]]);
    const tree = runTree(detail, stopped, new Set(["n:orders"]));
    const orders = tree?.rows.find((r) => r.id === "n:orders");
    expect(orders?.untimed).toBe(true);
    expect(orders?.loading).toBe(false);
    expect(tree?.rows.some((r) => r.id.startsWith("n:orders/"))).toBe(false);
  });

  test("a running flow and pipeline run their bars to now", () => {
    const live = flowRun(
      [node({ node: "orders", position: 0, status: "running", startedAt: at(1), pipelineRunId: 11, pipeline: "orders" })],
      "running",
      null,
    );
    const going = new Map([[11, pipelineRun(11, 2, [["read", 0, 1], ["write", 0.5, null]], "running")]]);
    const tree = runTree(live, going, openAtFirst(live), T0 + 6000);
    expect(tree?.rows.map((r) => [r.id, r.end, r.live, r.status])).toEqual([
      ["flow", 6000, true, "running"],
      ["n:orders", 6000, true, "running"],
      ["n:orders/s:read", 3000, false, "succeeded"],
      ["n:orders/s:write", 6000, true, "running"],
    ]);
  });

  test("before the run starts there is nothing to draw", () => {
    const notYet = flowRun([node({ node: "a", position: 0 })], "queued", null);
    notYet.run.startedAt = null;
    expect(runTree(notYet, new Map(), new Set())).toBeNull();
  });

  test("a pipeline node's outcome, in the flow's words, and a row in words", () => {
    expect(stepStatus("finished", true, "succeeded")).toBe("succeeded");
    expect(stepStatus("ended_early", true, "succeeded")).toBe("succeeded");
    expect(stepStatus("stopped", true, "cancelled")).toBe("cancelled");
    expect(stepStatus("failed", true, "failed")).toBe("failed");
    expect(stepStatus(null, false, "running")).toBe("running");
    const tree = runTree(detail, runs, openAtFirst(detail))!;
    const words = (id: string) => treeRowWords(tree.rows.find((r) => r.id === id)!, (ms) => `${ms / 1000} s`);
    expect(words("flow")).toBe("Flow nightly: succeeded after 9 s");
    expect(words("n:regions/i:1")).toBe("item 2 (APAC): succeeded after 3 s");
    expect(words("n:tell")).toBe("tell: skipped");
  });
});
