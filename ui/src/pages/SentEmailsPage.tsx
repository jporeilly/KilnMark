import { useEffect, useState } from "react";
import { formatTime, notifyApi, type MailLogEntry } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, DataTable, EmptyState, PageHeader, StatusChip, type Tone } from "../components/ui";

const STATUSES: { value: string; label: string }[] = [
  { value: "all", label: "Every attempt" },
  { value: "sent", label: "Sent" },
  { value: "retrying", label: "Failed, will try again" },
  { value: "failed", label: "Given up" },
  { value: "rate_limited", label: "Over the hourly limit, moved to the digest" },
];

const TONE: Record<MailLogEntry["status"], Tone> = { sent: "good", retrying: "warn", failed: "bad", rate_limited: "neutral" };

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
      <PageHeader
        title="Sent emails"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Email", href: href.email() }, { label: "Sent emails" }]}
        lead="Every attempt to send an email, newest first, kept for 30 days. Only the subject is kept, never the email's text."
      />
      <div className="field km-field-narrow">
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
      <Card title="Attempts" titleId="log-h" className="km-section">
        {entries === null && !error && <p role="status" className="muted">Loading…</p>}
        {entries?.length === 0 && <EmptyState title="None" compact>No emails of this kind in the last 30 days.</EmptyState>}
        {entries && entries.length > 0 && (
          <DataTable label="Sent emails">
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
                      <StatusChip tone={TONE[e.status]} dot>{LABEL[e.status]}</StatusChip>
                      {e.attempt > 1 && <div className="small muted">attempt {e.attempt}</div>}
                    </td>
                    <td className="small">{e.error ?? ""}</td>
                  </tr>
                ))}
              </tbody>
          </DataTable>
        )}
      </Card>
    </>
  );
}
