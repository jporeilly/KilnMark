package io.kilnmark.server.api;

import io.kilnmark.server.SecretBox;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Every API error is {@code {"error": "..."}} with a fitting status. */
public record ApiError(String error) {

    static WebApplicationException of(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    @Provider
    public static class BadRequest implements ExceptionMapper<IllegalArgumentException> {
        @Override
        public Response toResponse(IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(e.getMessage())).build();
        }
    }

    /** A stored password sealed with another key (ADR 0039): 409, saying what to do. */
    @Provider
    public static class WrongKey implements ExceptionMapper<SecretBox.WrongKey> {
        @Override
        public Response toResponse(SecretBox.WrongKey e) {
            return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(e.getMessage())).build();
        }
    }
}
