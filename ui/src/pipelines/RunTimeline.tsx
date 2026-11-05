import { useState } from "react";
import { IconClockHour4, IconHourglass, IconRoute, IconStack2 } from "@tabler/icons-react";
import { formatNumber, type PipelineRunDetail, type PipelineNodeQuality } from "../api";
import { formatBytes, formatDuration } from "./RunStatus";
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
  slowestNode,
  ticks,
  timeline,
  type Clock,
  type TimelineRow,
} from "./timeline";

const DECISION_TONE: Record<string, Tone> = { passed: "good", warned: "warn", quarantined: "orange", stopped: "bad" };
const SCORE_TONE: Record<string, Tone> = { good: "good", fair: "warn", poor: "bad", none: "neutral" };

/** What a gate decided, or what a write's rows measured, in words: the marker at the end of its bar. */
export function qualityMark(q: PipelineNodeQuality | undefined): { text: string; tone: Tone } | null {
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

/** One node's bar: lighter while it waits for its first row, ticks at its first and last row. */
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
    </>
  );
}

/**
 * The run as a Gantt chart: one bar per node from its start to its end, lighter while it waits for
 * its first row, so nodes working at the same time overlap; its time and quality at its end.
 * While the run goes it is drawn from the latest progress, and the node holding the run back says
 * Slowest. The same in a table for screen readers and anyone who prefers one.
 */
