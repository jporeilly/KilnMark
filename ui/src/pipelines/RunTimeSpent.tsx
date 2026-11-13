import { IconClockHour4, IconHourglass, IconStack2, IconTargetArrow } from "@tabler/icons-react";
import { type PipelineRunDetail, plural } from "../api";
import { StatName, StatTile, StatusChip } from "../components/ui";
import { qualityMark } from "./RunTimeline";
import { shares, timeSpent, type SpentRow } from "./timeSpent";
import { offsetWords, peakConcurrent, shareOfRun, shareWords, slowestNode, timeline, type Clock } from "./timeline";

/** "What limited this run: check, working 87% of the time", or the source when nothing was held up. */
export function limitWords(limit: { node: string; working: number; bySource: boolean } | null, live: boolean): string {
  if (!limit) return live ? "Not known yet." : "Not known.";
  const when = live ? "so far" : "of the time";
  return limit.bySource
    ? `Nothing was held up: this run was limited by reading its source, ${limit.node} (working ${shareWords(limit.working)} ${when}).`
    : `What limited this run: ${limit.node}, working ${shareWords(limit.working)} ${when}.`;
}

/** What a node did, in words, at the end of its row: a gate's verdict, a write's quality, else its rows. */
function result(detail: PipelineRunDetail, node: string): string | null {
  const mark = qualityMark((detail.quality ?? []).find((q) => q.node === node));
  if (mark) return mark.text;
  const s = detail.nodes.find((x) => x.node === node);
  if (s?.rowsOut != null) return `${plural(s.rowsOut, "row")} out`;
  if (s?.rowsIn != null) return `${plural(s.rowsIn, "row")} in`;
  const p = detail.progress?.nodes.find((x) => x.node === node);
  if (p) return p.rowsOut > 0 ? `${plural(p.rowsOut, "row")} out so far` : `${plural(p.rowsIn, "row")} in so far`;
  return null;
}

function Segments({ r }: { r: SpentRow }) {
  const s = shares(r);
  const pc = (x: number) => `${(x * 100).toFixed(2)}%`;
  return (
    <span className="pl-ts-bar">
      {s.working > 0 && (
        <span className="pl-ts-working" style={{ width: pc(s.working) }} title={`Working ${shareWords(s.working)}`} />
      )}
      {s.waiting > 0 && (
        <span className="pl-ts-waiting" style={{ width: pc(s.waiting) }} title={`Waiting for rows ${shareWords(s.waiting)}`} />
      )}
      {s.heldUp > 0 && (
        <span
          className="pl-ts-heldup"
          style={{ width: pc(s.heldUp) }}
          title={`Held up by the next node ${shareWords(s.heldUp)}`}
        />
      )}
    </span>
  );
}

/**
 * Where each node spent its time (docs/guide/pipelines.md, "Reading a run"): the run at a glance in
 * tiles, what limited it in one line, and per node a bar of 100% split into working, waiting for rows
 * and held up by the next node (back-pressure), each with its own pattern, with "% working" and what
 * the node did at the right. A table says the same.
 */
export function RunTimeSpent({ detail, clock }: { detail: PipelineRunDetail; clock?: Clock }) {
  const spent = timeSpent(detail, clock);
  const t = timeline(detail, clock);
  if (!spent || !t) {
    const unfinished = detail.run.status === "queued" || detail.run.status === "running";
    return (
      <p className="muted">
        {unfinished ? "Where each node spends its time appears once the nodes start." : "This run's nodes were not timed."}
      </p>
    );
  }
  const longest = slowestNode(t);
  return (
    <div className="pl-ts">
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
          value={longest ? <StatName name={longest.node} /> : "–"}
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
          label="What limited this run"
          value={spent.limit ? <StatName name={spent.limit.node} /> : "–"}
          icon={<IconTargetArrow size={18} aria-hidden="true" />}
          tint="teal"
          note={
            spent.limit
              ? spent.limit.bySource
                ? `reading its source, working ${shareWords(spent.limit.working)}`
                : `working ${shareWords(spent.limit.working)} of the time`
              : undefined
          }
        />
      </section>
      <p className="pl-ts-limit">
        <strong>{limitWords(spent.limit, spent.live)}</strong>
      </p>
      <ul className="pl-ts-rows" aria-label="Where each node spent its time">
        {spent.rows.map((r) => {
          const s = shares(r);
          const words = result(detail, r.node);
          const limit = spent.limit?.node === r.node;
          return (
            <li key={r.node} className={`pl-ts-row${limit ? " is-limit" : ""}`}>
              <span className="pl-ts-name">
                <span className="mono pl-tl-node">{r.node}</span>
                {limit && (
                  <StatusChip tone="orange" kind="limit">
                    Limited the run
                  </StatusChip>
                )}
              </span>
              <span
                className="pl-ts-track"
                role="img"
                aria-label={`${r.node}: working ${shareWords(s.working)}, waiting for rows ${shareWords(s.waiting)}, held up by the next node ${shareWords(s.heldUp)}${r.running ? ", so far" : ""}`}
              >
                <Segments r={r} />
              </span>
              <span className="pl-ts-result small">
                <strong>{shareWords(s.working)} working</strong>
                {words && <> · {words}</>}
              </span>
            </li>
          );
        })}
      </ul>
      <p className="small muted pl-tl-key" aria-hidden="true">
        <span className="pl-ts-working" /> Working <span className="pl-ts-waiting" /> Waiting for rows{" "}
        <span className="pl-ts-heldup" /> Held up by the next node
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
                <th scope="col">Waiting for rows</th>
                <th scope="col">Held up by the next node</th>
                <th scope="col">Result</th>
              </tr>
            </thead>
            <tbody>
              {spent.rows.map((r) => {
                const s = shares(r);
                return (
                  <tr key={r.node}>
                    <th scope="row">
                      <span className="mono">{r.node}</span>
                      {spent.limit?.node === r.node && <div>Limited the run</div>}
                    </th>
                    <td>
                      {offsetWords(r.active)}
                      {r.running ? " so far" : ""}
                    </td>
                    <td>{shareWords(s.working)}</td>
                    <td>{shareWords(s.waiting)}</td>
                    <td>{shareWords(s.heldUp)}</td>
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
