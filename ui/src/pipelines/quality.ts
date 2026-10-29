/**
 * Words for a run's measured quality (docs/guide/pipelines.md, "Quality gates" and "Quality of
 * what a run writes"): scores as a percentage and a word, never by colour alone, and what a gate
 * did with the rows. Pure functions, unit tested.
 */
import { formatNumber, formatScore, rating, type GateOutcome, type PipelineNodeQuality } from "../api";

export const DIMENSION_WORDS: Record<string, string> = {
  completeness: "Completeness",
  uniqueness: "Uniqueness",
  validity: "Validity",
  consistency: "Consistency",
  accuracy: "Accuracy (estimated)",
  timeliness: "Timeliness",
  volume: "Volume",
  integrity: "Referential integrity",
};

export const dimensionWord = (d: string) => DIMENSION_WORDS[d] ?? d;

/** A score or share as a percentage, as the asset pages write scores (formatScore): "98%". */
export const percent = (score: number) => formatScore(score);

/** "Quality 98% (Good)". */
export const scoreWords = (score: number) => `Quality ${percent(score)} (${rating(score).word})`;

const plural = (n: number, one: string, many = `${one}s`) => `${formatNumber(n)} ${n === 1 ? one : many}`;

/** What a gate did with the rows: "900 passed · 100 quarantined". */
export function gateWords(g: GateOutcome): string {
  if (g.decision === "stopped") {
    return `Stopped the run after ${plural(g.rowsIn, "row")}`;
  }
  const parts = [`${formatNumber(g.rowsPassed)} passed`];
  if (g.rowsQuarantined > 0) parts.push(`${formatNumber(g.rowsQuarantined)} quarantined`);
  else if (g.rowsFailing > 0) parts.push(`${formatNumber(g.rowsFailing)} failed, let through`);
  return parts.join(" · ");
}

export const DECISION_WORDS: Record<GateOutcome["decision"], string> = {
  passed: "Passed",
  warned: "Warned",
  quarantined: "Quarantined rows",
  stopped: "Stopped the run",
};

export const ON_FAIL_WORDS: Record<GateOutcome["onFail"], string> = {
  stop: "Stop the run",
  quarantine: "Quarantine failing rows",
  warn: "Warn only",
};

export const RULE_STATUS_WORDS: Record<string, string> = { pass: "Pass", warn: "Warn", fail: "Fail", error: "Could not run" };

/** The badge words for a node on the canvas after a run; null when the run measured nothing for it. */
export function nodeQualityWords(q: PipelineNodeQuality | undefined | null): string | null {
  if (!q) return null;
  const parts: string[] = [];
  if (q.measured) parts.push(scoreWords(q.measured.overall));
  if (q.gate) parts.push(gateWords(q.gate));
  if (parts.length === 0) return q.role === "output" ? "Quality not measured" : null;
  return parts.join(" · ");
}

/** The tone a score's badge takes; the words say the same. */
export const scoreTone = (score: number | null | undefined) => (score == null ? "none" : rating(score).tone);

/** Whether a run's score is below its pipeline's threshold. */
export const belowThreshold = (score: number | null | undefined, threshold: number | null | undefined) =>
  score != null && threshold != null && score < threshold;
