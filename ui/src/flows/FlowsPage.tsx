import { useCallback, useEffect, useState } from "react";
import { allows, flowsApi, formatTime, type AccessRole, type FlowSummary } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { docHref } from "../docs/links";
import { FlowRunStatus, RunFlowButton } from "./RunFlow";
import "../pipelines/pipelines.css";
import { Who } from "../components/Who";
import { lastRunStatus, noticeText, type Notice } from "../components/runNotice";

/**
 * A team's own flows: what each does, its latest version, its last run; Run now for stewards, New
 * flow for designers. Samples added from the Library are listed there, not here (ADR 0043).
 */
export function FlowsPage({ role }: { role: AccessRole }) {
  useTitle("Flows");
  const steward = allows(role, "steward");
  const designer = allows(role, "designer");
  const [all, setAll] = useState<FlowSummary[] | null>(null);
  const flows = all?.filter((f) => !f.library) ?? null;
  const samples = all?.filter((f) => f.library).length ?? 0;
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<Notice>("");

  const load = useCallback(() => {
    flowsApi
      .list()
      .then((f) => {
        setAll(f);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, []);

  useEffect(load, [load]);

  // While a run is queued or running, look again every few seconds so its status stays current.
  const active = flows?.some((f) => f.lastRun && ["queued", "running"].includes(f.lastRun.status));
  useEffect(() => {
    if (!active) return;
    const t = window.setInterval(load, 4000);
    return () => window.clearInterval(t);
  }, [active, load]);

  return (
    <>
      <div className="pl-head">
        <h1 tabIndex={-1}>Flows</h1>
        {designer && (
          <div className="pl-head-actions">
            <a className="km-button" href={href.library("templates")}>
              From a template
            </a>
            <a className="pl-link-button" href={href.newFlow()}>
              New flow
            </a>
          </div>
        )}
      </div>
      <p className="lead">
        Flows run pipelines in an order, and decide what runs next from how each ended and what it measured. Each is
        saved as YAML with every version. <a href={docHref("flows")}>How to build and run one</a>.
      </p>
      <p role="status" className="notice">
        {noticeText(notice, typeof notice === "string" ? null : lastRunStatus(all, notice.run))}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="flows-h" className="panel">
        <h2 id="flows-h">All flows</h2>
        {flows === null && !error && <p role="status">Loading…</p>}
        {flows?.length === 0 && (
          <p>
            No flows yet.{" "}
            {designer ? (
              <>
                Choose <strong>New flow</strong> to start from one of your pipelines, or{" "}
                <strong>From a template</strong> to run pipelines one after another.
              </>
            ) : (
              "Designers add them."
            )}
          </p>
        )}
        {flows && flows.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Flows table (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Flow</th>
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
                {flows.map((f) => (
                  <tr key={f.name}>
                    <th scope="row">
                      <a href={href.flow(f.name)}>{f.displayName}</a>
                      {f.displayName !== f.name && <div className="small muted mono">{f.name}</div>}
                      {f.description && <div className="small muted">{f.description}</div>}
                    </th>
                    <td>
                      Version {f.latestVersion}
                      {!f.valid && <div className="small pl-bad">Has problems: cannot run</div>}
                      <div className="small muted">
                        Saved {formatTime(f.updatedAt)} by <Who name={f.updatedBy} />
                      </div>
                    </td>
                    <td>
                      <FlowRunStatus run={f.lastRun} />
                      {f.lastRun && (
                        <div className="small muted">
                          <a href={href.flowRun(f.lastRun.id)}>Run {f.lastRun.id}</a>,{" "}
                          {formatTime(f.lastRun.finishedAt ?? f.lastRun.startedAt ?? f.lastRun.requestedAt)}
                        </div>
                      )}
                    </td>
                    {steward && (
                      <td>
                        <RunFlowButton
                          name={f.name}
                          label={`Run ${f.displayName} now`}
                          disabled={!f.valid}
                          title={f.valid ? undefined : "Fix its problems first"}
                          onQueued={(run) => {
                            setError(null);
                            setNotice({ run: run.id, of: f.displayName });
                            load();
                          }}
                          onError={setError}
                        />
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
          ? `${samples} sample ${samples === 1 ? "flow is" : "flows are"} in the `
          : "Sample flows, and templates to start from, are in the "}
        <a href={href.library()}>Library</a>, apart from your own.
      </p>
      {!designer && (
        <p className="hint">
          {steward
            ? `Your role (${role}) can read and run flows. Designers build and change them.`
            : `Your role (${role}) can read flows and their runs. Stewards run them; designers build them.`}
        </p>
      )}
    </>
  );
}
