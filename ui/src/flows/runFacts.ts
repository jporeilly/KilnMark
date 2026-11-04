/**
 * A flow run's summary and where each node spent its time (docs/guide/flows.md, "Reading a run"),
 * from the times Kilnmark records for each node and for each pipeline run a node started, and
 * what those pipeline runs wrote. Nothing here is estimated. Pure, unit tested.
 */
import { formatNumber, type FlowNodeRun, type FlowNodeStatus, type FlowRunDetail } from "../api";
import { offsetWords } from "../pipelines/timeline";

const ms = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null);
const num = (n: FlowNodeRun, k: string) => (typeof n.reported?.[k] === "number" ? (n.reported[k] as number) : null);
const plural = (n: number, one: string, many: string) => `${formatNumber(n)} ${n === 1 ? one : many}`;

/** What a node reported, in words: rows written, score, share set aside, rows changed. */
export function reportedWords(n: FlowNodeRun): string {
  const out: string[] = [];
  const written = num(n, "rows_written");
  if (written != null) out.push(`${formatNumber(written)} rows written`);
  const score = num(n, "score");
  if (score != null) out.push(`score ${Math.round(score * 100)}%`);
  const aside = num(n, "quarantined_share");
  if (aside != null) out.push(`${(aside * 100).toFixed(aside < 0.1 ? 1 : 0)}% set aside`);
  const changed = num(n, "rows_affected");
  if (changed != null) out.push(`${formatNumber(changed)} rows changed`);
  return out.join(", ");
}

const COUNTED: [FlowNodeStatus, string][] = [
  ["succeeded", "succeeded"],
  ["failed", "failed"],
  ["cancelled", "cancelled"],
  ["skipped", "skipped"],
  ["running", "running"],
  ["pending", "not started"],
];

/** The run's nodes by how they stand, in words: "3 succeeded, 1 failed, 1 skipped". */
export function nodeCountWords(nodes: FlowNodeRun[]): string {
  const parts = COUNTED.map(([status, word]) => {
    const n = nodes.filter((x) => x.status === status).length;
    return n > 0 ? `${n} ${word}` : null;
  }).filter(Boolean);
  return parts.length > 0 ? parts.join(", ") : "None";
}

export type FlowTotals = {
  pipelineRuns: number;
  /** The rows its pipeline runs wrote in all; null when none has said. */
  rowsWritten: number | null;
  /** The lowest quality score among its pipeline runs; null when nothing was measured. */
  lowestScore: number | null;
};

/**
 * What the run's pipeline runs came to: the server's totals. From an older server, which does not
 * give them: the pipeline runs counted from its nodes and items, and the rows and lowest score its
 * run-pipeline nodes reported, not known when it has a for-each node (its items report none here).
 */
export function flowTotals(detail: FlowRunDetail): FlowTotals {
  const r = detail.run;
  if (r.pipelineRuns != null) {
    return { pipelineRuns: r.pipelineRuns, rowsWritten: r.rowsWritten ?? null, lowestScore: r.qualityScore ?? null };
  }
  const pipelineRuns = detail.nodes.reduce(
    (n, x) => n + (x.pipelineRunId != null ? 1 : 0) + (x.items ?? []).filter((i) => i.pipelineRunId != null).length,
    0,
  );
  const each = detail.nodes.some((n) => n.items != null);
  const ran = detail.nodes.filter((n) => n.pipelineRunId != null);
  const rows = ran.map((n) => num(n, "rows_written")).filter((x): x is number => x != null);
  const scores = ran.map((n) => num(n, "score")).filter((x): x is number => x != null);
  return {
    pipelineRuns,
    rowsWritten: each || rows.length === 0 ? null : rows.reduce((a, b) => a + b, 0),
    lowestScore: each || scores.length === 0 ? null : Math.min(...scores),
  };
}

/** One node's time, from its start to its end (or to now while it runs), split three ways. */
export type FlowSpentRow = {
  node: string;
  type: string;
  /** Milliseconds from its start to its end, or to now while it runs. */
  active: number;
  /** Running its pipeline (any of them, for a for-each node), or doing its own work. */
  working: number;
  /** None of its pipeline runs running, and at least one queued for a worker. */
  queued: number;
  /** The rest: a wait node waiting; before its pipeline run was asked for; the flow seeing a run end. */
  waiting: number;
  running: boolean;
  /** False when the node ran a pipeline whose run times were not given (older servers): not split. */
  split: boolean;
};

