/**
 * A run while it goes, for the canvas and the run page: each node's rows so far and rate, and the
 * node holding the run back, from the run's latest progress. Pure functions, unit tested.
 */
import { formatNumber, type PipelineRunProgress } from "../api";

/** Rows a second in words: "1,250 rows/s", "0.4 rows/s", or "0 rows/s". */
export function rateWords(rate: number): string {
  if (!Number.isFinite(rate) || rate <= 0) return "0 rows/s";
  if (rate < 10) return `${rate.toFixed(1)} rows/s`;
  return `${formatNumber(Math.round(rate))} rows/s`;
}

export type LiveNode = {
  rowsIn: number;
  rowsOut: number;
  /** Rows a second since the snapshot before, when rows are moving; null otherwise. */
  rate: number | null;
  state: "not_started" | "waiting" | "running" | "ended";
  slowest: boolean;
};

/** Each node's live figures by id, from a running run's progress. */
export function liveNodes(progress: PipelineRunProgress | null | undefined): Map<string, LiveNode> {
  const out = new Map<string, LiveNode>();
  for (const s of progress?.nodes ?? []) {
    out.set(s.node, {
      rowsIn: s.rowsIn,
      rowsOut: s.rowsOut,
      rate: s.state === "running" ? s.rowsPerSecond : null,
      state: s.state,
      slowest: progress?.slowest === s.node,
    });
  }
  return out;
}

/** Rows on each connection so far, by "from.port>to.port". */
export function liveEdgeRows(progress: PipelineRunProgress | null | undefined): Map<string, number> {
  return new Map((progress?.edges ?? []).map((e) => [`${e.from}.${e.fromPort}>${e.to}.${e.toPort}`, e.rows]));
}