export function RunTimeline({
  detail,
  clock,
  tiles = true,
}: {
  detail: PipelineRunDetail;
  clock?: Clock;
  /** The four tiles; off where the page shows its own. */
  tiles?: boolean;
}) {
  const [showPath, setShowPath] = useState(false);
  const [chosen, setChosen] = useState<string | null>(null);
  const t = timeline(detail, clock);
  if (!t) {
    const unfinished = detail.run.status === "queued" || detail.run.status === "running";
    return (
      <p className="muted">{unfinished ? "The timeline appears once the nodes start." : "This run's nodes were not timed."}</p>
    );
  }
  const axis = ticks(t.span);
  const slowest = t.rows.find((r) => r.slowest);
  const path = criticalPath(
    t,
    (detail.progress?.edges ?? []).map((e) => ({ from: e.from, to: e.to })),
  );
  const onPath = new Set(showPath && path ? path.nodes : []);
  const quality = new Map((detail.quality ?? []).map((q) => [q.node, q]));
  const longest = slowestNode(t);
  const summary = `Timeline of ${t.rows.length} ${t.rows.length === 1 ? "node" : "nodes"} over ${offsetWords(t.span)}${
    t.live ? ", so far" : ""
  }${slowest ? `; the slowest node now is ${slowest.node}` : ""}${
    path && showPath ? `; the critical path is ${path.nodes.join(", ")}` : ""
  }. The table below says the same.`;
  return (
    <div className="pl-tl">
      {tiles && (
        <section aria-label="The run at a glance" className="km-grid-4 pl-tl-tiles">
          <StatTile
            label="Wall-clock time"
            value={offsetWords(t.span)}
            icon={<IconClockHour4 size={18} aria-hidden="true" />}
            tint="sky"
            note={t.live ? "so far" : "from start to end"}
          />
          <StatTile
            label="Most nodes at once"
            value={String(peakConcurrent(t))}
            icon={<IconStack2 size={18} aria-hidden="true" />}
            tint="violet"
            note={`of ${t.rows.length}`}
          />
          <StatTile
            label={t.live ? "Slowest node now" : "Longest node"}
            value={longest ? <span className="mono">{longest.node}</span> : "–"}
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
                ? `${path.nodes.join(" → ")}, ${shareWords(path.share)} of the run`
                : t.live
                  ? "known when the run ends"
                  : "not known"
            }
          />
        </section>
      )}
      {path && (
        <label className="pl-tl-toggle small">
          <input type="checkbox" checked={showPath} onChange={(e) => setShowPath(e.target.checked)} /> Show the critical path
        </label>
      )}
      <p className="run-sentence">
        {runSentence(detail, t.span, t.live)} Choose a row for everything recorded about it.
      </p>
      <p className="visually-hidden">{summary}</p>
      <div className="pl-tl-chart">
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
        <ul className="pl-tl-rows" aria-label="The run, node by node">
          <li className={`pl-tl-row${chosen == null ? " is-chosen" : ""}`}>
            <span className="pl-tl-name">
              <span className="run-dot run-dot--run" aria-hidden="true" />
              <button
                type="button"
                className="run-name mono"
                aria-current={chosen == null ? "true" : undefined}
                onClick={() => setChosen(null)}
              >
                {detail.run.pipeline}
              </button>
            </span>
            <span className="pl-tl-track" aria-hidden="true" onClick={() => setChosen(null)}>
              {axis.map((x) => (
                <span key={x} className="pl-tl-grid" style={{ left: pct(x, t.span) }} />
              ))}
              <span className={`pl-tl-run${t.live ? " is-running" : ""}`} style={{ left: 0, width: "100%" }} />
              {!t.live && (
                <span className="run-took" style={{ left: "100%" }}>
                  {offsetWords(t.span)}
                </span>
              )}
            </span>
          </li>
          {t.rows.map((r) => {
            const o = r.outcome ? OUTCOME[r.outcome] : null;
            const mark = r.start != null && !r.running ? qualityMark(quality.get(r.node)) : null;
            return (
              <li
                key={r.node}
                className={`pl-tl-row${r.slowest ? " is-slowest" : ""}${onPath.has(r.node) ? " is-critical" : ""}${
                  chosen === r.node ? " is-chosen" : ""
                }`}
              >
                <span className="pl-tl-name">
                  <span className="run-dot" aria-hidden="true" />
                  <button
                    type="button"
                    className="run-name mono pl-tl-node"
                    aria-current={chosen === r.node ? "true" : undefined}
                    aria-label={rowWords(r)}
                    onClick={() => setChosen(r.node)}
                  >
                    {r.node}
                  </button>
                  {r.slowest ? (
                    <StatusChip tone="orange" kind="slowest">
                      Slowest
                    </StatusChip>
                  ) : (
                    o && o.word !== "Finished" && o.word !== "Ended" && <StatusChip tone={o.tone}>{o.word}</StatusChip>
                  )}
                </span>
                <span className="pl-tl-track" aria-hidden="true" onClick={() => setChosen(r.node)}>
                  {axis.map((x) => (
                    <span key={x} className="pl-tl-grid" style={{ left: pct(x, t.span) }} />
                  ))}
                  <Bar r={r} span={t.span} critical={onPath.has(r.node)} />
                  {r.start != null && r.end != null && (
                    <span
                      className={`run-took${(r.end ?? 0) / t.span > 0.7 && mark ? " is-before" : ""}`}
                      style={{ left: pct(r.end, t.span) }}
                    >
                      {offsetWords(r.end - r.start)}
                      {mark && <span className={`pl-tl-quality is-${mark.tone}`}> · {mark.text}</span>}
                    </span>
                  )}
                </span>
              </li>
            );
          })}
        </ul>
        <p className="small muted pl-tl-key" aria-hidden="true">
          <span className="pl-tl-key-wait" /> Waiting for its first row <span className="pl-tl-key-bar" /> Rows moving
          {onPath.size > 0 && (
            <>
              {" "}
              <span className="pl-tl-key-critical" /> On the critical path
            </>
          )}
        </p>
      </div>
      <NodeDetails detail={detail} t={t} node={chosen} quality={quality} />
      <details className="pl-tl-table">
        <summary>Show the timeline as a table</summary>
        <p className="small muted">Times count from the start of the run.</p>
        <div className="table-scroll" role="region" aria-label="Timeline of the run as a table" tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Node</th>
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
                <tr key={r.node}>
                  <th scope="row">
                    <span className="mono">{r.node}</span>
                    {r.slowest && <div>Slowest now</div>}
                    {r.outcome && OUTCOME[r.outcome] && <div className="muted">{OUTCOME[r.outcome].word}</div>}
                  </th>
                  <td>{r.start == null ? "Did not start" : offsetWords(r.start)}</td>
                  <td>{r.firstRow == null ? (r.running ? "Waiting" : "No rows") : offsetWords(r.firstRow)}</td>
                  <td>{r.lastRow == null || r.running ? "–" : offsetWords(r.lastRow)}</td>
                  <td>{r.end == null ? (r.running ? "Still running" : "–") : offsetWords(r.end)}</td>
                  <td>{r.end == null || r.start == null ? "–" : offsetWords(r.end - r.start)}</td>
                  <td>{shareOfRun(r, t.span) == null ? "–" : shareWords(shareOfRun(r, t.span)!)}</td>
                  {path && <td>{path.nodes.includes(r.node) ? "On it" : "–"}</td>}
                  {quality.size > 0 && <td>{qualityMark(quality.get(r.node))?.text ?? "–"}</td>}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}

