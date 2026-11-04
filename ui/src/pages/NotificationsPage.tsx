import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { api, formatTime, notifyApi, type MyNotifications, type NotificationKindId } from "../api";
import { go, href } from "../router";
import { useTitle } from "../components/useTitle";
import { Button, Card, EmptyState, PageHeader, Switch } from "../components/ui";
import { EmailChoiceFields, KindBoxes, type EmailChoices } from "../components/NotificationChoices";
import { notificationsApi, type NotificationPage } from "../notifications/api";
import { NotificationList } from "../notifications/NotificationList";
import { markNotificationsRead } from "../notifications/store";

/** An unread count as the bell shows it: "99+" when the server says there are more. */
export const unreadText = (n: { unread: number; more?: boolean }) => (n.more ? `${n.unread}+` : String(n.unread));

/**
 * A message that takes focus each time it changes (an error from the server, a test result), so
 * keyboard and screen-reader users land on it even when the button they pressed was disabled.
 */
export function useFocusOn<T>(value: T | null) {
  const ref = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (value) ref.current?.focus();
  }, [value]);
  return ref;
}

/**
 * Settings › Notifications, for every signed-in person: what they see in Kilnmark and what they
 * get by email (their own address, confirmed first; kinds, least severity, at once or a daily
 * digest, sources), and their latest notifications.
 */
