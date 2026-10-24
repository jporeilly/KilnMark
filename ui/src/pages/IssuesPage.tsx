import { useCallback, useEffect, useState } from "react";
import { allows, formatTime, qualityApi, type AccessRole, type Issue } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";

const SEVERITY: Record<Issue["severity"], string> = { high: "High", medium: "Medium", low: "Low" };
const STATUS: Record<Issue["status"], string> = { open: "Open", resolved: "Resolved", dismissed: "Dismissed" };

/** The stewards' task list: failing rules and significant drift, most severe first. */
export function IssuesPage({ role }: { role: AccessRole }) {
  useTitle("Issues");
  const steward = allows(role, "steward");
  const [status, setStatus] = useState("open");
  const [issues, setIssues] = useState<Issue[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");

  const load = useCallback(() => {
    qualityApi
      .issues(status)
      .then((i) => {
        setIssues(i);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [status]);

  useEffect(load, [load]);

  const set = async (issue: Issue, to: Issue["status"]) => {
    try {
      await qualityApi.setIssue(issue.id, to);
      setNotice(`"${issue.title}" marked ${STATUS[to].toLowerCase()}.`);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <>
      <h1 tabIndex={-1}>Issues</h1>
      <p className="lead">
        Rules that fail or warn (when the rule asks for a task) and significant drift between scans. A rule's task
        closes itself when the rule passes again.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section aria-labelledby="issues-h" className="panel">
        <h2 id="issues-h">{STATUS[status as Issue["status"]] ?? "All"} issues</h2>
        <div className="field inline-field">
          <label htmlFor="issue-status">Show</label>
          <select id="issue-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="open">Open</option>
            <option value="resolved">Resolved</option>
            <option value="dismissed">Dismissed</option>
            <option value="all">All</option>
          </select>
        </div>
        {issues === null && !error && <p>Loading…</p>}
        {issues?.length === 0 && <p>Nothing here. {status === "open" ? "No open issues." : ""}</p>}
        {issues && issues.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Issues table (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">Severity</th>
                  <th scope="col">Issue</th>
                  <th scope="col">Asset</th>
                  <th scope="col">Status</th>
                  {steward && (
                    <th scope="col">
                      <span className="visually-hidden">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {issues.map((i) => (
                  <tr key={i.id}>
                    <td>
                      <span className={`badge badge-sev-${i.severity}`}>{SEVERITY[i.severity]}</span>
                    </td>
                    <th scope="row">
                      {i.title}
                      {i.detail && <div className="small muted">{i.detail}</div>}
                      <div className="small muted">Opened {formatTime(i.createdAt)}</div>
                    </th>
                    <td className="small">
                      <a href={href.asset(i.assetUrn)} className="mono">{i.assetUrn}</a>
                      {i.field && <div className="mono">{i.field}</div>}
                    </td>
                    <td>
                      {STATUS[i.status]}
                      {i.closedBy && <div className="small muted">by {i.closedBy}</div>}
                    </td>
                    {steward && (
                      <td>
                        <div className="buttons">
                          {i.status === "open" ? (
                            <>
                              <button type="button" onClick={() => set(i, "resolved")} aria-label={`Resolve: ${i.title}`}>
                                Resolve
                              </button>
                              <button type="button" onClick={() => set(i, "dismissed")} aria-label={`Dismiss: ${i.title}`}>
                                Dismiss
                              </button>
                            </>
                          ) : (
                            <button type="button" onClick={() => set(i, "open")} aria-label={`Reopen: ${i.title}`}>
                              Reopen
                            </button>
                          )}
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
      {!steward && <p className="hint">Your role ({role}) can read issues. Stewards resolve and dismiss them.</p>}
    </>
  );
}
