/**
 * What a run's timeline shows, worked out from the run: each step's start, first row, last row and
 * end as milliseconds from the run's start, from the steps recorded when it ended or, while it
 * runs, from its latest progress. Pure functions, unit tested.
 */
import type { PipelineRunDetail, PipelineRunProgress, PipelineStepProgress } from "../api";
import { formatDuration } from "./RunStatus";

export type TimelineRow = {
  step: string;
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
  /** The step holding the run back right now. */
  slowest: boolean;
};

export type Timeline = { span: number; rows: TimelineRow[]; live: boolean };

const ms = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null);

/**
 * The timeline of a run: null when nothing was timed (a run from before steps were timed, or one
 * still queued). `now` is the time to draw a running step's bar to.
 */
export function timeline(detail: PipelineRunDetail, now: number = Date.now()): Timeline | null {
  const run = detail.run;
  const unfinished = run.status === "queued" || run.status === "running";
  const timed = detail.steps.some((s) => s.startedAt != null);
  const progress: PipelineRunProgress | null | undefined = detail.progress;
  const live = !timed && unfinished && progress != null && progress.steps.length > 0;
  if (!timed && !live) return null;
  const origin =
    ms(run.startedAt) ??
    Math.min(
      ...(timed ? detail.steps.map((s) => ms(s.startedAt) ?? Infinity) : progress!.steps.map((s) => ms(s.startedAt) ?? Infinity)),
    );
  const at = (iso: string | null | undefined) => {
    const t = ms(iso);
    return t == null ? null : Math.max(0, t - origin);
  };
  let rows: TimelineRow[];
  if (timed) {
    rows = detail.steps.map((s) => ({
      step: s.step,
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
    const types = new Map(detail.steps.map((s) => [s.step, s.type]));
    rows = progress!.steps.map((s: PipelineStepProgress) => ({
      step: s.step,
      type: types.get(s.step) ?? null,
      start: at(s.startedAt),
      firstRow: at(s.firstRowAt),
      lastRow: at(s.lastRowAt),
      end: at(s.finishedAt),
      running: s.state === "waiting" || s.state === "running",
      outcome: s.state === "ended" ? "ended" : s.state,
      slowest: progress!.slowest === s.step,
    }));
  }
  const ended = ms(run.finishedAt);
  const last = Math.max(0, ...rows.flatMap((r) => [r.start ?? 0, r.firstRow ?? 0, r.lastRow ?? 0, r.end ?? 0]));
  const until = unfinished ? Math.max(last, now - origin) : ended != null ? Math.max(last, ended - origin) : last;
  return { span: Math.max(1, until), rows, live };
}

/** Where on the timeline, as a percentage of its width. */
export const pct = (t: number, span: number) => `${Math.min(100, Math.max(0, (t / span) * 100)).toFixed(3)}%`;

/** Round times for the axis: at most `most` ticks, at 1, 2 or 5 times a power of ten, from 0. */
export function ticks(span: number, most = 6): number[] {
  if (span <= 0) return [0];
  const rough = span / Math.max(1, most - 1);
  const power = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 5, 10].map((m) => m * power).find((s) => span / s <= most - 1) ?? 10 * power;
  const out: number[] = [];
  for (let t = 0; t <= span + 1e-9; t += step) out.push(Math.round(t * 1000) / 1000);
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
