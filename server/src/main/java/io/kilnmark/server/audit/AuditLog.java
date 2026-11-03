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
import io.kilnmark.server.auth.ApiTokens;
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
    /** A flow's sql node started (ADR 0028); detail: source, statements, version. Target: flow/run/node. */
    public static final String FLOW_SQL = "flow.sql";

    /** A column was marked sensitive; detail: field. */
    public static final String MARK_SET = "mark.set";
    /** A request that may change something succeeded; detail: method, status. Target: the path. */
    public static final String API_REQUEST = "api.request";
    /** A request was refused for want of sign-in or a role; detail: method, status. Target: the path. */
    public static final String API_REFUSED = "api.refused";

    /** Every kind of event, for the API's filter. */
    public static final Set<String> ACTIONS = Set.of(TOKEN_CREATED, TOKEN_REVOKED, MARK_SET, MARK_CLEARED,
        SOURCE_DELETED, WRITING_CHANGED, SQL_CHANGED, FLOW_SQL, API_REQUEST, API_REFUSED);

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
            Integer skipped = refusalSkips(actor.tokenId() == null ? ANONYMOUS : "token " + actor.tokenId());
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
     * Records Allow writing and Allow SQL turned on or off between {@code before} (null for a new
     * source) and {@code after}; {@code via} says how (the form, an import). A new source records a
     * setting only when it is on.
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
