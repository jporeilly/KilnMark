import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
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
import { EmailChoiceFields, Switch, type EmailChoices } from "../components/NotificationChoices";

const SECURITY: { value: MailSecurity; label: string }[] = [
  { value: "starttls", label: "STARTTLS (usually port 587)" },
  { value: "tls", label: "TLS from the start (usually port 465)" },
  { value: "none", label: "None (usually port 25; only for a relay on a trusted network)" },
];

/** Focuses an error message once it is shown, so keyboard and screen-reader users land on it. */
function useErrorFocus() {
  const ref = useRef<HTMLParagraphElement>(null);
  const [error, setError] = useState<string | null>(null);
  const show = (message: string | null) => {
    setError(message);
    if (message) window.setTimeout(() => ref.current?.focus(), 0);
  };
  const node = error ? (
    <p role="alert" className="error" tabIndex={-1} ref={ref}>
      {error}
    </p>
  ) : null;
  return { show, node };
}

/** Settings › Email, for admins: the outgoing mail server, a test email, and team addresses. */
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
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>
            <a href={href.settings()}>Settings</a>
          </li>
          <li aria-current="page">Email</li>
        </ol>
      </nav>
      <h1 tabIndex={-1}>Email</h1>
      <p className="lead">
        The mail server Kilnmark sends notifications through, and team addresses for distribution lists. Each person
        chooses their own emails under <a href={href.notifications()}>Settings, Notifications</a>. See every email sent
        or tried under <a href={href.emailLog()}>Sent emails</a>.
      </p>
      <MailServer />
      {kinds.length > 0 && <Teams kinds={kinds} timeZone={timeZone} sources={sources} />}
    </>
  );
}

