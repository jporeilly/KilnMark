/**
 * The notice a page shows after someone starts a run, kept true as the run goes: "Run 12 of version 1
 * is queued." becomes "is running", then "succeeded" or "failed", as the page's list of runs (read
 * again every few seconds while one is going) says. A page keeps the run, not the words.
 */
export type RunNote = { run: number; of: string };
export type Notice = string | RunNote;

type Status = "queued" | "running" | "succeeded" | "failed" | "cancelled" | "partial";

const WORDS: Record<Status, string> = {
  queued: "is queued",
  running: "is running",
  succeeded: "succeeded",
  partial: "succeeded in part",
  failed: "failed",
  cancelled: "was cancelled",
};

/** The notice in words; a run's from its status now (unknown: as it was started, queued). */
export function noticeText(n: Notice, status: Status | null | undefined): string {
  if (typeof n === "string") return n;
  return `Run ${n.run} of ${n.of} ${WORDS[status ?? "queued"] ?? WORDS.queued}.`;
}

/** The status of run `id` in a list of runs, or undefined when the list does not have it. */
export const statusIn = (runs: { id: number; status: Status }[] | null | undefined, id: number) =>
  runs?.find((r) => r.id === id)?.status;

/** The status of run `id` in a list of pipelines or flows, by their last runs; undefined once another has started. */
export const lastRunStatus = (items: { lastRun: { id: number; status: Status } | null }[] | null | undefined, id: number) =>
  items?.find((i) => i.lastRun?.id === id)?.lastRun?.status;
