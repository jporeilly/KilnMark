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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.kilnmark.server.Db;
import io.kilnmark.server.auth.AccessRole;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Notifications' tables (migration V13): mail settings, subscribers, the people who use the app,
 * events, their dedupe state, in-app notifications, deliveries and the send log.
 */
@ApplicationScoped
public class NotificationStore {

    /** Advisory lock class held while one dedupe key's state is decided. */
    private static final int KEY_LOCK_CLASS = 0x4B4E;
    /** How long a confirmation code stays valid. */
    static final Duration CONFIRM_FOR = Duration.ofDays(7);

    private final Db db;

    /** Whether a token works now, as sign-in judges it (ADR 0037); unset in tests that build this by hand. */
    @jakarta.inject.Inject
    io.kilnmark.server.auth.ApiTokens tokens;

    /** SQL true while token {@code t} works: revoked, expired and past the longest lifetime all stop it. */
    private String works(String t) {
        return tokens == null ? "(" + t + ".revoked_at IS NULL AND (" + t + ".expires_at IS NULL OR " + t
            + ".expires_at > now()))" : tokens.worksSql(t);
    }

    public NotificationStore(Db db) {
        this.db = db;
    }

    // ---- mail settings ----

    /** The saved mail settings, with the sealed password, if an admin has saved any. */
    public record SavedSettings(boolean enabled, String host, Integer port, MailSecurity security, String username,
                                byte[] password, String fromAddress, String fromName, String baseUrl,
                                boolean allowPlainAuth, List<String> allowedDomains, boolean requireConfirmation,
                                int maxAttempts, int rateLimitPerHour, int undeliveredHours, String undeliveredAction,
                                String updatedBy, Instant updatedAt) {
    }