/** The run in one sentence: what started when, how long it took and how it ended. */
function runSentence(detail: PipelineRunDetail, span: number, live: boolean): string {
  const r = detail.run;
  const at = r.startedAt ? new Date(r.startedAt).toLocaleTimeString() : null;
  if (!at) return `${r.pipeline} has not started yet.`;
  return live
    ? `${r.pipeline} started ${at} and has been running for ${offsetWords(span)}.`
    : `${r.pipeline} started ${at}, took ${offsetWords(span)} and ${r.status === "succeeded" ? "succeeded" : r.status}.`;
}

/** Everything recorded about the chosen node, or about the run when none is chosen. */
function NodeDetails({
  detail,
  t,
  node,
  quality,
}: {
  detail: PipelineRunDetail;
  t: { span: number; rows: TimelineRow[]; live: boolean };
  node: string | null;
  quality: Map<string, PipelineNodeQuality>;
}) {
  const facts: [string, string][] = [];
  const r = node == null ? null : t.rows.find((x) => x.node === node) ?? null;
  const n = node == null ? null : detail.nodes.find((x) => x.node === node) ?? null;
  if (r == null) {
    const run = detail.run;
    facts.push(["Status", run.status], ["Took", t.live ? `${offsetWords(t.span)} so far` : offsetWords(t.span)]);
    facts.push(["Nodes", String(t.rows.length)]);
    if (run.rowsWritten != null) facts.push(["Rows written", formatNumber(run.rowsWritten)]);
    if (run.error) facts.push(["Error", run.error]);
  } else {
    if (r.type) facts.push(["Type", r.type]);
    if (r.outcome && OUTCOME[r.outcome]) facts.push(["How it ended", OUTCOME[r.outcome].word]);
    facts.push(["Started", r.start == null ? "Did not start" : offsetWords(r.start)]);
    facts.push(["First row", r.firstRow == null ? (r.running ? "Waiting" : "No rows") : offsetWords(r.firstRow)]);
    facts.push(["Last row", r.lastRow == null || r.running ? "–" : offsetWords(r.lastRow)]);
    facts.push(["Ended", r.end == null ? (r.running ? "Still running" : "–") : offsetWords(r.end)]);
    if (r.start != null && r.end != null) facts.push(["Took", formatDuration(Math.round(r.end - r.start))]);
    const share = shareOfRun(r, t.span);
    if (share != null) facts.push(["Share of the run", shareWords(share)]);
    if (n?.rowsIn != null) facts.push(["Rows in", formatNumber(n.rowsIn)]);
    if (n?.rowsOut != null) facts.push(["Rows out", formatNumber(n.rowsOut)]);
    if (n?.peakMemory != null) facts.push(["Peak memory", formatBytes(n.peakMemory)]);
    const mark = qualityMark(quality.get(r.node));
    if (mark) facts.push(["Quality", mark.text]);
    if (n?.error) facts.push(["Error", n.error]);
  }
  return (
    <div className="run-details" aria-live="polite">
      <h3>{r == null ? `Run of ${detail.run.pipeline}` : `Node ${r.node}`}</h3>
      <dl>
        {facts.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
    </div>
  );
}
