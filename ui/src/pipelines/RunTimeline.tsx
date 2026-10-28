import { useState } from "react";
import { IconClockHour4, IconHourglass, IconRoute, IconStack2 } from "@tabler/icons-react";
import { formatNumber, type PipelineRunDetail, type PipelineStepQuality } from "../api";
import { StatTile, StatusChip, type Tone } from "../components/ui";
import { scoreTone, scoreWords } from "./quality";
import {
  criticalPath,
  offsetWords,
  pct,
  peakConcurrent,
  rowWords,
  shareOfRun,
  shareWords,
  slowestStep,
  ticks,
  timeline,
  type Clock,
  type TimelineRow,
} from "./timeline";

const DECISION_TONE: Record<string, Tone> = { passed: "good", warned: "warn", quarantined: "orange", stopped: "bad" };
const SCORE_TONE: Record<string, Tone> = { good: "good", fair: "warn", poor: "bad", none: "neutral" };

/** What a gate decided, or what a write's rows measured, in words: the marker at the end of its bar. */
export function qualityMark(q: PipelineStepQuality | undefined): { text: string; tone: Tone } | null {
  if (!q) return null;
  const g = q.gate;
  if (g) {
    const text =
      g.decision === "passed"
        ? "Passed"
        : g.decision === "quarantined"
          ? `Quarantined ${formatNumber(g.rowsQuarantined)}`
          : g.decision === "stopped"
            ? "Stopped the run"
            : `Warned: ${formatNumber(g.rowsFailing)} failing`;
    return { text, tone: DECISION_TONE[g.decision] ?? "neutral" };
  }
  if (q.role === "output" && q.measured)
    return { text: scoreWords(q.measured.overall), tone: SCORE_TONE[scoreTone(q.measured.overall)] };
  return null;
}

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
function Bar({ r, span, critical }: { r: TimelineRow; span: number; critical: boolean }) {
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
          className={`pl-tl-bar${r.outcome === "failed" ? " is-failed" : ""}${r.running ? " is-running" : ""}${
            critical ? " is-critical" : ""
          }`}
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
export function RunTimeline({ detail, clock }: { detail: PipelineRunDetail; clock?: Clock }) {
  const [showPath, setShowPath] = useState(true);
  const t = timeline(detail, clock);
  if (!t) {
    const unfinished = detail.run.status === "queued" || detail.run.status === "running";
    return (
      <p className="muted">{unfinished ? "The timeline appears once the steps start." : "This run's steps were not timed."}</p>
    );
  }
  const axis = ticks(t.span);
  const slowest = t.rows.find((r) => r.slowest);
  const path = criticalPath(
    t,
    (detail.progress?.edges ?? []).map((e) => ({ from: e.from, to: e.to })),
  );
  const onPath = new Set(showPath && path ? path.steps : []);
  const quality = new Map((detail.quality ?? []).map((q) => [q.step, q]));
  const longest = slowestStep(t);
  const summary = `Timeline of ${t.rows.length} ${t.rows.length === 1 ? "step" : "steps"} over ${offsetWords(t.span)}${
    t.live ? ", so far" : ""
  }${slowest ? `; the slowest step now is ${slowest.step}` : ""}${
    path && showPath ? `; the critical path is ${path.steps.join(", ")}` : ""
  }. The table below says the same.`;
  return (
    <div className="pl-tl">
      <section aria-label="The run at a glance" className="km-grid-4 pl-tl-tiles">
        <StatTile
          label="Wall-clock time"
          value={offsetWords(t.span)}
          icon={<IconClockHour4 size={18} aria-hidden="true" />}
          tint="sky"
          note={t.live ? "so far" : "from start to end"}
        />
        <StatTile
          label="Most steps at once"
          value={String(peakConcurrent(t))}
          icon={<IconStack2 size={18} aria-hidden="true" />}
          tint="violet"
          note={`of ${t.rows.length}`}
        />
        <StatTile
          label={t.live ? "Slowest step now" : "Longest step"}
          value={longest ? <span className="mono">{longest.step}</span> : "–"}
          icon={<IconHourglass size={18} aria-hidden="true" />}
          tint="orange"
          note={
            longest
              ? t.live
                ? "holding the run back"
                : `${shareWords(shareOfRun(longest, t.span) ?? 0)} of the run`
              : t.live
                ? "none holding it back"
                : undefined
          }
        />
        <StatTile
          label="Critical path"
          value={path ? shareWords(path.share) : "–"}
          icon={<IconRoute size={18} aria-hidden="true" />}
          tint="teal"
          note={
            path
              ? `${path.steps.join(" → ")}, ${shareWords(path.share)} of the run`
              : t.live
                ? "known when the run ends"
                : "not known"
          }
        />
      </section>
      {path && (
        <label className="pl-tl-toggle small">
          <input type="checkbox" checked={showPath} onChange={(e) => setShowPath(e.target.checked)} /> Show the critical path
        </label>
      )}
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
            const mark = r.start != null && !r.running ? qualityMark(quality.get(r.step)) : null;
            return (
              <li
                key={r.step}
                className={`pl-tl-row${r.slowest ? " is-slowest" : ""}${onPath.has(r.step) ? " is-critical" : ""}`}
              >
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
                  <Bar r={r} span={t.span} critical={onPath.has(r.step)} />
                  {mark && (
                    <span
                      className={`pl-tl-mark${(r.end ?? t.span) / t.span < 0.35 ? " is-after" : ""}`}
                      style={{ left: pct(r.end ?? t.span, t.span) }}
                    >
                      <StatusChip tone={mark.tone} kind="quality-mark">
                        {mark.text}
                      </StatusChip>
                    </span>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
        <p className="small muted pl-tl-key" aria-hidden="true">
          <span className="pl-tl-key-wait" /> Waiting for its first row <span className="pl-tl-key-bar" /> Rows moving{" "}
          <span className="pl-tl-key-tick" /> First and last row
          {onPath.size > 0 && (
            <>
              {" "}
              <span className="pl-tl-key-critical" /> On the critical path
            </>
          )}
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
                <th scope="col">Share of the run</th>
                {path && <th scope="col">Critical path</th>}
                {quality.size > 0 && <th scope="col">Quality</th>}
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
                  <td>{shareOfRun(r, t.span) == null ? "–" : shareWords(shareOfRun(r, t.span)!)}</td>
                  {path && <td>{path.steps.includes(r.step) ? "On it" : "–"}</td>}
                  {quality.size > 0 && <td>{qualityMark(quality.get(r.step))?.text ?? "–"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
