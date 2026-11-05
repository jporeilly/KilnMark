package io.kilnmark.server.auth;

import java.util.Locale;
import java.util.Set;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Refuses a request that may change something when it would be signed in by the session cookie of
 * a provider sign-in (ADR 0036) and does not carry {@code X-Requested-With: JavaScript}, which only
 * Kilnmark's own pages send. A page on another site can make a browser send the cookie (the cookie is
 * SameSite=Lax, so cross-site navigations still carry it) but cannot add that header without the
 * browser first asking Kilnmark, which never allows other sites. API tokens are unaffected: a request
 * with an {@code Authorization: Bearer} header is signed in by its token, never by the cookie.
 */
@ApplicationScoped
public class SessionRequests {

    /** The header Kilnmark's pages send with every request; also what makes the OIDC client answer 499, not redirect. */
    public static final String HEADER = "X-Requested-With";
    /** Its value. */
    public static final String VALUE = "JavaScript";

    /** The OIDC client's session cookie, and its per-tenant variants ({@code q_session_<tenant>}). */
    static final String SESSION_COOKIE = "q_session";

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    void register(@Observes Filters filters) {
        filters.register(rc -> {
            var request = rc.request();
            boolean session = false;
            for (var c : request.cookies()) {
                if (c.getName().startsWith(SESSION_COOKIE)) {
                    session = true;
                    break;
                }
            }
            String authorization = request.getHeader("Authorization");
            boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
            if (refused(request.method().name(), session, bearer, request.getHeader(HEADER))) {
                rc.response().setStatusCode(403).putHeader("Content-Type", "application/json")
                    .end("{\"error\":\"This request came with a sign-in session but not from Kilnmark's own pages, so "
                        + "it was refused. Use Kilnmark's web UI, or an API token for scripts.\"}");
                return;
            }
            rc.next();
        }, 15);
    }

    /** Whether to refuse: a method that may change something, signed in by the session cookie, without the header. */
    static boolean refused(String method, boolean sessionCookie, boolean bearer, String header) {
        if (SAFE.contains(method.toUpperCase(Locale.ROOT)) || !sessionCookie || bearer) {
            return false;
        }
        return header == null || !VALUE.equalsIgnoreCase(header.strip());
    }
}