export function NotificationsPage({ confirm }: { confirm: string | null }) {
  useTitle("Notifications");
  const [mine, setMine] = useState<MyNotifications | null>(null);
  const [sources, setSources] = useState<{ name: string; displayName: string }[]>([]);
  const [email, setEmail] = useState("");
  const [enabled, setEnabled] = useState(false);
  const [choices, setChoices] = useState<EmailChoices | null>(null);
  const [inApp, setInApp] = useState<NotificationKindId[]>([]);
  const [error, setError] = useState<{ text: string } | null>(null);
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);
  const errorRef = useFocusOn(error);

  const apply = (m: MyNotifications) => {
    setMine(m);
    setEmail(m.email ?? "");
    setEnabled(m.enabled);
    setChoices({ kinds: m.kinds, minSeverity: m.minSeverity, mode: m.mode, digestHour: m.digestHour, sources: m.sources });
    setInApp(m.inAppKinds);
  };
  const fail = (text: string) => setError({ text });

  useEffect(() => {
    const load = confirm
      ? notifyApi.confirm(confirm).then((m) => {
          setNotice("Your email address is confirmed.");
          go(href.notifications()); // the code leaves the address bar
          return m;
        })
      : notifyApi.mine();
    load
      .then(apply)
      .catch((e: Error) => {
        fail(e.message);
        if (confirm) notifyApi.mine().then(apply).catch(() => undefined);
      });
    api
      .sources()
      .then((s) => setSources(s.map((x) => ({ name: x.name, displayName: x.displayName }))))
      .catch(() => setSources([]));
  }, [confirm]);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!choices || saving) return;
    setNotice("");
    if (enabled && !email.trim()) return fail("Email address: enter where to send, or turn email off.");
    if (enabled && choices.kinds.length === 0) return fail("Kinds of event by email: tick at least one, or turn email off.");
    if (choices.sources !== null && choices.sources.length === 0) return fail("Sources: tick at least one, or choose every source.");
    setSaving(true);
    setError(null);
    try {
      const m = await notifyApi.saveMine({
        email: email.trim(),
        enabled,
        ...choices,
        allSources: choices.sources === null,
        inAppKinds: inApp,
      });
      apply(m);
      setNotice(
        m.confirmationRequired && m.email && !m.confirmed
          ? "Saved. Check your inbox: emails start once you confirm your address."
          : "Your notification settings are saved.",
      );
    } catch (err) {
      fail((err as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Notifications"
        crumbs={[{ label: "Settings", href: href.settings() }, { label: "Notifications" }]}
        lead="What Kilnmark tells you about: failing quality rules and their recovery, new issues, failed scans and connection tests, drift, pipeline runs and flows. Notifications show here in Kilnmark; email is yours to turn on."
      />
      <p role="status" className="notice">
        {notice}
      </p>
      {error && (
        <p role="alert" className="error" tabIndex={-1} ref={errorRef}>
          {error.text}
        </p>
      )}
      {mine && !mine.available && <p className="warning">{mine.unavailable}</p>}
      {mine && choices && mine.available && (
        <form onSubmit={save} className="form km-section">
          <Card title="In Kilnmark" titleId="inapp-h">
            <KindBoxes
              idPrefix="inapp"
              legend="Kinds of event shown in Kilnmark"
              hint="At any severity. Untick a kind to stop seeing it here; email is chosen below, on its own."
              choices={mine.kindChoices}
              value={inApp}
              onChange={setInApp}
            />
          </Card>
          <Card title="By email" titleId="email-h">
            {!mine.mailReady && mine.mailNote && <p className="hint">{mine.mailNote} You can still choose now.</p>}
            <Switch
              id="email-on"
              label="Send me emails"
              checked={enabled}
              onChange={setEnabled}
              defaultNote={mine.saved ? undefined : enabled ? "Default for admins: on" : "Default: off"}
              help={
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
                For you alone.
                {mine.allowedDomains.length > 0 && <> This server sends only to {mine.allowedDomains.join(", ")}.</>}
                {mine.confirmationRequired && " A new address gets a confirmation email first, and nothing else until you confirm it."}
                {" "}Emails never hold values from the data: counts, shares and a link back.
              </p>
            </div>
            {mine.saved && mine.email && <Confirmation mine={mine} onChange={apply} onError={fail} onNotice={setNotice} />}
            <EmailChoiceFields
              idPrefix="mine"
              value={choices}
              onChange={setChoices}
              kindChoices={mine.kindChoices}
              sources={sources}
              timeZone={mine.timeZone}
              disabled={!enabled}
            />
          </Card>
          <Button type="submit" variant="primary" aria-disabled={saving || undefined}>
            {saving ? "Saving…" : "Save notification settings"}
          </Button>
        </form>
      )}
      {mine?.available && <Recent />}
    </>
  );
}

/** Whether the address is confirmed, and if not: a box for the code from the email, and sending another. */
function Confirmation({
  mine,
  onChange,
  onError,
  onNotice,
}: {
  mine: MyNotifications;
  onChange: (m: MyNotifications) => void;
  onError: (text: string) => void;
  onNotice: (text: string) => void;
}) {
  const [code, setCode] = useState("");
  if (mine.paused) {
    return (
      <p className="warning">
        Your address is outside the domains this server sends to, so it gets nothing for now. Use an address in{" "}
        {mine.allowedDomains.join(", ")}.
      </p>
    );
  }
  if (!mine.confirmationRequired || mine.confirmed) {
    return mine.confirmed ? <p className="hint">Your address is confirmed.</p> : null;
  }
  const submit = async () => {
    try {
      onChange(await notifyApi.confirm(code.trim()));
      setCode("");
      onNotice("Your email address is confirmed.");
    } catch (e) {
      onError((e as Error).message);
    }
  };
  const resend = async () => {
    try {
      onChange(await notifyApi.resendConfirmation());
      onNotice("A new confirmation email is on its way.");
    } catch (e) {
      onError((e as Error).message);
    }
  };
  return (
    <Card title="Confirm your address" titleId="confirm-h" level={3} accentTop="accent">
      <p>
        Not confirmed yet: you get no email until you are.
        {mine.confirmationSentAt
          ? ` A confirmation email went to ${mine.email} at ${formatTime(mine.confirmationSentAt)}. Open its link, or type its code here.`
          : " No confirmation email has gone yet, because email is not set up; ask for one once it is."}
      </p>
      <div className="field">
        <label htmlFor="confirm-code">Code from the email</label>
        <input id="confirm-code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code"
          spellCheck={false} />
      </div>
      <div className="buttons">
        <Button onClick={submit} disabled={!code.trim()}>
          Confirm
        </Button>
        <Button variant="subtle" onClick={resend}>
          Send another confirmation email
        </Button>
      </div>
    </Card>
  );
}

/** The latest in-app notifications, with how each reached you, and marking them read. */
function Recent() {
  const [feed, setFeed] = useState<NotificationPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(() => {
    notificationsApi
      .list(20)
      .then(setFeed)
      .catch((e: Error) => setError(e.message));
  }, []);
  useEffect(load, [load]);
  const read = async (ids: string[] | "all") => {
    try {
      await markNotificationsRead(ids);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };
  return (
    <Card
      title="Your latest notifications"
      titleId="recent-h"
      className="km-section"
      actions={
        feed && feed.unread > 0 ? (
          <Button size="sm" onClick={() => read("all")}>
            Mark all read ({unreadText(feed)} unread)
          </Button>
        ) : undefined
      }
    >
      {error && (
        <p role="alert" className="error">
          {error}
        </p>
      )}
      {feed === null && !error && <p role="status" className="muted">Loading…</p>}
      {feed?.items.length === 0 && (
        <EmptyState title="Nothing yet" compact>Notifications are kept for 90 days.</EmptyState>
      )}
      {feed && feed.items.length > 0 && <NotificationList items={feed.items} onOpen={(n) => read([n.id])} />}
    </Card>
  );
}
