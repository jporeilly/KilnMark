# Notifications and email alerts

This guide shows you how Kilnmark tells you when something needs attention: in the app, and by
email if you want it. It covers what can notify you, how to choose what you get, how an admin sets
up email, and what an email contains.

## What can notify you

| Event | When | Severity |
| --- | --- | --- |
| **Quality rule failing** | A rule starts to warn, fail or not run | High for a failure, medium for a warning, low when it could not run |
| **Quality rule recovered** | A rule that was failing passes again | The failure's severity by email; shown as information in the app |
| **Issue opened** | A new task appears in the issue list, from a failing rule or from drift | The issue's severity |
| **Scan failed** | A scan of a source fails | High |
| **Drift detected** | A scan finds changes since the previous one: columns added, removed, renamed or retyped, value shapes or nulls that shift, row counts that drop or spike, data that stops arriving | The highest severity among the changes |
| **Connection test failed** | Testing a saved source's connection fails | Medium |
| **Pipeline run failed** | A pipeline run fails | High |
| **Pipeline quality below threshold** | A pipeline run succeeds, but what it wrote scores below the pipeline's quality threshold | Medium |

The two pipeline events arrive with pipelines; the other six work now.

**One message per problem.** A rule that fails on every scan tells you once, when it starts
failing. You hear again only if it gets worse, for example when a warning becomes a failure, and
once more when it recovers. A failed scan or connection test is told once until a scan or test of
that source works again. Drift and new issues are told each time they happen.

## In the app

Notifications appear in Kilnmark for every role, with nothing to set up. Each one says what
happened and links to where to look: the asset, the source or the issue list. Open
**Settings**, then **Notifications**, to see your latest ones under **Your latest
notifications** and to mark them read.

- You see every kind of event, at every severity, until you untick a kind under **Kinds of event
  shown in Kilnmark**.
