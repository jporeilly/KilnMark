import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import {
  api,
  formatTime,
  notifyApi,
  type EmailStatus,
  type MyNotifications,
  type NotificationItem,
  type NotificationKindId,
} from "../api";
import { href } from "../router";
import { useTitle } from "../components/useTitle";
import { EmailChoiceFields, KindBoxes, Switch, type EmailChoices } from "../components/NotificationChoices";

/** Badge classes from the existing ones (tokens.css colours), by severity and by email status. */
export const severityBadge = (s: string) => (s === "info" ? "badge badge-none" : `badge badge-sev-${s}`);
export const emailBadge: Record<EmailStatus, string> = {
  sent: "badge badge-succeeded",
  queued: "badge badge-queued",
  failed: "badge badge-failed",
  skipped: "badge badge-none",
};

/**
 * Settings › Notifications, for every signed-in person: what they see in Kilnmark and what they
 * get by email (their own address, kinds, least severity, at once or a daily digest, sources), and
 * their latest notifications.
 */
export function NotificationsPage() {
  useTitle("Notifications");
  const [mine, setMine] = useState<MyNotifications | null>(null);
  const [sources, setSources] = useState<{ name: string; displayName: string }[]>([]);
  const [email, setEmail] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [choices, setChoices] = useState<EmailChoices | null>(null);
  const [inApp, setInApp] = useState<NotificationKindId[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const errorRef = useRef<HTMLParagraphElement>(null);

  const apply = (m: MyNotifications) => {
    setMine(m);
    setEmail(m.email ?? "");
    setEnabled(m.enabled);
    setChoices({ kinds: m.kinds, minSeverity: m.minSeverity, mode: m.mode, digestHour: m.digestHour, sources: m.sources });
    setInApp(m.inAppKinds);
  };

  useEffect(() => {
    notifyApi
      .mine()
      .then(apply)
      .catch((e: Error) => setError(e.message));
    api
      .sources()
      .then((s) => setSources(s.map((x) => ({ name: x.name, displayName: x.displayName }))))
      .catch(() => setSources([]));
  }, []);

  const fail = (message: string) => {
    setError(message);
    window.setTimeout(() => errorRef.current?.focus(), 0);
  };

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!choices) return;
    setNotice("");
    if (enabled && !email.trim()) return fail("Email address: enter where to send, or turn email off.");
    if (enabled && choices.kinds.length === 0) return fail("Kinds of event by email: tick at least one, or turn email off.");
    if (choices.sources !== null && choices.sources.length === 0) return fail("Sources: tick at least one, or choose every source.");
    setSaving(true);
    setError(null);
    try {
      apply(await notifyApi.saveMine({ email: email.trim(), enabled, ...choices, inAppKinds: inApp }));
      setNotice("Your notification settings are saved.");
    } catch (err) {
      fail((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <nav aria-label="Breadcrumb" className="crumbs">
        <ol>
          <li>
            <a href={href.settings()}>Settings</a>
          </li>
          <li aria-current="page">Notifications</li>
        </ol>
      </nav>
      <h1 tabIndex={-1}>Notifications</h1>
      <p className="lead">
        What Kilnmark tells you about: failing quality rules and their recovery, new issues, failed scans and connection
        tests, drift, and pipeline runs. Notifications show here in Kilnmark; email is yours to turn on.
      </p>
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error}
        </p>
      )}
      {mine && !mine.available && <p className="warning">{mine.unavailable}</p>}
      {mine && choices && mine.available && (
        <form onSubmit={save} className="form">
          <section className="panel" aria-labelledby="inapp-h">
            <h2 id="inapp-h">In Kilnmark</h2>
            <KindBoxes
              idPrefix="inapp"
              legend="Kinds of event shown in Kilnmark"
              hint="At any severity. Untick a kind to stop seeing it here; email is chosen below, on its own."
              choices={mine.kindChoices}
              value={inApp}
              onChange={setInApp}
            />
          </section>
          <section className="panel" aria-labelledby="email-h">
            <h2 id="email-h">By email</h2>
            {!mine.mailReady && mine.mailNote && <p className="hint">{mine.mailNote} You can still choose now.</p>}
            <Switch
              id="email-on"
              label="Send me emails"
              checked={enabled}
              onChange={setEnabled}
              hint={
                mine.saved
                  ? undefined
                  : "Until you save, this shows the default for your role: on for admins (high-severity events at once), off for everyone else."
              }
            />
            <div className="field">
              <label htmlFor="email-address">Email address</label>
              <input
                id="email-address"
                type="email"
                autoComplete="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                maxLength={254}
                required={enabled}
                aria-describedby="email-address-hint"
              />
              <p id="email-address-hint" className="hint">
                For you alone. Emails never hold values from the data: counts, shares and a link back.
              </p>
            </div>
            <EmailChoiceFields
              idPrefix="mine"
              value={choices}
              onChange={setChoices}
              kindChoices={mine.kindChoices}
              sources={sources}
              timeZone={mine.timeZone}
              disabled={!enabled}
            />
          </section>
          <button type="submit" className="primary" disabled={saving}>
            {saving ? "Saving…" : "Save notification settings"}
          </button>
        </form>
      )}
      {mine?.available && <Recent />}
    </>
  );
}

/** The latest in-app notifications, with how each reached you, and marking them read. */
function Recent() {
  const [feed, setFeed] = useState<{ items: NotificationItem[]; unread: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    notifyApi
      .feed(20)
      .then(setFeed)
      .catch((e: Error) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  const read = async (ids: string[] | "all") => {
    try {
      await (ids === "all" ? notifyApi.markAllRead() : notifyApi.markRead(ids));
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <section className="panel" aria-labelledby="recent-h">
      <div className="panel-head">
        <h2 id="recent-h">Your latest notifications</h2>
        {feed && feed.unread > 0 && (
          <button type="button" onClick={() => read("all")}>
            Mark all {feed.unread} read
          </button>
        )}
      </div>
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {feed === null && !error && <p>Loading…</p>}
      {feed?.items.length === 0 && <p>Nothing yet. Notifications are kept for 90 days.</p>}
      {feed && feed.items.length > 0 && (
        <ul className="notification-list">
          {feed.items.map((n) => {
            const mail = n.channels.find((c) => c.type === "email");
            return (
              <li key={n.id}>
                <p>
                  <span className={severityBadge(n.severity)}>{n.severity}</span>{" "}
                  {n.link ? <a href={n.link}>{n.title}</a> : n.title}
                  {!n.read && <span className="muted small"> · unread</span>}
                </p>
                {n.detail && <p className="small">{n.detail}</p>}
                <p className="small muted">
                  {formatTime(n.createdAt)}
                  {mail && mail.type === "email" && (
                    <>
                      {" · "}email to you: <span className={emailBadge[mail.status]}>{mail.status}</span>
                    </>
                  )}
                  {n.teamEmails && n.teamEmails.length > 0 && (
                    <> · team emails: {n.teamEmails.map((t) => `${t.name} (${t.status})`).join(", ")}</>
                  )}
                </p>
                {!n.read && (
                  <button type="button" className="small" onClick={() => read([n.id])} aria-label={`Mark read: ${n.title}`}>
                    Mark read
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
