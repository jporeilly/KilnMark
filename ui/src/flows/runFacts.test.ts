import { describe, expect, test } from "vitest";
import type { FlowItemRun, FlowNodeRun, FlowRunDetail } from "../api";
import { barWidths, flowShares, flowTimeSpent, flowTotals, nodeCountWords, peak, reportedWords, splitTime, timedOut, timedOutItems, waitWords } from "./runFacts";

const T0 = Date.parse("2026-11-04T10:00:00Z");
const at = (s: number) => new Date(T0 + s * 1000).toISOString();
const S = (s: number) => s * 1000;

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

const item = (i: number, requested: number, started: number | null, finished: number | null): FlowItemRun => ({
  item: i,
  value: `v${i}`,
  status: finished != null ? "succeeded" : started != null ? "running" : "queued",
  pipelineRunId: 100 + i,
  requestedAt: at(requested),
  startedAt: started == null ? null : at(started),
  finishedAt: finished == null ? null : at(finished),
});

const detail = (nodes: FlowNodeRun[], run: Partial<FlowRunDetail["run"]> = {}): FlowRunDetail => ({
  run: {
    id: 7,
    flow: "nightly",
    version: 2,
    status: "succeeded",
    requestedBy: "admin",
    requestedRole: "admin",
    requestedAt: at(-5),
    startedAt: at(0),
    finishedAt: at(40),
    cancelRequested: false,
    error: null,
    parameters: null,
    ...run,
  },
  nodes,
});

/** clean runs a pipeline 0-10 s (asked for at 2 s, taken at 4 s, ended at 9 s); each runs three items 10-30 s; pause waits 30-40 s. */
const finished = detail([
  node({
    node: "clean",
    position: 0,
    status: "succeeded",
    startedAt: at(0),
    finishedAt: at(10),
    pipelineRunId: 50,
    reported: { rows_written: 2000, score: 0.9 },
    pipelineRunTimes: { requestedAt: at(2), startedAt: at(4), finishedAt: at(9) },
  }),
  node({
    node: "each",
    type: "for-each",
    position: 1,
    status: "succeeded",
    startedAt: at(10),
    finishedAt: at(30),
    items: [item(0, 10, 11, 20), item(1, 10, 12, 25), item(2, 20, 26, 29)],
  }),
  node({ node: "pause", type: "wait", position: 2, status: "succeeded", startedAt: at(30), finishedAt: at(40) }),
  node({ node: "tell", type: "notify", position: 3, status: "skipped" }),
]);

describe("nodeCountWords", () => {
  test("counts the nodes by how they stand, leaving out what none are", () => {
    expect(nodeCountWords(finished.nodes)).toBe("3 succeeded, 1 skipped");
    expect(nodeCountWords([node({ node: "a", position: 0, status: "running" }), node({ node: "b", position: 1 })])).toBe(
      "1 running, 1 not started",
    );
    expect(nodeCountWords([])).toBe("None");
  });
});

describe("reportedWords", () => {
  test("says the rows written and the score", () => {
    expect(reportedWords(finished.nodes[0])).toBe("2,000 rows written, score 90%");
  });
});

describe("timedOut and timedOutItems", () => {
  test("read what the node's row records", () => {
    expect(timedOut(node({ node: "a", position: 0, status: "failed", reported: { timed_out: true } }))).toBe(true);
    expect(timedOut(node({ node: "a", position: 0, status: "failed", reported: { rows_written: 3 } }))).toBe(false);
    expect(timedOut(node({ node: "a", position: 0 }))).toBe(false);
    const each = node({ node: "e", position: 0, type: "for-each", reported: { timed_out_items: [0, 2, "x"] } });
    expect([...timedOutItems(each)]).toEqual([0, 2]);
    expect(timedOutItems(node({ node: "e", position: 0 })).size).toBe(0);
  });
});

describe("flowTotals", () => {
  test("takes the server's totals when it gives them", () => {
    expect(flowTotals({ ...finished, run: { ...finished.run, pipelineRuns: 4, rowsWritten: 9000, qualityScore: 0.8 } })).toEqual({
      pipelineRuns: 4,
      rowsWritten: 9000,
      lowestScore: 0.8,
    });
    expect(flowTotals({ ...finished, run: { ...finished.run, pipelineRuns: 0, rowsWritten: null, qualityScore: null } })).toEqual({
      pipelineRuns: 0,
      rowsWritten: null,
      lowestScore: null,
    });
  });

  test("from an older server, counts the pipeline runs and leaves rows unknown when items wrote some", () => {
    expect(flowTotals(finished)).toEqual({ pipelineRuns: 4, rowsWritten: null, lowestScore: null });
  });

  test("from an older server without items, adds up what the nodes reported", () => {
    const two = detail([
      node({ node: "a", position: 0, status: "succeeded", pipelineRunId: 1, reported: { rows_written: 10, score: 0.95 } }),
      node({ node: "b", position: 1, status: "succeeded", pipelineRunId: 2, reported: { rows_written: 5, score: 0.7 } }),
      node({ node: "c", position: 2, type: "notify", status: "succeeded" }),
    ]);
    expect(flowTotals(two)).toEqual({ pipelineRuns: 2, rowsWritten: 15, lowestScore: 0.7 });
  });
});

