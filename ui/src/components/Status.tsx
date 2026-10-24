import type { Run } from "../api";

const LABEL: Record<Run["status"], string> = {
  queued: "Queued",
  running: "Scanning",
  succeeded: "Scanned",
  partial: "Partly scanned",
  failed: "Failed",
};

/** A run's status as words and colour, never colour alone. */
export function Status({ run }: { run: Run | null }) {
  if (!run) return <span className="badge badge-none">Never scanned</span>;
  return <span className={`badge badge-${run.status}`}>{LABEL[run.status]}</span>;
}
