import { IconClockHour4, IconClockPause, IconHourglass, IconStack2 } from "@tabler/icons-react";
import { formatNumber, formatTime, type FlowRunDetail } from "../api";
import { StatTile, StatusChip } from "../components/ui";
import { href } from "../router";
import { formatDuration } from "../pipelines/RunStatus";
import { ScoreChip } from "../pipelines/RunSummary";
import { offsetWords, shareWords } from "../pipelines/timeline";
import { itemsProgress } from "./chart";
import { FlowRunStatus } from "./RunFlow";
import {
  barWidths,
  flowShares,
  flowTimeSpent,
  flowTotals,
  nodeCountWords,
  reportedWords,
  waitWords,
  type FlowSpentRow,
} from "./runFacts";
import "./flows.css";

/** How long something took, or has taken so far; a dash before it starts. */
export function took(start: string | null, end: string | null, now: number = Date.now()): string {
  if (!start) return "–";
  return formatDuration(Math.max(0, (end ? new Date(end).getTime() : now) - new Date(start).getTime()));
}

/**
 * A flow run's facts, as a pipeline run's are given: status, version, who started it, when it was
 * asked for, started and finished, how long it took; then its nodes by how they stand, the pipeline
 * runs it started, the rows they wrote and the lowest quality among them; and why it failed.
 */
