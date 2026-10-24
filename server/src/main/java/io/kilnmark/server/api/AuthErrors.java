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
        + "that can: steward to start scans, admin to add sources or manage tokens.";

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

    /** A token was sent but is not valid. */
    @Provider
    public static class Failed implements ExceptionMapper<AuthenticationFailedException> {
        @Override
        public Response toResponse(AuthenticationFailedException e) {
            return unauthorized(NOT_ACCEPTED);
        }
    }

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
