import { useCallback, useEffect, useState } from "react";
import { allows, formatNumber, formatTime, pipelinesApi, samplesApi, type AccessRole, type PipelineSummary } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { RunStatus } from "./RunStatus";
import { AddSamples } from "./AddSamples";
import "./pipelines.css";

/** Every pipeline: what it does, its latest version, its last run, and Run now for stewards. */
export function PipelinesPage({ role }: { role: AccessRole }) {
  useTitle("Pipelines");
  const steward = allows(role, "steward");
  const [pipelines, setPipelines] = useState<PipelineSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [starting, setStarting] = useState<string | null>(null);

  const load = useCallback(() => {
    pipelinesApi
      .list()
      .then((p) => {
        setPipelines(p);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  // Add samples only where this server offers them (an admin can turn them off).
  // Unknown until asked; if asking fails, the button shows and the server says no if they are off.
  const [samplesOffered, setSamplesOffered] = useState<boolean | null>(null);
  useEffect(() => {
    if (!steward) return;
    let live = true;
    samplesApi
      .offered()
      .then((o) => live && setSamplesOffered(o.offered))
      .catch(() => live && setSamplesOffered(true));
    return () => {
      live = false;
    };
  }, [steward]);

  // While a run is queued or running, look again every few seconds so its status stays current.
  const active = pipelines?.some((p) => p.lastRun && ["queued", "running"].includes(p.lastRun.status));
  useEffect(() => {
    if (!active) return;
    const t = window.setInterval(load, 4000);
    return () => window.clearInterval(t);
  }, [active, load]);

  const runNow = async (p: PipelineSummary) => {
    setStarting(p.name);
    setError(null);
    try {
      const run = await pipelinesApi.run(p.name);
      setNotice(`Run ${run.id} of ${p.displayName} is queued.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setStarting(null);
    }
  };

  return (
    <>
      <div className="pl-head">
        <h1 tabIndex={-1}>Pipelines</h1>
        {steward && (
          <div className="pl-head-actions">
            {samplesOffered === true && <AddSamples onAdded={load} />}
            <a className="pl-link-button" href={href.newPipeline()}>
              New pipeline
            </a>
          </div>
        )}
      </div>
      <p className="lead">
        Pipelines read from saved sources, change the rows and write them somewhere, measuring the data on the way.
        Each is saved as YAML with every version. <a href={docHref("pipelines")}>How to build and run one</a>.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="pipelines-h" className="panel">
        <h2 id="pipelines-h">All pipelines</h2>
        {pipelines === null && !error && <p role="status">Loading…</p>}
        {pipelines?.length === 0 && (
          <p>
            No pipelines yet.{" "}
            {steward ? (
              <>
                Choose <strong>New pipeline</strong> to start from a template that uses your sources
                {samplesOffered === true ? (
                  <>
                    , or <strong>Add samples</strong> for small pipelines that show one idea each.
                  </>
                ) : (
                  "."
                )}
              </>
            ) : (
              "Stewards add them."
            )}
          </p>
        )}
        {pipelines && pipelines.length > 0 && (
          <div
            className="table-scroll"
            role="region"
            aria-label="Pipelines table (scrolls sideways on small screens)"
            tabIndex={0}
          >
            <table>
              <thead>
                <tr>
                  <th scope="col">Pipeline</th>
                  <th scope="col">Latest version</th>
                  <th scope="col">Last run</th>
                  {steward && (
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {pipelines.map((p) => (
                  <tr key={p.name}>
                    <th scope="row">
                      <a href={href.pipeline(p.name)}>{p.displayName}</a>
                      {p.displayName !== p.name && <div className="small muted mono">{p.name}</div>}
                      {p.description && <div className="small muted">{p.description}</div>}
                    </th>
                    <td>
                      Version {p.latestVersion}
                      {!p.valid && <div className="small pl-bad">Has problems: cannot run</div>}
                      <div className="small muted">
                        Saved {formatTime(p.updatedAt)} by {p.updatedBy}
                      </div>
                    </td>
                    <td>
                      <RunStatus run={p.lastRun} />
                      {p.lastRun && (
                        <div className="small muted">
                          <a href={href.pipelineRun(p.lastRun.id)}>Run {p.lastRun.id}</a>,{" "}
                          {formatTime(p.lastRun.finishedAt ?? p.lastRun.startedAt ?? p.lastRun.requestedAt)}
                          {p.lastRun.rowsWritten != null && <>, {formatNumber(p.lastRun.rowsWritten)} rows written</>}
                        </div>
                      )}
                    </td>
                    {steward && (
                      <td>
                        <button
                          type="button"
                          onClick={() => runNow(p)}
                          disabled={!p.valid || starting === p.name}
                          aria-label={`Run ${p.displayName} now`}
                          title={p.valid ? undefined : "Fix its problems first"}
                        >
                          {starting === p.name ? "Queueing…" : "Run now"}
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {!steward && <p className="hint">Your role ({role}) can read pipelines and their runs. Stewards edit and run them.</p>}
    </>
  );
}
