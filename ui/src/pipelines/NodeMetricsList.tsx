import { formatNumber } from "../api";
import { rateWords } from "./live";
import { shareOf, splitWords, type NodeMetrics } from "./nodeMetrics";
import { formatBytes, formatDuration } from "./RunStatus";

/**
 * A node's metrics from a run that has ended: rows, time, rows a second, time to its first row,
 * peak memory, and how its time went as a bar (working, waiting for rows, held up by the next node).
 */
export function NodeMetricsList({ m, label }: { m: NodeMetrics; label: string }) {
  const split = m.split;
  const total = split ? split.workingMs + split.waitingMs + split.heldUpMs : 0;
  return (
    <div className="pl-metrics">
      <dl className="pl-metrics-list" aria-label={label}>
        <div>
          <dt>Rows in</dt>
          <dd>{m.rowsIn == null ? "–" : formatNumber(m.rowsIn)}</dd>
        </div>
        <div>
          <dt>Rows out</dt>
          <dd>{m.rowsOut == null ? "–" : formatNumber(m.rowsOut)}</dd>
        </div>
        <div>
          <dt>Time</dt>
          <dd>{formatDuration(m.durationMs)}</dd>
        </div>
        <div>
          <dt>Rows a second</dt>
          <dd>{m.rate == null ? "–" : rateWords(m.rate)}</dd>
        </div>
        <div>
          <dt>First row after</dt>
          <dd>{formatDuration(m.firstRowMs)}</dd>
        </div>
        <div>
          <dt>Peak memory</dt>
          <dd>{formatBytes(m.peakMemory)}</dd>
        </div>
      </dl>
      {split && total > 0 && (
        <div className="pl-split">
          <div className="pl-split-bar" role="img" aria-label={`How its time went: ${splitWords(m)}`}>
            <span className="pl-split-work" style={{ width: shareOf(split.workingMs, total) }} />
            <span className="pl-split-wait" style={{ width: shareOf(split.waitingMs, total) }} />
            <span className="pl-split-held" style={{ width: shareOf(split.heldUpMs, total) }} />
          </div>
          <p className="small pl-split-key" aria-hidden="true">
            <span className="pl-split-dot pl-split-work" /> Working {shareOf(split.workingMs, total)}
            <span className="pl-split-dot pl-split-wait" /> Waiting for rows {shareOf(split.waitingMs, total)}
            <span className="pl-split-dot pl-split-held" /> Held up {shareOf(split.heldUpMs, total)}
          </p>
        </div>
      )}
      {m.slowest && <p className="small pl-split-slowest">This node held the run back most.</p>}
    </div>
  );
}
