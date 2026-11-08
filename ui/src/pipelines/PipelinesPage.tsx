import { useCallback, useEffect, useState } from "react";
import { allows, formatTime, pipelinesApi, type AccessRole, type PipelineSummary, plural } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { RunStatus } from "./RunStatus";
import "./pipelines.css";
import { Who } from "../components/Who";
import { lastRunStatus, noticeText, type Notice } from "../components/runNotice";

/**
 * A team's own pipelines: what each does, its latest version, its last run; Run now for stewards,
 * New pipeline for designers. Samples added from the Library are listed there, not here (ADR 0043).
 */
export function PipelinesPage({ role }: { role: AccessRole }) {
  useTitle("Pipelines");
  const steward = allows(role, "steward");
  const designer = allows(role, "designer");
  const [all, setAll] = useState<PipelineSummary[] | null>(null);
  const pipelines = all?.filter((p) => !p.library) ?? null;
  const samples = all?.filter((p) => p.library).length ?? 0;
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>("");
  const [starting, setStarting] = useState<string | null>(null);

  const load = useCallback(() => {
    pipelinesApi
      .list()
      .then((p) => {
        setAll(p);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

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
      setNotice({ run: run.id, of: p.displayName });
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
        {designer && (
          <div className="pl-head-actions">
            <a className="km-button" href={href.library("templates")}>
              From a template
            </a>
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
        {noticeText(notice, typeof notice === "string" ? null : lastRunStatus(all, notice.run))}
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
            {designer ? (
              <>
                Choose <strong>New pipeline</strong> to start from your sources, or{" "}
                <strong>From a template</strong> for a common job, such as copying a table or loading a file.
              </>
            ) : (
              "Designers add them."
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
                        Saved {formatTime(p.updatedAt)} by <Who name={p.updatedBy} />
                      </div>
                    </td>
                    <td>
                      <RunStatus run={p.lastRun} />
                      {p.lastRun && (
                        <div className="small muted">
                          <a href={href.pipelineRun(p.lastRun.id)}>Run {p.lastRun.id}</a>,{" "}
                          {formatTime(p.lastRun.finishedAt ?? p.lastRun.startedAt ?? p.lastRun.requestedAt)}
                          {p.lastRun.rowsWritten != null && <>, {plural(p.lastRun.rowsWritten, "row")} written</>}
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
      <p className="hint">
        {samples > 0
          ? `${samples} sample ${samples === 1 ? "pipeline is" : "pipelines are"} in the `
          : "Samples that show one idea each, and templates to start from, are in the "}
        <a href={href.library()}>Library</a>, apart from your own.
      </p>
      {!designer && (
        <p className="hint">
          {steward
            ? `Your role (${role}) can read and run pipelines. Designers build and change them.`
            : `Your role (${role}) can read pipelines and their runs. Stewards run them; designers build them.`}
        </p>
      )}
    </>
  );
}