export type FlowSpent = {
  rows: FlowSpentRow[];
  live: boolean;
  /** The run's wall-clock time: start to end, or to now. */
  span: number;
  /** Most nodes going at once. */
  mostNodes: number;
  /** Most pipeline runs running at once; null when their times were not given. */
  mostPipelineRuns: number | null;
  /** The node that took longest (while the run goes, so far). */
  longest: FlowSpentRow | null;
  /** Its pipeline runs' time queued for a worker, summed; null when not given. */
  queued: { ms: number; runs: number } | null;
  /**
   * Time run-pipeline nodes spent before asking for their run, while another run of the pipeline
   * was going (one run of a pipeline at a time); null when not given.
   */
  busy: number | null;
  /** Time spent in wait nodes. */
  waitNodes: number;
};

type PipelineSpan = { requested: number | null; started: number | null; finished: number | null };

const PIPELINE_TYPES = new Set(["run-pipeline", "for-each"]);

/** The pipeline runs a node started, with their times; null when it started some whose times are not given. */
function pipelineSpans(n: FlowNodeRun): PipelineSpan[] | null {
  if (n.type === "for-each") {
    const items = (n.items ?? []).filter((i) => i.pipelineRunId != null);
    if (items.some((i) => i.requestedAt === undefined)) return null;
    return items.map((i) => ({ requested: ms(i.requestedAt), started: ms(i.startedAt), finished: ms(i.finishedAt) }));
  }
  const t = n.pipelineRunTimes;
  if (t) return [{ requested: ms(t.requestedAt), started: ms(t.startedAt), finished: ms(t.finishedAt) }];
  return n.pipelineRunId == null ? [] : null;
}

/** Peak overlap of intervals; one starting as another ends does not overlap it. */
export function peak(intervals: [number, number][]): number {
  const marks: [number, number][] = [];
  for (const [a, b] of intervals) marks.push([a, 1], [Math.max(a, b), -1]);
  marks.sort((x, y) => x[0] - y[0] || x[1] - y[1]);
  let now = 0;
  let most = 0;
  for (const [, d] of marks) {
    now += d;
    most = Math.max(most, now);
  }
  return most;
}

/**
 * A node's time from `start` to `end` split by what its pipeline runs were doing: working while
 * any ran, queued while none ran and one waited for a worker, and waiting otherwise.
 */
export function splitTime(start: number, end: number, spans: PipelineSpan[], now: number) {
  const running: [number, number][] = [];
  const queued: [number, number][] = [];
  for (const s of spans) {
    if (s.started != null) running.push([s.started, s.finished ?? now]);
    if (s.requested != null) queued.push([s.requested, s.started ?? s.finished ?? now]);
  }
  const points = new Set([start, end]);
  for (const [a, b] of [...running, ...queued]) {
    if (a > start && a < end) points.add(a);
    if (b > start && b < end) points.add(b);
  }
  const sorted = [...points].sort((a, b) => a - b);
  const out = { working: 0, queued: 0, waiting: 0 };
  const covers = (list: [number, number][], t: number) => list.some(([a, b]) => a <= t && t < b);
  for (let i = 0; i + 1 < sorted.length; i++) {
    const a = sorted[i];
    const b = sorted[i + 1];
    const mid = (a + b) / 2;
    if (covers(running, mid)) out.working += b - a;
    else if (covers(queued, mid)) out.queued += b - a;
    else out.waiting += b - a;
  }
  return out;
}

/**
 * Where each node of a flow run spent its time: null before the run starts. `now` is the time to
 * measure running nodes to, normally the page's clock.
 */
