package io.kilnmark.server.api;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.server.Db;
import io.kilnmark.server.auth.AccessRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Which API tokens are used by a local AI assistant (ADR 0016, admins only). Through the MCP
 * server such a token also sees sources whose AI rule is {@code local_only}; other tokens see only
 * sources whose rule is {@code allowed}. Kept apart from the token resource so the token API stays
 * as it is (a class path under /api/v1/tokens would also take over the token resource's paths).
 */
@Path("/api/v1/ai/assistant-tokens")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class AssistantTokenResource {

    /** Body of {@code PUT /api/v1/ai/assistant-tokens/{id}}. */
    public record Flag(Boolean localAssistant) {
    }

    /** The ids of tokens flagged as used by a local assistant. */
    public record Flagged(List<Long> tokenIds) {
    }

    private final Db db;

    public AssistantTokenResource(Db db) {
        this.db = db;
    }

    @GET
    public Flagged flagged() {
        return new Flagged(db.tx(c -> {
            List<Long> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM kilnmark.api_token WHERE local_assistant ORDER BY id"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
            }
            return out;
        }));
    }

    /**
     * Sets or clears the flag. Set it only for an assistant whose model runs on your own network:
     * the token's MCP calls then see the metadata of local_only sources.
     */
    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Flag set(@PathParam("id") long id, Flag body) {
        if (body == null || body.localAssistant() == null) {
            throw new IllegalArgumentException("localAssistant: true or false");
        }
        int n = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.api_token SET local_assistant = ? WHERE id = ?")) {
                ps.setBoolean(1, body.localAssistant());
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        });
        if (n == 0) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No token " + id);
        }
        return body;
    }
}
