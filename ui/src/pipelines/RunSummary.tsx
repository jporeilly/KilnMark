import { useCallback, useEffect, useRef, useState } from "react";
import { formatNumber, formatTime, pipelinesApi, rating, type PipelineNodeRun, type PipelineRun, type PipelineRunDetail } from "../api";
import { StatusChip, type Tone } from "../components/ui";
import { href } from "../router";
import { belowThreshold, nodeQualityWords, percent } from "./quality";
import { formatBytes, formatDuration, RunStatus, runTime } from "./RunStatus";
import { rateWords } from "./live";
import { runOrigin, shareWords } from "./timeline";
import { Who } from "../components/Who";

/** How often a queued or running run is looked at again, here and on the run page. */
export const RUN_POLL_MS = 2000;

const LIVE_STATE: Record<string, string> = {
  not_started: "Not started",
  waiting: "Waiting for rows",
  running: "Running",
  ended: "Ended",
};

const OUTCOME: Record<NonNullable<PipelineNodeRun["outcome"]>, string> = {
  finished: "Finished",
  ended_early: "Ended early",
  stopped: "Stopped by the engine",
  failed: "Failed",
};

const TONE: Record<string, Tone> = { good: "good", fair: "warn", poor: "bad" };

/** A run's status as a sentence, announced when it changes: "Run 8 is running.", "Run 8 failed." */
export function runStatusSentence(id: number, status: PipelineRun["status"] | string): string {
  switch (status) {
    case "queued":
      return `Run ${id} is queued.`;
    case "running":
      return `Run ${id} is running.`;
    case "succeeded":
      return `Run ${id} succeeded.`;
    case "failed":
      return `Run ${id} failed.`;
    case "cancelled":
      return `Run ${id} was cancelled.`;
    default:
      return `Run ${id}: ${status}.`;
  }
}

export const isUnfinished =(r: PipelineRun | null | undefined) => r != null && (r.status === "queued" || r.status === "running");

/** Rows per port, in words: "main 10, lookup 4". */
function portRows(ports: Record<string, number | null> | undefined): string {
  if (!ports || Object.keys(ports).length === 0) return "";
  return Object.entries(ports)
    .map(([port, n]) => `${port} ${n == null ? "not counted" : formatNumber(n)}`)
    .join(", ");
}

/** A score as a chip: the percentage and its word, never colour alone. */
export function ScoreChip({ score }: { score: number }) {
  const r = rating(score);
  return (
    <StatusChip tone={TONE[r.tone]}>
      {percent(score)} · {r.word}
    </StatusChip>
  );
}

/**
 * One run, looked at again every two seconds while it is queued or running, and once more as it
 * ends. With id null, nothing. A change of id keeps the last run shown until the new one arrives.
 */
export function useRunDetail(id: number | null) {
  const [detail, setDetail] = useState<PipelineRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  // Counts each look at the run, so the log can fetch what happened since.
  const [tick, setTick] = useState(0);
  // When the run was last fetched, by the browser's clock: a live run's "now" is its last snapshot plus the time since.
  const [fetchedAt, setFetchedAt] = useState(0);
  const last = useRef<{ id: number; status: string } | null>(null);
  const current = useRef(id);
  current.current = id;

  const load = useCallback(() => {
    if (id == null) return;
    pipelinesApi
      .runDetail(id)
      .then((d) => {
        if (current.current !== id) return;
        if (last.current?.id === id && last.current.status !== d.run.status) setNotice(runStatusSentence(id, d.run.status));
        last.current = { id, status: d.run.status };
        setDetail(d);
        setFetchedAt(Date.now());
        setTick((n) => n + 1);
        setError(null);
      })
      .catch((e: Error) => current.current === id && setError(e.message));
  }, [id]);

  useEffect(load, [load]);
  const unfinished = detail != null && detail.run.id === id && isUnfinished(detail.run);
  useEffect(() => {
    if (!unfinished) return;
    const t = window.setInterval(load, RUN_POLL_MS);
    return () => window.clearInterval(t);
  }, [unfinished, load]);
  return { detail, error, notice, tick, fetchedAt, load, unfinished };
}

/**
 * A run's facts: status, version, when it was asked for, started and finished, how long it took,
 * the rows read (when given) and written, its quality, and why it failed, as the server words it.
 */
export function RunFacts({ run: r, rowsRead }: { run: PipelineRun; rowsRead?: number | null }) {
  return (
    <>
      <dl className="pl-facts">
        <dt>Status</dt>
        <dd>
          <RunStatus run={r} />
        </dd>
        <dt>Version</dt>
        <dd>
          <a href={href.pipeline(r.pipeline, r.version)}>Version {r.version}</a>
        </dd>
        <dt>Asked for</dt>
        <dd>
          {formatTime(r.requestedAt)} by <Who name={r.requestedBy} />
        </dd>
        <dt>Started</dt>
        <dd>{formatTime(r.startedAt)}</dd>
        <dt>Finished</dt>
        <dd>{formatTime(r.finishedAt)}</dd>
        <dt>Took</dt>
        <dd>{runTime(r)}</dd>
        {rowsRead !== undefined && (
          <>
            <dt>Rows read</dt>
            <dd>{formatNumber(rowsRead)}</dd>
          </>
        )}
        <dt>Rows written</dt>
        <dd>{formatNumber(r.rowsWritten)}</dd>
        {r.qualityScore != null && (
          <>
            <dt>Quality</dt>
            <dd>
              <ScoreChip score={r.qualityScore} /> <span className="small muted">lowest of what it wrote</span>
              {r.qualityThreshold != null && (
                <div className={`small${belowThreshold(r.qualityScore, r.qualityThreshold) ? " pl-bad" : ""}`}>
                  {belowThreshold(r.qualityScore, r.qualityThreshold)
                    ? `Below the pipeline's threshold of ${percent(r.qualityThreshold)}`
                    : `At or above the pipeline's threshold of ${percent(r.qualityThreshold)}`}
                </div>
              )}
            </dd>
          </>
        )}
      </dl>
      {r.error && (
        <p className={r.status === "failed" ? "pl-bad" : "muted"}>
          <strong>{r.status === "failed" ? "Why it failed: " : "Note: "}</strong>
          {r.error}
        </p>
      )}
    </>
  );
}

