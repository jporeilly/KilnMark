import { useCallback, useEffect, useState } from "react";
import { auditApi, formatTime, type AuditEvent, plural } from "../api";
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
  { value: "source.max_runs", label: "Most runs at once changed", tone: "neutral" },
  { value: "flow.sql", label: "Flow ran SQL", tone: "warn" },
  { value: "auth.sign_in", label: "Signed in", tone: "neutral" },
  { value: "auth.sign_in_refused", label: "Sign-in given no role", tone: "bad" },
  { value: "auth.sign_out", label: "Signed out", tone: "neutral" },
  { value: "auth.person_added", label: "Person added", tone: "warn" },
  { value: "auth.people_imported", label: "People imported", tone: "warn" },
  { value: "auth.person_activated", label: "Person activated", tone: "warn" },
  { value: "auth.person_disabled", label: "Person disabled", tone: "neutral" },
  { value: "server.start_refused", label: "Start refused", tone: "bad" },
  { value: "server.install_id_made", label: "Install id made", tone: "neutral" },
  { value: "server.mode_recorded", label: "Mode recorded", tone: "neutral" },
  { value: "auth.realm_stamped", label: "Keycloak realm stamped", tone: "neutral" },
  { value: "access.review", label: "Access review viewed", tone: "neutral" },
  { value: "unmask.requested", label: "Sensitive values asked for", tone: "warn" },
  { value: "unmask.approved", label: "Sensitive values approved", tone: "warn" },
  { value: "unmask.refused", label: "Sensitive values refused", tone: "neutral" },
  { value: "database.restored", label: "Database restored", tone: "warn" },
  { value: "schedule.set", label: "Schedule set", tone: "neutral" },
  { value: "schedule.deleted", label: "Schedule deleted", tone: "neutral" },
  { value: "schedule.paused", label: "Schedule paused", tone: "warn" },
  { value: "starter.added", label: "Starter Pack turned on", tone: "warn" },
  { value: "starter.removed", label: "Starter Pack turned off", tone: "neutral" },
  { value: "starter.people", label: "Starter Pack people changed", tone: "warn" },
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
  if (e.action === "source.max_runs") {
    const limit = (v: unknown) => (v == null ? "no limit" : String(v));
    return `From ${limit(d.before)} to ${limit(d.after)}${d.via ? ` (${String(d.via)})` : ""}`;
  }
  if (e.action === "flow.sql") {
    const n = Number(d.statements ?? 0);
    return `${plural(n, "statement")} on ${String(d.source ?? "")}, version ${String(d.version ?? "")}`;
  }
  if (e.action === "token.created") {
    const parts = [String(d.role ?? "")];
    if (d.unmask) parts.push("sees unmasked values");
    parts.push(d.expiresAt ? `expires ${formatTime(String(d.expiresAt))}` : "never expires");
    return `${d.name ? `${String(d.name)}: ` : ""}${parts.filter(Boolean).join(", ")}`;
  }
  if (e.action === "auth.sign_in") {
    return `${String(d.role ?? "")}${d.unmask ? ", sees unmasked values" : ""}`;
  }
  if (e.action === "auth.sign_in_refused") {
    return String(d.reason ?? "");
  }
  if (e.action === "database.restored") {
    return `From ${String(d.backup ?? "a backup")}, taken ${d.takenAt ? formatTime(String(d.takenAt)) : "at an unknown time"}`;
  }
  if (e.action === "schedule.paused") {
    const what = d.pipeline ? `Pipeline ${String(d.pipeline)}` : `Flow ${String(d.flow ?? "")}`;
    return `${what}: paused, ${String(d.reason ?? "its setter no longer has the role")}`;
  }
  if (e.action === "schedule.set" || e.action === "schedule.deleted") {
    const what = d.pipeline ? `Pipeline ${String(d.pipeline)}` : `Flow ${String(d.flow ?? "")}`;
    return `${what}: ${String(d.cron ?? "")} (${String(d.zone ?? "UTC")}), as ${String(d.runAs ?? "steward")}${d.enabled === false ? ", paused" : ""}`;
  }
  if (e.action === "starter.added" && d.refused) {
    return `Refused, nothing changed: ${String(d.refused)}`;
  }
  if (e.action === "auth.person_added") {
    const groups = Array.isArray(d.groups) ? (d.groups as unknown[]).map(String).join(", ") : "";
    const via = d.via === "import" ? "from a file" : "one at a time";
    return `Added ${String(d.person ?? "")} (${groups}) ${via}, with a password they change at their first sign-in`;
  }
  if (e.action === "auth.person_activated") {
    return `Gave ${String(d.person ?? "")} a temporary password and enabled them`;
  }
  if (e.action === "auth.person_disabled") {
    return `Disabled ${String(d.person ?? "")}: their temporary password went unused`;
  }
  if (e.action === "server.mode_recorded") {
    return `Recorded at the first start: ${String(d.mode ?? "")}`;
  }
  if (e.action === "auth.realm_stamped") {
    return "The bundled Keycloak's realm was told which install it belongs to";
  }
  if (e.action === "server.install_id_made") {
    return "This install was given an id of its own: backups taken before do not name it, so take a new one";
  }
  if (e.action === "server.start_refused") {
    return String(d.reason ?? "");
  }
  if (e.action === "auth.people_imported") {
    return `Made ${String(d.made ?? 0)}, already there ${String(d.alreadyThere ?? 0)}, refused ${String(d.refused ?? 0)}`;
  }
  if (e.action === "starter.people") {
    const verb: Record<string, string> = {
      add: "Added",
      "new-password": "Gave a new password to",
      remove: "Removed",
    };
    const people = Array.isArray(d.people) ? (d.people as unknown[]).map(String).join(", ") : "";
    return `${verb[String(d.action)] ?? String(d.action ?? "")}: ${people}`;
  }
  if (e.action === "starter.added" || e.action === "starter.removed") {
    const steps = (d.steps ?? {}) as Record<string, string>;
    const failed = Object.entries(steps).filter(([, v]) => v === "failed").map(([k]) => k);
    return failed.length ? `Not every step worked: ${failed.join(", ")}` : "Every step worked";
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
