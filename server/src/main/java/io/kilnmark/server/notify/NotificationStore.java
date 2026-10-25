package io.kilnmark.server.notify;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.kilnmark.server.Db;
import io.kilnmark.server.auth.AccessRole;
import jakarta.enterprise.context.ApplicationScoped;

/** Notifications' tables (migration V13): mail settings, subscribers, events, their state, deliveries and the log. */
@ApplicationScoped
public class NotificationStore {

    /** Advisory lock class held while one dedupe key's state is decided. */
    private static final int KEY_LOCK_CLASS = 0x4B4E;

    private final Db db;

    public NotificationStore(Db db) {
        this.db = db;
    }

    // ---- mail settings ----

    /** The saved mail settings, with the sealed password, if an admin has saved any. */
    public record SavedSettings(boolean enabled, String host, Integer port, MailSecurity security, String username,
                                byte[] password, String fromAddress, String fromName, String baseUrl, String updatedBy,
                                Instant updatedAt) {
    }

    public Optional<SavedSettings> settings() {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT enabled, host, port, security, username, password, "
                + "from_address, from_name, base_url, updated_by, updated_at FROM kilnmark.mail_settings WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SavedSettings(rs.getBoolean(1), rs.getString(2), integer(rs, 3),
                    MailSecurity.parse(rs.getString(4)), rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getString(10), Db.instant(rs.getTimestamp(11))));
            }
        });
    }

    /**
     * Saves the mail settings. {@code password}: the sealed password to store; {@code keep} true keeps
     * the stored one instead (password ignored), false with a null password removes it.
     */
    public void saveSettings(MailSettings.Valid v, byte[] password, boolean keep, String by) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.mail_settings (id, enabled, host, port, security, username, password, from_address,
                    from_name, base_url, updated_by, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, host = EXCLUDED.host, port = EXCLUDED.port,
                    security = EXCLUDED.security, username = EXCLUDED.username,
                    password = CASE WHEN ? THEN mail_settings.password ELSE EXCLUDED.password END,
                    from_address = EXCLUDED.from_address, from_name = EXCLUDED.from_name, base_url = EXCLUDED.base_url,
                    updated_by = EXCLUDED.updated_by, updated_at = now()""")) {
                ps.setBoolean(1, v.enabled());
                ps.setString(2, v.host());
                setInteger(ps, 3, v.port());
                ps.setString(4, v.security().id());
                ps.setString(5, v.username());
                ps.setBytes(6, keep ? null : password);
                ps.setString(7, v.fromAddress());
                ps.setString(8, v.fromName());
                ps.setString(9, v.baseUrl());
                ps.setString(10, by);
                ps.setBoolean(11, keep);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ---- subscribers ----

    /**
     * A recipient as stored: a person (by token, with the token's role and whether it still works)
     * or a team address (no token; written for a viewer).
     */
    public record Subscriber(long id, Long tokenId, String tokenName, AccessRole role, boolean tokenActive,
                             String teamName, Subscription subscription, String createdBy, Instant createdAt,
                             Instant updatedAt) {
        public boolean team() {
            return tokenId == null;
        }

        /** Whether email may go to this recipient now: a team address, or a person whose token still works. */
        public boolean reachable() {
            return team() || tokenActive;
        }

        public MailMessages.Recipient recipient() {
            return new MailMessages.Recipient(subscription.email(), team() ? AccessRole.VIEWER : role, team());
        }
    }

    private static final String SUBSCRIBER_COLUMNS = """
        s.id, s.token_id, t.name, t.role,
        (t.id IS NOT NULL AND t.revoked_at IS NULL AND (t.expires_at IS NULL OR t.expires_at > now())),
        s.team_name, s.email, s.enabled, s.kinds, s.min_severity, s.mode, s.digest_hour, s.sources, s.created_by,
        s.created_at, s.updated_at, s.in_app_kinds
        FROM kilnmark.notify_subscriber s LEFT JOIN kilnmark.api_token t ON t.id = s.token_id""";

    private static Set<NotificationKind> kinds(Array a) throws SQLException {
        Set<NotificationKind> kinds = EnumSet.noneOf(NotificationKind.class);
        for (String k : strings(a)) {
            try {
                kinds.add(NotificationKind.parse(k));
            } catch (IllegalArgumentException e) {
                // a kind this version does not know (a newer server wrote it): left out
            }
        }
        return kinds;
    }

    private static Subscriber subscriber(ResultSet rs) throws SQLException {
        Array sources = rs.getArray(13);
        Subscription sub = new Subscription(blankToNull(rs.getString(7)), rs.getBoolean(8), kinds(rs.getArray(9)),
            Severity.parse(rs.getString(10)), Subscription.Mode.parse(rs.getString(11)), rs.getInt(12),
            sources == null ? null : strings(sources), kinds(rs.getArray(17)));
        String role = rs.getString(4);
        return new Subscriber(rs.getLong(1), longOrNull(rs, 2), rs.getString(3), role == null ? null : AccessRole.parse(role),
            rs.getBoolean(5), rs.getString(6), sub, rs.getString(14), Db.instant(rs.getTimestamp(15)),
            Db.instant(rs.getTimestamp(16)));
    }

    public Optional<Subscriber> forToken(long tokenId) {
        return one("SELECT " + SUBSCRIBER_COLUMNS + " WHERE s.token_id = ?", ps -> ps.setLong(1, tokenId));
    }

    public Optional<Subscriber> subscriber(long id) {
        return one("SELECT " + SUBSCRIBER_COLUMNS + " WHERE s.id = ?", ps -> ps.setLong(1, id));
    }

    public List<Subscriber> teams() {
        return list("SELECT " + SUBSCRIBER_COLUMNS + " WHERE s.token_id IS NULL ORDER BY lower(s.team_name), s.id",
            ps -> { });
    }

    /** Every subscriber: candidates for an event, filtered by {@link Subscription#wants} and {@link Subscription#wantsInApp}. */
    List<Subscriber> all(Connection c) throws SQLException {
        List<Subscriber> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + SUBSCRIBER_COLUMNS);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(subscriber(rs));
            }
        }
        return out;
    }

    /** Saves a person's own subscription. */
    public Subscriber saveForToken(long tokenId, Subscription s, String by) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.notify_subscriber (token_id, email, enabled, kinds, min_severity, mode, digest_hour,
                    sources, in_app_kinds, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (token_id) DO UPDATE SET email = EXCLUDED.email, enabled = EXCLUDED.enabled,
                    kinds = EXCLUDED.kinds, min_severity = EXCLUDED.min_severity, mode = EXCLUDED.mode,
                    digest_hour = EXCLUDED.digest_hour, sources = EXCLUDED.sources, in_app_kinds = EXCLUDED.in_app_kinds,
                    updated_at = now()""")) {
                ps.setLong(1, tokenId);
                bind(c, ps, 2, s);
                ps.setString(10, by);
                ps.executeUpdate();
            }
            return null;
        });
        return forToken(tokenId).orElseThrow();
    }

    public Subscriber addTeam(String name, Subscription s, String by) {
        long id = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.notify_subscriber (team_name, email, enabled, kinds, min_severity, mode, digest_hour,
                    sources, in_app_kinds, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setString(1, name);
                bind(c, ps, 2, s.withoutInApp());
                ps.setString(10, by);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        return subscriber(id).orElseThrow();
    }

    public Optional<Subscriber> updateTeam(long id, String name, Subscription s) {
        int n = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kilnmark.notify_subscriber SET team_name = ?, email = ?, enabled = ?, kinds = ?, min_severity = ?,
                    mode = ?, digest_hour = ?, sources = ?, in_app_kinds = ?, updated_at = now()
                WHERE id = ? AND token_id IS NULL""")) {
                ps.setString(1, name);
                bind(c, ps, 2, s.withoutInApp());
                ps.setLong(10, id);
                return ps.executeUpdate();
            }
        });
        return n == 0 ? Optional.empty() : subscriber(id);
    }

    public boolean deleteTeam(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM kilnmark.notify_subscriber WHERE id = ? AND token_id IS NULL")) {
                ps.setLong(1, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Binds email, enabled, kinds, min_severity, mode, digest_hour, sources and in_app_kinds from {@code at}. */
    private static void bind(Connection c, PreparedStatement ps, int at, Subscription s) throws SQLException {
        ps.setString(at, s.email());
        ps.setBoolean(at + 1, s.enabled());
        ps.setArray(at + 2, c.createArrayOf("text", s.kinds().stream().map(NotificationKind::id).sorted().toArray()));
        ps.setString(at + 3, s.minSeverity().id());
        ps.setString(at + 4, s.mode().id());
        ps.setInt(at + 5, s.digestHour());
        if (s.sources() == null) {
            ps.setNull(at + 6, Types.ARRAY);
        } else {
            ps.setArray(at + 6, c.createArrayOf("text", s.sources().toArray()));
        }
        ps.setArray(at + 7, c.createArrayOf("text", s.inAppKinds().stream().map(NotificationKind::id).sorted().toArray()));
    }

    // ---- events, dedupe and fan-out ----

    /** What recording an event did: whether it was worth telling, to how many by email and how many in the app. */
    public record Recorded(boolean sent, Long eventId, int deliveries, int inApp) {
    }

    /** A person who can sign in: a token that still works, with its role. */
    private record Person(long tokenId, AccessRole role) {
    }

    private static List<Person> people(Connection c) throws SQLException {
        List<Person> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, role FROM kilnmark.api_token "
            + "WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Person(rs.getLong(1), AccessRole.parse(rs.getString(2))));
            }
        }
        return out;
    }

    /**
     * Records an event in one transaction: decides with {@link Dedupe} against what was said before
     * about its key, keeps the new state and, when it is worth telling: stores it, adds an in-app
     * notification, written for their role by {@code inApp}, for every person who wants it (their
     * saved choices, or the defaults), and queues an email for every subscriber who wants one, at
     * once or in their next digest. {@code deliver} false (email not set up) queues no email.
     */
    public Recorded record(NotificationEvent e, boolean deliver, ZoneId zone, Instant now, InAppWriter inApp) {
        return db.tx(c -> {
            lock(c, e.dedupeKey());
            Dedupe.State current = state(c, e.dedupeKey());
            Dedupe.Decision d = Dedupe.decide(e.kind(), e.severity(), current);
            if (!d.send()) {
                return new Recorded(false, null, 0, 0);
            }
            NotificationEvent mailed = d.severity() == e.severity() ? e : new NotificationEvent(e.kind(), d.severity(),
                e.subjectUrn(), e.title(), e.detail(), e.link(), e.source(), e.field(), e.restrictedDetail(),
                e.dedupeKey(), e.occurredAt());
            long id = insertEvent(c, mailed);
            saveState(c, e.dedupeKey(), d.next(), id);
            List<Subscriber> subscribers = all(c);
            Map<Long, Subscriber> byToken = new HashMap<>();
            subscribers.stream().filter(s -> !s.team()).forEach(s -> byToken.put(s.tokenId(), s));
            int shown = 0;
            for (Person p : people(c)) {
                Subscriber s = byToken.get(p.tokenId());
                Subscription sub = s == null ? Subscription.defaults(p.role()) : s.subscription();
                if (sub.wantsInApp(mailed.kind(), mailed.source())) {
                    InAppWriter.Item item = inApp.write(mailed, p.role());
                    insertInbox(c, p.tokenId(), id, mailed.kind(), item);
                    shown++;
                }
            }
            int n = 0;
            if (deliver) {
                for (Subscriber s : subscribers) {
                    Subscription sub = s.subscription();
                    if (!s.reachable() || !sub.wants(mailed.kind(), mailed.severity(), mailed.source())) {
                        continue;
                    }
                    boolean digest = sub.mode() == Subscription.Mode.DIGEST;
                    queue(c, s.id(), id, digest, digest ? Digest.nextAt(now, sub.digestHour(), zone) : now);
                    n++;
                }
            }
            return new Recorded(true, id, n, shown);
        });
    }

    /** Writes an event's in-app notification for one role (see {@link InAppFeed#write}). */
    @FunctionalInterface
    public interface InAppWriter {
        /** What one person sees: written for their role. */
        record Item(Severity severity, String title, String detail, String link) {
        }

        Item write(NotificationEvent e, AccessRole role);
    }

    private static void insertInbox(Connection c, long token, long event, NotificationKind kind, InAppWriter.Item item)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.notify_inbox (token_id, event_id, kind, "
            + "severity, title, detail, link) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (token_id, event_id) DO NOTHING")) {
            ps.setLong(1, token);
            ps.setLong(2, event);
            ps.setString(3, kind.id());
            ps.setString(4, item.severity().id());
            ps.setString(5, item.title());
            ps.setString(6, item.detail() == null ? null : NotificationEvent.clip(item.detail(), 8000));
            ps.setString(7, item.link());
            ps.executeUpdate();
        }
    }

    /** Closes a problem quietly (a scan that works again): the next failure is mailed. */
    public void clear(String key) {
        db.tx(c -> {
            lock(c, key);
            Dedupe.State current = state(c, key);
            Dedupe.State next = Dedupe.clear(current);
            if (next != current) {
                saveState(c, key, next, null);
            }
            return null;
        });
    }

    private static void lock(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            ps.setInt(1, KEY_LOCK_CLASS);
            ps.setString(2, key);
            ps.execute();
        }
    }

    Dedupe.State state(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT open, severity FROM kilnmark.notify_state WHERE dedupe_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Dedupe.State(rs.getBoolean(1), Severity.parse(rs.getString(2))) : null;
            }
        }
    }

    private static void saveState(Connection c, String key, Dedupe.State s, Long eventId) throws SQLException {
        if (s == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO kilnmark.notify_state (dedupe_key, open, severity, event_id) VALUES (?, ?, ?, ?)
            ON CONFLICT (dedupe_key) DO UPDATE SET open = EXCLUDED.open, severity = EXCLUDED.severity,
                event_id = coalesce(EXCLUDED.event_id, notify_state.event_id), updated_at = now()""")) {
            ps.setString(1, key);
            ps.setBoolean(2, s.open());
            ps.setString(3, s.severity().id());
            setLong(ps, 4, eventId);
            ps.executeUpdate();
        }
    }

    private static long insertEvent(Connection c, NotificationEvent e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO kilnmark.notify_event (kind, severity, source, subject_urn, field_name, title, detail,
                restricted_detail, link, dedupe_key, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
            ps.setString(1, e.kind().id());
            ps.setString(2, e.severity().id());
            ps.setString(3, e.source());
            ps.setString(4, e.subjectUrn());
            ps.setString(5, e.field());
            ps.setString(6, e.title());
            ps.setString(7, e.detail());
            ps.setString(8, e.restrictedDetail());
            ps.setString(9, e.link());
            ps.setString(10, e.dedupeKey());
            ps.setTimestamp(11, Db.timestamp(e.occurredAt()));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void queue(Connection c, long subscriber, long event, boolean digest, Instant due) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.notify_delivery (subscriber_id, event_id, "
            + "digest, due_at) VALUES (?, ?, ?, ?) ON CONFLICT (subscriber_id, event_id) DO NOTHING")) {
            ps.setLong(1, subscriber);
            ps.setLong(2, event);
            ps.setBoolean(3, digest);
            ps.setTimestamp(4, Db.timestamp(due));
            ps.executeUpdate();
        }
    }

    private static final String EVENT_COLUMNS = "id, kind, severity, subject_urn, title, detail, link, source, field_name, "
        + "restricted_detail, dedupe_key, occurred_at";

    private static NotificationEvent event(ResultSet rs) throws SQLException {
        return new NotificationEvent(NotificationKind.parse(rs.getString(2)), Severity.parse(rs.getString(3)),
            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
            rs.getString(10), rs.getString(11), Db.instant(rs.getTimestamp(12)));
    }

    /** Events by id. */
    public Map<Long, NotificationEvent> events(List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return db.tx(c -> {
            Map<Long, NotificationEvent> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + EVENT_COLUMNS
                + " FROM kilnmark.notify_event WHERE id = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getLong(1), event(rs));
                    }
                }
            }
            return out;
        });
    }

    // ---- deliveries (workers) ----

    /** A delivery a worker has claimed; {@code attempts} counts this one. */
    public record Claimed(long id, long subscriberId, long eventId, int attempts) {
    }

    /** Puts back deliveries whose worker stopped while sending (claimed longer ago than {@code after}). */
    public int requeueStale(Duration after) {
        return update("UPDATE kilnmark.notify_delivery SET status = 'queued', locked_by = NULL, locked_at = NULL "
            + "WHERE status = 'sending' AND locked_at < now() - make_interval(secs => ?)",
            ps -> ps.setDouble(1, after.toMillis() / 1000.0));
    }

    /** Claims up to {@code limit} due deliveries to be sent on their own. */
    public List<Claimed> claimImmediate(String worker, int limit) {
        return claim("""
            UPDATE kilnmark.notify_delivery SET status = 'sending', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id IN (SELECT id FROM kilnmark.notify_delivery
                         WHERE status = 'queued' AND NOT digest AND due_at <= now()
                         ORDER BY due_at, id FOR UPDATE SKIP LOCKED LIMIT ?)
            RETURNING id, subscriber_id, event_id, attempts""", ps -> {
                ps.setString(1, worker);
                ps.setInt(2, limit);
            });
    }

    /** Subscribers with a digest due now, at most {@code limit}. */
    public List<Long> digestsDue(int limit) {
        return list("SELECT subscriber_id FROM kilnmark.notify_delivery WHERE status = 'queued' AND digest "
            + "AND due_at <= now() GROUP BY subscriber_id ORDER BY min(due_at) LIMIT ?", ps -> ps.setInt(1, limit),
            rs -> rs.getLong(1));
    }

    /** Claims every due digest delivery of one subscriber. */
    public List<Claimed> claimDigest(String worker, long subscriber) {
        return claim("""
            UPDATE kilnmark.notify_delivery SET status = 'sending', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id IN (SELECT id FROM kilnmark.notify_delivery
                         WHERE subscriber_id = ? AND status = 'queued' AND digest AND due_at <= now()
                         ORDER BY id FOR UPDATE SKIP LOCKED)
            RETURNING id, subscriber_id, event_id, attempts""", ps -> {
                ps.setString(1, worker);
                ps.setLong(2, subscriber);
            });
    }

    private List<Claimed> claim(String sql, Binder binder) {
        return list(sql, binder, rs -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)));
    }

    public void sent(List<Long> ids) {
        update("UPDATE kilnmark.notify_delivery SET status = 'sent', finished_at = now(), locked_by = NULL, locked_at = NULL, "
            + "last_error = NULL WHERE id = ANY (?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint",
                ids.toArray())));
    }

    /** Puts claimed deliveries back without counting the attempt (the worker is stopping). */
    public void release(List<Long> ids) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', attempts = greatest(attempts - 1, 0), "
            + "locked_by = NULL, locked_at = NULL WHERE id = ANY (?) AND status = 'sending'",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray())));
    }

    /** Due again at {@code due}, after a failure. */
    public void retry(List<Long> ids, Instant due, String error) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', due_at = ?, last_error = ?, locked_by = NULL, "
            + "locked_at = NULL WHERE id = ANY (?)", ps -> {
                ps.setTimestamp(1, Db.timestamp(due));
                ps.setString(2, error);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", ids.toArray()));
            });
    }

    /** Given up. */
    public void failed(List<Long> ids, String error) {
        finish(ids, "failed", error);
    }

    /** Not sent, and never will be: the recipient turned email off, or their token stopped working, meanwhile. */
    public void skipped(List<Long> ids, String why) {
        finish(ids, "skipped", why);
    }

    private void finish(List<Long> ids, String status, String error) {
        update("UPDATE kilnmark.notify_delivery SET status = ?, last_error = ?, finished_at = now(), locked_by = NULL, "
            + "locked_at = NULL WHERE id = ANY (?)", ps -> {
                ps.setString(1, status);
                ps.setString(2, error);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", ids.toArray()));
            });
    }

    /** Over the rate limit: waits for the recipient's digest at {@code due}, without counting the attempt. */
    public void toDigest(long id, Instant due) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', digest = true, due_at = ?, "
            + "attempts = greatest(attempts - 1, 0), locked_by = NULL, locked_at = NULL WHERE id = ?", ps -> {
                ps.setTimestamp(1, Db.timestamp(due));
                ps.setLong(2, id);
            });
    }

    // ---- the log ----

    /** One attempt to send, as admins see it. */
    public record LogEntry(long id, Instant at, String recipient, Long subscriberId, String kind, List<Long> eventIds,
                           String subject, String status, int attempt, String error) {
    }

    public void log(String recipient, Long subscriber, String kind, List<Long> events, String subject, String status,
                    int attempt, String error) {
        update("INSERT INTO kilnmark.notify_log (recipient, subscriber_id, kind, event_ids, subject, status, attempt, "
            + "error) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", ps -> {
                ps.setString(1, recipient);
                setLong(ps, 2, subscriber);
                ps.setString(3, kind);
                ps.setArray(4, ps.getConnection().createArrayOf("bigint", events.toArray()));
                ps.setString(5, subject);
                ps.setString(6, status);
                ps.setInt(7, attempt);
                ps.setString(8, error == null ? null : NotificationEvent.clip(error, 2000));
            });
    }

    /** Emails sent to {@code recipient} on their own (not digests or tests) since {@code since}. */
    public long sentSince(String recipient, Instant since) {
        return list("SELECT count(*) FROM kilnmark.notify_log WHERE recipient = ? AND status = 'sent' "
            + "AND kind NOT IN ('digest', 'test') AND at > ?", ps -> {
                ps.setString(1, recipient);
                ps.setTimestamp(2, Db.timestamp(since));
            }, rs -> rs.getLong(1)).get(0);
    }

    /** The log, newest first; {@code status} null for every status. */
    public List<LogEntry> log(String status, int limit) {
        return list("SELECT id, at, recipient, subscriber_id, kind, event_ids, subject, status, attempt, error "
            + "FROM kilnmark.notify_log WHERE (?::text IS NULL OR status = ?) ORDER BY at DESC, id DESC LIMIT ?", ps -> {
                ps.setString(1, status);
                ps.setString(2, status);
                ps.setInt(3, limit);
            }, rs -> {
                List<Long> ids = new ArrayList<>();
                Array a = rs.getArray(6);
                if (a != null) {
                    for (Object o : (Object[]) a.getArray()) {
                        ids.add(((Number) o).longValue());
                    }
                }
                return new LogEntry(rs.getLong(1), Db.instant(rs.getTimestamp(2)), rs.getString(3), longOrNull(rs, 4),
                    rs.getString(5), ids, rs.getString(7), rs.getString(8), rs.getInt(9), rs.getString(10));
            });
    }

    /**
     * Deletes in-app notifications older than {@code inAppAge}, and log entries, closed problems and
     * events with nothing left to send or show, older than {@code age}.
     */
    public int purge(Duration age, Duration inAppAge) {
        double secs = age.toMillis() / 1000.0;
        return db.tx(c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM kilnmark.notify_inbox WHERE created_at < now() - make_interval(secs => ?)")) {
                ps.setDouble(1, inAppAge.toMillis() / 1000.0);
                n += ps.executeUpdate();
            }
            for (String sql : List.of(
                "DELETE FROM kilnmark.notify_log WHERE at < now() - make_interval(secs => ?)",
                "DELETE FROM kilnmark.notify_state WHERE NOT open AND updated_at < now() - make_interval(secs => ?)",
                """
                DELETE FROM kilnmark.notify_event e WHERE e.occurred_at < now() - make_interval(secs => ?)
                  AND NOT EXISTS (SELECT 1 FROM kilnmark.notify_delivery d
                                  WHERE d.event_id = e.id AND d.status IN ('queued', 'sending'))
                  AND NOT EXISTS (SELECT 1 FROM kilnmark.notify_inbox i WHERE i.event_id = e.id)""")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setDouble(1, secs);
                    n += ps.executeUpdate();
                }
            }
            return n;
        });
    }

    // ---- the in-app feed ----

    /** One in-app notification, as stored for its person. */
    public record InboxRow(long id, Long eventId, String kind, String severity, String title, String detail, String link,
                           Instant createdAt, boolean read) {
    }

    /** What happened to an email about an event: {@code queued}, {@code sent}, {@code failed} or {@code skipped}, and when. */
    public record EmailStatus(String status, Instant at) {
    }

    /** A team address's email about an event, by the team's name (never its address). */
    public record TeamEmail(String name, String status) {
    }

    /** A person's notifications, newest first; only unread ones when {@code unreadOnly}. */
    public List<InboxRow> inbox(long tokenId, boolean unreadOnly, int limit) {
        return list("SELECT id, event_id, kind, severity, title, detail, link, created_at, read FROM kilnmark.notify_inbox "
            + "WHERE token_id = ? AND (NOT ? OR NOT read) ORDER BY created_at DESC, id DESC LIMIT ?", ps -> {
                ps.setLong(1, tokenId);
                ps.setBoolean(2, unreadOnly);
                ps.setInt(3, limit);
            }, rs -> new InboxRow(rs.getLong(1), longOrNull(rs, 2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), Db.instant(rs.getTimestamp(8)), rs.getBoolean(9)));
    }

    /** How many of a person's notifications are unread (an index-only count). */
    public long unread(long tokenId) {
        return list("SELECT count(*) FROM kilnmark.notify_inbox WHERE token_id = ? AND NOT read",
            ps -> ps.setLong(1, tokenId), rs -> rs.getLong(1)).get(0);
    }

    /** Marks some of a person's notifications read; ids that are not theirs are left alone. Returns how many changed. */
    public int markRead(long tokenId, List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return update("UPDATE kilnmark.notify_inbox SET read = true, read_at = now() WHERE token_id = ? "
            + "AND id = ANY (?) AND NOT read", ps -> {
                ps.setLong(1, tokenId);
                ps.setArray(2, ps.getConnection().createArrayOf("bigint", ids.toArray()));
            });
    }

    /** Marks every one of a person's notifications read. */
    public int markAllRead(long tokenId) {
        return update("UPDATE kilnmark.notify_inbox SET read = true, read_at = now() WHERE token_id = ? AND NOT read",
            ps -> ps.setLong(1, tokenId));
    }

    /** What became of this person's own emails about these events, by event. */
    public Map<Long, EmailStatus> emails(long tokenId, List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, EmailStatus> out = new HashMap<>();
        list("""
            SELECT d.event_id, d.status, coalesce(d.finished_at, d.due_at)
            FROM kilnmark.notify_delivery d JOIN kilnmark.notify_subscriber s ON s.id = d.subscriber_id
            WHERE s.token_id = ? AND d.event_id = ANY (?)""", ps -> {
                ps.setLong(1, tokenId);
                ps.setArray(2, ps.getConnection().createArrayOf("bigint", eventIds.toArray()));
            }, rs -> out.put(rs.getLong(1), new EmailStatus(feedStatus(rs.getString(2)), Db.instant(rs.getTimestamp(3)))));
        return out;
    }

    /** What became of team addresses' emails about these events, by event: names and statuses only. */
    public Map<Long, List<TeamEmail>> teamEmails(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TeamEmail>> out = new HashMap<>();
        list("""
            SELECT d.event_id, s.team_name, d.status
            FROM kilnmark.notify_delivery d JOIN kilnmark.notify_subscriber s ON s.id = d.subscriber_id
            WHERE s.token_id IS NULL AND d.event_id = ANY (?) ORDER BY lower(s.team_name), s.id""",
            ps -> ps.setArray(1, ps.getConnection().createArrayOf("bigint", eventIds.toArray())),
            rs -> out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                .add(new TeamEmail(rs.getString(2), feedStatus(rs.getString(3)))));
        return out;
    }

    /** A delivery's status as the feed shows it: one being sent right now still counts as queued. */
    static String feedStatus(String status) {
        return "sending".equals(status) ? "queued" : status;
    }

    // ---- helpers ----

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private <T> List<T> list(String sql, Binder binder, Reader<T> reader) {
        return db.tx(c -> {
            List<T> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(reader.read(rs));
                    }
                }
            }
            return out;
        });
    }

    private List<Subscriber> list(String sql, Binder binder) {
        return list(sql, binder, NotificationStore::subscriber);
    }

    private Optional<Subscriber> one(String sql, Binder binder) {
        return list(sql, binder).stream().findFirst();
    }

    private int update(String sql, Binder binder) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                return ps.executeUpdate();
            }
        });
    }

    private static List<String> strings(Array a) throws SQLException {
        if (a == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) a.getArray()).map(String::valueOf).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Long longOrNull(ResultSet rs, int i) throws SQLException {
        long v = rs.getLong(i);
        return rs.wasNull() ? null : v;
    }

    private static Integer integer(ResultSet rs, int i) throws SQLException {
        int v = rs.getInt(i);
        return rs.wasNull() ? null : v;
    }

    private static void setLong(PreparedStatement ps, int i, Long v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.BIGINT);
        } else {
            ps.setLong(i, v);
        }
    }

    private static void setInteger(PreparedStatement ps, int i, Integer v) throws SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }
}