/** The rows the run read: what its read nodes passed on (so far, while it runs). Null when not known. */
export function rowsRead(detail: PipelineRunDetail, reads: ReadonlySet<string>): number | null {
  const rows =
    detail.nodes.length > 0
      ? detail.nodes.filter((s) => reads.has(s.node)).map((s) => s.rowsOut)
      : (detail.progress?.nodes ?? []).filter((s) => reads.has(s.node)).map((s) => s.rowsOut);
  if (rows.length === 0 || rows.some((n) => n == null)) return null;
  return rows.reduce<number>((a, n) => a + (n ?? 0), 0);
}

/**
 * The run's nodes as a table: while it runs, each node's rows so far and rate and the node holding
 * it back; once ended, how each ended, rows in and out per port, peak memory, time, share of the
 * run and its error as the server gives it. `quality` adds each write's and gate's measured quality.
 */
export function RunNodes({ detail, quality = false, label = "" }: { detail: PipelineRunDetail; quality?: boolean; label?: string }) {
  const unfinished = isUnfinished(detail.run);
  const live = unfinished && detail.nodes.length === 0 ? (detail.progress?.nodes ?? []) : [];
  const origin = runOrigin(detail);
  const workerEnd = detail.progress?.workerFinishedAt ? Date.parse(detail.progress.workerFinishedAt) : null;
  const runMs = origin != null && workerEnd != null ? Math.max(1, workerEnd - origin) : null;
  const measured = (node: string) => nodeQualityWords(detail.quality?.find((q) => q.node === node)) ?? "–";
  if (live.length > 0) {
    return (
      <>
        <p className="small muted">So far; the full record of each node is kept when the run ends.</p>
        <div className="table-scroll" role="region" aria-label={`Nodes of the run so far${label}`} tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Node</th>
                <th scope="col">Now</th>
                <th scope="col">Rows in</th>
                <th scope="col">Rows out</th>
                <th scope="col">Rows a second</th>
              </tr>
            </thead>
            <tbody>
              {live.map((s) => (
                <tr key={s.node}>
                  <th scope="row">
                    <span className="mono">{s.node}</span>
                  </th>
                  <td>
                    {detail.progress?.slowest === s.node ? (
                      <StatusChip tone="orange" kind="slowest">
                        Slowest
                      </StatusChip>
                    ) : (
                      LIVE_STATE[s.state]
                    )}
                  </td>
                  <td>{formatNumber(s.rowsIn)}</td>
                  <td>{formatNumber(s.rowsOut)}</td>
                  <td>{s.state === "running" ? rateWords(s.rowsPerSecond) : "–"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </>
    );
  }
  if (detail.nodes.length === 0) {
    return <p className="muted">{unfinished ? "The nodes are recorded when the run ends." : "No nodes were recorded."}</p>;
  }
  return (
    <div className="table-scroll" role="region" aria-label={`Nodes of the run${label}`} tabIndex={0}>
      <table>
        <thead>
          <tr>
            <th scope="col">Node</th>
            <th scope="col">How it ended</th>
            <th scope="col">Rows in</th>
            <th scope="col">Rows out</th>
            <th scope="col">Peak memory</th>
            <th scope="col">Time</th>
            <th scope="col">Share of the run</th>
            {quality && <th scope="col">Quality</th>}
            <th scope="col">Error</th>
          </tr>
        </thead>
        <tbody>
          {detail.nodes.map((s) => (
            <tr key={s.node}>
              <th scope="row">
                <span className="mono">{s.node}</span>
                <div className="small muted">{s.type}</div>
              </th>
              <td>
                {s.outcome ? (
                  <span
                    className={`badge ${
                      s.outcome === "failed"
                        ? "badge-failed"
                        : s.outcome === "stopped"
                          ? "badge-running"
                          : s.outcome === "ended_early"
                            ? "badge-none"
                            : "badge-succeeded"
                    }`}
                  >
                    {OUTCOME[s.outcome]}
                  </span>
                ) : (
                  "–"
                )}
              </td>
              <td>
                {formatNumber(s.rowsIn)}
                {Object.keys(s.ports?.in ?? {}).length > 1 && <div className="small muted">{portRows(s.ports?.in)}</div>}
              </td>
              <td>
                {formatNumber(s.rowsOut)}
                {Object.keys(s.ports?.out ?? {}).length > 0 && <div className="small muted">{portRows(s.ports?.out)}</div>}
              </td>
              <td>{formatBytes(s.peakMemory)}</td>
              <td>{formatDuration(s.durationMs)}</td>
              <td>{runMs && s.durationMs != null ? shareWords(Math.min(1, s.durationMs / runMs)) : "–"}</td>
              {quality && <td>{measured(s.node)}</td>}
              <td className={s.error ? "pl-bad" : undefined}>{s.error ?? "–"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