export function flowTimeSpent(detail: FlowRunDetail, now: number = Date.now()): FlowSpent | null {
  const started = detail.nodes.map((n) => ms(n.startedAt)).filter((x): x is number => x != null);
  const origin = ms(detail.run.startedAt) ?? (started.length > 0 ? Math.min(...started) : null);
  if (origin == null || started.length === 0) return null;
  const live = detail.run.status === "queued" || detail.run.status === "running";
  const rows: FlowSpentRow[] = [];
  const nodeSpans: [number, number][] = [];
  const pipelineRunning: [number, number][] = [];
  let unknown = false;
  let queuedMs = 0;
  let queuedRuns = 0;
  let busy = 0;
  let waitNodes = 0;
  for (const n of [...detail.nodes].sort((a, b) => a.position - b.position)) {
    const start = ms(n.startedAt);
    if (start == null) continue;
    const going = n.status === "running";
    // The browser's clock can be behind the server's: a node never ends before it starts.
    const end = Math.max(start, ms(n.finishedAt) ?? (going ? now : start));
    const active = end - start;
    nodeSpans.push([start, end]);
    const row: FlowSpentRow = { node: n.node, type: n.type, active, working: 0, queued: 0, waiting: 0, running: going, split: true };
    if (!PIPELINE_TYPES.has(n.type)) {
      if (n.type === "wait") {
        row.waiting = active;
        waitNodes += active;
      } else {
        row.working = active;
      }
    } else {
      const spans = pipelineSpans(n);
      if (spans == null) {
        row.split = false;
        unknown = true;
      } else {
        Object.assign(row, splitTime(start, end, spans, Math.max(now, start)));
        for (const s of spans) {
          if (s.started != null) pipelineRunning.push([s.started, Math.max(s.started, s.finished ?? now)]);
          if (s.requested != null) {
            queuedRuns++;
            queuedMs += Math.max(0, (s.started ?? s.finished ?? now) - s.requested);
          }
        }
        if (n.type === "run-pipeline") {
          const asked = spans[0]?.requested ?? (going ? now : null);
          if (asked != null) busy += Math.max(0, Math.min(asked, end) - start);
        }
      }
    }
    rows.push(row);
  }
  if (rows.length === 0) return null;
  const runEnd = ms(detail.run.finishedAt) ?? (live ? now : Math.max(...nodeSpans.map(([, b]) => b)));
  const span = Math.max(1, runEnd - origin, ...nodeSpans.map(([, b]) => b - origin));
  const longest = rows.reduce<FlowSpentRow | null>((a, b) => (a == null || b.active > a.active ? b : a), null);
  return {
    rows,
    live,
    span,
    mostNodes: peak(nodeSpans),
    mostPipelineRuns: unknown ? null : peak(pipelineRunning),
    longest: longest && longest.active > 0 ? longest : null,
    queued: unknown ? null : { ms: queuedMs, runs: queuedRuns },
    busy: unknown ? null : busy,
    waitNodes,
  };
}

/** A node's share of its own time: working, queued and waiting, adding up to 1. */
export function flowShares(r: FlowSpentRow): { working: number; queued: number; waiting: number } {
  if (r.active <= 0 || !r.split) return { working: 0, queued: 0, waiting: 0 };
  return { working: r.working / r.active, queued: r.queued / r.active, waiting: r.waiting / r.active };
}

/** Waits shorter than this are left out of the run's line in words: they are the flow looking again. */
export const WORTH_SAYING_MS = 1000;

/**
 * What the run waited for, in words, only what was measured: "Its 6 pipeline runs waited 4.2 s in
 * all for a worker. Nodes waited 30.0 s for a pipeline busy with another run. Wait nodes waited
 * 10.0 s. At most 3 pipeline runs ran at once." Null when there is nothing to say.
 */
export function waitWords(s: FlowSpent): string | null {
  const out: string[] = [];
  if (s.queued && s.queued.runs > 0) {
    const runs = s.queued.runs === 1 ? "Its pipeline run" : `Its ${s.queued.runs} pipeline runs`;
    const waited = s.queued.ms >= WORTH_SAYING_MS ? offsetWords(s.queued.ms) : "under a second";
    out.push(`${runs} waited ${waited} in all for a worker${s.live ? " so far" : ""}.`);
  }
  if (s.busy != null && s.busy >= WORTH_SAYING_MS) {
    out.push(`Nodes waited ${offsetWords(s.busy)} for a pipeline busy with another run.`);
  }
  if (s.waitNodes > 0) out.push(`Wait nodes waited ${offsetWords(s.waitNodes)}.`);
  if (s.mostPipelineRuns != null && s.mostPipelineRuns > 1) {
    out.push(`At most ${plural(s.mostPipelineRuns, "pipeline run", "pipeline runs")} ran at once.`);
  }
  return out.length > 0 ? out.join(" ") : null;
}