export function FlowRunFacts({ detail, now }: { detail: FlowRunDetail; now?: number }) {
  const r = detail.run;
  const totals = flowTotals(detail);
  const going = r.status === "queued" || r.status === "running";
  return (
    <>
      <dl className="pl-facts">
        <dt>Status</dt>
        <dd>
          <FlowRunStatus run={r} />
        </dd>
        <dt>Version</dt>
        <dd>
          <a href={href.flow(r.flow, r.version)}>Version {r.version}</a>
        </dd>
        <dt>Started by</dt>
        <dd>
          {r.requestedBy}
          {r.requestedRole ? ` (${r.requestedRole})` : ""}
        </dd>
        <dt>Asked for</dt>
        <dd>{formatTime(r.requestedAt)}</dd>
        <dt>Started</dt>
        <dd>{formatTime(r.startedAt)}</dd>
        <dt>Finished</dt>
        <dd>{formatTime(r.finishedAt)}</dd>
        <dt>Took</dt>
        <dd>
          {took(r.startedAt, r.finishedAt, now)}
          {going && r.startedAt ? <span className="small muted"> so far</span> : null}
        </dd>
        <dt>Nodes</dt>
        <dd>{nodeCountWords(detail.nodes)}</dd>
        <dt>Pipeline runs</dt>
        <dd>{formatNumber(totals.pipelineRuns)} asked for</dd>
        <dt>Rows written</dt>
        <dd>
          {totals.rowsWritten == null ? (
            <span className="muted">{going ? "Known as its pipeline runs end." : "Not known."}</span>
          ) : (
            <>
              {formatNumber(totals.rowsWritten)}{" "}
              <span className="small muted">by its pipeline runs, {going ? "so far" : "in all"}</span>
            </>
          )}
        </dd>
        {totals.lowestScore != null && (
          <>
            <dt>Quality</dt>
            <dd>
              <ScoreChip score={totals.lowestScore} /> <span className="small muted">lowest of what its pipeline runs wrote</span>
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

/** What a node did, in words, at the end of its row: its items' progress or what it reported. */
function result(detail: FlowRunDetail, node: string): string | null {
  const n = detail.nodes.find((x) => x.node === node);
  if (!n) return null;
  if (n.items && n.items.length > 0) return itemsProgress(n.items);
  return reportedWords(n) || null;
}

/** A node's bar on the run's scale: split into its parts, or one plain part when it cannot be split. */
function Segments({ r, span }: { r: FlowSpentRow; span: number }) {
  const s = flowShares(r);
  const w = barWidths(r, span);
  const pc = (x: number) => `${(x * 100).toFixed(2)}%`;
  return (
    <span className="pl-ts-bar">
      {w.plain > 0 && <span className="fr-ts-plain" style={{ width: pc(w.plain) }} title="Not split" />}
      {w.working > 0 && <span className="pl-ts-working" style={{ width: pc(w.working) }} title={`Working ${shareWords(s.working)}`} />}
      {w.queued > 0 && (
        <span className="pl-ts-heldup" style={{ width: pc(w.queued) }} title={`Queued for a worker ${shareWords(s.queued)}`} />
      )}
      {w.waiting > 0 && <span className="pl-ts-waiting" style={{ width: pc(w.waiting) }} title={`Waiting ${shareWords(s.waiting)}`} />}
    </span>
  );
}

const rowLabel = (r: FlowSpentRow, span: number) => {
  const time = `${offsetWords(r.active)}${r.running ? " so far" : ""}, ${shareWords(Math.min(1, r.active / span))} of the run`;
  if (!r.split) return `${r.node}: ${time}, not split: its pipeline run times are not known`;
  const s = flowShares(r);
  return `${r.node}: ${time}; working ${shareWords(s.working)}, queued for a worker ${shareWords(s.queued)}, waiting ${shareWords(s.waiting)}`;
};

/**
 * Where each node spent its time (docs/guide/flows.md, "Reading a run"), as a pipeline run shows
 * it: the run at a glance in tiles, what it waited for in one line, and per node a bar on the
 * run's scale split into working, queued for a worker and waiting, each with its own pattern (one
 * plain bar for a node whose pipeline run times are not known). A table says the same. From the
 * times recorded for each node and each pipeline run it asked for.
 */
export function FlowTimeSpent({ detail, now }: { detail: FlowRunDetail; now: number }) {
  const spent = flowTimeSpent(detail, now);
  if (!spent) {
    const going = detail.run.status === "queued" || detail.run.status === "running";
    return <p className="muted">{going ? "Where each node spends its time appears once the nodes start." : "No node of this run started."}</p>;
  }
  const { longest, queued } = spent;
  const words = waitWords(spent);
  return (
    <div className="pl-ts">
      <section aria-label="The run at a glance" className="km-grid-4 pl-tl-tiles">
        <StatTile
          label="Wall-clock time"
          value={offsetWords(spent.span)}
          icon={<IconClockHour4 size={18} aria-hidden="true" />}
          tint="sky"
          note={spent.live ? "so far" : "from start to end"}
        />
        <StatTile
          label="Most nodes at once"
          value={String(spent.mostNodes)}
          icon={<IconStack2 size={18} aria-hidden="true" />}
          tint="violet"
          note={`of ${spent.rows.length} that ran`}
        />
        <StatTile
          label={spent.live ? "Longest node so far" : "Longest node"}
          value={longest ? <span className="mono">{longest.node}</span> : "–"}
          icon={<IconHourglass size={18} aria-hidden="true" />}
          tint="orange"
          note={longest ? `${offsetWords(longest.active)}, ${shareWords(Math.min(1, longest.active / spent.span))} of the run` : undefined}
        />
        <StatTile
          label="Queued for a worker"
          value={queued.runs > 0 ? offsetWords(queued.ms) : "–"}
          icon={<IconClockPause size={18} aria-hidden="true" />}
          tint="amber"
          note={
            queued.of === 0
              ? "no pipeline runs"
              : queued.runs === 0
                ? "not recorded for this run"
                : queued.runs < queued.of
                  ? `in all, across ${queued.runs} of ${queued.of} pipeline runs`
                  : `in all, across ${queued.runs} ${queued.runs === 1 ? "pipeline run" : "pipeline runs"}`
          }
        />
      </section>
      {words && (
        <p className="pl-ts-limit">
          <strong>{words}</strong>
        </p>
      )}
      <ul className="pl-ts-rows" aria-label="Where each node spent its time">
        {spent.rows.map((r) => {
          const s = flowShares(r);
          const said = result(detail, r.node);
          const isLongest = longest?.node === r.node && spent.rows.length > 1;
          return (
            <li key={r.node} className="pl-ts-row">
              <span className="pl-ts-name">
                <span className="mono pl-tl-node">{r.node}</span>
                {isLongest && (
                  <StatusChip tone="orange" kind="limit">
                    Longest
                  </StatusChip>
                )}
              </span>
              <span className="pl-ts-track" role="img" aria-label={rowLabel(r, spent.span)}>
                <Segments r={r} span={spent.span} />
              </span>
              <span className="pl-ts-result small">
                <strong>
                  {r.split ? `${shareWords(s.working)} working` : `${offsetWords(r.active)}${r.running ? " so far" : ""}, not split`}
                </strong>
                {said && <> · {said}</>}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="small muted pl-tl-key" aria-hidden="true">
        <span className="pl-ts-working" /> Working <span className="pl-ts-heldup" /> Queued for a worker{" "}
        <span className="pl-ts-waiting" /> Waiting
        {spent.rows.some((r) => !r.split) && (
          <>
            {" "}
            <span className="fr-ts-plain" /> Not split
          </>
        )}
      </p>
      <p className="small muted">
        Each bar is as long as the node&apos;s time, on the run&apos;s scale.
        {spent.rows.some((r) => !r.split) &&
          " A bar that is not split is a node whose pipeline run times are not known: its run was deleted, or is from before they were recorded."}
      </p>
      <details className="pl-tl-table">
        <summary>Show where each node spent its time as a table</summary>
        <div className="table-scroll" role="region" aria-label="Where each node spent its time, as a table" tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Node</th>
                <th scope="col">Time</th>
                <th scope="col">Working</th>
                <th scope="col">Queued for a worker</th>
                <th scope="col">Waiting</th>
                <th scope="col">Result</th>
              </tr>
            </thead>
            <tbody>
              {spent.rows.map((r) => {
                const s = flowShares(r);
                return (
                  <tr key={r.node}>
                    <th scope="row">
                      <span className="mono">{r.node}</span>
                    </th>
                    <td>
                      {offsetWords(r.active)}
                      {r.running ? " so far" : ""}
                    </td>
                    <td>{r.split ? shareWords(s.working) : "–"}</td>
                    <td>{r.split ? shareWords(s.queued) : "–"}</td>
                    <td>{r.split ? shareWords(s.waiting) : "–"}</td>
                    <td>{result(detail, r.node) ?? "–"}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  );
}
