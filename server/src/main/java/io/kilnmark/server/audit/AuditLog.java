package io.kilnmark.server.audit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.server.Db;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.OidcIdentityAugmentor;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.OidcAccess;
import io.kilnmark.server.sources.CommonSettings;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The audit log (V28): who did what, for the controls banks and other regulated users must show
 * (an audit trail as NIST SP 800-53 AU-2 and AU-9, NYDFS Part 500 and DORA expect). Every request
 * that changes something, and every request refused for want of a role, is an {@code api.*} event
 * ({@link AuditFilter}); the changes that decide who sees sensitive values and what reaches a source
 * are events of their own, with what changed. Each event is a row of {@code kilnmark.audit_event},
 * which only ever grows, and one JSON line on the {@value #CATEGORY} log category, for a SIEM.
 * Admins read it ({@code GET /api/v1/audit}).
 *
 * <p>An event is recorded after the change it describes has been made: its JSON line at once, and
 * its row by one thread of its own, in the order recorded (read the table in {@code id} order), so
 * no request waits on it or blocks a thread that must not block. If the row cannot be written (the
 * database is unreachable) the change stands and the server logs an error saying so; the JSON line,
 * which a SIEM keeps, has the event either way. Refusals are kept in the table at most
 * {@value #REFUSALS_PER_MINUTE} a minute for each caller (each token, and everyone not signed in
 * together), with a count of the rest, and {@code api.*} events never take the last
 * {@value #RESERVED} places in the queue, so no flood of requests keeps a token, mark or source
 * event out of the table: when even those are taken, such an event is stored on the caller's thread.
 */
@ApplicationScoped
public class AuditLog {

    /** The log category every event is written to, one JSON object a line, for a SIEM to collect. */
    public static final String CATEGORY = "kilnmark.audit";
    private static final Logger LOG = Logger.getLogger(AuditLog.class);
    private static final Logger AUDIT = Logger.getLogger(CATEGORY);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A token was created; detail: role, unmask, expiresAt. */
    public static final String TOKEN_CREATED = "token.created";
    /** A token was revoked. */
    public static final String TOKEN_REVOKED = "token.revoked";
    /** A column stopped being marked sensitive; detail: field. */
    public static final String MARK_CLEARED = "mark.cleared";
    /** A source was deleted; detail: assets, runs, marked. */
    public static final String SOURCE_DELETED = "source.deleted";
    /** Allow writing was turned on or off; detail: on, via. */
    public static final String WRITING_CHANGED = "source.allow_writing";
    /** Allow SQL was turned on or off; detail: on, via. */
    public static final String SQL_CHANGED = "source.allow_sql";
    /** Most runs at once was changed (ADR 0040); detail: before, after (null: no limit), via. */
    public static final String MAX_RUNS_CHANGED = "source.max_runs";
    /** A flow's sql node started (ADR 0028); detail: source, statements, version. Target: flow/run/node. */
    public static final String FLOW_SQL = "flow.sql";

    /** A column was marked sensitive; detail: field. */
    public static final String MARK_SET = "mark.set";
    /** A request that may change something succeeded; detail: method, status. Target: the path. */
    public static final String API_REQUEST = "api.request";
    /** A request was refused for want of sign-in or a role; detail: method, status. Target: the path. */
    public static final String API_REFUSED = "api.refused";

    /** Someone signed in with the identity provider (ADR 0036); detail: role, unmask. */
    public static final String SIGN_IN = "auth.sign_in";
    /** Someone signed in with the identity provider but was given no role; detail: reason. */
    public static final String SIGN_IN_REFUSED = "auth.sign_in_refused";
    /** Someone signed out of a provider sign-in. */
    public static final String SIGN_OUT = "auth.sign_out";

    /** A designer token's unmask grant waits for a second admin (ADR 0038); detail: name. */
    public static final String UNMASK_REQUESTED = "unmask.requested";
    /** A second admin approved a token's unmask grant; detail: name, asked by. */
    public static final String UNMASK_APPROVED = "unmask.approved";
    /** An admin refused a token's unmask grant; detail: name, asked by. */
    public static final String UNMASK_REFUSED = "unmask.refused";

    /** An admin downloaded the access review (ADR 0037); detail: format. */
    public static final String ACCESS_REVIEW = "access.review";

    /**
     * The database was restored from a backup (ADR 0039), recorded at the next start from the note
     * {@code restore.sh} leaves ({@link RestoreRecord}); detail: backup, takenAt, version,
     * previousDatabase, keys.
     */
    public static final String DATABASE_RESTORED = "database.restored";

    /**
     * A schedule was added or changed (ADR 0046); detail: pipeline or flow, cron, zone, runAs,
     * enabled. Never the values its runs start with. Target: schedule id.
     */
    public static final String SCHEDULE_SET = "schedule.set";
    /** A schedule was deleted; detail as {@link #SCHEDULE_SET}. Target: schedule id. */
    public static final String SCHEDULE_DELETED = "schedule.deleted";
    /**
     * A schedule was paused because whoever set it no longer holds its role (ADR 0046); detail as
     * {@link #SCHEDULE_SET}, with reason. Target: schedule id.
     */
    public static final String SCHEDULE_PAUSED = "schedule.paused";

    /** Every kind of event, for the API's filter. */
    public static final Set<String> ACTIONS = Set.of(TOKEN_CREATED, TOKEN_REVOKED, MARK_SET, MARK_CLEARED,
        SOURCE_DELETED, WRITING_CHANGED, SQL_CHANGED, MAX_RUNS_CHANGED, FLOW_SQL, API_REQUEST, API_REFUSED, SIGN_IN, SIGN_IN_REFUSED,
        SIGN_OUT, ACCESS_REVIEW, UNMASK_REQUESTED, UNMASK_APPROVED, UNMASK_REFUSED, DATABASE_RESTORED, SCHEDULE_SET,
        SCHEDULE_DELETED, SCHEDULE_PAUSED);

    /** The most events one request returns. */
    public static final int MAX_LIMIT = 500;
    /** The most events waiting to be stored at once. */
    static final int QUEUE = 10_000;
    /** The most refusals kept each minute for one caller (a token, or everyone not signed in). */
    static final int REFUSALS_PER_MINUTE = 30;
    /** Places in the queue only events other than {@code api.*} may take. */
    static final int RESERVED = 1_000;
    /** The longest target kept: a path can be as long as a caller likes. */
    static final int MAX_TARGET = 300;

    /** Who acted: a name for people, and the token they used, when one was. */
    public record Actor(String name, Long tokenId) {
        public Actor {
            name = name == null || name.isBlank() ? "unknown" : ApiTokens.loggable(name);
        }

        /** The server itself: start-up, samples, jobs. */
        public static Actor server(String what) {
            return new Actor("server (" + what + ")", null);
        }

        @Override
        public String toString() {
            return tokenId == null || tokenId == 0 ? name : "token " + tokenId + " (" + name + ")";
        }
    }

    /** One event as read back. */
    public record Event(long id, Instant at, String actor, Long tokenId, String action, String target,
                        Map<String, Object> detail) {
    }

    /**
     * A person who has signed in with the identity provider and been given a role, as the audit log
     * knows them (ADR 0037): when they last signed in, the role and grant their last sign-in with a
     * role gave, and how many sign-ins gave them one. {@code refused} is the reason their latest
     * sign-in was given no role, when it was; the role is cleared only when that reason is that none
     * of their groups has one any more (a sign-in without MFA, say, does not take a role away).
     */
    public record ProviderAccess(String name, Instant lastSignIn, String role, boolean unmask, long signIns,
                                 String refused) {
    }

    /**
     * What a person signed in through the identity provider last had, as the access review reads it
     * (ADR 0037): {@code role} as recorded (null for none, or one this server does not know),
     * {@code groups} their group names (null for a sign-in recorded before they were), and
     * {@code at}, when that sign-in was.
     */
    public record Standing(AccessRole role, List<String> groups, Instant at) {
    }

    /**
     * The latest successful sign-in of a person, by their key; the actions are literals, so the
     * partial index on sign-ins (V36) serves it. Shared with the test that reads its plan.
     */
    public static final String LATEST_SIGN_IN_BY_KEY = "SELECT id, detail::text, at FROM kilnmark.audit_event "
        + "WHERE action = '" + SIGN_IN + "' AND detail ->> 'person' = ? ORDER BY id DESC LIMIT 1";
    /** As {@link #LATEST_SIGN_IN_BY_KEY}, by name, among sign-ins recorded before the key was. */
    public static final String LATEST_SIGN_IN_BY_NAME = "SELECT id, detail::text, at FROM kilnmark.audit_event "
        + "WHERE action = '" + SIGN_IN + "' AND actor = ? AND detail ->> 'person' IS NULL ORDER BY id DESC LIMIT 1";
    /** Whether the person, by key, was refused for want of a group after sign-in event {@code id}. */
    public static final String NO_GROUP_AFTER_BY_KEY = "SELECT 1 FROM kilnmark.audit_event WHERE action = '"
        + SIGN_IN_REFUSED + "' AND detail ->> 'person' = ? AND id > ? AND detail ->> 'reason' = ? LIMIT 1";
    /** As {@link #NO_GROUP_AFTER_BY_KEY}, by name, among refusals recorded before the key was. */
    public static final String NO_GROUP_AFTER_BY_NAME = "SELECT 1 FROM kilnmark.audit_event WHERE action = '"
        + SIGN_IN_REFUSED + "' AND actor = ? AND detail ->> 'person' IS NULL AND id > ? AND detail ->> 'reason' = ? "
        + "LIMIT 1";

    /**
     * A person's standing now: their latest successful sign-in (by their key, {@code person};
     * only when none carries it, by their name, {@code actor}, among sign-ins recorded before the
     * key was), unless a later sign-in was refused because none of their groups has a role any
     * more. Other refusals (no second factor, say) take nothing away, however many there are. Events
     * are ordered by their id, the order the database stored them, not by the times different
     * servers' clocks gave them. Empty when they hold no role or Kilnmark has no sign-in of theirs.
     */
    public Optional<Standing> providerStanding(String person, String actor) {
        return db.tx(c -> {
            boolean keyed = true;
            Object[] latest = latest(c, LATEST_SIGN_IN_BY_KEY, person);
            if (latest == null) {
                keyed = false;
                latest = latest(c, LATEST_SIGN_IN_BY_NAME, actor);
            }
            if (latest == null) {
                return Optional.<Standing>empty();
            }
            long id = (Long) latest[0];
            if (refusedAfter(c, NO_GROUP_AFTER_BY_KEY, person, id)
                || (!keyed && refusedAfter(c, NO_GROUP_AFTER_BY_NAME, actor, id))) {
                return Optional.<Standing>empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> d = (Map<String, Object>) latest[1];
            return Optional.of(new Standing(knownRole(d.get("role")), groupNames(d.get("groups")), (Instant) latest[2]));
        });
    }

    /** {id, detail, at} of the one sign-in {@code sql} finds, or null. */
    private static Object[] latest(java.sql.Connection c, String sql, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Object[] {rs.getLong(1), detail(rs.getString(2)), Db.instant(rs.getTimestamp(3))}
                    : null;
            }
        }
    }

    private static boolean refusedAfter(java.sql.Connection c, String sql, String value, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            ps.setLong(2, id);
            ps.setString(3, OidcAccess.NO_GROUP);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** A recorded role, or null when there is none or this server does not know it (counts as none). */
    private static AccessRole knownRole(Object role) {
        if (role == null) {
            return null;
        }
        try {
            return AccessRole.parse(String.valueOf(role));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> groupNames(Object groups) {
        if (!(groups instanceof List<?> l)) {
            return null;
        }
        return l.stream().filter(g -> g != null).map(String::valueOf).toList();
    }

    /** Everyone who has signed in with the identity provider and been given a role, most recent first. */
    public List<ProviderAccess> providerAccess() {
        return db.tx(c -> {
            List<ProviderAccess> out = new ArrayList<>();
            // every sign-in event, newest first per person: the latest says what happened last, the
            // latest with a role says what their groups gave them
            try (PreparedStatement ps = c.prepareStatement("SELECT actor, at, action, detail::text FROM "
                + "kilnmark.audit_event WHERE action IN (?, ?) ORDER BY actor, at DESC")) {
                ps.setString(1, SIGN_IN);
                ps.setString(2, SIGN_IN_REFUSED);
                try (ResultSet rs = ps.executeQuery()) {
                    String actor = null;
                    Instant last = null;
                    String refused = null;
                    Map<String, Object> given = null;
                    long count = 0;
                    while (true) {
                        boolean more = rs.next();
                        String next = more ? rs.getString(1) : null;
                        if (actor != null && (!more || !actor.equals(next))) {
                            if (given != null) {
                                boolean gone = OidcAccess.NO_GROUP.equals(refused);
                                out.add(new ProviderAccess(actor, last, gone || given.get("role") == null ? null
                                    : String.valueOf(given.get("role")), !gone && Boolean.TRUE.equals(given.get("unmask")),
                                    count, refused));
                            }
                            actor = null;
                        }
                        if (!more) {
                            break;
                        }
                        if (actor == null) {
                            actor = next;
                            last = Db.instant(rs.getTimestamp(2));
                            refused = SIGN_IN_REFUSED.equals(rs.getString(3))
                                ? String.valueOf(detail(rs.getString(4)).getOrDefault("reason", "")) : null;
                            given = null;
                            count = 0;
                        }
                        if (SIGN_IN.equals(rs.getString(3))) {
                            count++;
                            if (given == null) {
                                given = detail(rs.getString(4));
                            }
                        }
                    }
                }
            }
            out.sort(java.util.Comparator.comparing(ProviderAccess::lastSignIn).reversed());
            return out;
        });
    }

    private final Db db;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(QUEUE), r -> {
            Thread t = new Thread(r, "kilnmark-audit");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    private final AtomicLong dropped = new AtomicLong();
    private final Object minuteLock = new Object();
    /** The clock refusals are counted by, in milliseconds; a test sets its own. */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private long minute;
    /** Refusals kept this minute, and those not kept since the last one kept, by caller. */
    private final Map<String, int[]> refusals = new java.util.HashMap<>();
    private volatile boolean stopping;

    public AuditLog(Db db) {
        this.db = db;
    }

    /** Stores what is waiting, for up to five seconds, as the server stops, before its database closes. */
    void onStop(@jakarta.enterprise.event.Observes io.quarkus.runtime.ShutdownEvent event) {
        stop();
    }

    @PreDestroy
    void stop() {
        if (stopping && writer.isTerminated()) {
            return;
        }
        stopping = true;
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.errorf("Audit: %d events were not stored as the server stopped (they are on the %s log category)",
                    writer.getQueue().size(), CATEGORY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Waits until every event recorded so far is stored, for up to {@code millis}; for tests. One
     * thread stores them in order, so a task queued now runs after all of them.
     */
    public void awaitStored(long millis) {
        try {
            writer.submit(() -> { }).get(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
            | java.util.concurrent.RejectedExecutionException e) {
            LOG.debugf("Audit: waiting for stored events: %s", e.getMessage());
        }
    }

    /** Who is not signed in, or whose sign-in failed. */
    public static final String ANONYMOUS = "anonymous";

    /** The caller of the current request; anonymous when it is not signed in or its sign-in failed. */
    public static Actor actor(SecurityIdentity identity) {
        try {
            if (identity == null || identity.isAnonymous()) {
                return new Actor(ANONYMOUS, null);
            }
            Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
            return new Actor(identity.getPrincipal().getName(), id);
        } catch (RuntimeException e) {
            return new Actor(ANONYMOUS, null); // a failed sign-in can throw again when asked
        }
    }

    /**
     * Records one event. Never throws for a failure to store it, which is logged as an error.
     *
     * @throws IllegalArgumentException for an action not in {@link #ACTIONS}: a mistake in the server
     */
    public void record(Actor actor, String action, String target, Map<String, ?> detail) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("not an audit action: " + action);
        }
        boolean keep = true;
        if (API_REFUSED.equals(action)) {
            // Each token, each provider sign-in (its sso: name), and everyone not signed in together
            String who = actor.tokenId() != null ? "token " + actor.tokenId()
                : actor.name() != null && actor.name().startsWith(OidcIdentityAugmentor.PREFIX) ? actor.name()
                : ANONYMOUS;
            Integer skipped = refusalSkips(who);
            keep = skipped != null; // over this minute's share: the JSON line only, counted for the next row kept
            if (skipped != null && skipped > 0) {
                Map<String, Object> more = new LinkedHashMap<>(detail == null ? Map.of() : detail);
                more.put("notKeptBefore", skipped);
                detail = more;
            }
        }
        Instant at = Instant.now();
        Map<String, Object> d = new LinkedHashMap<>();
        if (detail != null) {
            detail.forEach((k, v) -> d.put(k, v instanceof String s ? ApiTokens.loggable(s) : v));
        }
        String json;
        try {
            json = JSON.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        String safeTarget = ApiTokens.loggable(target == null ? "" : target);
        if (safeTarget.length() > MAX_TARGET) {
            safeTarget = safeTarget.substring(0, MAX_TARGET) + "...";
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", at.toString());
        line.put("actor", actor.name());
        line.put("tokenId", actor.tokenId());
        line.put("action", action);
        line.put("target", safeTarget);
        line.put("detail", d);
        try {
            AUDIT.info(JSON.writeValueAsString(line));
        } catch (JsonProcessingException e) {
            AUDIT.infof("%s %s %s", actor, action, safeTarget);
        }
        if (!keep) {
            return;
        }
        String body = json;
        String where = safeTarget;
        boolean request = action.startsWith("api.");
        if (stopping) {
            if (!request) {
                store(at, actor, action, where, body); // start-up or shutdown work still records its changes
            } else {
                LOG.errorf("Audit: an event was not stored as the server was stopping (it is on the %s log "
                    + "category): %s %s %s", CATEGORY, actor, action, where);
            }
            return;
        }
        if (request && writer.getQueue().size() >= QUEUE - RESERVED) {
            notStored();
            return;
        }
        try {
            writer.execute(() -> store(at, actor, action, where, body));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (request && writer.isShutdown()) {
                LOG.errorf("Audit: an event was not stored as the server was stopping (it is on the %s log "
                    + "category): %s %s %s", CATEGORY, actor, action, where);
            } else if (request) {
                notStored();
            } else {
                store(at, actor, action, where, body); // never lose a token, mark or source event to a flood
            }
        }
    }

    private void notStored() {
        long n = dropped.incrementAndGet();
        if (n == 1 || n % 1000 == 0) {
            LOG.errorf("Audit: %d request events not stored, as too many were waiting (each is on the %s log "
                + "category)", n, CATEGORY);
        }
    }

    private void store(Instant at, Actor actor, String action, String target, String detail) {
        try {
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.audit_event "
                    + "(at, actor, token_id, action, target, detail) VALUES (?, ?, ?, ?, ?, ?::jsonb)")) {
                    ps.setTimestamp(1, Db.timestamp(at));
                    ps.setString(2, actor.name());
                    if (actor.tokenId() == null) {
                        ps.setNull(3, Types.BIGINT);
                    } else {
                        ps.setLong(3, actor.tokenId());
                    }
                    ps.setString(4, action);
                    ps.setString(5, target);
                    ps.setString(6, detail);
                    return ps.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            LOG.errorf("Audit: an event could not be stored in the audit log (it is on the %s log category): %s %s "
                + "%s: %s", CATEGORY, actor, action, target, e.getMessage());
        }
    }

    /** Counts refusals by {@code millis} rather than the system clock, or by it again with null; for tests. */
    void clock(java.util.function.LongSupplier millis) {
        synchronized (minuteLock) {
            clock = millis == null ? System::currentTimeMillis : millis;
        }
    }

    /** Starts this minute's share of refusals afresh for every caller; for tests. */
    void resetRefusals() {
        synchronized (minuteLock) {
            refusals.clear();
        }
    }

    /**
     * For a refusal of {@code caller}: null when this minute's share is used up (it is counted), else
     * how many were not kept since the last one that was.
     */
    private Integer refusalSkips(String caller) {
        synchronized (minuteLock) {
            long now = clock.getAsLong() / 60_000;
            if (now != minute) {
                minute = now;
                refusals.values().forEach(c -> c[0] = 0); // the counts not kept carry over to the next one kept
                refusals.values().removeIf(c -> c[1] == 0);
            }
            int[] c = refusals.computeIfAbsent(caller, k -> new int[2]);
            if (c[0] >= REFUSALS_PER_MINUTE) {
                c[1]++;
                return null;
            }
            c[0]++;
            int skipped = c[1];
            c[1] = 0;
            return skipped;
        }
    }

    /**
     * Records Allow writing and Allow SQL turned on or off, and Most runs at once changed, between
     * {@code before} (null for a new source) and {@code after}; {@code via} says how (the form, an
     * import). A new source records a setting only when it is on, or set.
     */
    public void sourceSettings(Actor actor, String source, Map<String, String> before, Map<String, String> after,
                               String via) {
        boolean writingBefore = before != null && CommonSettings.allowsWriting(before);
        boolean writingAfter = CommonSettings.allowsWriting(after);
        if (writingBefore != writingAfter) {
            record(actor, WRITING_CHANGED, source, Map.of("on", writingAfter, "via", via));
        }
        boolean sqlBefore = before != null && CommonSettings.allowsSql(before);
        boolean sqlAfter = CommonSettings.allowsSql(after);
        if (sqlBefore != sqlAfter) {
            record(actor, SQL_CHANGED, source, Map.of("on", sqlAfter, "via", via));
        }
        Integer runsBefore = before == null ? null : CommonSettings.maxRuns(before);
        Integer runsAfter = CommonSettings.maxRuns(after);
        if (!java.util.Objects.equals(runsBefore, runsAfter)) {
            Map<String, Object> detail = new java.util.LinkedHashMap<>();
            detail.put("before", runsBefore);
            detail.put("after", runsAfter);
            detail.put("via", via);
            record(actor, MAX_RUNS_CHANGED, source, detail);
        }
    }

    /**
     * Events newest first: of {@code action} when given, before event {@code before} when given
     * (for the next page), at most {@code limit}.
     */
    public List<Event> list(String action, Long before, int limit) {
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        StringBuilder sql = new StringBuilder("SELECT id, at, actor, token_id, action, target, detail::text "
            + "FROM kilnmark.audit_event WHERE true");
        if (action != null) {
            sql.append(" AND action = ?");
        }
        if (before != null) {
            sql.append(" AND id < ?");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (action != null) {
                    ps.setString(i++, action);
                }
                if (before != null) {
                    ps.setLong(i++, before);
                }
                ps.setInt(i, n);
                List<Event> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long token = rs.getLong(4);
                        Long tokenId = rs.wasNull() ? null : token;
                        out.add(new Event(rs.getLong(1), Db.instant(rs.getTimestamp(2)), rs.getString(3), tokenId,
                            rs.getString(5), rs.getString(6), detail(rs.getString(7))));
                    }
                }
                return out;
            }
        });
    }

    private static Map<String, Object> detail(String json) throws SQLException {
        try {
            return json == null ? Map.of() : JSON.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
