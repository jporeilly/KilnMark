import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { PipelineRun, PipelineRunDetail, PipelineRunProgress, PipelineStepRun } from "../api";
import { badgeRows, runWords, stepChips } from "./canvas/StepNode";
import { liveEdgeRows, liveSteps, rateWords } from "./live";
import { RunTimeline } from "./RunTimeline";
import { offsetWords, rowWords, ticks, timeline } from "./timeline";

const T0 = Date.parse("2026-10-28T10:00:00Z");
const at = (ms: number) => new Date(T0 + ms).toISOString();

const run = (over: Partial<PipelineRun> = {}): PipelineRun =>
  ({
    id: 7,
    pipeline: "customer-copy",
    version: 2,
    status: "succeeded",
    requestedBy: "jo",
    requestedAt: at(-500),
    startedAt: at(0),
    finishedAt: at(4000),
    cancelRequested: false,
    error: null,
    rowsWritten: 5000,
    qualityScore: null,
    qualityThreshold: null,
    ...over,
  }) as PipelineRun;

const step = (
  id: string,
  start: number,
  first: number | null,
  last: number | null,
  end: number,
  outcome: PipelineStepRun["outcome"] = "finished",
): PipelineStepRun => ({
  step: id,
  type: "t",
  position: 0,
  rowsIn: 5000,
  rowsOut: 5000,
  ports: null,
  peakMemory: 1024,
  durationMs: end - start,
  error: null,
  outcome,
  startedAt: at(start),
  firstRowAt: first == null ? null : at(first),
  lastRowAt: last == null ? null : at(last),
  finishedAt: at(end),
});

const detail = (over: Partial<PipelineRunDetail> = {}): PipelineRunDetail => ({
  run: run(),
  steps: [
    step("customers", 10, 400, 2900, 3000),
    step("tidy", 12, 420, 2950, 3010),
    step("clean", 15, 450, 3900, 3950, "failed"),
  ],
  written: [],
  read: [],
  lineage: [],
  notes: [],
  ...over,
});

const progress: PipelineRunProgress = {
  at: at(2000),
  slowest: "tidy",
  steps: [
    {
      step: "customers",
      state: "running",
      rowsIn: 0,
      rowsOut: 3000,
      rowsPerSecond: 1500.2,
      startedAt: at(10),
      firstRowAt: at(400),
      lastRowAt: at(1990),
      finishedAt: null,
    },
    {
      step: "tidy",
      state: "running",
      rowsIn: 2600,
      rowsOut: 2400,
      rowsPerSecond: 1200,
      startedAt: at(12),
      firstRowAt: at(420),
      lastRowAt: at(1980),
      finishedAt: null,
    },
    {
      step: "clean",
      state: "waiting",
      rowsIn: 0,
      rowsOut: 0,
      rowsPerSecond: 0,
      startedAt: at(15),
      firstRowAt: null,
      lastRowAt: null,
      finishedAt: null,
    },
  ],
  edges: [
    {
      from: "customers",
      fromPort: "out",
      to: "tidy",
      toPort: "in",
      rows: 2600,
      queued: 4,
      capacity: 4,
      full: true,
      empty: false,
    },
    {
      from: "tidy",
      fromPort: "out",
      to: "clean",
      toPort: "in",
      rows: 0,
      queued: 0,
      capacity: 4,
      full: false,
      empty: true,
    },
  ],
};

