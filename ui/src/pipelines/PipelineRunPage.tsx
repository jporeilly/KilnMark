import { useCallback, useEffect, useRef, useState } from "react";
import { allows, formatNumber, formatTime, pipelinesApi, type AccessRole, type PipelineRunDetail } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { formatBytes, formatDuration, RunStatus, runTime } from "./RunStatus";
import "./pipelines.css";

/** Rows per port, in words: "main 10, lookup 4". */
function portRows(ports: Record<string, number | null> | undefined): string {
  if (!ports || Object.keys(ports).length === 0) return "";
  return Object.entries(ports)
    .map(([port, n]) => `${port} ${n == null ? "not counted" : formatNumber(n)}`)
    .join(", ");
}

/**
 * One pipeline run: its status and timings, each step's rows in and out per port, memory, time and
 * error, what its writes wrote (linked to the catalogue) and the lineage recorded. Looks again every
 * two seconds until the run has finished, and says when its status changes.
 */
export function PipelineRunPage({ id, role }: { id: number; role: AccessRole }) {
  useTitle(`Pipeline run ${id}`);
  const steward = allows(role, "steward");
  const [detail, setDetail] = useState<PipelineRunDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const last = useRef<string | null>(null);

  const load = useCallback(() => {
    pipelinesApi
      .runDetail(id)
      .then((d) => {
        if (last.current != null && last.current !== d.run.status) {
          setNotice(`Run ${id} is now ${d.run.status}.`);
        }
        last.current = d.run.status;
        setDetail(d);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [id]);

  useEffect(load, [load]);
  const unfinished = detail != null && (detail.run.status === "queued" || detail.run.status === "running");
  useEffect(() => {
    if (!unfinished) return;
    const t = window.setInterval(load, 2000);
    return () => window.clearInterval(t);
  }, [unfinished, load]);

  const cancel = async () => {
    try {
      await pipelinesApi.cancel(id);
      setNotice(`Asked run ${id} to stop.`);
      load();
    } catch (e) {
      setError((e as Error).message);
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
  // The engine does not time steps yet: the column appears once it does.
  const timed = detail.steps.some((s) => s.durationMs != null);
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
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="run-h" className="panel">
        <h2 id="run-h">Summary</h2>
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
            {formatTime(r.requestedAt)} by {r.requestedBy}
          </dd>
          <dt>Started</dt>
          <dd>{formatTime(r.startedAt)}</dd>
          <dt>Finished</dt>
          <dd>{formatTime(r.finishedAt)}</dd>
          <dt>Took</dt>
          <dd>{runTime(r)}</dd>
          <dt>Rows written</dt>
          <dd>{formatNumber(r.rowsWritten)}</dd>
        </dl>
        {r.error && (
          <p className={r.status === "failed" ? "pl-bad" : "muted"}>
            <strong>{r.status === "failed" ? "Why it failed: " : "Note: "}</strong>
            {r.error}
          </p>
        )}
      </section>

      <section aria-labelledby="steps-h" className="panel">
        <h2 id="steps-h">Steps</h2>
        {detail.steps.length === 0 ? (
          <p className="muted">{unfinished ? "The steps are recorded when the run ends." : "No steps were recorded."}</p>
        ) : (
          <div className="table-scroll" role="region" aria-label="Steps of the run" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Step</th>
                  <th scope="col">Rows in</th>
                  <th scope="col">Rows out</th>
                  <th scope="col">Peak memory</th>
                  {timed && <th scope="col">Time</th>}
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                {detail.steps.map((s) => (
                  <tr key={s.step}>
                    <th scope="row">
                      <span className="mono">{s.step}</span>
                      <div className="small muted">{s.type}</div>
                    </th>
                    <td>
                      {formatNumber(s.rowsIn)}
                      {Object.keys(s.ports?.in ?? {}).length > 1 && (
                        <div className="small muted">{portRows(s.ports?.in)}</div>
                      )}
                    </td>
                    <td>
                      {formatNumber(s.rowsOut)}
                      {Object.keys(s.ports?.out ?? {}).length > 0 && (
                        <div className="small muted">{portRows(s.ports?.out)}</div>
                      )}
                    </td>
                    <td>{formatBytes(s.peakMemory)}</td>
                    {timed && <td>{formatDuration(s.durationMs)}</td>}
                    <td className={s.error ? "pl-bad" : undefined}>{s.error ?? "–"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section aria-labelledby="written-h" className="panel">
        <h2 id="written-h">What it wrote</h2>
        {detail.written.length === 0 ? (
          <p className="muted">{unfinished ? "Known when the run ends." : "Nothing was written."}</p>
        ) : (
          <ul className="pl-written">
            {detail.written.map((w) => (
              <li key={w.step}>
                {w.registered ? (
                  <a href={href.asset(w.urn)} className="mono">
                    {w.target}
                  </a>
                ) : (
                  <span className="mono">{w.target}</span>
                )}{" "}
                in {w.source} ({w.mode}), by step <span className="mono">{w.step}</span>: {formatNumber(w.rows)} rows.
                {!w.registered && <span className="muted"> Not in the catalogue.</span>}
              </li>
            ))}
          </ul>
        )}
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
