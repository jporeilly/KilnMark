package io.kilnmark.server.api;

import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.Samples;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The templates in the Library (ADR 0043): pipelines and flows to start one's own from, bundled with
 * the server like the samples but never added as they are. Unlike the samples, they are listed
 * whether or not the server offers the samples: they hold no data and name no source.
 */
@Path("/api/v1/templates")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class TemplateResource {

    /**
     * One template: its file under samples/, name, type (pipeline or flow), what it is for, its YAML,
     * and its explanation, read from the comment it starts with.
     */
    public record Template(String file, String name, String type, String description, String yaml,
                           Samples.Explanation explanation) {
    }

    @GET
    @Operation(summary = "List the templates", description = "The pipelines and flows to start one's own from, each "
        + "with its explanation (what it is for, why, what to look for, what it needs) and its YAML, which has <...> "
        + "where a source, table or pipeline of one's own goes. Open one in the editor, give it a name and replace "
        + "the gaps; saving checks it as any pipeline or flow. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The templates"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<Template> list() {
        return Samples.templates().stream()
            .map(s -> new Template(s.file(), s.name(), s.type(), s.description(), s.yaml(), s.explanation()))
            .toList();
    }
}