describe("a run's timeline", () => {
  test("each step from the run's start, overlapping, to the run's end", () => {
    const t = timeline(detail())!;
    expect(t.live).toBe(false);
    expect(t.span).toBe(4000);
    expect(t.rows.map((r) => [r.step, r.start, r.firstRow, r.lastRow, r.end])).toEqual([
      ["customers", 10, 400, 2900, 3000],
      ["tidy", 12, 420, 2950, 3010],
      ["clean", 15, 450, 3900, 3950],
    ]);
    expect(rowWords(t.rows[0])).toBe("Started at 10 ms, first row at 400 ms, last row at 2.9 s, ended at 3.0 s, after 3.0 s");
  });

  test("nothing to draw for a run whose steps were not timed", () => {
    const old = detail({
      steps: [
        {
          ...step("a", 0, 0, 0, 0),
          startedAt: null,
          firstRowAt: null,
          lastRowAt: null,
          finishedAt: null,
        },
      ],
    });
    expect(timeline(old)).toBeNull();
    expect(renderToStaticMarkup(<RunTimeline detail={old} />)).toContain("were not timed");
  });

  test("while the run goes, from its progress, to now, with the slowest step", () => {
    const t = timeline(
      detail({
        run: run({ status: "running", finishedAt: null }),
        steps: [],
        progress,
      }),
      T0 + 2500,
    )!;
    expect(t.live).toBe(true);
    expect(t.span).toBe(2500);
    expect(t.rows.find((r) => r.slowest)?.step).toBe("tidy");
    const clean = t.rows.find((r) => r.step === "clean")!;
    expect(clean.running).toBe(true);
    expect(rowWords(clean)).toBe("Started at 15 ms, waiting for its first row, still running");
  });

  test("drawn with words: an aria label, Slowest as a word, and a table", () => {
    const html = renderToStaticMarkup(
      <RunTimeline
        detail={detail({
          run: run({ status: "running", finishedAt: null }),
          steps: [],
          progress,
        })}
        now={T0 + 2500}
      />,
    );
    expect(html).toContain('role="img"');
    expect(html).toContain("the slowest step now is tidy");
    expect(html).toContain(">Slowest<");
    expect(html).toContain("Show the timeline as a table");
    expect(html).toContain("Waiting for rows");
    const done = renderToStaticMarkup(<RunTimeline detail={detail()} />);
    expect(done).toContain("pl-tl-bar is-failed");
    expect(done).toContain(">Failed<");
  });

  test("round ticks on the axis", () => {
    expect(ticks(4000)).toEqual([0, 1000, 2000, 3000, 4000]);
    expect(ticks(950)).toEqual([0, 200, 400, 600, 800]);
    expect(ticks(125_000).length).toBeLessThanOrEqual(6);
    expect(offsetWords(0)).toBe("0 s");
    expect(offsetWords(90_000)).toBe("1 min 30 s");
  });
});

describe("live figures while a run goes", () => {
  test("rows so far, the rate and the slowest step", () => {
    const m = liveSteps(progress);
    expect(m.get("tidy")).toEqual({
      rowsIn: 2600,
      rowsOut: 2400,
      rate: 1200,
      state: "running",
      slowest: true,
    });
    expect(m.get("clean")?.rate).toBeNull();
    expect(liveEdgeRows(progress).get("customers.out>tidy.in")).toBe(2600);
    expect(rateWords(1500.2)).toBe("1,500 rows/s");
    expect(rateWords(0.44)).toBe("0.4 rows/s");
    expect(rateWords(0)).toBe("0 rows/s");
  });

  test("the canvas chips: rows and rate in one chip, Slowest in words in another, on the first row", () => {
    const m = liveSteps(progress);
    const info = (id: string) => {
      const l = m.get(id)!;
      return {
        rowsIn: l.rowsIn,
        rowsOut: l.rowsOut,
        outcome: null,
        error: null,
        live: { rate: l.rate, state: l.state, slowest: l.slowest },
      };
    };
    const [first] = stepChips({
      problems: 0,
      run: info("tidy"),
      quality: null,
      predicted: null,
    });
    expect(first).toMatchObject([
      { kind: "live", tone: "info", text: "2,400 out · 1,200 rows/s", title: "2,400 rows out · 1,200 rows/s" },
      { kind: "slowest", tone: "orange", text: "Slowest" },
    ]);
    expect(
      stepChips({
        problems: 0,
        run: info("clean"),
        quality: null,
        predicted: null,
      })[0],
    ).toMatchObject([{ kind: "live", tone: "neutral", text: "Waiting for rows" }]);
    expect(runWords(info("tidy"))).toBe("2,400 rows out · 1,200 rows/s · Slowest");
    // Live chips sit on the first row, so a step's height does not change as they come and go.
    expect(
      badgeRows({
        problems: 0,
        run: info("tidy"),
        quality: null,
        predicted: null,
      }),
    ).toEqual({
      first: true,
      measured: false,
      predicted: false,
      gate: false,
    });
  });
});
