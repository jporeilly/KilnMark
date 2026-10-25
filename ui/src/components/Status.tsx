import type { Run } from "../api";
import { StatusChip, type Tone } from "./ui";

const LABEL: Record<Run["status"], string> = {
  queued: "Queued",
  running: "Scanning",
  succeeded: "Scanned",
  partial: "Partly scanned",
  failed: "Failed",
};
const TONE: Record<Run["status"], Tone> = { queued: "info", running: "info", succeeded: "good", partial: "warn", failed: "bad" };

/** A scan's status as a chip: words with a tint, never colour alone. */
export function Status({ run }: { run: Run | null }) {
  if (!run) return <StatusChip tone="neutral">Never scanned</StatusChip>;
  return (
    <StatusChip tone={TONE[run.status]} dot>
      {LABEL[run.status]}
    </StatusChip>
  );
}
