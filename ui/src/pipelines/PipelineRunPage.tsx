import { useEffect, useState } from "react";
import { allows, formatNumber, pipelinesApi, rating, type AccessRole, type PipelineNodeQuality, plural } from "../api";
import { Meter, StatusChip, type FillTone, type Tone } from "../components/ui";
import {
  DECISION_WORDS,
  dimensionWord,
  gateWords,
  ON_FAIL_WORDS,
  percent,
  RULE_STATUS_WORDS,
} from "./quality";
import { dimensionRows, ESTIMATE, gapWords, pointsWords } from "./prediction";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { RunTimeSpent } from "./RunTimeSpent";
import { RunTimeline } from "./RunTimeline";
import { RunRates } from "./RunRates";
import { RunLogPanel } from "./RunLogPanel";
import { WrittenFilesList } from "./WrittenFilesList";
import { runOrigin } from "./timeline";
import { RunFacts, RunNodes, ScoreChip, useRunDetail } from "./RunSummary";
import { retrying, RunTriesList } from "../components/RunTries";
import "./pipelines.css";

const FILL: Record<string, FillTone> = { good: "good", fair: "warn", poor: "bad" };
const DECISION_TONE: Record<string, Tone> = { passed: "good", warned: "warn", quarantined: "orange", stopped: "bad" };
const RULE_TONE: Record<string, Tone> = { pass: "good", warn: "warn", fail: "bad", error: "neutral" };

/**
 * Measured beside predicted, per dimension, with the gap: the estimate made when the run started
 * from the catalogue's metadata against what the run measured. The gap says where the metadata,
 * or the pipeline, needs attention.
 */