function MailServer() {
  const [view, setView] = useState<MailSettingsView | null>(null);
  const [form, setForm] = useState({
    enabled: true,
    host: "",
    port: "",
    security: "starttls" as MailSecurity,
    username: "",
    password: "",
    removePassword: false,
    fromAddress: "",
    fromName: "",
    baseUrl: "",
  });
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const err = useErrorFocus();

  const apply = (v: MailSettingsView) => {
    setView(v);
    setForm({
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
    });
  };
  useEffect(() => {
    notifyApi
      .settings()
      .then(apply)
      .catch((e: Error) => err.show(e.message));
  }, []);

  const set = (patch: Partial<typeof form>) => setForm({ ...form, ...patch });

  const save = async (e: FormEvent) => {
    e.preventDefault();
    setNotice("");
    const port = form.port.trim() ? Number(form.port) : null;
    if (port !== null && !(Number.isInteger(port) && port >= 1 && port <= 65535)) {
      return err.show("Port: a whole number from 1 to 65535, or empty for the usual one.");
    }
    setSaving(true);
    err.show(null);
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
      });
      apply(v);
      setNotice("Mail settings saved.");
    } catch (x) {
      err.show((x as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="panel" aria-labelledby="server-h">
      <h2 id="server-h">Mail server</h2>
      {view && !view.saved && (
        <p className="hint">
          Not saved yet: what is shown comes from the server's configuration (kilnmark.mail), if any. Saving keeps these
          settings in the database, and they win from then on.
        </p>
      )}
      {view?.saved && view.updatedAt && (
        <p className="hint">
          Last saved {formatTime(view.updatedAt)} by {view.updatedBy}.
        </p>
      )}
      <p role="status" className="notice">
        {notice}
      </p>
      {err.node}
      <form onSubmit={save} className="form">
        <Switch
          id="mail-enabled"
          label="Send emails"
          checked={form.enabled}
          onChange={(enabled) => set({ enabled })}
          hint="Off pauses every email; they wait and go once it is back on. In-app notifications go on regardless."
        />
        <div className="field-row">
          <div className="field">
            <label htmlFor="mail-host">SMTP server</label>
            <input id="mail-host" value={form.host} onChange={(e) => set({ host: e.target.value })} autoComplete="off"
              placeholder="smtp.example.com" required={form.enabled} />
          </div>
          <div className="field">
            <label htmlFor="mail-port">Port</label>
            <input id="mail-port" inputMode="numeric" value={form.port} onChange={(e) => set({ port: e.target.value })}
              aria-describedby="mail-port-hint" />
            <p id="mail-port-hint" className="hint">Empty for the usual port for the security chosen.</p>
          </div>
        </div>
        <div className="field">
          <label htmlFor="mail-security">Security</label>
          <select id="mail-security" value={form.security} onChange={(e) => set({ security: e.target.value as MailSecurity })}>
            {SECURITY.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="mail-user">User name</label>
            <input id="mail-user" value={form.username} onChange={(e) => set({ username: e.target.value })}
              autoComplete="off" aria-describedby="mail-user-hint" />
            <p id="mail-user-hint" className="hint">Empty to send without signing in.</p>
          </div>
          <div className="field">
            <label htmlFor="mail-password">Password</label>
            <input id="mail-password" type="password" value={form.password} autoComplete="new-password"
              onChange={(e) => set({ password: e.target.value, removePassword: false })}
              aria-describedby="mail-password-hint" disabled={form.removePassword} />
            <p id="mail-password-hint" className="hint">
              {view?.hasPassword
                ? "A password is kept. Leave this empty to keep it. It is never shown again."
                : "Kept encrypted, and never shown again."}
            </p>
            {view?.hasPassword && (
              <label className="choice">
                <input type="checkbox" checked={form.removePassword}
                  onChange={(e) => set({ removePassword: e.target.checked, password: "" })} />
                <span>Remove the stored password</span>
              </label>
            )}
          </div>
        </div>
        <div className="field-row">
          <div className="field">
            <label htmlFor="mail-from">From address</label>
            <input id="mail-from" type="email" value={form.fromAddress} onChange={(e) => set({ fromAddress: e.target.value })}
              placeholder="kilnmark@example.com" required={form.enabled} />
          </div>
          <div className="field">
            <label htmlFor="mail-from-name">From name</label>
            <input id="mail-from-name" value={form.fromName} onChange={(e) => set({ fromName: e.target.value })}
              placeholder="Kilnmark" maxLength={100} />
          </div>
        </div>
        <div className="field">
          <label htmlFor="mail-base">Address of Kilnmark, for links</label>
          <input id="mail-base" type="url" value={form.baseUrl} onChange={(e) => set({ baseUrl: e.target.value })}
            placeholder="https://kilnmark.example.com" aria-describedby="mail-base-hint" />
          <p id="mail-base-hint" className="hint">
            How people open Kilnmark. Emails link back to the asset, source or issue list, and to each person's
            settings. Empty for emails without links.
          </p>
        </div>
        <button type="submit" className="primary" disabled={saving}>
          {saving ? "Saving…" : "Save mail settings"}
        </button>
      </form>
      <TestEmail ready={!!view?.host && !!view?.fromAddress} />
    </section>
  );
}

function TestEmail({ ready }: { ready: boolean }) {
  const [to, setTo] = useState("");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ ok: boolean; message: string } | null>(null);
  const resultRef = useRef<HTMLParagraphElement>(null);
  const send = async (e: FormEvent) => {
    e.preventDefault();
    setSending(true);
    setResult(null);
    try {
      setResult(await notifyApi.test(to.trim()));
    } catch (x) {
      setResult({ ok: false, message: (x as Error).message });
    } finally {
      setSending(false);
      window.setTimeout(() => resultRef.current?.focus(), 0);
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
        <button type="submit" disabled={sending || !ready}>
          {sending ? "Sending…" : "Send a test email"}
        </button>
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
  kinds: ["rule_failed", "rule_recovered", "scan_failed", "drift", "connection_failed", "pipeline_failed", "pipeline_low_quality"],
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
  const err = useErrorFocus();
  const load = useCallback(() => {
    notifyApi
      .teams()
      .then(setTeams)
      .catch((e: Error) => err.show(e.message));
  }, []);
  useEffect(load, [load]);

  const edit = (t: TeamAddress | null) => {
    setNotice("");
    err.show(null);
    setEditing(t ? t.id : "new");
    setForm(t ? { ...t } : blankTeam());
    window.setTimeout(() => document.getElementById("team-name")?.focus(), 0);
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (form.kinds.length === 0) return err.show("Kinds of event by email: tick at least one.");
    if (form.sources !== null && form.sources.length === 0) return err.show("Sources: tick at least one, or choose every source.");
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
      };
      if (editing === "new") await notifyApi.addTeam(body);
      else if (editing !== null) await notifyApi.updateTeam(editing, body);
      setNotice(`Team address ${body.name} saved.`);
      setEditing(null);
      load();
    } catch (x) {
      err.show((x as Error).message);
    }
  };

  const remove = async (t: TeamAddress) => {
    if (!window.confirm(`Delete the team address ${t.name}? It gets no more email.`)) return;
    try {
      await notifyApi.deleteTeam(t.id);
      setNotice(`Team address ${t.name} deleted.`);
      load();
    } catch (x) {
      err.show((x as Error).message);
    }
  };

  return (
    <section className="panel" aria-labelledby="teams-h">
      <div className="panel-head">
        <h2 id="teams-h">Team addresses</h2>
        {editing === null && (
          <button type="button" onClick={() => edit(null)}>
            Add a team address
          </button>
        )}
      </div>
      <p className="hint">
        A plain address, such as a distribution list, with its own choices. Team emails are written for a viewer: they
        never include detail only stewards may see. Team addresses get no in-app notifications.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {err.node}
      {teams?.length === 0 && editing === null && <p>No team addresses yet.</p>}
      {teams && teams.length > 0 && (
        <div className="table-scroll" role="region" aria-label="Team addresses (scrolls sideways on small screens)" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">Address</th>
                <th scope="col">Gets</th>
                <th scope="col">
                  <span className="visually-hidden">Actions</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {teams.map((t) => (
                <tr key={t.id}>
                  <th scope="row">{t.name}</th>
                  <td>{t.email}</td>
                  <td className="small">
                    {!t.enabled
                      ? "Nothing (off)"
                      : `${t.kinds.length} kinds, ${t.minSeverity} and above, ${
                          t.mode === "digest" ? `daily at ${String(t.digestHour).padStart(2, "0")}:00` : "as they happen"
                        }${t.sources ? `, ${t.sources.length} sources` : ""}`}
                  </td>
                  <td>
                    <div className="buttons">
                      <button type="button" onClick={() => edit(t)} aria-label={`Edit ${t.name}`}>
                        Edit
                      </button>
                      <button type="button" onClick={() => remove(t)} aria-label={`Delete ${t.name}`}>
                        Delete
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
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
            <button type="submit" className="primary">
              Save team address
            </button>
            <button type="button" onClick={() => setEditing(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
