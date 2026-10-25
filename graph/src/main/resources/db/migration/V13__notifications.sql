-- Notifications (docs/design/notifications.md), in the app and by email: the mail server an admin
-- sets, who gets which events, the events themselves, what has already been said about each
-- recurring problem, each person's in-app notifications, the emails waiting to go, and a log of
-- every attempt to send one.
-- V13, not V12: the pipelines change in flight takes V12. Refers only to tables from V1 (install_info)
-- and V4 (api_token), so it applies with or without V12.

SET search_path = kilnmark, public;

-- The outgoing mail server, one row, edited by admins under Settings › Email. Until it is saved the
-- server uses kilnmark.mail.* from its configuration. The password is sealed with the server's
-- secret key (SecretBox), like source passwords, and never leaves the server through the API.
CREATE TABLE mail_settings (
    id            smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    enabled       boolean NOT NULL DEFAULT true,
    host          text CHECK (host IS NULL OR length(host) BETWEEN 1 AND 253),
    port          integer CHECK (port IS NULL OR port BETWEEN 1 AND 65535),
    security      text NOT NULL DEFAULT 'starttls' CHECK (security IN ('none', 'starttls', 'tls')),
    username      text CHECK (username IS NULL OR length(username) BETWEEN 1 AND 320),
    password      bytea,
    from_address  text CHECK (from_address IS NULL OR length(from_address) BETWEEN 3 AND 254),
    from_name     text CHECK (from_name IS NULL OR length(from_name) BETWEEN 1 AND 100),
    base_url      text CHECK (base_url IS NULL OR length(base_url) BETWEEN 8 AND 500),
    updated_by    text NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now()
);

-- Who gets notified: a person (by the API token they sign in with, until sign-in by OpenID Connect
-- arrives in v0.2) or a team address an admin adds for a distribution list. A team address gets
-- what a viewer may see, and has no in-app notifications. kinds lists the event kinds mailed
-- (NotificationKind ids), in_app_kinds those shown in the app; sources NULL means every source.
-- min_severity applies to email only. digest_hour is in the server's time zone. A person with no
-- row gets the defaults (Subscription.defaults): every kind in the app, and no email unless admin.
CREATE TABLE notify_subscriber (
    id            bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_id      bigint UNIQUE REFERENCES api_token (id) ON DELETE CASCADE,
    team_name     text CHECK (team_name IS NULL OR length(team_name) BETWEEN 1 AND 100),
    email         text CHECK (email IS NULL OR length(email) BETWEEN 3 AND 254),   -- a person may give none
    enabled       boolean NOT NULL DEFAULT true,
    kinds         text[] NOT NULL DEFAULT '{}',
    min_severity  text NOT NULL DEFAULT 'high' CHECK (min_severity IN ('info', 'low', 'medium', 'high')),
    mode          text NOT NULL DEFAULT 'immediate' CHECK (mode IN ('immediate', 'digest')),
    digest_hour   smallint NOT NULL DEFAULT 8 CHECK (digest_hour BETWEEN 0 AND 23),
    sources       text[],
    in_app_kinds  text[] NOT NULL DEFAULT '{}',
    created_by    text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CHECK ((token_id IS NULL) = (team_name IS NOT NULL)),
    CHECK (token_id IS NOT NULL OR email IS NOT NULL)
);

