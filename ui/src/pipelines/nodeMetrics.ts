/**
 * A node's metrics from a run that has ended: its rows, its time and how that time went (working,
 * waiting for rows, held up by the next node), its rate and its memory. Shown in the Nodes tab and in
 * a node's settings under the canvas. Pure functions, unit tested.
 */
import type { PipelineNodeRun } from "../api";

export type NodeMetrics = {
  rowsIn: number | null;
  rowsOut: number | null;
  /** From start to end. */
  durationMs: number | null;
  /** Rows a second while rows moved (first to last row), or over its whole time when those are not known. */
  rate: number | null;
  /** From the start to its first row: time spent setting up, or waiting for the nodes before it. */
  firstRowMs: number | null;
  /** Its time, split: working, waiting for rows on its inputs, and held up by the next node. Null when not recorded. */
  split: { workingMs: number; waitingMs: number; heldUpMs: number } | null;
  peakMemory: number | null;
  outcome: PipelineNodeRun["outcome"];
  /** The node that held the run back most. */
  slowest: boolean;
};

const at = (iso: string | null | undefined) => (iso ? new Date(iso).getTime() : null);

/** A node's metrics from its run; {@code slowest} names the run's slowest node, if known. */
export function nodeMetrics(r: PipelineNodeRun, slowest: string | null = null): NodeMetrics {
  const rows = r.rowsOut ?? r.rowsIn;
  const first = at(r.firstRowAt);
  const last = at(r.lastRowAt);
  const moving = first != null && last != null && last > first ? last - first : r.durationMs;
  const rate = rows != null && moving != null && moving > 0 ? rows / (moving / 1000) : null;
  const started = at(r.startedAt);
  const waiting = r.waitingForRowsMs ?? null;
  const heldUp = r.heldUpMs ?? null;
  const split =
    r.durationMs != null && (waiting != null || heldUp != null)
      ? {
          waitingMs: Math.max(0, waiting ?? 0),
          heldUpMs: Math.max(0, heldUp ?? 0),
          workingMs: Math.max(0, r.durationMs - Math.max(0, waiting ?? 0) - Math.max(0, heldUp ?? 0)),
        }
      : null;
  return {
    rowsIn: r.rowsIn,
    rowsOut: r.rowsOut,
    durationMs: r.durationMs,
    rate,
    firstRowMs: started != null && first != null && first >= started ? first - started : null,
    split,
    peakMemory: r.peakMemory,
    outcome: r.outcome,
    slowest: slowest != null && slowest === r.node,
  };
}

/** A share of a node's time as a whole percentage, "0%" to "100%". */
export function shareOf(part: number, whole: number): string {
  if (!(whole > 0)) return "0%";
  return `${Math.round((Math.min(part, whole) / whole) * 100)}%`;
}

/** The split in a few words: "80% working, 15% waiting for rows, 5% held up". */
export function splitWords(m: NodeMetrics): string | null {
  if (!m.split || m.durationMs == null) return null;
  const { workingMs, waitingMs, heldUpMs } = m.split;
  const total = workingMs + waitingMs + heldUpMs || m.durationMs;
  return `${shareOf(workingMs, total)} working, ${shareOf(waitingMs, total)} waiting for rows, ${shareOf(heldUpMs, total)} held up`;
}
