import { useCallback, useEffect, useState, type FormEvent, type KeyboardEvent } from "react";
import {
  api,
  formatTime,
  notifyApi,
  type KindChoice,
  type MailSecurity,
  type MailSettingsView,
  type TeamAddress,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { Button, Card, DataTable, PageHeader, StatusChip, Switch } from "../components/ui";
import { EmailChoiceFields, Locked, severityPhrase, type EmailChoices } from "../components/NotificationChoices";
import { useFocusOn } from "./NotificationsPage";

const SECURITY: { value: MailSecurity; label: string }[] = [
  { value: "starttls", label: "STARTTLS (usually port 587)" },
  { value: "tls", label: "TLS from the start (usually port 465)" },
  { value: "none", label: "None (usually port 25; only for a relay on a trusted network)" },
];

/** Settings › Email, for admins: the outgoing mail server, its rules, a test email, and team addresses. */
export function EmailSettingsPage() {
  useTitle("Email");
  const [kinds, setKinds] = useState<KindChoice[]>([]);
  const [timeZone, setTimeZone] = useState("");
  const [sources, setSources] = useState<{ name: string; displayName: string }[]>([]);
  useEffect(() => {
    notifyApi
      .mine()
      .then((m) => {
        setKinds(m.kindChoices);
        setTimeZone(m.timeZone);
      })
      .catch(() => setKinds([]));
    api
      .sources()
      .then((s) => setSources(s.map((x) => ({ name: x.name, displayName: x.displayName }))))
      .catch(() => setSources([]));
  }, []);
  return (
    <>
      <PageHeader
        title="Email"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Email" }]}
        lead={
          <>
            The mail server Kilnmark sends notifications through, the rules for email, and team addresses for
            distribution lists. Each person chooses their own emails under{" "}
            <a href={href.notifications()}>Settings, Notifications</a>. See every email sent or tried under{" "}
            <a href={href.emailLog()}>Sent emails</a>.
          </>
        }
      />
      <MailServer />
      {kinds.length > 0 && <Teams kinds={kinds} timeZone={timeZone} sources={sources} />}
    </>
  );
}

type Form = {
  enabled: boolean;
  host: string;
  port: string;
  security: MailSecurity;
  username: string;
  password: string;
  removePassword: boolean;
  fromAddress: string;
  fromName: string;
  baseUrl: string;
  allowPlainAuth: boolean;
  allowedDomains: string[];
  requireConfirmation: boolean;
  maxAttempts: string;
  rateLimitPerHour: string;
  undeliveredHours: string;
  undeliveredAction: "digest" | "drop";
};

const toForm = (v: MailSettingsView): Form => ({
  enabled: v.enabled,
  host: v.host ?? "",
  port: v.port == null ? "" : String(v.port),
  security: v.security,
  username: v.username ?? "",
  password: "",
  removePassword: false,
  fromAddress: v.fromAddress ?? "",
  fromName: v.fromName ?? "",
  baseUrl: v.baseUrl ?? (v.saved ? "" : window.location.origin),
  allowPlainAuth: v.allowPlainAuth,
  allowedDomains: v.allowedDomains,
  requireConfirmation: v.requireConfirmation,
  maxAttempts: String(v.maxAttempts),
  rateLimitPerHour: String(v.rateLimitPerHour),
  undeliveredHours: String(v.undeliveredHours),
  undeliveredAction: v.undeliveredAction,
});

function MailServer() {
  const [view, setView] = useState<MailSettingsView | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ text: string } | null>(null);
  const errorRef = useFocusOn(error);

  const apply = (v: MailSettingsView) => {
    setView(v);
    setForm(toForm(v));
  };
  useEffect(() => {
    notifyApi
      .settings()
      .then(apply)
      .catch((e: Error) => setError({ text: e.message }));
  }, []);

  if (!form || !view) {
    return (
      <Card title="Mail server" titleId="server-h" className="km-section">
        {error ? (
          <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
            {error.text}
          </p>
        ) : (
          <p role="status" className="muted">Loading…</p>
        )}
      </Card>
    );
  }

  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch });
  const locked = (id: string) => view.locked.includes(id);
  const lim = view.limits;
  const port = form.port.trim() ? Number(form.port) : null;
  const destinationChanged =
    form.host.trim().toLowerCase() !== (view.host ?? "") ||
    (port ?? null) !== (view.port ?? null) ||
    form.security !== view.security ||
    (form.username.trim() || null) !== (view.username ?? null);
  const needsPassword = view.hasPassword && destinationChanged && !!form.username.trim() && !form.password;
  const plain = form.security === "none" && !!form.username.trim();

  const number = (label: string, text: string, min: number, max: number): number | string => {
    const n = Number(text);
    return Number.isInteger(n) && n >= min && n <= max ? n : `${label}: a whole number from ${min} to ${max}.`;
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (saving) return;
    setNotice("");
    if (port !== null && !(Number.isInteger(port) && port >= 1 && port <= 65535)) {
      return setError({ text: "Port: a whole number from 1 to 65535, or empty for the usual one." });
    }
    const attempts = number("Tries before giving up", form.maxAttempts, lim.minAttempts, lim.maxAttempts);
    const rate = number("Emails per address per hour", form.rateLimitPerHour, lim.minRatePerHour, lim.maxRatePerHour);
    const hours = number("Undelivered emails wait", form.undeliveredHours, lim.minUndeliveredHours, lim.maxUndeliveredHours);
    for (const v of [attempts, rate, hours]) if (typeof v === "string") return setError({ text: v });
    if (needsPassword) {
      return setError({
        text: "Password: the server, port, security or user name changed, so enter the password again (or remove it).",
      });
    }
    setSaving(true);
    setError(null);
    try {
      const v = await notifyApi.saveSettings({
        enabled: form.enabled,
        host: form.host,
        port,
        security: form.security,
        username: form.username,
        ...(form.removePassword ? { password: "" } : form.password ? { password: form.password } : {}),
        fromAddress: form.fromAddress,
        fromName: form.fromName,
        baseUrl: form.baseUrl,
        allowPlainAuth: form.allowPlainAuth,
        allowedDomains: form.allowedDomains,
        requireConfirmation: form.requireConfirmation,
        maxAttempts: attempts as number,
        rateLimitPerHour: rate as number,
        undeliveredHours: hours as number,
        undeliveredAction: form.undeliveredAction,
      });
      apply(v);
      setNotice(
        v.pausedAddresses.length > 0
          ? `Mail settings saved. ${v.pausedAddresses.length} addresses are outside the allowed domains and are paused.`
          : "Mail settings saved.",
      );
    } catch (x) {
      setError({ text: (x as Error).message });
    } finally {
      setSaving(false);
    }
  };

  const text = (id: string, key: keyof Form, label: string, extra: Record<string, unknown> = {}, hint?: string) => (
    <div className="field">
      <label htmlFor={`mail-${id}`}>{label}</label>
      <input
        id={`mail-${id}`}
        value={form[key] as string}
        onChange={(e) => set({ [key]: e.target.value } as Partial<Form>)}
        disabled={locked(id)}
        aria-describedby={[hint ? `mail-${id}-hint` : "", locked(id) ? `mail-${id}-locked` : ""].join(" ").trim() || undefined}
        {...extra}
      />
      {locked(id) && <Locked id={`mail-${id}-locked`} />}
      {hint && (
        <p id={`mail-${id}-hint`} className="hint">
          {hint}
        </p>
      )}
    </div>
  );

  return (
    <Card title="Mail server" titleId="server-h" className="km-section">
      {!view.saved && (
        <p className="hint">
          Not saved yet: what is shown comes from the server's configuration (kilnmark.mail), if any. Saving keeps these
          settings in the database, and they win from then on, except those set by the server's configuration.
        </p>
      )}
      {view.saved && view.updatedAt && (
        <p className="hint">
          Last saved {formatTime(view.updatedAt)} by {view.updatedBy}.
        </p>
      )}
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error.text}
        </p>
      )}
      {view.pausedAddresses.length > 0 && (
        <div className="warning" role="region" aria-labelledby="paused-h">
          <h3 id="paused-h">Paused: outside the allowed domains</h3>
          <p>These addresses get no email until the allowed domains include them, or their owners change them:</p>
          <ul>
            {view.pausedAddresses.map((p) => (
              <li key={`${p.kind}-${p.email}-${p.name}`}>
                {p.email} ({p.kind === "team" ? `team address ${p.name}` : `${p.name ?? "a person"}'s own`})
              </li>
            ))}
          </ul>
        </div>
      )}
      <form onSubmit={save} className="form">
        <Switch
          id="mail-enabled"
          label="Send emails"
          checked={form.enabled}
          onChange={(enabled) => set({ enabled })}
          help="Off pauses every email: they wait, and go once it is back on (or as the undelivered rule below says). In-app notifications go on regardless."
        />
        <div className="field-row">
          {text("host", "host", "SMTP server", { autoComplete: "off", placeholder: "smtp.example.com", required: form.enabled })}
          {text("port", "port", "Port", { inputMode: "numeric" }, "Empty for the usual port for the security chosen.")}
        </div>
        <div className="field">
          <label htmlFor="mail-security">Security</label>
          <select id="mail-security" value={form.security} disabled={locked("security")}
            onChange={(e) => set({ security: e.target.value as MailSecurity })}
            aria-describedby={locked("security") ? "mail-security-locked" : undefined}>
            {SECURITY.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
          {locked("security") && <Locked id="mail-security-locked" />}
        </div>
        <div className="field-row">
          {text("username", "username", "User name", { autoComplete: "off" }, "Empty to send without signing in.")}
          <div className="field">
            <label htmlFor="mail-password">Password</label>
            <input id="mail-password" type="password" value={form.password} autoComplete="new-password"
              onChange={(e) => set({ password: e.target.value, removePassword: false })}
              aria-describedby="mail-password-hint" aria-invalid={needsPassword || undefined}
              disabled={form.removePassword} />
            <p id="mail-password-hint" className="hint">
              {view.hasPassword
                ? "A password is kept. Leave this empty to keep it; it is never shown again. Changing the server, port, security or user name clears it: enter it again then."
                : "Kept encrypted, and never shown again."}
            </p>
            {needsPassword && (
              <p className="warning">
                The server, port, security or user name changed: enter the password again. A password is only sent
                where it was entered for.
              </p>
            )}
            {view.hasPassword && (
              <label className="choice">
                <input type="checkbox" checked={form.removePassword}
                  onChange={(e) => set({ removePassword: e.target.checked, password: "" })} />
                <span>Remove the stored password</span>
              </label>
            )}
          </div>
        </div>
        <Switch
          id="mail-plain"
          label="Allow sign-in without encryption"
          checked={form.allowPlainAuth}
          disabled={locked("allow-plain-auth")}
          defaultNote={locked("allow-plain-auth") ? "Set by the server's configuration" : undefined}
          onChange={(allowPlainAuth) => set({ allowPlainAuth })}
          help="With security None, the user name and password are then sent unencrypted: anyone on the network between Kilnmark and the mail server can read them. Only for a relay on a trusted network, and only for a password entered here; a password from the server's configuration is never sent unencrypted. Turning this on is written to the server log."
        />
        {plain && !form.allowPlainAuth && (
          <p className="warning">
            Security None with a user name would send the password unencrypted, so saving is refused. Choose STARTTLS or
            TLS, leave the user name empty, or allow sign-in without encryption.
          </p>
        )}
        <div className="field-row">
          {text("from", "fromAddress", "From address", { type: "email", placeholder: "kilnmark@example.com", required: form.enabled })}
          {text("from-name", "fromName", "From name", { placeholder: "Kilnmark", maxLength: 100 })}
        </div>
        {text("base-url", "baseUrl", "Address of Kilnmark, for links", { type: "url", placeholder: "https://kilnmark.example.com" },
          "How people open Kilnmark. Emails link back with it, to the asset, source or issue list, to each person's settings, and to confirm an address. Empty for emails without links.")}

        <fieldset className="group">
          <legend>Who may get email</legend>
          <DomainList value={form.allowedDomains} onChange={(allowedDomains) => set({ allowedDomains })}
            readOnly={locked("allowed-domains")} />
          <Switch
            id="mail-confirm"
            label="Personal addresses need confirming"
            checked={form.requireConfirmation}
            disabled={locked("require-confirmation")}
            defaultNote={locked("require-confirmation") ? "Set by the server's configuration" : undefined}
            onChange={(requireConfirmation) => set({ requireConfirmation })}
            help="A person's new address gets a confirmation email with a link and a code, and nothing else until they confirm it. Team addresses, which only admins add, need none."
          />
          {!form.requireConfirmation && (
            <p className="warning">
              Without confirmation, anyone who can sign in can have notifications sent to any address. Turn it off only on
              a closed network where confirmation links cannot be opened.
            </p>
          )}
        </fieldset>

        <fieldset className="group">
          <legend>Retries and limits</legend>
          <NumberField id="attempts" label="Tries before giving up" unit="tries" value={form.maxAttempts}
            min={lim.minAttempts} max={lim.maxAttempts} onChange={(maxAttempts) => set({ maxAttempts })}
            readOnly={locked("max-attempts")}
            hint="A failed email is tried again after 1, 3, 9 and 27 minutes, then hourly, until this many tries." />
          <NumberField id="rate" label="Emails per address per hour" unit="emails an hour" value={form.rateLimitPerHour}
            min={lim.minRatePerHour} max={lim.maxRatePerHour} onChange={(rateLimitPerHour) => set({ rateLimitPerHour })}
            readOnly={locked("rate-limit-per-hour")}
            hint="Beyond this, an address's emails wait for its next daily digest. Digests do not count." />
          <NumberField id="undelivered" label="Undelivered emails wait" unit="hours" value={form.undeliveredHours}
            min={lim.minUndeliveredHours} max={lim.maxUndeliveredHours}
            onChange={(undeliveredHours) => set({ undeliveredHours })} readOnly={locked("undelivered-after")}
            hint="How long an email may wait unsent (sending paused, or the mail server down) before the rule below applies." />
          <fieldset className="group" disabled={locked("undelivered-action")}>
            <legend>Then</legend>
            <label className="choice">
              <input type="radio" name="undelivered-action" checked={form.undeliveredAction === "digest"}
                onChange={() => set({ undeliveredAction: "digest" })} />
              <span>Put it in the recipient's next daily digest</span>
            </label>
            <label className="choice">
              <input type="radio" name="undelivered-action" checked={form.undeliveredAction === "drop"}
                onChange={() => set({ undeliveredAction: "drop" })} />
              <span>Drop it (it shows as skipped)</span>
            </label>
            {locked("undelivered-action") && <Locked id="undelivered-action-locked" />}
          </fieldset>
        </fieldset>
        <Button type="submit" variant="primary" aria-disabled={saving || undefined}>
          {saving ? "Saving…" : "Save mail settings"}
        </Button>
      </form>
      <TestEmail ready={!!view.host && !!view.fromAddress} />
    </Card>
  );
}

