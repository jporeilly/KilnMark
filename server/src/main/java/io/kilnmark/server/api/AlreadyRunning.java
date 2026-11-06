package io.kilnmark.server.api;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * A run was asked for while one of the same pipeline or flow is queued or running: 409, saying so.
 * Its own type, so a schedule can tell this from a run that cannot start, and skip the fire.
 */
public final class AlreadyRunning extends WebApplicationException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public AlreadyRunning(String message) {
        super(message, Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }
}
