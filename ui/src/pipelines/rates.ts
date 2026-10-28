/**
 * Rows a second through a run: each step's rate at each progress snapshot, and how full its output
 * queue was (back-pressure: the next step not keeping up), on the worker's clock from the run's
 * origin. Pure functions, unit tested.
 */
import type { PipelineRunDetail } from "../api";
import { runNow, runOrigin, type Clock } from "./timeline";

export type RatePoint = { t: number; rate: number; fill: number };

export type RateLine = {
  step: string;
  points: RatePoint[];
  peak: number;
  /** The mean of the rates while the step was moving rows (points with a rate above 0). */
  average: number;
  /** It writes to a queue another step reads: its fill is shown. */
  hasOutput: boolean;
  /** The share of the snapshots in which its output queue was full (fill of 95% or more). */
  fullShare: number;
};

export type Rates = { span: number; lines: RateLine[] };

/** A queue this full or fuller counts as full. */
export const FULL = 0.95;

/** The run's rates over time, or null when no snapshots were kept (older runs, or before the first). */
export function rates(detail: PipelineRunDetail, clock: Clock = {}): Rates | null {
  const series = detail.progressSeries;
  const origin = runOrigin(detail);
  if (!series || series.points.length === 0 || origin == null) return null;
  const outputs = new Set((detail.progress?.edges ?? []).map((e) => e.from));
  const lines = series.steps.map((step, i) => {
    const points = series.points.map((p) => ({ t: p.t, rate: p.r[i] ?? 0, fill: p.q[i] ?? 0 }));
    const moving = points.filter((p) => p.rate > 0);
    return {
      step,
      points,
      peak: Math.max(0, ...points.map((p) => p.rate)),
      average: moving.length ? moving.reduce((a, p) => a + p.rate, 0) / moving.length : 0,
      hasOutput: outputs.has(step) || points.some((p) => p.fill > 0),
      fullShare: points.length ? points.filter((p) => p.fill >= FULL).length / points.length : 0,
    };
  });
  const last = Math.max(...series.points.map((p) => p.t));
  return { span: Math.max(1, last, runNow(detail, origin, clock) ?? 0), lines };
}

/** The point nearest a time, for hover and the keyboard. */
export function nearest(points: RatePoint[], t: number): number {
  let best = 0;
  for (let i = 1; i < points.length; i++) if (Math.abs(points[i].t - t) < Math.abs(points[best].t - t)) best = i;
  return best;
}

/** An SVG path through the rates, in a box `w` by `h`, scaled to `top` rows a second and `span` ms. */
export function ratePath(points: RatePoint[], span: number, top: number, w: number, h: number): string {
  if (points.length === 0) return "";
  const x = (t: number) => ((t / span) * w).toFixed(1);
  const y = (r: number) => (h - (top > 0 ? (r / top) * (h - 2) : 0) - 1).toFixed(1);
  return points.map((p, i) => `${i === 0 ? "M" : "L"}${x(p.t)},${y(p.rate)}`).join(" ");
}