describe("peak", () => {
  test("counts overlaps; one starting as another ends does not overlap it", () => {
    expect(peak([[0, 10], [5, 15], [10, 20]])).toBe(2);
    expect(peak([[0, 10], [10, 20]])).toBe(1);
    expect(peak([])).toBe(0);
  });
});

describe("splitTime", () => {
  test("splits a run-pipeline node: waiting before asking, queued, running, then waiting for the flow to see the end", () => {
    expect(splitTime(0, S(10), [{ requested: S(2), started: S(4), finished: S(9) }], S(100))).toEqual({
      working: S(5),
      queued: S(2),
      waiting: S(3),
    });
  });

  test("a for-each node is working while any item runs, queued while none runs and one waits", () => {
    const spans = [
      { requested: S(10), started: S(11), finished: S(20) },
      { requested: S(10), started: S(12), finished: S(25) },
      { requested: S(20), started: S(26), finished: S(29) },
    ];
    expect(splitTime(S(10), S(30), spans, S(100))).toEqual({ working: S(17), queued: S(2), waiting: S(1) });
  });

  test("a run still queued counts to now", () => {
    expect(splitTime(0, S(10), [{ requested: S(1), started: null, finished: null }], S(10))).toEqual({
      working: 0,
      queued: S(9),
      waiting: S(1),
    });
  });
});

