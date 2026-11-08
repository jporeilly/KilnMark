/**
 * Predicted quality (docs/guide/quality-gates.md, "Predicted quality"): what the catalogue's metadata
 * says each write and gate will score before the pipeline runs, and suggestions that would raise
 * it. Every figure is an estimate and is labelled so wherever it is shown. Calls and pure word
 * helpers, unit tested; loaded with the pipeline pages, outside the main bundle.
 */
import { call, rating, type QualityScore, plural } from "../api";
import { dimensionWord, percent } from "./quality";

export type PredictedRule = {
  name: string;
  type: string;
  field: string | null;
  dimension: string;
  origin: "asset" | "inline";
  /** Share of rows expected to fail; null when the metadata cannot tell. */
  failingShare: number | null;
  status: "pass" | "warn" | "fail" | "error";
  basis: string;
};
export type PredictedGate = {
  onFail: string;
  rowsIn: number | null;
  rowsPassed: number | null;
  rowsQuarantined: number | null;
  decision: "passed" | "warned" | "quarantined" | "stopped" | null;
  rules: PredictedRule[];
};
export type PredictedQuality = {
  node: string;
  role: "output" | "gate";
  asset: string | null;
  /** Null when nothing could be predicted. */
  overall: number | null;
  dimensions: Record<string, number>;
  reasons: string[];
  unknown: string[];
  assumptions: string[];
  rows: number | null;
  gate: PredictedGate | null;
};
export type Prediction = {
  estimate: true;
  valid: boolean;
  nodes: { node: string; type: string | null; rows: Record<string, number | null> }[];
  outputs: PredictedQuality[];
};
export type SuggestedNode = { id: string; type: string; settings: Record<string, unknown>; inputs: Record<string, string> };
export type Placement = { before: string; port: string; from: string; through: string };
export type Effect = {
  output: string;
  before: number | null;
  after: number | null;
  dimensionsBefore: Record<string, number>;
  dimensionsAfter: Record<string, number>;
  rowsBefore: number | null;
  rowsAfter: number | null;
  note: string | null;
};
export type Suggestion = {
  id: string;
  kind: string;
  title: string;
  why: string;
  source: "rules" | "ai";
  nodes: SuggestedNode[];
  placement: Placement;
  effect: Effect | null;
};
export type AiState = "off" | "not_asked" | "used" | "withheld" | "unavailable";
export type Advice = { estimate: true; suggestions: Suggestion[]; ai: { state: AiState; message: string; model: string | null } };
/** What a run stored about its prediction, beside the predicted score. */
export type PredictionDetail = { unknown?: string[]; assumptions?: string[]; rows?: number | null; gate?: PredictedGate | null };

export const predictionApi = {
  predict: (yaml: string, signal?: AbortSignal) =>
    call<Prediction>("/api/v1/pipelines/predict", { method: "POST", body: JSON.stringify({ yaml }), signal }),
  suggest: (yaml: string, ai: boolean, signal?: AbortSignal) =>
    call<Advice>("/api/v1/pipelines/suggest", { method: "POST", body: JSON.stringify({ yaml, ai }), signal }),
};

/** The words every prediction carries. */
export const ESTIMATE = "(estimate)";

/** "Predicted 91% (estimate)", or that it is unknown. */
export function predictedWords(p: Pick<PredictedQuality, "overall"> | null | undefined): string | null {
  if (!p) return null;
  return p.overall == null ? `Predicted: unknown ${ESTIMATE}` : `Predicted ${percent(p.overall)} ${ESTIMATE}`;
}

/** A change in score in points: "+4 points", "−3 points", "no change". */
export function pointsWords(delta: number): string {
  const n = Math.round(delta * 100);
  if (n === 0) return "no change";
  return `${n > 0 ? "+" : "−"}${plural(Math.abs(n), "point")}`;
}

/** A suggestion's effect: "Predicted 89% → 94% (+5 points, estimate)". */
export function effectWords(e: Effect | null): string {
  if (!e || e.before == null || e.after == null) return `Effect unknown ${ESTIMATE}`;
  const change = pointsWords(e.after - e.before);
  return `Predicted ${percent(e.before)} → ${percent(e.after)} (${change}, estimate)`;
}

/** The dimensions whose predicted score a suggestion changes, in words: "Consistency +5 points". */
export function changedDimensions(e: Effect | null): string[] {
  if (!e) return [];
  const out: string[] = [];
  for (const [d, after] of Object.entries(e.dimensionsAfter)) {
    const before = e.dimensionsBefore[d];
    if (before != null && Math.round((after - before) * 100) !== 0) out.push(`${dimensionWord(d)} ${pointsWords(after - before)}`);
  }
  return out;
}

export type DimensionRow = { dimension: string; measured: number | null; predicted: number | null; gap: number | null };

/** Predicted beside measured, one row per dimension either has; the gap is measured minus predicted. */
export function dimensionRows(measured: QualityScore | null | undefined, predicted: QualityScore | null | undefined): DimensionRow[] {
  const dims = [...new Set([...Object.keys(measured?.dimensions ?? {}), ...Object.keys(predicted?.dimensions ?? {})])];
  return dims.map((d) => {
    const m = measured?.dimensions[d] ?? null;
    const p = predicted?.dimensions[d] ?? null;
    return { dimension: d, measured: m, predicted: p, gap: m != null && p != null ? m - p : null };
  });
}

/**
 * What the gap between measured and predicted says, in words, or null when there is nothing to
 * compare (no dimension both have). The overall gap is the mean over the dimensions both have: within a couple of points the metadata told the truth; otherwise the dimension with the
 * widest gap is named, since that is where the metadata or the pipeline needs attention.
 */
export function gapWords(measured: QualityScore | null | undefined, predicted: QualityScore | null | undefined): string | null {
  if (!measured || !predicted || predicted.overall == null) return null;
  // Over the dimensions both have, so a dimension only one side could judge does not make a gap.
  const rows = dimensionRows(measured, predicted).filter((r) => r.gap != null);
  if (rows.length === 0) return null;
  const gap = rows.reduce((n, r) => n + r.gap!, 0) / rows.length;
  const widest = rows.sort((a, b) => Math.abs(b.gap!) - Math.abs(a.gap!))[0];
  if (Math.abs(Math.round(gap * 100)) <= 2 && (!widest || Math.abs(Math.round(widest.gap! * 100)) <= 2)) {
    return "Measured as predicted: the catalogue's metadata matched the rows.";
  }
  const side = gap < 0 ? "below" : "above";
  const points = Math.abs(Math.round(gap * 100));
  const lead = points === 0 ? "Measured overall as predicted" : `Measured ${plural(points, "point")} ${side} the estimate`;
  const where =
    widest && Math.abs(Math.round(widest.gap! * 100)) > 2
      ? `; the widest gap is in ${dimensionWord(widest.dimension).toLowerCase()} (${pointsWords(widest.gap!)}). Check the metadata of what this node reads, or the nodes before it.`
      : ".";
  return lead + where;
}

/** The tone a predicted score takes; the words say the same. */
export const predictedTone = (score: number | null | undefined) => (score == null ? "none" : rating(score).tone);

/** Per node id, the predicted quality of each write and gate. */
export const predictionsByNode = (p: Prediction | null): Map<string, PredictedQuality> =>
  new Map((p?.outputs ?? []).map((o) => [o.node, o]));

/** "1,234 rows (estimate)", or "rows unknown". */
export const rowsWords = (n: number | null | undefined) =>
  n == null ? "rows unknown" : `about ${n.toLocaleString("en-GB")} ${n === 1 ? "row" : "rows"} ${ESTIMATE}`;
