import { lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  allows,
  flowsApi,
  formatTime,
  type AccessRole,
  type FlowItemRun,
  type FlowNodeStatus,
  type FlowRunDetail,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { itemsProgress } from "./chart";
import { FlowRunChart } from "./FlowRunChart";
import { FlowRunFacts, FlowTimeSpent, took } from "./FlowRunSummary";
import { readFlowGraph, TYPE_WORDS, type FlowGraphResult } from "./graph";
import { FlowStatus } from "./RunFlow";
import { reportedWords, timedOut, timedOutItems } from "./runFacts";
import "../pipelines/pipelines.css";

const FlowDiagram = lazy(() => import("./FlowDiagram"));

const LOOK_MS = 2000;

/** Said beside the status of a node, or an item, that took longer than its timeout and was stopped. */
function TimedOut() {
  return (
    <>
      {" "}
      <span className="badge badge-failed">Timed out</span>
    </>
  );
}

/**
 * A for-each node's items: each value with its status and a link to its pipeline run. Open while
 * there are few; with many, closed until asked for. A value withheld from viewers is said as such.
 * An item whose run took longer than the node's timeout is marked as timed out.
 */
function ItemRuns({ node, items, late }: { node: string; items: FlowItemRun[]; late: Set<number> }) {
  return (
    <details className="small fr-items" open={items.length <= 10}>
      <summary>
        Items of {node} ({items.length})
      </summary>
      <ol>
        {items.map((i) => (
          <li key={i.item}>
            {i.value == null ? <span className="muted">Item {i.item + 1}</span> : <span className="mono">{String(i.value)}</span>}{" "}
            <FlowStatus status={i.status} />
            {late.has(i.item) && i.status !== "succeeded" && <TimedOut />}
            {i.pipelineRunId != null ? (
              <>
                {" "}
                <a href={href.pipelineRun(i.pipelineRunId)}>Pipeline run {i.pipelineRunId}</a>
              </>
            ) : (
              i.status === "failed" && <span className="muted"> Could not be started; the node's message says why.</span>
            )}
          </li>
        ))}
      </ol>
    </details>
  );
}

/**
 * One flow run, laid out as a pipeline run is: its summary (status, version, who started it, its
 * times, its nodes by how they stand, and what its pipeline runs came to) and the values it ran
 * with; where each node spent its time; the diagram of its version with each node's status on it;
 * the run chart; and each node in order, with its status, time and message, linked to the pipeline
 * run it started. Looked at every two seconds while it is queued or running. Stewards can cancel
 * it while it goes.
 */
export function FlowRunPage({ id, role }: { id: number; role: AccessRole }) {
  useTitle(`Flow run ${id}`);
  const steward = allows(role, "steward");
  const [detail, setDetail] = useState<FlowRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [cancelling, setCancelling] = useState(false);
  // The diagram's YAML: the run's own version, read once.
  const [graph, setGraph] = useState<FlowGraphResult | null>(null);
  // The page's clock, read when an answer arrives, so running bars and times move with each look.
  const [now, setNow] = useState(() => Date.now());

  // Only the latest answer counts: an earlier look can arrive after a later one.
  const sequence = useRef(0);
  const load = useCallback(() => {
    const mine = ++sequence.current;
    flowsApi
      .runDetail(id)
      .then((d) => {
        if (sequence.current !== mine) return;
        setDetail(d);
        setNow(Date.now());
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

  const flow = detail?.run.flow;
  const flowVersion = detail?.run.version;
  useEffect(() => {
    if (flow == null || flowVersion == null) return;
    let live = true;
    flowsApi
      .version(flow, flowVersion)
      .then((v) => live && setGraph(readFlowGraph(v.yaml)))
      .catch(() => live && setGraph({ ok: false, error: "The flow's version could not be read." }));
    return () => {
      live = false;
    };
  }, [flow, flowVersion]);

  const statuses = useMemo(() => {
    const out: Record<string, FlowNodeStatus> = {};
    for (const n of detail?.nodes ?? []) out[n.node] = n.status;
    return out;
  }, [detail]);

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
      <p role="status" className="notice">
        {going ? notice : ""}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="fr-run-h" className="panel">
        <h2 id="fr-run-h">Summary</h2>
        <FlowRunFacts detail={detail} now={now} />
      </section>
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
      <section aria-labelledby="fr-spent-h" className="panel">
        <h2 id="fr-spent-h">Where each node spent its time</h2>
        <FlowTimeSpent detail={detail} now={now} />
      </section>
      <section aria-labelledby="fr-diagram-h" className="panel">
        <h2 id="fr-diagram-h">Diagram</h2>
        {graph == null ? (
          <p role="status">Loading…</p>
        ) : graph.ok ? (
          <Suspense fallback={<p role="status">Loading…</p>}>
            <FlowDiagram graph={graph.graph} statuses={statuses} label={`Diagram of version ${run.version} of ${run.flow}, with each node's status in this run`} />
          </Suspense>
        ) : (
          <p className="muted">{graph.error}</p>
        )}
      </section>
      <section aria-labelledby="fr-chart-h" className="panel">
        <h2 id="fr-chart-h">Run chart</h2>
        <FlowRunChart detail={detail} now={now} />
      </section>
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
                const progress = n.items && n.items.length > 0 ? itemsProgress(n.items) : null;
                const said = [n.message === progress ? null : n.message, reportedWords(n)].filter(Boolean).join(". ");
                return (
                  <tr key={n.node}>
                    <th scope="row">
                      <span className="mono">{n.node}</span>
                      <div className="small muted">
                        {TYPE_WORDS[n.type] ?? n.type}
                        {n.pipeline && (
                          <>
                            : <a href={href.pipeline(n.pipeline)}>{n.pipeline}</a>
                          </>
                        )}
                      </div>
                    </th>
                    <td>
                      <FlowStatus status={n.status} />
                      {timedOut(n) && <TimedOut />}
                    </td>
                    <td>{n.startedAt ? formatTime(n.startedAt) : "–"}</td>
                    <td>{took(n.startedAt, n.finishedAt, now)}</td>
                    <td>
                      {progress && <div>{progress}.</div>}
                      {said || (!progress && <span className="muted">–</span>)}
                      {progress && n.items && <ItemRuns node={n.node} items={n.items} late={timedOutItems(n)} />}
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