-- Something that happened, written once and shared by every email about it. detail holds words,
-- counts and shares only (what a viewer may see); restricted_detail is shown only to recipients
-- whose role may see values of field_name on subject_urn (see Sights.recipient). link is the
-- in-app address (#/...), made absolute with the base URL when an email is written.
CREATE TABLE notify_event (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind               text NOT NULL CHECK (kind ~ '^[a-z][a-z_]{0,39}$'),
    severity           text NOT NULL CHECK (severity IN ('info', 'low', 'medium', 'high')),
    source             text,
    subject_urn        text,
    field_name         text,
    title              text NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    detail             text CHECK (detail IS NULL OR length(detail) <= 4000),
    restricted_detail  text CHECK (restricted_detail IS NULL OR length(restricted_detail) <= 4000),
    link               text CHECK (link IS NULL OR length(link) <= 2000),
    dedupe_key         text NOT NULL CHECK (length(dedupe_key) BETWEEN 1 AND 500),
    occurred_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notify_event_at_idx ON notify_event (occurred_at);

-- What has been said about each recurring problem (a rule, a source's scans or connection), so a
-- rule failing on every scan is mailed once until it recovers, then once more when it does.
CREATE TABLE notify_state (
    dedupe_key  text PRIMARY KEY CHECK (length(dedupe_key) BETWEEN 1 AND 500),
    open        boolean NOT NULL,
    severity    text NOT NULL CHECK (severity IN ('info', 'low', 'medium', 'high')),
    event_id    bigint,                         -- the last event recorded; events are deleted later
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- One person's in-app notifications (the bell and the home page's Notifications panel), written
-- for their role when the event happens: title and detail hold no values their role may not see.
-- Kept for kilnmark.notifications.in-app-retention (90 days). event_id ties it to the email
-- deliveries of the same event, for the feed's channels.
CREATE TABLE notify_inbox (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token_id    bigint NOT NULL REFERENCES api_token (id) ON DELETE CASCADE,
    event_id    bigint REFERENCES notify_event (id) ON DELETE SET NULL,
    kind        text NOT NULL CHECK (kind ~ '^[a-z][a-z_]{0,39}$'),
    severity    text NOT NULL CHECK (severity IN ('info', 'low', 'medium', 'high')),
    title       text NOT NULL CHECK (length(title) BETWEEN 1 AND 300),
    detail      text CHECK (detail IS NULL OR length(detail) <= 8000),
    link        text CHECK (link IS NULL OR length(link) <= 2000),
    read        boolean NOT NULL DEFAULT false,
    created_at  timestamptz NOT NULL DEFAULT now(),
    read_at     timestamptz,
    UNIQUE (token_id, event_id)
);
CREATE INDEX notify_inbox_feed_idx ON notify_inbox (token_id, read, created_at DESC);
CREATE INDEX notify_inbox_at_idx ON notify_inbox (created_at);
CREATE INDEX notify_inbox_event_idx ON notify_inbox (event_id);

-- One event for one subscriber: sent on its own as soon as a worker gets to it, or with the others
-- in that subscriber's daily digest (digest true, due at their digest hour). Workers claim rows with
-- SELECT ... FOR UPDATE SKIP LOCKED and send outside the transaction; a failed send is due again
-- after a back-off, and failed for good after kilnmark.notifications.max-attempts.
CREATE TABLE notify_delivery (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subscriber_id  bigint NOT NULL REFERENCES notify_subscriber (id) ON DELETE CASCADE,
    event_id       bigint NOT NULL REFERENCES notify_event (id) ON DELETE CASCADE,
    digest         boolean NOT NULL,
    status         text NOT NULL DEFAULT 'queued'
                   CHECK (status IN ('queued', 'sending', 'sent', 'failed', 'skipped')),
    due_at         timestamptz NOT NULL DEFAULT now(),
    attempts       integer NOT NULL DEFAULT 0,
    locked_by      text,
    locked_at      timestamptz,
    last_error     text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,                -- sent, given up, or skipped (the recipient turned email off)
    UNIQUE (subscriber_id, event_id)
);
CREATE INDEX notify_delivery_due_idx ON notify_delivery (due_at) WHERE status = 'queued';
CREATE INDEX notify_delivery_event_idx ON notify_delivery (event_id);

-- Every attempt to send an email, for admins (Settings › Sent emails), deleted after
-- kilnmark.notifications.log-retention (30 days). No foreign keys: the log outlives subscribers.
-- Never holds the email's text, only its subject.
CREATE TABLE notify_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    at             timestamptz NOT NULL DEFAULT now(),
    recipient      text NOT NULL,
    subscriber_id  bigint,
    kind           text NOT NULL,              -- an event kind, 'digest' or 'test'
    event_ids      bigint[] NOT NULL DEFAULT '{}',
    subject        text,
    status         text NOT NULL CHECK (status IN ('sent', 'retrying', 'failed', 'rate_limited')),
    attempt        integer NOT NULL DEFAULT 1,
    error          text CHECK (error IS NULL OR length(error) <= 2000)
);
CREATE INDEX notify_log_at_idx ON notify_log (at DESC);
CREATE INDEX notify_log_recipient_idx ON notify_log (recipient, at DESC) WHERE status = 'sent';

INSERT INTO install_info (key, value) VALUES ('schema_notifications', '13')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();
