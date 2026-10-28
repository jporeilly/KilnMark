import type { PipelineRunDetail } from "../api";
import { StatusChip, type Tone } from "../components/ui";
import { offsetWords, pct, rowWords, ticks, timeline, type TimelineRow } from "./timeline";

const OUTCOME: Record<string, { word: string; tone: Tone }> = {
  finished: { word: "Finished", tone: "neutral" },
  ended_early: { word: "Ended early", tone: "warn" },
  stopped: { word: "Stopped by the engine", tone: "warn" },
  failed: { word: "Failed", tone: "bad" },
  waiting: { word: "Waiting for rows", tone: "neutral" },
  running: { word: "Running", tone: "info" },
  ended: { word: "Ended", tone: "neutral" },
  not_started: { word: "Not started", tone: "neutral" },
};

/** One step's bar: lighter while it waits for its first row, ticks at its first and last row. */
function Bar({ r, span }: { r: TimelineRow; span: number }) {
  if (r.start == null) return <span className="pl-tl-none">Did not start</span>;
  const end = r.end ?? span;
  const solidFrom = r.firstRow ?? end;
  const words = rowWords(r);
  return (
    <>
      <span
        className={`pl-tl-wait${r.running && r.firstRow == null ? " is-running" : ""}`}
        style={{
          left: pct(r.start, span),
          width: pct(Math.max(0, solidFrom - r.start), span),
        }}
        title={words}
      />
      {r.firstRow != null && (
        <span
          className={`pl-tl-bar${r.outcome === "failed" ? " is-failed" : ""}${r.running ? " is-running" : ""}`}
          style={{
            left: pct(r.firstRow, span),
            width: pct(Math.max(0, end - r.firstRow), span),
          }}
          title={words}
        />
      )}
      {r.firstRow != null && <span className="pl-tl-tick" style={{ left: pct(r.firstRow, span) }} title="First row" />}
      {r.lastRow != null && !r.running && (
        <span className="pl-tl-tick is-last" style={{ left: pct(r.lastRow, span) }} title="Last row" />
      )}
    </>
  );
}

/**
 * The run as a Gantt chart: one bar per step from its start to its end, lighter while it waits for
 * its first row, with ticks at its first and last row, so steps working at the same time overlap.
 * While the run goes it is drawn from the latest progress, and the step holding the run back says
 * Slowest. The same in a table for screen readers and anyone who prefers one.
 */
export function RunTimeline({ detail, now }: { detail: PipelineRunDetail; now?: number }) {
  const t = timeline(detail, now);
  if (!t) {
    const unfinished = detail.run.status === "queued" || detail.run.status === "running";
    return (
      <p className="muted">{unfinished ? "The timeline appears once the steps start." : "This run's steps were not timed."}</p>
    );
  }
  const axis = ticks(t.span);
  const slowest = t.rows.find((r) => r.slowest);
  const summary = `Timeline of ${t.rows.length} ${t.rows.length === 1 ? "step" : "steps"} over ${offsetWords(t.span)}${
    t.live ? ", so far" : ""
  }${slowest ? `; the slowest step now is ${slowest.step}` : ""}. The table below says the same.`;
  return (
    <div className="pl-tl">
      <div className="pl-tl-chart" role="img" aria-label={summary}>
        <div className="pl-tl-axis" aria-hidden="true">
          <span className="pl-tl-name" />
          <span className="pl-tl-track">
            {axis.map((x) => (
              <span key={x} className="pl-tl-axis-tick" style={{ left: pct(x, t.span) }}>
                {offsetWords(x)}
              </span>
            ))}
          </span>
        </div>
        <ul className="pl-tl-rows" aria-hidden="true">
          {t.rows.map((r) => {
            const o = r.outcome ? OUTCOME[r.outcome] : null;
            return (
              <li key={r.step} className={`pl-tl-row${r.slowest ? " is-slowest" : ""}`}>
                <span className="pl-tl-name">
                  <span className="mono pl-tl-step">{r.step}</span>
                  {r.slowest ? (
                    <StatusChip tone="orange" kind="slowest">
                      Slowest
                    </StatusChip>
                  ) : (
                    o && o.word !== "Finished" && o.word !== "Ended" && <StatusChip tone={o.tone}>{o.word}</StatusChip>
                  )}
                </span>
                <span className="pl-tl-track">
                  {axis.map((x) => (
                    <span key={x} className="pl-tl-grid" style={{ left: pct(x, t.span) }} />
                  ))}
                  <Bar r={r} span={t.span} />
                </span>
              </li>
            );
          })}
        </ul>
        <p className="small muted pl-tl-key" aria-hidden="true">
          <span className="pl-tl-key-wait" /> Waiting for its first row <span className="pl-tl-key-bar" /> Rows moving{" "}
          <span className="pl-tl-key-tick" /> First and last row
        </p>
      </div>
      <details className="pl-tl-table">
        <summary>Show the timeline as a table</summary>
        <p className="small muted">Times count from the start of the run.</p>
        <div className="table-scroll" role="region" aria-label="Timeline of the run as a table" tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Step</th>
                <th scope="col">Started</th>
                <th scope="col">First row</th>
                <th scope="col">Last row</th>
                <th scope="col">Ended</th>
                <th scope="col">Took</th>
              </tr>
            </thead>
            <tbody>
              {t.rows.map((r) => (
                <tr key={r.step}>
                  <th scope="row">
                    <span className="mono">{r.step}</span>
                    {r.slowest && <div>Slowest now</div>}
                    {r.outcome && OUTCOME[r.outcome] && <div className="muted">{OUTCOME[r.outcome].word}</div>}
                  </th>
                  <td>{r.start == null ? "Did not start" : offsetWords(r.start)}</td>
                  <td>{r.firstRow == null ? (r.running ? "Waiting" : "No rows") : offsetWords(r.firstRow)}</td>
                  <td>{r.lastRow == null || r.running ? "–" : offsetWords(r.lastRow)}</td>
                  <td>{r.end == null ? (r.running ? "Still running" : "–") : offsetWords(r.end)}</td>
                  <td>{r.end == null || r.start == null ? "–" : offsetWords(r.end - r.start)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