- If you limit your notifications to some sources (see [Choosing what you get](#choosing-what-you-get)),
  that applies here too.
- Notifications are kept for 90 days, read or not.
- Each notification shows whether an email went to you about it, and its status: sent, queued,
  failed or skipped. Admins also see which team addresses got it.

## By email

Email is off until you turn it on, except for admins, who get high-severity events at once as soon
as they give an address. Email works only once an admin has set up the mail server (see [Setting
up email (admins)](#setting-up-email-admins)); until then the settings page says so, and you can
still make your choices.

To turn it on:

1. Open **Settings**, then **Notifications**.
2. Under **By email**, switch **Send me emails** on.
3. Enter your **Email address**.
4. Choose what you get, as described below.
5. Choose **Save notification settings**.

## Choosing what you get

| Setting | What it does |
| --- | --- |
| **Kinds of event by email** | Tick the events you want emails about. Admins start with every kind but **Issue opened**, since issues come from failing rules and drift, which are already ticked |
| **Least severity by email** | High only, medium and high, low and above, or everything |
| **When emails go** | **Each one as it happens**, or **One email a day**, a digest of everything since the last one, at the **Digest time** you choose |
| **Sources** | **Every source, including ones added later**, or only the sources you tick. This applies to notifications in the app too |

**Times are the server's.** The digest time is in the server's time zone, which the page names,
for example *Europe/London*. If you are in another time zone, choose the hour that suits you there.

**The hourly limit.** Kilnmark sends one address at most 20 emails of their own in an hour (an
administrator can change the number). Anything more waits for that address's next daily digest,
so a burst of alerts becomes one summary rather than a flood.

## What an email looks like

Every email has a plain text version and a simple HTML version, with no images and nothing that
tracks whether you opened it. The subject says the severity and what happened, for example:

```
[Kilnmark] High: email not null fails on crm.customers
```

The email then says what happened, for example *312 of 48,210 rows (0.65%) fail*, when it happened
(in the server's time zone), which source and asset it concerns, and links to it in Kilnmark. The
footer links to your notification settings. A daily digest groups its events by kind, most severe
first.

**No values from your data.** Emails give counts, shares, and the names of tables, columns and
rules, never values from the rows. Some events carry more detail, such as the reason a connection
test failed, which only stewards and admins see in the app. That detail is in the email only when
your role may see it, and never for a column marked sensitive, whoever you are. The same goes for
notifications in the app.

## Setting up email (admins)

Open **Settings**, then **Email**, and fill in **Mail server**:

| Field | What to enter |
| --- | --- |
| **Send emails** | On to send. Off pauses every email: they wait and go once it is back on. Notifications in the app carry on either way |
| **SMTP server** | The mail server's name, such as `smtp.example.com`, without `smtp://` or a port |
| **Port** | Empty for the usual one: 587 for STARTTLS, 465 for TLS, 25 for none |
| **Security** | **STARTTLS** (the usual choice), **TLS from the start**, or **None** for a relay on a trusted network only |
| **User name** and **Password** | The account to sign in as, or empty to send without signing in. The password is kept encrypted and never shown again: leave the box empty to keep it, or tick **Remove the stored password** |
| **From address** and **From name** | Who emails come from, such as `kilnmark@example.com` and *Kilnmark* |
| **Address of Kilnmark, for links** | How people open Kilnmark, such as `https://kilnmark.example.com`. Emails link back with it; leave it empty for emails without links |

Choose **Save mail settings**, then **Send a test email** to your own address. The answer says
whether it went or, if not, what the mail server said and what to check.

Before anyone saves these settings, Kilnmark uses the server's configuration (`kilnmark.mail.*`),
if its administrator set any. Once saved here, these settings win, except that a password set in
the configuration (for example `KILNMARK_MAIL_PASSWORD`) is used while none is saved here.

### Team addresses

A team address is a plain email address, such as a distribution list, with its own choices of
kinds, severity, timing and sources. Under **Team addresses**, choose **Add a team address**, give
it a name and an address, choose what it gets, and save. New team addresses get high-severity
events in a daily digest at 08:00.

Team emails are written for a viewer: they never include detail that only stewards may see. Team
addresses get no notifications in the app, as there is no one to sign in. In the feed, admins see
team addresses by name, never by address.

### Sent emails

**Settings**, **Sent emails** lists every attempt to send an email for the last 30 days: when, to
whom, the subject, and the outcome.

| Status | Meaning |
| --- | --- |
| **Sent** | The mail server accepted it |
| **Will try again** | It failed; Kilnmark tries again after 1, 3, 9 and 27 minutes, then hourly, five times in all |
| **Given up** | It failed every time, or the mail server refused it outright |
| **Moved to digest** | The address had reached its hourly limit; the email waits for its daily digest |

Only the subject is kept, never the email's text.

## For developers: the notifications API

The app's notifications come from three endpoints, each answering for whoever's API token is
used. Nobody can read or mark another person's notifications.

- `GET /api/v1/notifications?limit=N&unread=true|false`: the latest notifications, newest first
  (`limit` 20 by default, at most 100; `unread=true` for unread ones only), with the number
  unread.
- `GET /api/v1/notifications/unread-count`: only the number unread, `{"unread": 3}`. Cheap enough
  to ask every minute.
- `POST /api/v1/notifications/read` with `{"ids": ["12", "13"]}` or `{"all": true}`: marks them
  read and answers 204. Ids that are not yours are ignored.

An item looks like this:

```json
{
  "id": "12",
  "kind": "rule_failed",
  "severity": "high",
  "title": "email not null fails on crm.customers",
  "detail": "312 of 48,210 rows (0.65%) fail.",
  "link": "#/asset/urn%3Akilnmark%3Alab%3Acrm.customers",
  "createdAt": "2026-10-25T06:00:00Z",
  "read": false,
  "channels": [{"type": "in_app"}, {"type": "email", "status": "sent", "at": "2026-10-25T06:00:04Z"}],
  "teamEmails": [{"name": "Data team", "status": "queued"}]
}
```

`kind` is one of `rule_failed`, `rule_recovered`, `issue_opened`, `scan_failed`, `drift`,
`connection_failed`, `pipeline_failed` and `pipeline_low_quality`; `severity` one of `high`,
`medium`, `low` and `info`. The email channel is there only when an email about the event was
meant for you, with `status` `sent`, `queued`, `failed` or `skipped`. `teamEmails` is there for
admins only. Email addresses never appear in the feed.

Your own choices are at `GET` and `PUT /api/v1/me/notifications`; email settings, team addresses
and the send log are at `/api/v1/mail/...` for admins. The interactive API documentation at
`/api/docs` lists them all.

## When something goes wrong

| What you see | What to do |
| --- | --- |
| *Email is not set up yet* | Ask an admin to enter the mail server under **Settings**, **Email** |
| *Email is paused* | An admin switched **Send emails** off. Emails wait in the queue until it is on again |
| *The mail server refused the user name or password* | Check them. Some services need an app password, or signing in allowed for the account |
| *Nothing accepted a connection at …* | Check the server name and port, and that a firewall lets the Kilnmark server reach it |
| *… did not answer within … seconds* | The port or the security setting is probably wrong: port 465 usually takes TLS, port 587 STARTTLS |
| *… does not offer STARTTLS* | Choose **TLS from the start** if the server listens for TLS, or **None** for a relay on a trusted network |
| *The mail server's certificate is not trusted* | Use a certificate from a public authority, or add the one that issued it to the server's Java trust store |
| No emails, but the test email works | Check that **Send me emails** is on, that the kinds and least severity cover the event, and that the source is among yours. Admins can check **Sent emails** |
| An email you expected came in the digest | The address reached its hourly limit, or digest is chosen under **When emails go** |
