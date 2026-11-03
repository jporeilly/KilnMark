import { describe, expect, test } from "vitest";
import type { PipelineNodeRun } from "../api";
import { nodeMetrics, shareOf, splitWords } from "./nodeMetrics";

const ran = (r: Partial<PipelineNodeRun>): PipelineNodeRun => ({
  node: "tidy",
  type: "compute",
  position: 1,
  rowsIn: 5000,
  rowsOut: 5000,
  ports: null,
  peakMemory: 2_097_152,
  durationMs: 4000,
  error: null,
  outcome: "finished",
  ...r,
});

describe("a node's metrics", () => {
  test("rows a second count only while rows moved", () => {
    const m = nodeMetrics(
      ran({
        startedAt: "2026-11-02T10:00:00.000Z",
        firstRowAt: "2026-11-02T10:00:01.000Z",
        lastRowAt: "2026-11-02T10:00:03.000Z",
        finishedAt: "2026-11-02T10:00:04.000Z",
      }),
    );
    expect(m.rate).toBe(2500);
    expect(m.firstRowMs).toBe(1000);
  });

  test("without row times the rate is over its whole time", () => {
    expect(nodeMetrics(ran({})).rate).toBe(1250);
    expect(nodeMetrics(ran({ durationMs: null })).rate).toBeNull();
    expect(nodeMetrics(ran({ rowsOut: null, rowsIn: 400, durationMs: 2000 })).rate).toBe(200);
  });

  test("its time splits into working, waiting for rows and held up", () => {
    const m = nodeMetrics(ran({ waitingForRowsMs: 600, heldUpMs: 200 }));
    expect(m.split).toEqual({ workingMs: 3200, waitingMs: 600, heldUpMs: 200 });
    expect(splitWords(m)).toBe("80% working, 15% waiting for rows, 5% held up");
    expect(nodeMetrics(ran({})).split).toBeNull();
    expect(splitWords(nodeMetrics(ran({})))).toBeNull();
  });

  test("the slowest node is marked", () => {
    expect(nodeMetrics(ran({}), "tidy").slowest).toBe(true);
    expect(nodeMetrics(ran({}), "check").slowest).toBe(false);
    expect(nodeMetrics(ran({})).slowest).toBe(false);
  });

  test("shares never pass 100%", () => {
    expect(shareOf(5, 0)).toBe("0%");
    expect(shareOf(150, 100)).toBe("100%");
    expect(shareOf(1, 3)).toBe("33%");
  });
});
