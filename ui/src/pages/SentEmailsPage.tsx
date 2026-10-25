import { useEffect, useState } from "react";
import { formatTime, notifyApi, type MailLogEntry } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";

const STATUSES: { value: string; label: string }[] = [
  { value: "all", label: "Every attempt" },
  { value: "sent", label: "Sent" },
  { value: "retrying", label: "Failed, will try again" },
  { value: "failed", label: "Given up" },
  { value: "rate_limited", label: "Over the hourly limit, moved to the digest" },
];

const BADGE: Record<MailLogEntry["status"], string> = {
  sent: "badge badge-succeeded",
  retrying: "badge badge-queued",
  failed: "badge badge-failed",
  rate_limited: "badge badge-none",
};

const LABEL: Record<MailLogEntry["status"], string> = {
  sent: "Sent",
  retrying: "Will try again",
  failed: "Given up",
  rate_limited: "Moved to digest",
};

/** Settings › Sent emails, for admins: every attempt to send an email in the last 30 days. */
export function SentEmailsPage() {
  useTitle("Sent emails");
  const [status, setStatus] = useState("all");
  const [entries, setEntries] = useState<MailLogEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setEntries(null);
    notifyApi
      .log(status)
      .then((e) => {
        setEntries(e);
        setError(null);
      })
      .catch((e: Error) => setError(e.message));
  }, [status]);
  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>
            <a href={href.settings()}>Settings</a>
          </li>
          <li>
            <a href={href.email()}>Email</a>
          </li>
          <li aria-current="page">Sent emails</li>
        </ol>
      </nav>
      <h1 tabIndex={-1}>Sent emails</h1>
      <p className="lead">
        Every attempt to send an email, newest first, kept for 30 days. Only the subject is kept, never the email's text.
      </p>
      <div className="field">
        <label htmlFor="log-status">Show</label>
        <select id="log-status" value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => (
            <option key={s.value} value={s.value}>
              {s.label}
            </option>
          ))}
        </select>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <section className="panel" aria-labelledby="log-h">
        <h2 id="log-h">Attempts</h2>
        {entries === null && !error && <p role="status">Loading…</p>}
        {entries?.length === 0 && <p>None.</p>}
        {entries && entries.length > 0 && (
          <div className="table-scroll" role="region" aria-label="Sent emails (scrolls sideways on small screens)" tabIndex={0}>
            <table>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">To</th>
                  <th scope="col">What</th>
                  <th scope="col">Status</th>
                  <th scope="col">Error</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.id}>
                    <td className="small">{formatTime(e.at)}</td>
                    <th scope="row">{e.recipient}</th>
                    <td className="small">
                      {e.subject ?? e.kind}
                      <div className="muted">
                        {e.kind}
                        {e.eventIds.length > 1 ? `, ${e.eventIds.length} events` : ""}
                      </div>
                    </td>
                    <td>
                      <span className={BADGE[e.status]}>{LABEL[e.status]}</span>
                      {e.attempt > 1 && <div className="small muted">attempt {e.attempt}</div>}
                    </td>
                    <td className="small">{e.error ?? ""}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
