package io.kilnmark.server.api;

import io.kilnmark.server.ai.AiViews;
import io.kilnmark.server.ai.AskSearch;
import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Asking in plain English: {@code POST /api/v1/search/ask} with {@code {"question": "..."}}. The
 * answer holds the interpreted search plan, so people see what was searched, and the results.
 */
@Path("/api/v1/search/ask")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class AskResource {

    private final AskSearch ask;
    private final SecurityIdentity identity;

    public AskResource(AskSearch ask, SecurityIdentity identity) {
        this.ask = ask;
        this.identity = identity;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public AiViews.AskResult ask(AiViews.Question body) {
        if (body == null || body.question() == null || body.question().isBlank()) {
            throw new IllegalArgumentException("question: what you are looking for, in your own words");
        }
        if (body.question().length() > 500) {
            throw new IllegalArgumentException("question: 500 characters at most");
        }
        int n = body.limit() == null ? 20 : Math.max(1, Math.min(body.limit(), 100));
        return AiViews.AskResult.of(ask.ask(body.question(), n, Callers.of(identity)));
    }
}