function PredictedBeside({ q }: { q: PipelineNodeQuality }) {
  const rows = dimensionRows(q.measured, q.predicted);
  const gap = gapWords(q.measured, q.predicted);
  const unknown = q.prediction?.unknown ?? [];
  const assumed = q.prediction?.assumptions ?? [];
  return (
    <div className="pl-predicted">
      {gap && <p className="small pl-gap">{gap}</p>}
      {rows.length > 0 && (
        <div className="table-scroll" role="region" aria-label={`Measured and predicted quality of ${q.node}`} tabIndex={0}>
          <table className="small pl-predicted-table">
            <thead>
              <tr>
                <th scope="col">Dimension</th>
                <th scope="col">Measured</th>
                <th scope="col">Predicted {ESTIMATE}</th>
                <th scope="col">Gap</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.dimension}>
                  <th scope="row">{dimensionWord(r.dimension)}</th>
                  <td>
                    {r.measured == null ? (
                      <span className="muted">Not measured</span>
                    ) : (
                      <span className="pl-cell-meter">
                        <Meter value={r.measured} tone={FILL[rating(r.measured).tone]} size="sm" />
                        {percent(r.measured)}
                      </span>
                    )}
                  </td>
                  <td>{r.predicted == null ? <span className="muted">Unknown</span> : percent(r.predicted)}</td>
                  <td className={r.gap != null && Math.abs(r.gap) > 0.02 ? "pl-gap-wide" : undefined}>
                    {r.gap == null ? "–" : Math.round(r.gap * 100) === 0 ? "none" : pointsWords(r.gap)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(unknown.length > 0 || assumed.length > 0) && (
        <details className="small pl-predicted-more">
          <summary>What the estimate could not tell, and assumed</summary>
          {unknown.length > 0 && (
            <ul aria-label="Not known">
              {unknown.map((u, i) => (
                <li key={i}>Not known: {u}</li>
              ))}
            </ul>
          )}
          {assumed.length > 0 && (
            <ul aria-label="Assumed">
              {assumed.map((u, i) => (
                <li key={i}>Assumed: {u}</li>
              ))}
            </ul>
          )}
        </details>
      )}
    </div>
  );
}

/** One write's or gate's measured quality: its score, each dimension and, for a gate, each rule. */
function NodeQuality({ q }: { q: PipelineNodeQuality }) {
  const g = q.gate;
  const h = `q-${q.node}`;
  return (
    <li className="pl-quality-node" aria-labelledby={h}>
      <h3 id={h} className="pl-quality-head">
        <span className="mono">{q.node}</span>
        <span className="small muted">{q.role === "gate" ? "Quality gate" : "Output"}</span>
        {q.measured ? <ScoreChip score={q.measured.overall} /> : <StatusChip tone="neutral">Not measured</StatusChip>}
        {q.predicted ? (
          <StatusChip tone="neutral" title="Worked out when the run started, from the catalogue's metadata">
            Predicted {percent(q.predicted.overall)} {ESTIMATE}
          </StatusChip>
        ) : q.prediction ? (
          <StatusChip tone="neutral">Predicted: unknown {ESTIMATE}</StatusChip>
        ) : null}
      </h3>
      {q.asset && (
        <p className="small">
          {q.role === "gate" ? "Rules of " : "Wrote "}
          <a href={href.asset(q.asset)} className="mono">
            {q.asset}
          </a>
        </p>
      )}
      {q.note && <p className="small muted">{q.note}</p>}
      {g && (
        <p className="small">
          <StatusChip tone={DECISION_TONE[g.decision] ?? "neutral"}>{DECISION_WORDS[g.decision] ?? g.decision}</StatusChip>{" "}
          {gateWords(g)} of {formatNumber(g.rowsIn)} checked. When rows fail: {ON_FAIL_WORDS[g.onFail]}
          {g.minScore != null ? `; lowest score ${percent(g.minScore)}` : ""}
          {g.maxFailingShare != null ? `; at most ${percent(g.maxFailingShare)} of rows failing` : ""}.
        </p>
      )}
      {g?.stopReason && <p className="small pl-bad">{g.stopReason}</p>}
      {q.predicted || q.prediction ? (
        <PredictedBeside q={q} />
      ) : (
        q.measured &&
        Object.keys(q.measured.dimensions).length > 0 && (
          <ul className="pl-dims">
            {Object.entries(q.measured.dimensions).map(([d, v]) => (
              <li key={d}>
                <span className="pl-dim-name">{dimensionWord(d)}</span>
                <Meter value={v} tone={FILL[rating(v).tone]} size="sm" />
                <span className="pl-dim-value">{percent(v)}</span>
              </li>
            ))}
          </ul>
        )
      )}
      {q.measured && q.measured.reasons.length > 0 && (
        <ul className="small muted pl-reasons">
          {q.measured.reasons.map((r, i) => (
            <li key={i}>{r}</li>
          ))}
        </ul>
      )}
      {g && g.rules.length > 0 && (
        <div className="table-scroll" role="region" aria-label={`Rules checked by ${q.node}`} tabIndex={0}>
          <table className="small">
            <thead>
              <tr>
                <th scope="col">Rule</th>
                <th scope="col">From</th>
                <th scope="col">Result</th>
                <th scope="col">Rows failing</th>
              </tr>
            </thead>
            <tbody>
              {g.rules.map((r, i) => (
                <tr key={`${i}-${r.origin}`}>
                  <th scope="row">
                    {r.name}
                    <div className="muted">
                      {r.type.replace(/_/g, " ")}
                      {r.field ? ` on ${r.field}` : ""} · {dimensionWord(r.dimension)}
                    </div>
                  </th>
                  <td>{r.origin === "asset" ? "The asset's rules" : "Written on the gate"}</td>
                  <td>
                    <StatusChip tone={RULE_TONE[r.status] ?? "neutral"}>{RULE_STATUS_WORDS[r.status] ?? r.status}</StatusChip>
                    {r.message && <div className="muted">{r.message}</div>}
                  </td>
                  <td>
                    {r.status === "error" || ["freshness", "row_count", "row_count_change"].includes(r.type)
                      ? "–"
                      : `${formatNumber(r.failing)} of ${formatNumber(r.total)}`}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </li>
  );
}

/**
 * One pipeline run: its status and timings, a timeline of its nodes, each node's rows in and out per
 * port, memory, time and error (while it runs, each node's rows so far and rate, and the node holding
 * it back), its log, what its writes wrote (linked to the catalogue) and the lineage recorded. Looks
 * again every two seconds until the run has finished, and says when its status changes.
 */
export function PipelineRunPage({ id, role }: { id: number; role: AccessRole }) {
  useTitle(`Pipeline run ${id}`);
  const steward = allows(role, "steward");
  const { detail: shownDetail, error: loadError, notice: statusNotice, tick, fetchedAt, load, unfinished } = useRunDetail(id);
  const detail = shownDetail?.run.id === id ? shownDetail : null;
  const [notice, setNotice] = useState("");
  const [cancelError, setCancelError] = useState<string | null>(null);
  const error = cancelError ?? loadError;
  useEffect(() => {
    if (statusNotice) setNotice(statusNotice);
  }, [statusNotice]);

  const cancel = async () => {
    try {
      await pipelinesApi.cancel(id);
      setNotice(`Asked run ${id} to stop.`);
      setCancelError(null);
      load();
    } catch (e) {
      setCancelError((e as Error).message);
    }
  };

  if (!detail) {
    return (
      <>
        <h1 tabIndex={-1}>Pipeline run {id}</h1>
        {error ? (
          <p role="alert" className="error">
            {error}
          </p>
        ) : (
          <p role="status">Loading…</p>
        )}
      </>
    );
  }
  const r = detail.run;
  // The run's time on the worker's clock (when it started and finished with the run), as the charts use.
  const origin = runOrigin(detail);
  const clock = { fetchedAt };
  const nodeNames = detail.nodes.length > 0 ? detail.nodes.map((s) => s.node) : (detail.progress?.nodes.map((s) => s.node) ?? []);
  return (
    <>
      <p className="small">
        <a href={href.pipelines()}>Pipelines</a> › <a href={href.pipeline(r.pipeline)}>{r.pipeline}</a>
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>
          Run {r.id} of {r.pipeline}
        </h1>
        {steward && unfinished && !r.cancelRequested && (
          <button type="button" onClick={cancel}>
            Cancel run
          </button>
        )}
      </div>
      <p role="status" className="notice">
        {retrying(r) ?? notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="run-h" className="panel">
        <h2 id="run-h">Summary</h2>
        <RunFacts run={r} />
      </section>
      <RunTriesList tries={detail.tries} run={r} />
      {r.status === "failed" && (
        <p className="small muted">
          A pipeline starts again from its beginning: there is no Run again from the failure for one. To carry on
          from part-way, make its stages the nodes of a flow.
        </p>
      )}

      <section aria-labelledby="spent-h" className="panel">
        <h2 id="spent-h">Where each node spent its time</h2>
        <RunTimeSpent detail={detail} clock={clock} />
      </section>
      <section aria-labelledby="timeline-h" className="panel">
        <h2 id="timeline-h">Run timeline</h2>
        <RunTimeline detail={detail} clock={clock} tiles={false} />
      </section>

      <section aria-labelledby="rates-h" className="panel">
        <h2 id="rates-h">Rows per second through the run</h2>
        <RunRates detail={detail} clock={clock} tick={tick} />
      </section>

      <section aria-labelledby="nodes-h" className="panel">
        <h2 id="nodes-h">Nodes</h2>
        <RunNodes detail={detail} />
      </section>

      <section aria-labelledby="log-h" className="panel">
        <h2 id="log-h">Log</h2>
        <RunLogPanel runId={r.id} nodes={nodeNames} origin={origin} tick={tick} final={!unfinished} />
      </section>

      {(detail.quality?.length ?? 0) > 0 && (
        <section aria-labelledby="quality-h" className="panel">
          <h2 id="quality-h">Quality</h2>
          <p className="small muted">
            Measured in the run itself: each gate from its rules, each output from the profile made as it was written.
            Beside each, the score Kilnmark predicted when the run started from the catalogue's metadata (an estimate);
            the gap shows where the metadata, or the pipeline, needs attention.
          </p>
          <ul className="pl-quality">
            {detail.quality!.map((q) => (
              <NodeQuality key={q.node} q={q} />
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="written-h" className="panel">
        <h2 id="written-h">What it wrote</h2>
        {detail.written.length === 0 ? (
          <p className="muted">{unfinished ? "Known when the run ends." : "Nothing was written."}</p>
        ) : (
          <ul className="pl-written">
            {detail.written.map((w) => (
              <li key={w.node}>
                {w.registered ? (
                  <a href={href.asset(w.urn)} className="mono">
                    {w.target}
                  </a>
                ) : (
                  <span className="mono">{w.target}</span>
                )}{" "}
                in {w.source} ({w.mode}), by node <span className="mono">{w.node}</span>: {plural(w.rows, "row")}.
                {!w.registered && <span className="muted"> Not in the catalogue.</span>}
              </li>
            ))}
          </ul>
        )}
        <WrittenFilesList runId={r.id} finished={!unfinished} />
        {detail.lineage.length > 0 && (
          <>
            <h3>Lineage</h3>
            <ul className="small">
              {detail.lineage.map((l, i) => (
                <li key={i}>
                  <a href={href.asset(l.output)} className="mono">
                    {l.output}
                  </a>{" "}
                  came from{" "}
                  <a href={href.asset(l.input)} className="mono">
                    {l.input}
                  </a>
                </li>
              ))}
            </ul>
          </>
        )}
        {detail.notes.length > 0 && (
          <>
            <h3>Notes</h3>
            <ul className="small">
              {detail.notes.map((n, i) => (
                <li key={i}>{n}</li>
              ))}
            </ul>
          </>
        )}
      </section>
    </>
  );
}
