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

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.server.Db;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.sources.CommonSettings;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The audit log (V27): who did what, for the controls banks and other regulated users must show
 * (an audit trail as NIST SP 800-53 AU-2 and AU-9, NYDFS Part 500 and DORA expect). Every request
 * that changes something, and every request refused for want of a role, is an {@code api.*} event
 * ({@link AuditFilter}); the changes that decide who sees sensitive values and what reaches a source
 * are events of their own, with what changed. Each event is a row of {@code kilnmark.audit_event},
 * which only ever grows, and one JSON line on the {@value #CATEGORY} log category, for a SIEM.
 * Admins read it ({@code GET /api/v1/audit}).
 *
 * <p>An event is recorded after the change it describes has been made. If the row cannot be
 * written the change stands, and the server logs an error saying so, with the event, so it is
 * never lost silently.
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

    /** A column was marked sensitive; detail: field. */
    public static final String MARK_SET = "mark.set";
    /** A request that may change something succeeded; detail: method, status. Target: the path. */
    public static final String API_REQUEST = "api.request";
    /** A request was refused for want of sign-in or a role; detail: method, status. Target: the path. */
    public static final String API_REFUSED = "api.refused";

    /** Every kind of event, for the API's filter. */
    public static final Set<String> ACTIONS = Set.of(TOKEN_CREATED, TOKEN_REVOKED, MARK_SET, MARK_CLEARED,
        SOURCE_DELETED, WRITING_CHANGED, SQL_CHANGED, API_REQUEST, API_REFUSED);

    /** The most events one request returns. */
    public static final int MAX_LIMIT = 500;

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

    public AuditLog(Db db) {
        this.db = db;
    }

    /** The caller of the current request. */
    public static Actor actor(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return new Actor("anonymous", null);
        }
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        return new Actor(identity.getPrincipal().getName(), id);
    }

    /** Records one event. Never throws: a failure is logged as an error, with the event. */
    public void record(Actor actor, String action, String target, Map<String, ?> detail) {
        if (!ACTIONS.contains(action)) {
            throw new IllegalArgumentException("not an audit action: " + action);
        }
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
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
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
        String body = json;
        try {
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.audit_event "
                    + "(actor, token_id, action, target, detail) VALUES (?, ?, ?, ?, ?::jsonb)")) {
                    ps.setString(1, actor.name());
                    if (actor.tokenId() == null) {
                        ps.setNull(2, Types.BIGINT);
                    } else {
                        ps.setLong(2, actor.tokenId());
                    }
                    ps.setString(3, action);
                    ps.setString(4, safeTarget);
                    ps.setString(5, body);
                    return ps.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            LOG.errorf("Audit: an event could not be stored in the audit log (it is on the %s log category): %s %s "
                + "%s: %s", CATEGORY, actor, action, safeTarget, e.getMessage());
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
