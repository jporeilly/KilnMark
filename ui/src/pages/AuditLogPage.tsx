import { useCallback, useEffect, useState } from "react";
import { auditApi, formatTime, type AuditEvent } from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Card, DataTable, EmptyState, PageHeader, StatusChip, type Tone } from "../components/ui";

/** The audit log's actions, in words, for the filter and the table. */
export const ACTIONS: { value: string; label: string; tone: Tone }[] = [
  { value: "token.created", label: "Token created", tone: "neutral" },
  { value: "token.revoked", label: "Token revoked", tone: "neutral" },
  { value: "mark.set", label: "Column marked sensitive", tone: "neutral" },
  { value: "mark.cleared", label: "Sensitive mark cleared", tone: "warn" },
  { value: "source.deleted", label: "Source deleted", tone: "warn" },
  { value: "source.allow_writing", label: "Allow writing changed", tone: "warn" },
  { value: "source.allow_sql", label: "Allow SQL changed", tone: "warn" },
  { value: "api.request", label: "Request", tone: "neutral" },
  { value: "api.refused", label: "Request refused", tone: "bad" },
];

const label = (action: string) => ACTIONS.find((a) => a.value === action) ?? { value: action, label: action, tone: "neutral" as Tone };

/** What an event's detail says, in a few words: the toggles and the grant first, then the rest as key: value. */
export function describe(e: AuditEvent): string {
  const d = e.detail ?? {};
  if (e.action === "source.allow_writing" || e.action === "source.allow_sql") {
    return `${d.on ? "Turned on" : "Turned off"}${d.via ? ` (${String(d.via)})` : ""}`;
  }
  if (e.action === "token.created") {
    const parts = [String(d.role ?? "")];
    if (d.unmask) parts.push("sees unmasked values");
    parts.push(d.expiresAt ? `expires ${formatTime(String(d.expiresAt))}` : "never expires");
    return `${d.name ? `${String(d.name)}: ` : ""}${parts.filter(Boolean).join(", ")}`;
  }
  if (e.action === "api.request" || e.action === "api.refused") {
    const call = `${String(d.method ?? "")} ${String(d.status ?? "")}`.trim();
    const parts = [call];
    if (d.client) parts.push(`from ${String(d.client)}`);
    if (d.notKeptBefore) parts.push(`${String(d.notKeptBefore)} more not kept before this`);
    return parts.join(", ");
  }
  return Object.entries(d)
    .map(([k, v]) => `${k}: ${v == null ? "none" : String(v)}`)
    .join(", ");
}

/** Settings › Audit log, for admins: who did what, newest first, a page at a time. */
export function AuditLogPage() {
  useTitle("Audit log");
  const [action, setAction] = useState("");
  const [events, setEvents] = useState<AuditEvent[] | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(
    (before: number | null) => {
      setLoading(true);
      auditApi
        .list(action || null, before)
        .then((p) => {
          setEvents((old) => (before == null || old == null ? p.events : [...old, ...p.events]));
          setNext(p.next);
          setError(null);
        })
        .catch((e: Error) => setError(e.message))
        .finally(() => setLoading(false));
    },
    [action],
  );

  useEffect(() => {
    setEvents(null);
    load(null);
  }, [load]);

  return (
    <>
      <PageHeader
        title="Audit log"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Audit log" }]}
        lead="Who did what, newest first: every change made through the API, every request refused, and the changes that decide who sees sensitive values and what reaches a source. Events are never changed or deleted, and each one also goes to the kilnmark.audit log category, for your SIEM."
      />
      <div className="field km-field-narrow">
        <label htmlFor="audit-action">Show</label>
        <select id="audit-action" value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="">Every event</option>
          {ACTIONS.map((a) => (
            <option key={a.value} value={a.value}>
              {a.label}
            </option>
          ))}
        </select>
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      <Card title="Events" titleId="audit-h" className="km-section">
        {events === null && !error && (
          <p role="status" className="muted">
            Loading…
          </p>
        )}
        {events?.length === 0 && (
          <EmptyState title="None" compact>
            No events of this kind yet.
          </EmptyState>
        )}
        {events && events.length > 0 && (
          <DataTable label="Audit log">
            <thead>
              <tr>
                <th scope="col">When</th>
                <th scope="col">Who</th>
                <th scope="col">What</th>
                <th scope="col">On</th>
                <th scope="col">Details</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => {
                const a = label(e.action);
                return (
                  <tr key={e.id}>
                    <td className="small">{formatTime(e.at)}</td>
                    <th scope="row">
                      {e.actor}
                      {e.tokenId != null && <div className="small muted">token {e.tokenId}</div>}
                    </th>
                    <td>
                      <StatusChip tone={a.tone} dot>
                        {a.label}
                      </StatusChip>
                    </td>
                    <td className="small mono">{e.target}</td>
                    <td className="small">{describe(e)}</td>
                  </tr>
                );
              })}
            </tbody>
          </DataTable>
        )}
        {next != null && (
          <p>
            <button type="button" onClick={() => load(next)} disabled={loading}>
              {loading ? "Loading…" : "Show older events"}
            </button>
          </p>
        )}
      </Card>
    </>
  );
}