/** A whole number with its unit after it, its bounds in the hint. */
function NumberField({
  id,
  label,
  unit,
  value,
  min,
  max,
  onChange,
  hint,
  readOnly,
}: {
  id: string;
  label: string;
  unit: string;
  value: string;
  min: number;
  max: number;
  onChange: (v: string) => void;
  hint: string;
  readOnly?: boolean;
}) {
  return (
    <div className="field">
      <label htmlFor={`mail-${id}`}>{label}</label>
      <span className="copy-row">
        <input id={`mail-${id}`} type="number" inputMode="numeric" min={min} max={max} step={1} value={value}
          onChange={(e) => onChange(e.target.value)} disabled={readOnly}
          aria-describedby={`mail-${id}-unit mail-${id}-hint${readOnly ? ` mail-${id}-locked` : ""}`} />
        <span id={`mail-${id}-unit`}>{unit}</span>
      </span>
      {readOnly && <Locked id={`mail-${id}-locked`} />}
      <p id={`mail-${id}-hint`} className="hint">
        {hint} From {min} to {max}.
      </p>
    </div>
  );
}

/** The allowed email domains: a list with Remove buttons, and a box to add one (Enter adds). */
export function DomainList({ value, onChange, readOnly }: { value: string[]; onChange: (v: string[]) => void; readOnly?: boolean }) {
  const [draft, setDraft] = useState("");
  const [status, setStatus] = useState("");
  const add = () => {
    const d = draft.trim().toLowerCase().replace(/^@/, "");
    if (!d) return;
    if (!value.includes(d)) onChange([...value, d]);
    setDraft("");
    setStatus(`${d} added. Save to apply.`);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter") {
      e.preventDefault();
      add();
    }
  };
  return (
    <div className="field" role="group" aria-labelledby="domains-label">
      <span id="domains-label">
        <strong>Allowed email domains</strong>
      </span>
      {/* As every field: the box under the name, what is listed under the box, the help last. */}
      {!readOnly && (
        <span className="copy-row">
          <label htmlFor="domain-new" className="visually-hidden">
            Domain to add
          </label>
          <input id="domain-new" value={draft} onChange={(e) => setDraft(e.target.value)} onKeyDown={onKey}
            placeholder="example.com" aria-describedby="domains-hint" autoComplete="off" />
          <Button onClick={add} disabled={!draft.trim()}>
            Add domain
          </Button>
        </span>
      )}
      {readOnly && <Locked id="domains-locked" />}
      {value.length === 0 ? (
        <p className="hint">No domains listed: any domain.</p>
      ) : (
        <ul className="domain-list">
          {value.map((d) => (
            <li key={d}>
              <span className="mono">{d}</span>
              {!readOnly && (
                <Button size="sm" variant="ghost" aria-label={`Remove ${d}`}
                  onClick={() => {
                    onChange(value.filter((x) => x !== d));
                    setStatus(`${d} removed. Save to apply.`);
                  }}>
                  Remove
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      <p role="status" className="hint">
        {status}
      </p>
      <p id="domains-hint" className="hint">
        Empty: any domain. Otherwise email goes only to these domains and their subdomains (example.com also allows
        mail.example.com), for personal and team addresses alike. Addresses outside them are paused, not deleted.
      </p>
    </div>
  );
}

function TestEmail({ ready }: { ready: boolean }) {
  const [to, setTo] = useState("");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const resultRef = useFocusOn(result);
  const send = async (e: FormEvent) => {
    e.preventDefault();
    if (sending) return;
    setSending(true);
    setResult(null);
    try {
      setResult(await notifyApi.test(to.trim()));
    } catch (x) {
      setResult({ ok: false, message: (x as Error).message });
    } finally {
      setSending(false);
    }
  };
  return (
    <section aria-labelledby="test-h">
      <h3 id="test-h">Send a test email</h3>
      <p className="hint">With the saved settings, even while sending is off. The answer says what the mail server said.</p>
      <form onSubmit={send} className="form">
        <div className="field">
          <label htmlFor="test-to">Send to</label>
          <input id="test-to" type="email" value={to} onChange={(e) => setTo(e.target.value)} required
            placeholder="you@example.com" />
        </div>
        <Button type="submit" disabled={!ready} aria-disabled={sending || undefined}>
          {sending ? "Sending…" : "Send a test email"}
        </Button>
      </form>
      {!ready && <p className="hint">Save a mail server and a from address first.</p>}
      {result && (
        <p ref={resultRef} tabIndex={-1} role={result.ok ? "status" : "alert"} className={result.ok ? "notice" : "error"}>
          {result.ok ? "Sent. " : "Not sent. "}
          {result.message}
        </p>
      )}
    </section>
  );
}

const blankTeam = (): EmailChoices & { name: string; email: string; enabled: boolean } => ({
  name: "",
  email: "",
  enabled: true,
  kinds: ["rule_failed", "rule_recovered", "scan_failed", "drift", "connection_failed", "pipeline_failed", "pipeline_low_quality",
    "flow_failed", "flow_notice"],
  minSeverity: "high",
  mode: "digest",
  digestHour: 8,
  sources: null,
});

function Teams({
  kinds,
  timeZone,
  sources,
}: {
  kinds: KindChoice[];
  timeZone: string;
  sources: { name: string; displayName: string }[];
}) {
  const [teams, setTeams] = useState<TeamAddress[] | null>(null);
  const [editing, setEditing] = useState<number | "new" | null>(null);
  const [form, setForm] = useState(blankTeam());
  const [notice, setNotice] = useState("");
  const [error, setError] = useState<{ text: string } | null>(null);
  const errorRef = useFocusOn(error);
  const load = useCallback(() => {
    notifyApi
      .teams()
      .then(setTeams)
      .catch((e: Error) => setError({ text: e.message }));
  }, []);
  useEffect(load, [load]);

  const edit = (t: TeamAddress | null) => {
    setNotice("");
    setError(null);
    setEditing(t ? t.id : "new");
    setForm(t ? { ...t } : blankTeam());
    window.setTimeout(() => document.getElementById("team-name")?.focus(), 0);
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (form.kinds.length === 0) return setError({ text: "Kinds of event by email: tick at least one." });
    if (form.sources !== null && form.sources.length === 0) {
      return setError({ text: "Sources: tick at least one, or choose every source." });
    }
    try {
      const body = {
        name: form.name.trim(),
        email: form.email.trim(),
        enabled: form.enabled,
        kinds: form.kinds,
        minSeverity: form.minSeverity,
        mode: form.mode,
        digestHour: form.digestHour,
        sources: form.sources,
        allSources: form.sources === null,
      };
      if (editing === "new") await notifyApi.addTeam(body);
      else if (editing !== null) await notifyApi.updateTeam(editing, body);
      setNotice(`Team address ${body.name} saved.`);
      setEditing(null);
      load();
    } catch (x) {
      setError({ text: (x as Error).message });
    }
  };

  const remove = async (t: TeamAddress) => {
    if (!window.confirm(`Delete the team address ${t.name}? It gets no more email.`)) return;
    try {
      await notifyApi.deleteTeam(t.id);
      setNotice(`Team address ${t.name} deleted.`);
      load();
    } catch (x) {
      setError({ text: (x as Error).message });
    }
  };

  return (
    <Card
      title="Team addresses"
      titleId="teams-h"
      className="km-section"
      actions={editing === null ? <Button onClick={() => edit(null)}>Add a team address</Button> : undefined}
    >
      <p className="hint">
        A plain address, such as a distribution list, with its own choices. Team emails are written for a viewer: they
        never include detail only stewards may see. Team addresses need no confirmation and get no in-app notifications.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error.text}
        </p>
      )}
      {teams?.length === 0 && editing === null && <p>No team addresses yet.</p>}
      {teams && teams.length > 0 && (
        <DataTable label="Team addresses">
            <thead>
              <tr>
                <th scope="col">Name and address</th>
                <th scope="col">Gets</th>
              </tr>
            </thead>
            <tbody>
              {teams.map((t) => (
                <tr key={t.id}>
                  <th scope="row">
                    {t.name}
                    <div className="small muted">{t.email}</div>
                    {t.paused && (
                      <div className="small">
                        <StatusChip tone="warn">Paused: outside the allowed domains</StatusChip>
                      </div>
                    )}
                    <div className="buttons row-actions">
                      <Button size="sm" onClick={() => edit(t)} aria-label={`Edit ${t.name}`}>
                        Edit
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => remove(t)} aria-label={`Delete ${t.name}`}>
                        Delete
                      </Button>
                    </div>
                  </th>
                  <td className="small">
                    {!t.enabled
                      ? "Nothing (off)"
                      : `${t.kinds.length} kinds, ${severityPhrase(t.minSeverity)}, ${
                          t.mode === "digest" ? `daily at ${String(t.digestHour).padStart(2, "0")}:00` : "as they happen"
                        }${t.sources ? `, ${t.sources.length} sources` : ", every source"}`}
                  </td>
                </tr>
              ))}
            </tbody>
        </DataTable>
      )}
      {editing !== null && (
        <form onSubmit={save} className="form" aria-labelledby="team-form-h">
          <h3 id="team-form-h">{editing === "new" ? "Add a team address" : `Edit ${form.name}`}</h3>
          <div className="field-row">
            <div className="field">
              <label htmlFor="team-name">Name</label>
              <input id="team-name" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })}
                required maxLength={100} placeholder="Data team" />
            </div>
            <div className="field">
              <label htmlFor="team-email">Address</label>
              <input id="team-email" type="email" value={form.email}
                onChange={(e) => setForm({ ...form, email: e.target.value })} required placeholder="data-team@example.com" />
            </div>
          </div>
          <Switch id="team-enabled" label="Send to this address" checked={form.enabled}
            onChange={(enabled) => setForm({ ...form, enabled })} />
          <EmailChoiceFields
            idPrefix="team"
            value={form}
            onChange={(v) => setForm({ ...form, ...v })}
            kindChoices={kinds}
            sources={sources}
            timeZone={timeZone}
            disabled={!form.enabled}
          />
          <div className="buttons">
            <Button type="submit" variant="primary">
              Save team address
            </Button>
            <Button onClick={() => setEditing(null)}>Cancel</Button>
          </div>
        </form>
      )}
    </Card>
  );
}
