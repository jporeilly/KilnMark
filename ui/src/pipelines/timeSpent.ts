/**
 * Where each node of a run spent its time, and what limited the run: each node's time from its start
 * to its end (or to now, while it runs) split into working, waiting for rows on its inputs, and held
 * up by the next node (waiting for room on its outputs: back-pressure). From the nodes recorded when
 * the run ended or, while it runs, its latest progress; on the worker's clock. Pure, unit tested.
 */
import type { PipelineRunDetail } from "../api";
import { runNow, runOrigin, type Clock } from "./timeline";

export type SpentRow = {
  node: string;
  /** Milliseconds from its start to its end, or to now while it runs. */
  active: number;
  working: number;
  waiting: number;
  heldUp: number;
  running: boolean;
  /** No input: a source, which reads. */
  source: boolean;
};

export type Spent = {
  rows: SpentRow[];
  live: boolean;
  /**
   * What limited the run: the node with the highest working share among the nodes others were held
   * up by; else, when nothing was held up, the source working most (the run was limited by reading).
   */
  limit: { node: string; working: number; bySource: boolean } | null;
};

/** A node's share of its own time: working, waiting for rows and held up, adding up to 1. */
export function shares(r: SpentRow): { working: number; waiting: number; heldUp: number } {
  if (r.active <= 0) return { working: 0, waiting: 0, heldUp: 0 };
  return { working: r.working / r.active, waiting: r.waiting / r.active, heldUp: r.heldUp / r.active };
}

/** A node counts as held up when the nodes after it held it up for at least this share of its time. */
export const HELD_UP_SHARE = 0.05;

const ms = (iso: string | null | undefined) => (iso ? Date.parse(iso) : null);

/** Where each node spent its time: null when its waits were not recorded (older runs) or nothing has started. */
export function timeSpent(detail: PipelineRunDetail, clock: Clock = {}): Spent | null {
  const origin = runOrigin(detail);
  if (origin == null) return null;
  const edges = detail.progress?.edges ?? [];
  const fed = new Set(edges.map((e) => e.to));
  const unfinished = detail.run.status === "queued" || detail.run.status === "running";
  const recorded = detail.nodes.some((s) => s.startedAt != null && s.waitingForRowsMs != null);
  const now = runNow(detail, origin, clock) ?? 0;
  const row = (node: string, start: number | null, end: number | null, waiting: number, heldUp: number, running: boolean) => {
    if (start == null) return null;
    const active = Math.max(0, (end ?? now) - start);
    const waits = Math.min(active, waiting + heldUp);
    const scale = waiting + heldUp > 0 ? waits / (waiting + heldUp) : 0;
    return {
      node,
      active,
      waiting: waiting * scale,
      heldUp: heldUp * scale,
      working: Math.max(0, active - waits),
      running,
      source: !fed.has(node),
    };
  };
  let rows: (SpentRow | null)[];
  let live = false;
  if (recorded) {
    rows = detail.nodes.map((s) =>
      row(
        s.node,
        ms(s.startedAt) == null ? null : ms(s.startedAt)! - origin,
        ms(s.finishedAt) == null ? null : ms(s.finishedAt)! - origin,
        s.waitingForRowsMs ?? 0,
        s.heldUpMs ?? 0,
        false,
      ),
    );
  } else if (unfinished && detail.progress && detail.progress.nodes.some((s) => s.waitingForRowsMs != null)) {
    live = true;
    rows = detail.progress.nodes.map((s) =>
      row(
        s.node,
        ms(s.startedAt) == null ? null : ms(s.startedAt)! - origin,
        ms(s.finishedAt) == null ? null : ms(s.finishedAt)! - origin,
        s.waitingForRowsMs ?? 0,
        s.heldUpMs ?? 0,
        s.state === "waiting" || s.state === "running",
      ),
    );
  } else {
    return null;
  }
  const out = rows.filter((r): r is SpentRow => r != null);
  if (out.length === 0) return null;
  return { rows: out, live, limit: limiting(out, edges) };
}

/** See {@link Spent.limit}. */
export function limiting(rows: SpentRow[], edges: { from: string; to: string }[]): Spent["limit"] {
  const byId = new Map(rows.map((r) => [r.node, r]));
  const heldUp = new Set(rows.filter((r) => shares(r).heldUp >= HELD_UP_SHARE).map((r) => r.node));
  const blamed = [...new Set(edges.filter((e) => heldUp.has(e.from)).map((e) => e.to))]
    .map((id) => byId.get(id))
    .filter((r): r is SpentRow => r != null && r.active > 0);
  const best = (list: SpentRow[]) => list.reduce((a, b) => (shares(b).working > shares(a).working ? b : a));
  if (blamed.length > 0) {
    const r = best(blamed);
    return { node: r.node, working: shares(r).working, bySource: false };
  }
  const sources = rows.filter((r) => r.source && r.active > 0);
  if (sources.length === 0) return null;
  const r = best(sources);
  return { node: r.node, working: shares(r).working, bySource: true };
}