    public Optional<SavedSettings> settings() {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT enabled, host, port, security, username, password, "
                + "from_address, from_name, base_url, allow_plain_auth, allowed_domains, require_confirmation, "
                + "max_attempts, rate_limit_per_hour, undelivered_hours, undelivered_action, updated_by, updated_at "
                + "FROM kilnmark.mail_settings WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new SavedSettings(rs.getBoolean(1), rs.getString(2), integer(rs, 3),
                    MailSecurity.parse(rs.getString(4)), rs.getString(5), rs.getBytes(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getBoolean(10), strings(rs.getArray(11)), rs.getBoolean(12), rs.getInt(13),
                    rs.getInt(14), rs.getInt(15), rs.getString(16), rs.getString(17), Db.instant(rs.getTimestamp(18))));
            }
        });
    }

    /** Saves the mail settings; {@code password} is the sealed password for {@link MailPolicy.PasswordAction#REPLACE}. */
    public void saveSettings(MailSettings.Valid v, MailPolicy.PasswordAction action, byte[] password, String by) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.mail_settings (id, enabled, host, port, security, username, password, from_address,
                    from_name, base_url, allow_plain_auth, allowed_domains, require_confirmation, max_attempts,
                    rate_limit_per_hour, undelivered_hours, undelivered_action, updated_by, updated_at)
                VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, host = EXCLUDED.host, port = EXCLUDED.port,
                    security = EXCLUDED.security, username = EXCLUDED.username,
                    password = CASE WHEN ? THEN mail_settings.password ELSE EXCLUDED.password END,
                    from_address = EXCLUDED.from_address, from_name = EXCLUDED.from_name, base_url = EXCLUDED.base_url,
                    allow_plain_auth = EXCLUDED.allow_plain_auth, allowed_domains = EXCLUDED.allowed_domains,
                    require_confirmation = EXCLUDED.require_confirmation, max_attempts = EXCLUDED.max_attempts,
                    rate_limit_per_hour = EXCLUDED.rate_limit_per_hour, undelivered_hours = EXCLUDED.undelivered_hours,
                    undelivered_action = EXCLUDED.undelivered_action, updated_by = EXCLUDED.updated_by,
                    updated_at = now()""")) {
                boolean keep = action == MailPolicy.PasswordAction.KEEP;
                ps.setBoolean(1, v.enabled());
                ps.setString(2, v.host());
                setInteger(ps, 3, v.port());
                ps.setString(4, v.security().id());
                ps.setString(5, v.username());
                ps.setBytes(6, action == MailPolicy.PasswordAction.REPLACE ? password : null);
                ps.setString(7, v.fromAddress());
                ps.setString(8, v.fromName());
                ps.setString(9, v.baseUrl());
                ps.setBoolean(10, v.allowPlainAuth());
                ps.setArray(11, c.createArrayOf("text", v.allowedDomains().toArray()));
                ps.setBoolean(12, v.requireConfirmation());
                ps.setInt(13, v.maxAttempts());
                ps.setInt(14, v.rateLimitPerHour());
                ps.setInt(15, v.undeliveredHours());
                ps.setString(16, v.undeliveredAction());
                ps.setString(17, by);
                ps.setBoolean(18, keep);
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
                             Instant updatedAt, Instant confirmedAt, Instant confirmSentAt, String paused) {
        public boolean team() {
            return tokenId == null;
        }

        /** Whether email may go to this recipient now: a team address, or a person whose token still works. */
        public boolean reachable() {
            return team() || tokenActive;
        }

        /** Whether the address is confirmed: team addresses need no confirmation. */
        public boolean confirmed() {
            return team() || confirmedAt != null;
        }

        /** Why email cannot go to this recipient now, or null when it can. */
        public String undeliverable(boolean requireConfirmation, List<String> allowedDomains) {
            if (!reachable()) {
                return "The recipient's API token was revoked or has expired";
            }
            if (!subscription.enabled() || subscription.email() == null) {
                return "The recipient turned email off";
            }
            if (requireConfirmation && !confirmed()) {
                return "The address is not confirmed yet";
            }
            if (paused != null || !MailPolicy.domainAllowed(subscription.email(), allowedDomains)) {
                return "The address is outside the allowed email domains";
            }
            return null;
        }

        public MailMessages.Recipient recipient() {
            return new MailMessages.Recipient(subscription.email(), team() ? AccessRole.VIEWER : role, team());
        }
    }

    /** The subscriber columns, with whether its token works now as sign-in judges it ({@link #works}). */
    private String subscriberColumns() {
        return SUBSCRIBER_COLUMNS.replace("TOKEN_WORKS", works("t"));
    }

    private static final String SUBSCRIBER_COLUMNS = """
        s.id, s.token_id, t.name, t.role,
        (t.id IS NOT NULL AND TOKEN_WORKS),
        s.team_name, s.email, s.enabled, s.kinds, s.min_severity, s.mode, s.digest_hour, s.sources, s.created_by,
        s.created_at, s.updated_at, s.in_app_kinds, s.email_confirmed_at, s.confirm_sent_at, s.paused
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
            Db.instant(rs.getTimestamp(16)), Db.instant(rs.getTimestamp(18)), Db.instant(rs.getTimestamp(19)),
            rs.getString(20));
    }

    public Optional<Subscriber> forToken(long tokenId) {
        return one("SELECT " + subscriberColumns() + " WHERE s.token_id = ?", ps -> ps.setLong(1, tokenId));
    }

    public Optional<Subscriber> subscriber(long id) {
        return one("SELECT " + subscriberColumns() + " WHERE s.id = ?", ps -> ps.setLong(1, id));
    }

    public List<Subscriber> teams() {
        return list("SELECT " + subscriberColumns() + " WHERE s.token_id IS NULL ORDER BY lower(s.team_name), s.id",
            ps -> { });
    }

    /** Addresses paused for now, people's and teams'. */
    public List<Subscriber> paused() {
        return list("SELECT " + subscriberColumns() + " WHERE s.paused IS NOT NULL ORDER BY s.id", ps -> { });
    }

    private List<Subscriber> all(Connection c) throws SQLException {
        List<Subscriber> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT " + subscriberColumns());
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(subscriber(rs));
            }
        }
        return out;
    }

    /**
     * Saves a person's own subscription. A changed address is unconfirmed again, and loses any pause
     * (the domain is checked before saving).
     */
    public Subscriber saveForToken(long tokenId, Subscription s, String by) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.notify_subscriber (token_id, email, enabled, kinds, min_severity, mode, digest_hour,
                    sources, in_app_kinds, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (token_id) DO UPDATE SET email = EXCLUDED.email, enabled = EXCLUDED.enabled,
                    kinds = EXCLUDED.kinds, min_severity = EXCLUDED.min_severity, mode = EXCLUDED.mode,
                    digest_hour = EXCLUDED.digest_hour, sources = EXCLUDED.sources, in_app_kinds = EXCLUDED.in_app_kinds,
                    email_confirmed_at = CASE WHEN notify_subscriber.email IS NOT DISTINCT FROM EXCLUDED.email
                        THEN notify_subscriber.email_confirmed_at END,
                    confirm_hash = CASE WHEN notify_subscriber.email IS NOT DISTINCT FROM EXCLUDED.email
                        THEN notify_subscriber.confirm_hash END,
                    confirm_sent_at = CASE WHEN notify_subscriber.email IS NOT DISTINCT FROM EXCLUDED.email
                        THEN notify_subscriber.confirm_sent_at END,
                    paused = CASE WHEN notify_subscriber.email IS NOT DISTINCT FROM EXCLUDED.email
                        THEN notify_subscriber.paused END,
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

    /** Records that a confirmation code (its SHA-256) was sent to a person's address. */
    public void confirmationSent(long tokenId, byte[] hash) {
        update("UPDATE kilnmark.notify_subscriber SET confirm_hash = ?, confirm_sent_at = now() WHERE token_id = ?",
            ps -> {
                ps.setBytes(1, hash);
                ps.setLong(2, tokenId);
            });
    }

    /** Confirms a person's address if {@code hash} is the code sent to it, within 7 days. */
    public boolean confirm(long tokenId, byte[] hash) {
        return update("UPDATE kilnmark.notify_subscriber SET email_confirmed_at = now(), confirm_hash = NULL "
            + "WHERE token_id = ? AND confirm_hash = ? AND confirm_sent_at > now() - make_interval(secs => ?)", ps -> {
                ps.setLong(1, tokenId);
                ps.setBytes(2, hash);
                ps.setDouble(3, CONFIRM_FOR.toSeconds());
            }) == 1;
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
                    mode = ?, digest_hour = ?, sources = ?, in_app_kinds = ?, paused = NULL, updated_at = now()
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

    /**
     * After the allowed domains change: pauses every address outside them (people's and teams'),
     * and lifts the pause from those now inside. Returns the addresses now paused.
     */
    public List<Subscriber> pauseOutside(List<String> domains) {
        db.tx(c -> {
            List<Long> pause = new ArrayList<>();
            List<Long> lift = new ArrayList<>();
            for (Subscriber s : all(c)) {
                String email = s.subscription().email();
                boolean outside = email != null && !MailPolicy.domainAllowed(email, domains);
                if (outside && s.paused() == null) {
                    pause.add(s.id());
                } else if (!outside && s.paused() != null) {
                    lift.add(s.id());
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.notify_subscriber SET paused = ? WHERE id = ANY (?)")) {
                ps.setString(1, "domain");
                ps.setArray(2, c.createArrayOf("bigint", pause.toArray()));
                ps.executeUpdate();
                ps.setNull(1, Types.VARCHAR);
                ps.setArray(2, c.createArrayOf("bigint", lift.toArray()));
                ps.executeUpdate();
            }
            return null;
        });
        return paused();
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

    // ---- people ----

    /** Notes that a token is used by a person in the app (at most one write an hour). */
    public void seen(long tokenId) {
        update("""
            INSERT INTO kilnmark.notify_person (token_id) VALUES (?)
            ON CONFLICT (token_id) DO UPDATE SET last_seen_at = now()
            WHERE notify_person.last_seen_at < now() - interval '1 hour'""", ps -> ps.setLong(1, tokenId));
    }

    // ---- events, dedupe and fan-out ----

    /** What recording an event did: whether it was worth telling, to how many by email and how many in the app. */
    public record Recorded(boolean sent, Long eventId, int deliveries, int inApp) {
    }

    /** How to record: whether email can go, the rules that gate it, and who counts as a person in the app. */
    public record Options(boolean deliver, boolean requireConfirmation, List<String> allowedDomains, ZoneId zone,
                          Duration personActiveFor) {
    }

    /**
     * Records an event in one transaction on one connection: decides with {@link Dedupe} against what
     * was said before about its key, keeps the new state and, when it is worth telling: stores it,
     * looks up once what it touches that is marked sensitive, adds the in-app notification for every
     * person who wants it (one INSERT ... SELECT, the detail written per role), and queues an email
     * for every subscriber who wants one and may get it, at once or in their next digest.
     */
    public Recorded record(NotificationEvent e, Options o, Instant now) {
        return db.tx(c -> {
            lock(c, e.dedupeKey());
            Dedupe.State current = state(c, e.dedupeKey());
            Dedupe.Decision d = Dedupe.decide(e.kind(), e.severity(), current);
            if (!d.send()) {
                return new Recorded(false, null, 0, 0);
            }
            NotificationEvent told = d.severity() == e.severity() ? e : new NotificationEvent(e.kind(), d.severity(),
                e.subjectUrn(), e.title(), e.detail(), e.link(), e.source(), e.field(), e.restrictedDetail(),
                e.dedupeKey(), e.occurredAt());
            long id = insertEvent(c, told);
            saveState(c, e.dedupeKey(), d.next(), id);
            Exposure exposure = exposure(c, told);
            int shown = inbox(c, id, told, exposure, o.personActiveFor());
            int n = 0;
            if (o.deliver()) {
                for (Subscriber s : all(c)) {
                    Subscription sub = s.subscription();
                    if (s.undeliverable(o.requireConfirmation(), o.allowedDomains()) != null
                        || !sub.wants(told.kind(), told.severity(), told.source())) {
                        continue;
                    }
                    boolean digest = sub.mode() == Subscription.Mode.DIGEST;
                    queue(c, s.id(), id, digest, digest ? Digest.nextAt(now, sub.digestHour(), o.zone()) : now);
                    n++;
                }
            }
            return new Recorded(true, id, n, shown);
        });
    }

    /** What an event touches that is marked sensitive: its asset's columns, and whether its source has any. */
    static Exposure exposure(Connection c, NotificationEvent e) throws SQLException {
        Set<String> marks = new HashSet<>();
        if (e.subjectUrn() != null) {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT name FROM kilnmark.sensitive_column WHERE asset_urn = ?")) {
                ps.setString(1, e.subjectUrn());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        marks.add(rs.getString(1));
                    }
                }
            }
        }
        boolean sourceMarked = false;
        if (e.source() != null) {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.sensitive_column m "
                + "JOIN kilnmark.asset a ON a.urn = m.asset_urn JOIN kilnmark.data_source s ON s.id = a.source_id "
                + "WHERE s.name = ? LIMIT 1")) {
                ps.setString(1, e.source());
                try (ResultSet rs = ps.executeQuery()) {
                    sourceMarked = rs.next();
                }
            }
        }
        return new Exposure(marks, sourceMarked);
    }

    /** {@link #exposure(Connection, NotificationEvent)} on a connection of its own, for the email worker. */
    public Exposure exposure(NotificationEvent e) {
        return db.tx(c -> exposure(c, e));
    }

    /**
     * In-app notifications for one event, in one statement: every person (a token seen in the app
     * lately, still working, not an AI assistant's) whose choices (or the defaults: every kind) want
     * it, with the detail written for their role.
     */
    private int inbox(Connection c, long event, NotificationEvent e, Exposure x, Duration activeFor)
        throws SQLException {
        NotificationStore.InAppWriter.Item admin = InAppFeed.write(e, AccessRole.ADMIN, x);
        NotificationStore.InAppWriter.Item steward = InAppFeed.write(e, AccessRole.STEWARD, x);
        NotificationStore.InAppWriter.Item viewer = InAppFeed.write(e, AccessRole.VIEWER, x);
        try (PreparedStatement ps = c.prepareStatement("""
            INSERT INTO kilnmark.notify_inbox (token_id, event_id, kind, severity, title, detail, link)
            SELECT t.id, ?, ?, ?, ?, CASE WHEN t.role IN ('admin', 'designer') THEN ? WHEN t.role = 'steward' THEN ? ELSE ? END, ?
            FROM kilnmark.notify_person p
            JOIN kilnmark.api_token t ON t.id = p.token_id
            LEFT JOIN kilnmark.notify_subscriber s ON s.token_id = t.id
            WHERE TOKEN_WORKS AND NOT t.local_assistant
              AND p.last_seen_at > now() - make_interval(secs => ?)
              AND (s.id IS NULL OR ? = ANY (s.in_app_kinds))
              AND (s.sources IS NULL OR ?::text IS NULL OR ? = ANY (s.sources))
            ON CONFLICT (token_id, event_id) DO NOTHING""".replace("TOKEN_WORKS", works("t")))) {
            ps.setLong(1, event);
            ps.setString(2, e.kind().id());
            ps.setString(3, viewer.severity().id());
            ps.setString(4, e.title());
            ps.setString(5, clip(admin.detail()));
            ps.setString(6, clip(steward.detail()));
            ps.setString(7, clip(viewer.detail()));
            ps.setString(8, e.link());
            ps.setDouble(9, activeFor.toSeconds());
            ps.setString(10, e.kind().id());
            ps.setString(11, e.source());
            ps.setString(12, e.source());
            return ps.executeUpdate();
        }
    }

    private static String clip(String detail) {
        return detail == null ? null : NotificationEvent.clip(detail, 8000);
    }

    /** Writes an event's in-app notification for one role (see {@link InAppFeed#write}). */
    @FunctionalInterface
    public interface InAppWriter {
        /** What one person sees: written for their role. */
        record Item(Severity severity, String title, String detail, String link) {
        }

        Item write(NotificationEvent e, AccessRole role);
    }

    /** Closes a problem quietly (a scan that works again): the next failure is told. */
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

    static Dedupe.State state(Connection c, String key) throws SQLException {
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

    private static final String INBOX_COLUMNS = "id, event_id, kind, severity, title, detail, link, created_at, read";

    private static InboxRow inboxRow(ResultSet rs) throws SQLException {
        return new InboxRow(rs.getLong(1), longOrNull(rs, 2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), Db.instant(rs.getTimestamp(8)), rs.getBoolean(9));
    }

    /** A person's notifications, newest first; only unread ones when {@code unreadOnly}. Each query has its index. */
    public List<InboxRow> inbox(long tokenId, boolean unreadOnly, int limit) {
        String sql = "SELECT " + INBOX_COLUMNS + " FROM kilnmark.notify_inbox WHERE token_id = ?"
            + (unreadOnly ? " AND NOT read" : "") + " ORDER BY created_at DESC, id DESC LIMIT ?";
        return list(sql, ps -> {
            ps.setLong(1, tokenId);
            ps.setInt(2, limit);
        }, NotificationStore::inboxRow);
    }

    /** How many of a person's notifications are unread, counting no further than {@code cap}. */
    public long unread(long tokenId, int cap) {
        return list("SELECT count(*) FROM (SELECT 1 FROM kilnmark.notify_inbox WHERE token_id = ? AND NOT read LIMIT ?) u",
            ps -> {
                ps.setLong(1, tokenId);
                ps.setInt(2, cap);
            }, rs -> rs.getLong(1)).get(0);
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

    /** Gives up queued deliveries already tried {@code max} times (a worker stopped while sending, each time). */
    public int failExhausted(int max) {
        return update("UPDATE kilnmark.notify_delivery SET status = 'failed', finished_at = now(), "
            + "last_error = coalesce(last_error, 'Tried ' || attempts || ' times') "
            + "WHERE status = 'queued' AND attempts >= ?", ps -> ps.setInt(1, max));
    }

    /** Claims the next due delivery to be sent on its own, if any: one at a time, so a slow server holds one row. */
    public Optional<Claimed> claimImmediate(String worker, int max) {
        return claim("""
            UPDATE kilnmark.notify_delivery SET status = 'sending', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id = (SELECT id FROM kilnmark.notify_delivery
                        WHERE status = 'queued' AND NOT digest AND due_at <= now() AND attempts < ?
                        ORDER BY due_at, id FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id, subscriber_id, event_id, attempts""", ps -> {
                ps.setString(1, worker);
                ps.setInt(2, max);
            }).stream().findFirst();
    }

    /** Subscribers with a digest due now, at most {@code limit}. */
    public List<Long> digestsDue(int limit) {
        return list("SELECT subscriber_id FROM kilnmark.notify_delivery WHERE status = 'queued' AND digest "
            + "AND due_at <= now() GROUP BY subscriber_id ORDER BY min(due_at) LIMIT ?", ps -> ps.setInt(1, limit),
            rs -> rs.getLong(1));
    }

    /** Claims every due digest delivery of one subscriber. */
    public List<Claimed> claimDigest(String worker, long subscriber, int max) {
        return claim("""
            UPDATE kilnmark.notify_delivery SET status = 'sending', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id IN (SELECT id FROM kilnmark.notify_delivery
                         WHERE subscriber_id = ? AND status = 'queued' AND digest AND due_at <= now() AND attempts < ?
                         ORDER BY id FOR UPDATE SKIP LOCKED)
            RETURNING id, subscriber_id, event_id, attempts""", ps -> {
                ps.setString(1, worker);
                ps.setLong(2, subscriber);
                ps.setInt(3, max);
            });
    }

    private List<Claimed> claim(String sql, Binder binder) {
        return list(sql, binder, rs -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4)));
    }

    /** The guard on every change a worker makes to its claims: still its own, and still being sent. */
    private static final String MINE = " AND locked_by = ? AND status = 'sending'";

    public void sent(String worker, List<Long> ids) {
        update("UPDATE kilnmark.notify_delivery SET status = 'sent', finished_at = now(), locked_by = NULL, "
            + "locked_at = NULL, last_error = NULL WHERE id = ANY (?)" + MINE, ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray()));
                ps.setString(2, worker);
            });
    }

    /** Puts claimed deliveries back without counting the attempt (the worker is stopping). */
    public void release(String worker, List<Long> ids) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', attempts = greatest(attempts - 1, 0), "
            + "locked_by = NULL, locked_at = NULL WHERE id = ANY (?)" + MINE, ps -> {
                ps.setArray(1, ps.getConnection().createArrayOf("bigint", ids.toArray()));
                ps.setString(2, worker);
            });
    }

    /** Due again at {@code due}, after a failure. */
    public void retry(String worker, List<Long> ids, Instant due, String error) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', due_at = ?, last_error = ?, locked_by = NULL, "
            + "locked_at = NULL WHERE id = ANY (?)" + MINE, ps -> {
                ps.setTimestamp(1, Db.timestamp(due));
                ps.setString(2, error);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", ids.toArray()));
                ps.setString(4, worker);
            });
    }

    /** Given up. */
    public void failed(String worker, List<Long> ids, String error) {
        finish(worker, ids, "failed", error);
    }

    /** Not sent, and never will be: the recipient turned email off, or their token stopped working, meanwhile. */
    public void skipped(String worker, List<Long> ids, String why) {
        finish(worker, ids, "skipped", why);
    }

    private void finish(String worker, List<Long> ids, String status, String error) {
        update("UPDATE kilnmark.notify_delivery SET status = ?, last_error = ?, finished_at = now(), locked_by = NULL, "
            + "locked_at = NULL WHERE id = ANY (?)" + MINE, ps -> {
                ps.setString(1, status);
                ps.setString(2, error);
                ps.setArray(3, ps.getConnection().createArrayOf("bigint", ids.toArray()));
                ps.setString(4, worker);
            });
    }

    /** Over the rate limit: waits for the recipient's digest at {@code due}, without counting the attempt. */
    public void toDigest(String worker, long id, Instant due) {
        update("UPDATE kilnmark.notify_delivery SET status = 'queued', digest = true, due_at = ?, "
            + "attempts = greatest(attempts - 1, 0), locked_by = NULL, locked_at = NULL WHERE id = ?" + MINE, ps -> {
                ps.setTimestamp(1, Db.timestamp(due));
                ps.setLong(2, id);
                ps.setString(3, worker);
            });
    }

    /**
     * Emails still not sent {@code after} they were queued (email paused, or the server down): with
     * {@code digest}, those meant to go on their own move to their recipient's next digest; with
     * {@code drop}, they are skipped, and so are digests overdue by as long. Returns how many.
     */
    public int expireUndelivered(Duration after, String action, ZoneId zone, Instant now) {
        double secs = after.toSeconds();
        if ("drop".equals(action)) {
            return update("UPDATE kilnmark.notify_delivery SET status = 'skipped', finished_at = now(), "
                + "last_error = 'Not sent within ' || ? || ' hours, so dropped' WHERE status = 'queued' "
                + "AND ((NOT digest AND created_at < now() - make_interval(secs => ?)) "
                + "OR (digest AND due_at < now() - make_interval(secs => ?)))", ps -> {
                    ps.setLong(1, after.toHours());
                    ps.setDouble(2, secs);
                    ps.setDouble(3, secs);
                });
        }
        return db.tx(c -> {
            Map<Long, Integer> hours = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT d.id, s.digest_hour FROM kilnmark.notify_delivery d "
                + "JOIN kilnmark.notify_subscriber s ON s.id = d.subscriber_id WHERE d.status = 'queued' AND NOT d.digest "
                + "AND d.created_at < now() - make_interval(secs => ?) FOR UPDATE OF d SKIP LOCKED")) {
                ps.setDouble(1, secs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hours.put(rs.getLong(1), rs.getInt(2));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.notify_delivery SET digest = true, due_at = ? "
                + "WHERE id = ?")) {
                for (Map.Entry<Long, Integer> h : hours.entrySet()) {
                    ps.setTimestamp(1, Db.timestamp(Digest.nextAt(now, h.getValue(), zone)));
                    ps.setLong(2, h.getKey());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return hours.size();
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
                ps.setString(1, recipient == null ? "" : recipient.toLowerCase(java.util.Locale.ROOT));
                setLong(ps, 2, subscriber);
                ps.setString(3, kind);
                ps.setArray(4, ps.getConnection().createArrayOf("bigint", events.toArray()));
                ps.setString(5, subject);
                ps.setString(6, status);
                ps.setInt(7, attempt);
                ps.setString(8, error == null ? null : NotificationEvent.clip(error, 2000));
            });
    }

    /** Emails sent to {@code recipient} (any case) on their own (not digests, tests or confirmations) since {@code since}. */
    public long sentSince(String recipient, Instant since) {
        return list("SELECT count(*) FROM kilnmark.notify_log WHERE recipient = ? AND status = 'sent' "
            + "AND kind NOT IN ('digest', 'test', 'confirm') AND at > ?", ps -> {
                ps.setString(1, recipient.toLowerCase(java.util.Locale.ROOT));
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
     * Deletes in-app notifications older than {@code inAppAge}; log entries, closed problems and
     * events with nothing left to send or show, older than {@code age}; and the state of problems
     * whose rule or source was deleted.
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
            for (String sql : List.of("""
                DELETE FROM kilnmark.notify_state st WHERE st.dedupe_key LIKE 'rule:%'
                  AND NOT EXISTS (SELECT 1 FROM kilnmark.quality_rule r WHERE 'rule:' || r.id = st.dedupe_key)""", """
                DELETE FROM kilnmark.notify_state st
                WHERE (st.dedupe_key LIKE 'scan:%' OR st.dedupe_key LIKE 'connection:%')
                  AND NOT EXISTS (SELECT 1 FROM kilnmark.data_source d
                                  WHERE st.dedupe_key IN ('scan:' || d.name, 'connection:' || d.name))""")) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    n += ps.executeUpdate();
                }
            }
            return n;
        });
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
