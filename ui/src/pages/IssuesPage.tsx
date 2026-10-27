import { useCallback, useEffect, useState } from "react";
import { allows, formatTime, qualityApi, type AccessRole, type Issue } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Icon } from "../components/icons";
import { formatAgo } from "../format";
import { Card, DataTable, EmptyState, FilterChips, PageHeader, SeverityChip, StatusChip, type Tone } from "../components/ui";

const STATUS: Record<Issue["status"], string> = { open: "Open", resolved: "Resolved", dismissed: "Dismissed" };
const STATUS_TONE: Record<Issue["status"], Tone> = { open: "orange", resolved: "good", dismissed: "neutral" };

/** "crm.customers" and its source from an asset URN, for people to read. */
const assetName = (urn: string) => {
  const m = /^urn:kilnmark:([^:]+):(.*)$/.exec(urn);
  return m ? { source: m[1], path: m[2] } : { source: "", path: urn };
};

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
      <PageHeader
        title="Issues"
        meta={issues ? `${issues.length} ${STATUS[status as Issue["status"]]?.toLowerCase() ?? "in all"}` : undefined}
        lead="Rules that fail or warn (when the rule asks for a task) and significant drift between scans. A rule's task closes itself when the rule passes again."
      />
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <Card
        title={`${STATUS[status as Issue["status"]] ?? "All"} issues`}
        titleId="issues-h"
        actions={
          <FilterChips
            label="Show issues"
            selected={status}
            onSelect={setStatus}
            options={[
              { id: "open", label: "Open" },
              { id: "resolved", label: "Resolved" },
              { id: "dismissed", label: "Dismissed" },
              { id: "all", label: "All" },
            ]}
          />
        }
      >
        {issues === null && !error && <p role="status" className="muted">Loading…</p>}
        {issues?.length === 0 && (
          <EmptyState compact icon={<Icon name="circle-check" size={22} />} title={status === "open" ? "No open issues" : "Nothing here"}>
            Failing rules and drift between scans show here.
          </EmptyState>
        )}
        {issues && issues.length > 0 && (
          <DataTable label="Issues table (scrolls sideways on small screens)">
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
              {issues.map((i) => {
                const a = assetName(i.assetUrn);
                return (
                  <tr key={i.id}>
                    <td>
                      <SeverityChip severity={i.severity} />
                    </td>
                    <th scope="row">
                      {i.title}
                      {i.detail && <div className="small muted">{i.detail}</div>}
                      <div className="small muted">
                        {i.driftEventId != null ? "Drift · " : i.ruleId != null ? "Rule · " : ""}
                        Opened <time dateTime={i.createdAt} title={formatTime(i.createdAt)}>{formatAgo(i.createdAt)}</time>
                      </div>
                    </th>
                    <td className="small">
                      <a href={href.asset(i.assetUrn, "quality")} className="km-strong-link" title={i.assetUrn}>
                        {a.path}
                      </a>
                      <div className="muted">{a.source}</div>
                      {i.field && <span className="km-code">{i.field}</span>}
                    </td>
                    <td>
                      <StatusChip tone={STATUS_TONE[i.status]}>{STATUS[i.status]}</StatusChip>
                      {i.closedBy && <div className="small muted">by {i.closedBy}</div>}
                    </td>
                    {steward && (
                      <td>
                        <div className="buttons tight">
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
                );
              })}
            </tbody>
          </DataTable>
        )}
      </Card>
      {!steward && <p className="hint">Your role ({role}) can read issues. Stewards resolve and dismiss them.</p>}
    </>
  );
}
