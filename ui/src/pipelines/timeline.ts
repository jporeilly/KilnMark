/**
 * What a run's timeline shows, worked out from the run: each node's start, first row, last row and
 * end as milliseconds from the run's start, from the nodes recorded when it ended or, while it
 * runs, from its latest progress. Pure functions, unit tested.
 */
import type { PipelineRunDetail, PipelineRunProgress, PipelineNodeProgress } from "../api";
import { formatDuration } from "./RunStatus";

export type TimelineRow = {
  node: string;
  type: string | null;
  /** Milliseconds from the run's start; null when it did not happen (yet). */
  start: number | null;
  firstRow: number | null;
  lastRow: number | null;
  end: number | null;
  /** Still going: its bar runs to the timeline's end. */
  running: boolean;
  /** How it ended (finished, ended_early, stopped, failed) or, while running, its state. */
  outcome: string | null;
  /** The node holding the run back right now. */
  slowest: boolean;
};

export type Timeline = { span: number; rows: TimelineRow[]; live: boolean };

const ms = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null);

/**
 * When a run is read: `now` (the browser's clock) and when the run was fetched, so a live run's "now"
 * is its last snapshot's time plus the time since, on the worker's clock.
 */
export type Clock = { now?: number; fetchedAt?: number };

/**
 * The run's origin on the worker's clock: when the worker started on it, else its earliest node's
 * start. Every time on the run page counts from here, so the database's and the browser's clocks
 * are never mixed with the worker's. Null when nothing was timed.
 */
export function runOrigin(detail: PipelineRunDetail): number | null {
  const started = ms(detail.progress?.workerStartedAt);
  if (started != null) return started;
  const starts = [
    ...detail.nodes.map((s) => ms(s.startedAt)),
    ...(detail.progress?.nodes ?? []).map((s) => ms(s.startedAt)),
  ].filter((t): t is number => t != null);
  return starts.length ? Math.min(...starts) : null;
}

/**
 * Where "now" is for a run on the worker's clock, in ms from its origin: for a live run, its last
 * snapshot's time plus the time since it was fetched; for a finished one, when the worker finished
 * with it (else its last node's end). Null when not known.
 */
export function runNow(detail: PipelineRunDetail, origin: number, clock: Clock = {}): number | null {
  const now = clock.now ?? Date.now();
  const unfinished = detail.run.status === "queued" || detail.run.status === "running";
  const at = ms(detail.progress?.at);
  if (unfinished) return at == null ? null : Math.max(0, at - origin + Math.max(0, now - (clock.fetchedAt ?? now)));
  const done = ms(detail.progress?.workerFinishedAt);
  if (done != null) return Math.max(0, done - origin);
  const ends = detail.nodes.map((s) => ms(s.finishedAt)).filter((t): t is number => t != null);
  return ends.length ? Math.max(0, Math.max(...ends) - origin) : null;
}

/**
 * The timeline of a run: null when nothing was timed (a run from before nodes were timed, or one
 * still queued). Times are on the worker's clock, from {@link runOrigin}.
 */
export function timeline(detail: PipelineRunDetail, clock: Clock = {}): Timeline | null {
  const run = detail.run;
  const unfinished = run.status === "queued" || run.status === "running";
  const timed = detail.nodes.some((s) => s.startedAt != null);
  const progress: PipelineRunProgress | null | undefined = detail.progress;
  const live = !timed && unfinished && progress != null && progress.nodes.length > 0;
  if (!timed && !live) return null;
  const origin = runOrigin(detail);
  if (origin == null) return null;
  const at = (iso: string | null | undefined) => {
    const t = ms(iso);
    return t == null ? null : Math.max(0, t - origin);
  };
  let rows: TimelineRow[];
  if (timed) {
    rows = detail.nodes.map((s) => ({
      node: s.node,
      type: s.type,
      start: at(s.startedAt),
      firstRow: at(s.firstRowAt),
      lastRow: at(s.lastRowAt),
      end: at(s.finishedAt),
      running: false,
      outcome: s.outcome,
      slowest: false,
    }));
  } else {
    const types = new Map(detail.nodes.map((s) => [s.node, s.type]));
    rows = progress!.nodes.map((s: PipelineNodeProgress) => ({
      node: s.node,
      type: types.get(s.node) ?? null,
      start: at(s.startedAt),
      firstRow: at(s.firstRowAt),
      lastRow: at(s.lastRowAt),
      end: at(s.finishedAt),
      running: s.state === "waiting" || s.state === "running",
      outcome: s.state === "ended" ? "ended" : s.state,
      slowest: progress!.slowest === s.node,
    }));
  }
  const last = Math.max(0, ...rows.flatMap((r) => [r.start ?? 0, r.firstRow ?? 0, r.lastRow ?? 0, r.end ?? 0]));
  const until = Math.max(last, runNow(detail, origin, clock) ?? 0);
  return { span: Math.max(1, until), rows, live };
}

