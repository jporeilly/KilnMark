import { useCallback, useEffect, useRef, useState } from "react";
import { allows, flowsApi, formatNumber, formatTime, type AccessRole, type FlowNodeRun, type FlowRunDetail } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { formatDuration } from "../pipelines/RunStatus";
import { FlowRunStatus, FlowStatus } from "./RunFlow";
import "../pipelines/pipelines.css";

const LOOK_MS = 2000;

/** How long something took, or has taken so far; a dash before it starts. */
function took(start: string | null, end: string | null): string {
  if (!start) return "–";
  return formatDuration((end ? new Date(end).getTime() : Date.now()) - new Date(start).getTime());
}

/** What a node reported, in words: rows written, score, share set aside, rows changed. */
export function reportedWords(n: FlowNodeRun): string {
  const r = n.reported ?? {};
  const out: string[] = [];
  const num = (k: string) => (typeof r[k] === "number" ? (r[k] as number) : null);
  const written = num("rows_written");
  if (written != null) out.push(`${formatNumber(written)} rows written`);
  const score = num("score");
  if (score != null) out.push(`score ${Math.round(score * 100)}%`);
  const aside = num("quarantined_share");
  if (aside != null) out.push(`${(aside * 100).toFixed(aside < 0.1 ? 1 : 0)}% set aside`);
  const changed = num("rows_affected");
  if (changed != null) out.push(`${formatNumber(changed)} rows changed`);
  return out.join(", ");
}

const TYPE: Record<string, string> = {
  "run-pipeline": "Runs a pipeline",
  notify: "Sends a notice",
  wait: "Waits",
  stop: "Ends the flow",
  sql: "Runs SQL",
};

/**
 * One flow run: its status, who started it and with which values, and each node of its version in
 * order, with its status, time and message, linked to the pipeline run it started. Looked at every
 * two seconds while it is queued or running. Stewards can cancel it while it goes.
 */
export function FlowRunPage({ id, role }: { id: number; role: AccessRole }) {
  useTitle(`Flow run ${id}`);
  const steward = allows(role, "steward");
  const [detail, setDetail] = useState<FlowRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [cancelling, setCancelling] = useState(false);

  // Only the latest answer counts: an earlier look can arrive after a later one.
  const sequence = useRef(0);
  const load = useCallback(() => {
    const mine = ++sequence.current;
    flowsApi
      .runDetail(id)
      .then((d) => {
        if (sequence.current !== mine) return;
        setDetail(d);
        setError(null);
      })
      .catch((e: Error) => {
        if (sequence.current === mine) setError(e.message);
      });
  }, [id]);

  useEffect(() => {
    setDetail(null);
    load();
  }, [load]);

  const going = detail != null && (detail.run.status === "queued" || detail.run.status === "running");
  useEffect(() => {
    if (!going) return;
    const t = window.setInterval(load, LOOK_MS);
    return () => window.clearInterval(t);
  }, [going, load]);

  const cancel = async () => {
    if (!window.confirm(`Cancel flow run ${id}? Nothing more starts, and the pipeline runs it started that are still going are cancelled.`))
      return;
    setCancelling(true);
    try {
      await flowsApi.cancel(id);
      setNotice("Asked to stop. It ends within a moment.");
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setCancelling(false);
    }
  };

  if (!detail) {
    return (
      <>
        <h1 tabIndex={-1}>Flow run {id}</h1>
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

  const { run, nodes } = detail;
  const values = Object.entries(run.parameters ?? {});

  return (
    <>
      <p className="small">
        <a href={href.flows()}>Flows</a> › <a href={href.flow(run.flow)}>{run.flow}</a>
      </p>
      <div className="pl-head">
        <h1 tabIndex={-1}>
          Run {run.id} of {run.flow}
        </h1>
        {steward && going && (
          <div className="pl-head-actions">
            <button type="button" onClick={cancel} disabled={cancelling || run.cancelRequested}>
              {run.cancelRequested ? "Stopping…" : cancelling ? "Cancelling…" : "Cancel run"}
            </button>
          </div>
        )}
      </div>
      <p>
        <FlowRunStatus run={run} />{" "}
        <span className="small muted">
          <a href={href.flow(run.flow, run.version)}>Version {run.version}</a>, started by {run.requestedBy}
          {run.requestedRole ? ` (${run.requestedRole})` : ""}, asked for {formatTime(run.requestedAt)}
          {run.startedAt && (
            <>
              ; {run.finishedAt ? "took" : "running for"} {took(run.startedAt, run.finishedAt)}
            </>
          )}
          .
        </span>
      </p>
      {run.error && <p className={run.status === "failed" ? "error" : "small muted"}>{run.error}</p>}
      <p role="status" className="notice">
        {going ? notice : ""}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {values.length > 0 && (
        <section aria-labelledby="fr-values-h" className="panel">
          <h2 id="fr-values-h">Values it ran with</h2>
          <dl className="small">
            {values.map(([k, v]) => (
              <div key={k}>
                <dt className="mono">{k}</dt>
                <dd>{Array.isArray(v) ? v.join(", ") : String(v)}</dd>
              </div>
            ))}
          </dl>
        </section>
      )}
      <section aria-labelledby="fr-nodes-h" className="panel">
        <h2 id="fr-nodes-h">Nodes</h2>
        <div className="table-scroll" role="region" aria-label="Nodes (scrolls sideways on small screens)" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Node</th>
                <th scope="col">Status</th>
                <th scope="col">Started</th>
                <th scope="col">Took</th>
                <th scope="col">What happened</th>
              </tr>
            </thead>
            <tbody>
              {nodes.map((n) => {
                const said = [n.message, reportedWords(n)].filter(Boolean).join(". ");
                return (
                  <tr key={n.node}>
                    <th scope="row">
                      <span className="mono">{n.node}</span>
                      <div className="small muted">
                        {TYPE[n.type] ?? n.type}
                        {n.pipeline && (
                          <>
                            : <a href={href.pipeline(n.pipeline)}>{n.pipeline}</a>
                          </>
                        )}
                      </div>
                    </th>
                    <td>
                      <FlowStatus status={n.status} />
                    </td>
                    <td>{n.startedAt ? formatTime(n.startedAt) : "–"}</td>
                    <td>{took(n.startedAt, n.finishedAt)}</td>
                    <td>
                      {said || <span className="muted">–</span>}
                      {n.pipelineRunId != null && (
                        <div className="small">
                          <a href={href.pipelineRun(n.pipelineRunId)}>Pipeline run {n.pipelineRunId}</a>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
