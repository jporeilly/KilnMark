import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import type { PipelineRun, PipelineRunDetail, PipelineRunProgress, PipelineNodeRun } from "../api";
import { badgeRows, runWords, nodeChips } from "./canvas/NodeCard";
import { liveEdgeRows, liveNodes, rateWords } from "./live";
import { RunTimeline, qualityMark } from "./RunTimeline";
import { RunTimeSpent, limitWords } from "./RunTimeSpent";
import { RatesChart } from "./RunRates";
import { rates, nearest, ratePath } from "./rates";
import { limiting, shares, timeSpent } from "./timeSpent";
import {
  criticalPath,
  offsetWords,
  peakConcurrent,
  rowWords,
  shareOfRun,
  shareWords,
  slowestNode,
  ticks,
  timeline,
} from "./timeline";

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

const node = (
  id: string,
  start: number,
  first: number | null,
  last: number | null,
  end: number,
  outcome: PipelineNodeRun["outcome"] = "finished",
): PipelineNodeRun => ({
  node: id,
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
  nodes: [
    node("customers", 10, 400, 2900, 3000),
    node("tidy", 12, 420, 2950, 3010),
    node("clean", 15, 450, 3900, 3950, "failed"),
  ],
  written: [],
  read: [],
  lineage: [],
  notes: [],
  progress: { at: at(4000), workerStartedAt: at(0), workerFinishedAt: at(4000), slowest: null, nodes: [], edges: [] },
  ...over,
});