/** Where on the timeline, as a percentage of its width. */
export const pct = (t: number, span: number) => `${Math.min(100, Math.max(0, (t / span) * 100)).toFixed(3)}%`;

/** Round times for the axis: at most `most` ticks, at 1, 2 or 5 times a power of ten, from 0. */
export function ticks(span: number, most = 6): number[] {
  if (span <= 0) return [0];
  const rough = span / Math.max(1, most - 1);
  const power = 10 ** Math.floor(Math.log10(rough));
  const node = [1, 2, 5, 10].map((m) => m * power).find((s) => span / s <= most - 1) ?? 10 * power;
  const out: number[] = [];
  for (let t = 0; t <= span + 1e-9; t += node) out.push(Math.round(t * 1000) / 1000);
  return out;
}

/** An offset on the axis: "0 s", "250 ms", "1.5 s", "2 min 5 s". */
export function offsetWords(t: number): string {
  if (t === 0) return "0 s";
  return formatDuration(Math.round(t));
}

/** A row in words, for its bar's label and the table: "Started at 0 s, first row at 120 ms, ...". */
export function rowWords(r: TimelineRow): string {
  if (r.start == null) return "Did not start";
  const parts = [`started at ${offsetWords(r.start)}`];
  parts.push(
    r.firstRow != null ? `first row at ${offsetWords(r.firstRow)}` : r.running ? "waiting for its first row" : "no rows",
  );
  if (r.lastRow != null && !r.running) parts.push(`last row at ${offsetWords(r.lastRow)}`);
  if (r.end != null) parts.push(`ended at ${offsetWords(r.end)}, after ${offsetWords(r.end - r.start)}`);
  else if (r.running) parts.push("still running");
  const words = parts.join(", ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/** An edge between nodes, as a run's progress lists them. */
export type TimelineEdge = { from: string; to: string };

/**
 * The critical path: the chain of nodes whose waits set the run's length. From the node that
 * finished last, walk back through the input whose rows arrived last (the node feeding it whose
 * last row out came latest, else whose end came latest), to a node with no input. `share` is the
 * part of the run (start to end, setup and cataloguing included) that the chain spans, from its
 * first node's start to its last node's end. Null while the run goes, or without times or edges.
 */
export function criticalPath(t: Timeline, edges: TimelineEdge[]): { nodes: string[]; share: number } | null {
  if (t.live || edges.length === 0) return null;
  const byId = new Map(t.rows.map((r) => [r.node, r]));
  const ended = t.rows.filter((r) => r.end != null && r.start != null);
  if (ended.length === 0) return null;
  let at = ended.reduce((a, b) => (b.end! > a.end! ? b : a));
  const path = [at];
  const seen = new Set([at.node]);
  for (;;) {
    const feeding = edges
      .filter((e) => e.to === at.node && !seen.has(e.from))
      .map((e) => byId.get(e.from))
      .filter((r): r is TimelineRow => r != null && r.start != null);
    if (feeding.length === 0) break;
    const key = (r: TimelineRow) => r.lastRow ?? r.end ?? -1;
    at = feeding.reduce((a, b) => (key(b) > key(a) ? b : a));
    seen.add(at.node);
    path.unshift(at);
  }
  const share = (path.at(-1)!.end! - path[0].start!) / t.span;
  return { nodes: path.map((r) => r.node), share: Math.max(0, Math.min(1, share)) };
}

/** The most nodes at work at once (each from its start to its end, or to now while it runs). */
export function peakConcurrent(t: Timeline): number {
  const marks: [number, number][] = [];
  for (const r of t.rows) {
    if (r.start == null) continue;
    marks.push([r.start, 1], [r.end ?? t.span, -1]);
  }
  // Ends before starts at the same moment: a node starting as another ends does not overlap it.
  marks.sort((a, b) => a[0] - b[0] || a[1] - b[1]);
  let now = 0;
  let peak = 0;
  for (const [, d] of marks) {
    now += d;
    peak = Math.max(peak, now);
  }
  return peak;
}

/** Each node's part of the run's time, from its start to its end (or to now): 0 to 1, or null. */
export function shareOfRun(r: TimelineRow, span: number): number | null {
  if (r.start == null) return null;
  return Math.max(0, Math.min(1, ((r.end ?? span) - r.start) / span));
}

/** The node that took longest, start to end; while the run goes, the one holding it back. */
export function slowestNode(t: Timeline): TimelineRow | null {
  const now = t.rows.find((r) => r.slowest);
  if (t.live) return now ?? null;
  const timed = t.rows.filter((r) => r.start != null && r.end != null);
  return timed.length ? timed.reduce((a, b) => (b.end! - b.start! > a.end! - a.start! ? b : a)) : null;
}

/** A share in words: "82%". */
export const shareWords = (x: number) => `${Math.round(x * 100)}%`;
