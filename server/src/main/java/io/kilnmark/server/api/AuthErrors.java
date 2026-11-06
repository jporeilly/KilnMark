package io.kilnmark.server.api;

import io.kilnmark.server.auth.ApiTokenMechanism;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Sign-in failures as {@code {"error": "..."}}, saying what to do. */
public final class AuthErrors {

    private AuthErrors() {
    }

    static final String SIGN_IN = "Sign in: send an API token as 'Authorization: Bearer km_...'. "
        + "Ask an admin for a token.";
    static final String NOT_ACCEPTED = "The API token was not accepted: it is unknown, has expired or was revoked. "
        + "Check it was copied in full, or ask an admin for a new one.";
    static final String NOT_ALLOWED = "Your API token's role cannot do this. Ask an admin for a token with a role "
        + "that can: steward to run pipelines and scan sources, designer to build pipelines and add sources, admin "
        + "for Settings and tokens.";

    private static Response unauthorized(String message) {
        return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
            .header("WWW-Authenticate", ApiTokenMechanism.CHALLENGE).entity(new ApiError(message)).build();
    }

    /** No token was sent. */
    @Provider
    public static class Unauthorized implements ExceptionMapper<UnauthorizedException> {
        @Override
        public Response toResponse(UnauthorizedException e) {
            return unauthorized(SIGN_IN);
        }
    }

    /** A provider session that could not be renewed, or whose renewal was refused (ADR 0036). */
    static final String SESSION_ENDED = "Your sign-in has ended. Sign in again.";

    /**
     * A token was sent but is not valid; or, with no token, a provider session cookie that no longer
     * signs anyone in (its renewal failed or was refused). That cookie is cleared here, at the path
     * and domain the OIDC client sets it with: this answer takes the place of the OIDC client's own
     * challenge, which would clear it, and otherwise each later request would ask the provider again.
     */
    @Provider
    public static class Failed implements ExceptionMapper<AuthenticationFailedException> {

        @jakarta.ws.rs.core.Context
        jakarta.ws.rs.core.HttpHeaders headers;

        @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.oidc.authentication.cookie-path",
            defaultValue = "/")
        String cookiePath;

        @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.oidc.authentication.cookie-domain")
        java.util.Optional<String> cookieDomain;

        @Override
        public Response toResponse(AuthenticationFailedException e) {
            java.util.List<String> sessions = headers == null || headers.getHeaderString("Authorization") != null
                ? java.util.List.of()
                : headers.getCookies().keySet().stream().filter(n -> n.startsWith(SESSION_COOKIE)).toList();
            if (sessions.isEmpty()) {
                return unauthorized(NOT_ACCEPTED);
            }
            Response.ResponseBuilder r = Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.APPLICATION_JSON).header("WWW-Authenticate", ApiTokenMechanism.CHALLENGE)
                .entity(new ApiError(SESSION_ENDED));
            for (String name : sessions) {
                r.cookie(new jakarta.ws.rs.core.NewCookie.Builder(name).value("").path(cookiePath)
                    .domain(cookieDomain == null ? null : cookieDomain.orElse(null)).maxAge(0).httpOnly(true)
                    .sameSite(jakarta.ws.rs.core.NewCookie.SameSite.LAX).build());
            }
            return r.build();
        }
    }

    /** The OIDC client's session cookie, and its chunks and per-tenant variants, as SessionRequests reads them. */
    static final String SESSION_COOKIE = "q_session";

    /** The token is valid but its role is too low. */
    @Provider
    public static class Forbidden implements ExceptionMapper<ForbiddenException> {
        @Override
        public Response toResponse(ForbiddenException e) {
            return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(NOT_ALLOWED)).build();
        }
    }
}
