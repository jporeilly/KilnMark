import type { PipelineRun } from "../api";

const LABEL: Record<PipelineRun["status"], string> = {
  queued: "Queued",
  running: "Running",
  succeeded: "Succeeded",
  failed: "Failed",
  cancelled: "Cancelled",
};

const CLASS: Record<PipelineRun["status"], string> = {
  queued: "badge-queued",
  running: "badge-running",
  succeeded: "badge-succeeded",
  failed: "badge-failed",
  cancelled: "badge-none",
};

/** A pipeline run's status in words (never colour alone); "Stopping" while a cancel is on its way. */
export function RunStatus({ run }: { run: PipelineRun | null }) {
  if (!run) return <span className="badge badge-none">Never run</span>;
  const stopping = run.cancelRequested && (run.status === "queued" || run.status === "running");
  return <span className={`badge ${CLASS[run.status]}`}>{stopping ? "Stopping" : LABEL[run.status]}</span>;
}

/** Bytes in plain units. */
export function formatBytes(n: number | null | undefined): string {
  if (n == null) return "–";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(1)} GB`;
}

/** A duration in plain units. */
export function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return "–";
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)} min ${Math.round((ms % 60_000) / 1000)} s`;
}

/** How long a run took, or has taken so far. */
export function runTime(run: PipelineRun): string {
  if (!run.startedAt) return "–";
  const end = run.finishedAt ? new Date(run.finishedAt).getTime() : Date.now();
  return formatDuration(end - new Date(run.startedAt).getTime());
}