const progress: PipelineRunProgress = {
  at: at(2000),
  workerStartedAt: at(0),
  slowest: "tidy",
  nodes: [
    {
      node: "customers",
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
      node: "tidy",
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
      node: "clean",
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
  test("each node from the run's start, overlapping, to the run's end", () => {
    const t = timeline(detail())!;
    expect(t.live).toBe(false);
    expect(t.span).toBe(4000);
    expect(t.rows.map((r) => [r.node, r.start, r.firstRow, r.lastRow, r.end])).toEqual([
      ["customers", 10, 400, 2900, 3000],
      ["tidy", 12, 420, 2950, 3010],
      ["clean", 15, 450, 3900, 3950],
    ]);
    expect(rowWords(t.rows[0])).toBe("Started at 10 ms, first row at 400 ms, last row at 2.9 s, ended at 3.0 s, after 3.0 s");
  });

  test("nothing to draw for a run whose nodes were not timed", () => {
    const old = detail({
      nodes: [
        {
          ...node("a", 0, 0, 0, 0),
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

  test("while the run goes, from its progress, to now, with the slowest node", () => {
    const t = timeline(
      detail({
        run: run({ status: "running", finishedAt: null }),
        nodes: [],
        progress,
      }),
      { now: T0 + 2500, fetchedAt: T0 + 2000 },
    )!;
    expect(t.live).toBe(true);
    expect(t.span).toBe(2500);
    expect(t.rows.find((r) => r.slowest)?.node).toBe("tidy");
    const clean = t.rows.find((r) => r.node === "clean")!;
    expect(clean.running).toBe(true);
    expect(rowWords(clean)).toBe("Started at 15 ms, waiting for its first row, still running");
  });

  test("drawn with words: a summary, each row a button saying how it ran, Slowest as a word, and a table", () => {
    const html = renderToStaticMarkup(
      <RunTimeline
        detail={detail({
          run: run({ status: "running", finishedAt: null }),
          nodes: [],
          progress,
        })}
        clock={{ now: T0 + 2500, fetchedAt: T0 + 2000 }}
      />,
    );
    expect(html).toContain('class="visually-hidden">Timeline of');
    expect(html).toContain("the slowest node now is tidy");
    expect(html).toContain("Choose a row for everything recorded about it.");
    expect(html).toContain(">Slowest<");
    expect(html).toContain("Show the timeline as a table");
    expect(html).toContain("Waiting for rows");
    const done = renderToStaticMarkup(<RunTimeline detail={detail()} />);
    expect(done).toContain("pl-tl-bar is-failed");
    expect(done).toContain(">Failed<");
    // A row for the run itself, chosen at first, with its details below; each node's time at its bar's end.
    expect(done).toMatch(/<button[^>]*aria-current="true"[^>]*>customer-copy<\/button>/);
    expect(done).toContain(">Run of customer-copy</h3>");
    expect(done).toContain('class="run-took"');
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
  test("rows so far, the rate and the slowest node", () => {
    const m = liveNodes(progress);
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
    const m = liveNodes(progress);
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
    const [first] = nodeChips({
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
      nodeChips({
        problems: 0,
        run: info("clean"),
        quality: null,
        predicted: null,
      })[0],
    ).toMatchObject([{ kind: "live", tone: "neutral", text: "Waiting for rows" }]);
    expect(runWords(info("tidy"))).toBe("2,400 rows out · 1,200 rows/s · Slowest");
    // Live chips sit on the first row, so a node's height does not change as they come and go.
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

describe("the critical path and the run at a glance", () => {
  const edges = (pairs: [string, string][]) =>
    pairs.map(([from, to]) => ({
      from,
      fromPort: "out",
      to,
      toPort: "in",
      rows: 1,
      queued: 0,
      capacity: 4,
      full: false,
      empty: true,
    }));
  const withEdges = (pairs: [string, string][]) =>
    detail({
      progress: {
        at: at(4000),
        workerStartedAt: at(0),
        workerFinishedAt: at(4000),
        slowest: null,
        nodes: [],
        edges: edges(pairs),
      },
    });

  test("walks back from the node that ended last through the input whose rows arrived last", () => {
    const d = detail({
      nodes: [
        node("customers", 10, 400, 2900, 3000),
        node("tidy", 12, 420, 2950, 3010),
        node("aside", 14, 430, 3600, 3610),
        node("clean", 15, 450, 3900, 3950),
      ],
      progress: {
        at: at(4000),
        workerStartedAt: at(0),
        workerFinishedAt: at(4000),
        slowest: null,
        nodes: [],
        edges: edges([
          ["customers", "tidy"],
          ["customers", "aside"],
          ["tidy", "clean"],
          ["aside", "clean"],
        ]),
      },
    });
    const t = timeline(d)!;
    const p = criticalPath(t, d.progress!.edges)!;
    expect(p.nodes).toEqual(["customers", "aside", "clean"]);
    expect(p.share).toBeCloseTo(0.985, 3);
    expect(shareWords(0.82)).toBe("82%");
    expect(peakConcurrent(t)).toBe(4);
    expect(slowestNode(t)?.node).toBe("clean");
    expect(shareOfRun(t.rows[0], t.span)).toBeCloseTo(0.7475, 3);
  });

  test("none while the run goes, or without its connections", () => {
    expect(criticalPath(timeline(detail())!, [])).toBeNull();
    const live = timeline(detail({ run: run({ status: "running", finishedAt: null }), nodes: [], progress }), {
      now: T0 + 2500,
      fetchedAt: T0 + 2000,
    })!;
    expect(criticalPath(live, progress.edges)).toBeNull();
    expect(slowestNode(live)?.node).toBe("tidy");
  });

  test("tiles, the path behind a toggle (off at first), and quality in words after each time", () => {
    const d = withEdges([
      ["customers", "tidy"],
      ["tidy", "clean"],
    ]);
    d.quality = [
      {
        node: "tidy",
        role: "gate",
        asset: null,
        measured: null,
        predicted: null,
        note: null,
        gate: {
          onFail: "quarantine",
          decision: "quarantined",
          asset: null,
          rowsIn: 5000,
          rowsPassed: 4771,
          rowsFailing: 229,
          rowsQuarantined: 229,
          minScore: null,
          maxFailingShare: null,
          stopReason: null,
          rules: [],
        },
      },
      {
        node: "clean",
        role: "output",
        asset: null,
        measured: { overall: 0.99, dimensions: {}, reasons: [] },
        predicted: null,
        note: null,
        gate: null,
      },
    ];
    const html = renderToStaticMarkup(<RunTimeline detail={d} />);
    expect(html).toContain("Wall-clock time");
    expect(html).toContain("Most nodes at once");
    expect(html).toContain("customers → tidy → clean, 99% of the run");
    expect(html).toContain("Show the critical path");
    // Off at first: the chart stays calm until someone asks for the path.
    expect(html).not.toContain("pl-tl-row is-critical");
    expect(html).not.toContain("the critical path is");
    expect(html).toContain("> · Quarantined 229<");
    expect(html).toContain("> · Quality 99% (Good)<");
    expect(qualityMark(d.quality[0])).toEqual({ text: "Quarantined 229", tone: "orange" });
    expect(qualityMark(undefined)).toBeNull();
  });
});

describe("where each node spent its time, and what limited the run", () => {
  const chain = (over: Partial<PipelineRunDetail> = {}) =>
    detail({
      nodes: [
        { ...node("read", 0, 300, 3800, 3900), waitingForRowsMs: 0, heldUpMs: 2600 },
        { ...node("check", 10, 320, 3850, 3900), waitingForRowsMs: 100, heldUpMs: 0 },
        { ...node("write", 20, 330, 3880, 3920), waitingForRowsMs: 3100, heldUpMs: 0 },
      ],
      progress: {
        at: at(4000),
        workerStartedAt: at(0),
        workerFinishedAt: at(4000),
        slowest: null,
        nodes: [],
        edges: [
          {
            from: "read",
            fromPort: "out",
            to: "check",
            toPort: "in",
            rows: 1,
            queued: 0,
            capacity: 4,
            full: false,
            empty: true,
            fill: 0,
          },
          {
            from: "check",
            fromPort: "out",
            to: "write",
            toPort: "in",
            rows: 1,
            queued: 0,
            capacity: 4,
            full: false,
            empty: true,
            fill: 0,
          },
        ],
      },
      ...over,
    });

  test("each node's time is working, waiting for rows and held up, adding up to all of it", () => {
    const s = timeSpent(chain())!;
    const read = s.rows.find((r) => r.node === "read")!;
    expect(read.active).toBe(3900);
    expect(shares(read).heldUp).toBeCloseTo(2600 / 3900, 5);
    const sum = shares(read).working + shares(read).waiting + shares(read).heldUp;
    expect(sum).toBeCloseTo(1, 5);
    expect(s.limit).toEqual({ node: "check", working: expect.closeTo(3790 / 3890, 5), bySource: false });
    expect(limitWords(s.limit, false)).toBe("What limited this run: check, working 97% of the time.");
  });

  test("with nothing held up, the run was limited by reading its source", () => {
    const rows = [
      { node: "read", active: 1000, working: 950, waiting: 0, heldUp: 50 - 50, running: false, source: true },
      { node: "write", active: 1000, working: 100, waiting: 900, heldUp: 0, running: false, source: false },
    ];
    const l = limiting(rows, [{ from: "read", to: "write" }]);
    expect(l).toEqual({ node: "read", working: 0.95, bySource: true });
    expect(limitWords(l, false)).toBe(
      "Nothing was held up: this run was limited by reading its source, read (working 95% of the time).",
    );
  });

  test("older runs without waits show nothing; drawn with tiles, patterns in words and a table", () => {
    expect(timeSpent(detail())).toBeNull();
    const html = renderToStaticMarkup(<RunTimeSpent detail={chain()} />);
    expect(html).toContain("What limited this run: check, working 97% of the time.");
    expect(html).toContain("read: working 33%, waiting for rows 0%, held up by the next node 67%");
    expect(html).toContain("Limited the run");
    expect(html).toContain("Show where each node spent its time as a table");
    expect(html).toContain("Wall-clock time");
    expect(html).not.toContain("Critical path");
  });

  test("live: from the progress, to its snapshot plus the time since it was fetched", () => {
    const live = detail({
      run: run({ status: "running", finishedAt: null }),
      nodes: [],
      progress: {
        ...progress,
        nodes: progress.nodes.map((p) => ({ ...p, waitingForRowsMs: 100, heldUpMs: p.node === "customers" ? 1500 : 0 })),
      },
    });
    const s = timeSpent(live, { now: T0 + 2500, fetchedAt: T0 + 2000 })!;
    expect(s.live).toBe(true);
    expect(s.rows.find((r) => r.node === "customers")!.active).toBe(2490);
    expect(s.limit?.node).toBe("tidy");
  });
});

describe("rows a second through the run", () => {
  const series = {
    nodes: ["read", "check"],
    stride: 1,
    points: [
      { t: 1000, r: [100, 80], q: [0.25, 0] },
      { t: 2000, r: [300, 90], q: [1, 0] },
      { t: 3000, r: [0, 85], q: [1, 0] },
    ],
  };
  const d = () =>
    detail({
      progress: {
        at: at(4000),
        workerStartedAt: at(0),
        workerFinishedAt: at(4000),
        slowest: null,
        nodes: [],
        edges: [
          {
            from: "read",
            fromPort: "out",
            to: "check",
            toPort: "in",
            rows: 1,
            queued: 0,
            capacity: 4,
            full: false,
            empty: true,
            fill: 0,
          },
        ],
      },
    });

  test("a line per node with its peak, average and how often its output queue was full", () => {
    const r = rates(d(), series)!;
    expect(r.span).toBe(4000);
    const read = r.lines[0];
    expect(read.peak).toBe(300);
    expect(read.average).toBe(200);
    expect(read.fullShare).toBeCloseTo(2 / 3, 5);
    expect(read.hasOutput).toBe(true);
    expect(r.lines[1].hasOutput).toBe(false);
    expect(nearest(read.points, 2400)).toBe(1);
    expect(ratePath(read.points, 4000, 300, 1000, 36)).toBe("M250.0,23.7 L500.0,-1.0 L750.0,35.0".replace("-1.0", "1.0"));
    expect(rates(detail(), null)).toBeNull();
  });

  test("drawn with a Held up band, its words and a table", () => {
    const html = renderToStaticMarkup(<RatesChart detail={d()} series={series} marks={[]} />);
    expect(html).toContain("peak 300 rows/s");
    expect(html).toContain("output queue full 67% of the time");
    expect(html).toContain("Held up: how full its output queue was");
    expect(html).toContain('class="is-full"');
    expect(html).toContain("Show rows a second as a table");
  });
});