describe("flowTimeSpent", () => {
  test("measures each node that ran, and the run at a glance", () => {
    const s = flowTimeSpent(finished, T0 + S(100))!;
    expect(s.live).toBe(false);
    expect(s.span).toBe(S(40));
    expect(s.rows.map((r) => r.node)).toEqual(["clean", "each", "pause"]);
    expect(s.rows[0]).toMatchObject({ active: S(10), working: S(5), queued: S(2), waiting: S(3), split: true });
    expect(s.rows[1]).toMatchObject({ active: S(20), working: S(17), queued: S(2), waiting: S(1) });
    expect(s.rows[2]).toMatchObject({ active: S(10), working: 0, waiting: S(10) });
    expect(flowShares(s.rows[0])).toEqual({ working: 0.5, queued: 0.2, waiting: 0.3 });
    expect(s.mostNodes).toBe(1);
    expect(s.mostPipelineRuns).toBe(2);
    expect(s.longest?.node).toBe("each");
    // 2 s for clean's run, then 1, 2 and 6 s for the items'.
    expect(s.queued).toEqual({ ms: S(11), runs: 4, of: 4 });
    expect(s.busy).toBe(S(2));
    expect(s.eachWaiting).toBe(S(1));
    expect(s.waitNodes).toBe(S(10));
    expect(waitWords(s)).toBe(
      "Its 4 pipeline runs waited 11.0 s in all for a worker. Nodes waited 2.0 s for a pipeline busy with another run. " +
        "For-each nodes waited 1.0 s with no item running or queued: for a pipeline busy with another run, or between items; " +
        "which is not recorded. Wait nodes waited 10.0 s. At most 2 pipeline runs ran at once.",
    );
  });

  test("draws every bar on the run's scale", () => {
    const s = flowTimeSpent(finished, T0 + S(100))!;
    expect(barWidths(s.rows[0], s.span)).toEqual({ working: 0.125, queued: 0.05, waiting: 0.075, plain: 0 });
    expect(barWidths(s.rows[2], s.span)).toEqual({ working: 0, queued: 0, waiting: 0.25, plain: 0 });
    expect(barWidths({ ...s.rows[0], split: false }, s.span)).toEqual({ working: 0, queued: 0, waiting: 0, plain: 0.25 });
  });

  test("while it runs, measures to now", () => {
    const going = detail(
      [
        node({
          node: "clean",
          position: 0,
          status: "running",
          startedAt: at(0),
          pipelineRunId: 50,
          pipelineRunTimes: { requestedAt: at(0), startedAt: at(1), finishedAt: null },
        }),
        node({ node: "other", type: "sql", position: 1, status: "running", startedAt: at(2) }),
      ],
      { status: "running", finishedAt: null },
    );
    const s = flowTimeSpent(going, T0 + S(6))!;
    expect(s.live).toBe(true);
    expect(s.span).toBe(S(6));
    expect(s.rows[0]).toMatchObject({ active: S(6), working: S(5), queued: S(1), running: true });
    expect(s.rows[1]).toMatchObject({ active: S(4), working: S(4) });
    expect(s.mostNodes).toBe(2);
    expect(s.queued).toEqual({ ms: S(1), runs: 1, of: 1 });
    expect(waitWords(s)).toBe("Its pipeline run waited 1.0 s in all for a worker so far.");
  });

  test("a node waiting for a busy pipeline has asked for nothing yet: all waiting, and counted as such", () => {
    const busy = detail(
      [node({ node: "copy", position: 0, status: "running", startedAt: at(0), message: "Waiting for copy to finish its current run" })],
      { status: "running", finishedAt: null },
    );
    const s = flowTimeSpent(busy, T0 + S(3))!;
    expect(s.rows[0]).toMatchObject({ active: S(3), waiting: S(3) });
    expect(s.busy).toBe(S(3));
    expect(s.queued).toEqual({ ms: 0, runs: 0, of: 0 });
  });

  test("a node that waited for a busy pipeline, then gave up, keeps its wait", () => {
    const gaveUp = detail(
      [
        node({
          node: "copy",
          position: 0,
          status: "failed",
          startedAt: at(0),
          finishedAt: at(600),
          message: "Waited 10 minutes for copy to finish its current run, and gave up",
        }),
      ],
      { status: "failed", finishedAt: at(600) },
    );
    const s = flowTimeSpent(gaveUp, T0 + S(700))!;
    expect(s.busy).toBe(600000);
    expect(s.rows[0]).toMatchObject({ active: S(600), waiting: S(600), split: true });
    expect(waitWords(s)).toBe("Nodes waited 10 min 0 s for a pipeline busy with another run.");
  });

  test("a node whose pipeline run was deleted is not split; the others still are, and the totals say of how many", () => {
    const partial = detail([
      node({ node: "gone", position: 0, status: "succeeded", startedAt: at(0), finishedAt: at(10), pipelineRunId: 1 }),
      node({
        node: "kept",
        position: 1,
        status: "succeeded",
        startedAt: at(10),
        finishedAt: at(20),
        pipelineRunId: 2,
        pipelineRunTimes: { requestedAt: at(10), startedAt: at(13), finishedAt: at(19) },
      }),
    ], { finishedAt: at(20) });
    const s = flowTimeSpent(partial, T0 + S(100))!;
    expect(s.rows.map((r) => r.split)).toEqual([false, true]);
    expect(barWidths(s.rows[0], s.span)).toEqual({ working: 0, queued: 0, waiting: 0, plain: 0.5 });
    expect(barWidths(s.rows[1], s.span)).toEqual({ working: 0.3, queued: 0.15, waiting: 0.05, plain: 0 });
    expect(s.queued).toEqual({ ms: S(3), runs: 1, of: 2 });
    expect(waitWords(s)).toBe("1 of its 2 pipeline runs waited 3.0 s in all for a worker. The times of one pipeline run are not known.");
  });

  test("from an older server without pipeline run times: not split, and nothing claimed", () => {
    const old = detail([
      node({ node: "a", position: 0, status: "succeeded", startedAt: at(0), finishedAt: at(10), pipelineRunId: 1 }),
      node({
        node: "b",
        type: "for-each",
        position: 1,
        status: "succeeded",
        startedAt: at(10),
        finishedAt: at(20),
        items: [{ item: 0, value: "x", status: "succeeded", pipelineRunId: 2, startedAt: at(11), finishedAt: at(19) }],
      }),
    ]);
    const s = flowTimeSpent(old, T0 + S(100))!;
    expect(s.rows.map((r) => r.split)).toEqual([false, false]);
    expect(flowShares(s.rows[0])).toEqual({ working: 0, queued: 0, waiting: 0 });
    expect(s.queued).toEqual({ ms: 0, runs: 0, of: 2 });
    expect(s.busy).toBe(0);
    expect(s.mostPipelineRuns).toBe(0);
    expect(s.longest?.node).toBe("a");
    expect(waitWords(s)).toBe("The times of 2 pipeline runs are not known.");
  });

  test("nothing before the run starts", () => {
    expect(flowTimeSpent(detail([node({ node: "a", position: 0 })], { status: "queued", startedAt: null, finishedAt: null }))).toBeNull();
  });
});
