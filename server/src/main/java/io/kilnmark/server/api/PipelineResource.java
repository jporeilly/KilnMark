package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Pipelines (docs/design/pipelines.md section 6): saved as YAML with every version, checked as
 * they are written, and run by workers. Viewers read; stewards add, change, delete and run. A
 * pipeline that writes also needs each target source to allow writing, which an admin sets.
 */
@Path("/api/v1/pipelines")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class PipelineResource {

    private final PipelineService pipelines;
    private final SecurityIdentity identity;

    public PipelineResource(PipelineService pipelines, SecurityIdentity identity) {
        this.pipelines = pipelines;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "List pipelines", description = "Every pipeline by name, with its latest version, whether "
        + "that version can run, and its latest run. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The pipelines"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public List<PipelineViews.PipelineSummary> list() {
        return pipelines.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Add a pipeline", description = "Saves a new pipeline with its YAML as version 1. Names are a "
        + "small letter followed by small letters, digits and dashes, at most 63 characters, and cannot change. A "
        + "pipeline with problems is saved as a draft (valid false) and cannot run until they are fixed. The ETag is "
        + "the latest version, to send back in If-Match when saving the next. Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Added; the body is the pipeline with its version and problems"),
        @APIResponse(responseCode = "400", description = "No YAML, a name that cannot be used, or YAML over the size "
            + "limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "409", description = "A pipeline with that name exists")})
    public Response create(PipelineViews.NewPipeline body) {
        PipelineViews.PipelineDetail d = pipelines.create(body, who());
        return Response.created(URI.create("/api/v1/pipelines/" + d.name())).tag(etag(d)).entity(d).build();
    }

    @POST
    @Path("validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check pipeline YAML", description = "Checks YAML without saving or running it: the problems, "
        + "each with its line, column and step, and each step's ports and the columns leaving each port where they "
        + "can be known before a run. For a pipeline not yet saved; give name to check the YAML's name against it. "
        + "Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What the check found; valid is false when there are problems"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public PipelineViews.Validation validateNew(PipelineViews.ValidateRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return pipelines.validate(body.yaml(), body.name() == null || body.name().isBlank() ? null : body.name());
    }

    @GET
    @Path("{name}")
    @Operation(summary = "A pipeline", description = "The pipeline with its latest version's YAML and problems, and "
        + "its latest run. The ETag is the latest version's number, for If-Match. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The pipeline"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public Response get(@PathParam("name") String name) {
        PipelineViews.PipelineDetail d = pipelines.get(name);
        return Response.ok(d).tag(etag(d)).build();
    }

    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Save a new version", description = "Saves the YAML as the pipeline's next version, with a "
        + "message saying what changed; earlier versions stay as they were. With If-Match set to the latest version "
        + "as you read it (the ETag), the save is refused (409) when someone has saved another since. Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Saved; the body is the pipeline with the new version"),
        @APIResponse(responseCode = "400", description = "No YAML, YAML over the size limit, or an If-Match that is "
            + "not a version number"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such pipeline"),
        @APIResponse(responseCode = "409", description = "Someone else saved a version after the one in If-Match")})
    public Response save(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch,
                         PipelineViews.PipelineChange body) {
        PipelineViews.PipelineDetail d = pipelines.save(name, PipelineService.readVersion(ifMatch), body, who());
        return Response.ok(d).tag(etag(d)).build();
    }

    @DELETE
    @Path("{name}")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Delete a pipeline", description = "Deletes the pipeline with its versions and runs. What its "
        + "runs wrote, and the lineage they recorded, stay in the catalogue. Refused while a run is queued or running. "
        + "Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such pipeline"),
        @APIResponse(responseCode = "409", description = "A run is queued or running")})
    public Response delete(@PathParam("name") String name) {
        pipelines.delete(name, who());
        return Response.noContent().build();
    }

    @GET
    @Path("{name}/versions")
    @Operation(summary = "A pipeline's versions", description = "Every saved version, newest first: who saved it, "
        + "when, the message and whether it could run. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The versions"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public List<PipelineViews.VersionSummary> versions(@PathParam("name") String name) {
        return pipelines.versions(name);
    }

    @GET
    @Path("{name}/versions/{n}")
    @Operation(summary = "One version", description = "A saved version with its YAML and the problems found when it "
        + "was saved. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The version"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline or version")})
    public PipelineViews.VersionDetail version(@PathParam("name") String name, @PathParam("n") int n) {
        return pipelines.version(name, n);
    }

    @POST
    @Path("{name}/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check YAML for this pipeline", description = "As POST /api/v1/pipelines/validate, checking "
        + "the YAML's name against this pipeline's. Nothing is saved. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "What the check found"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public PipelineViews.Validation validate(@PathParam("name") String name, PipelineViews.ValidateRequest body) {
        pipelines.get(name);
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return pipelines.validate(body.yaml(), name);
    }

    @POST
    @Path("{name}/runs")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Run a pipeline", description = "Queues a run of one version (the latest when version is "
        + "left out) for a worker. The version is checked again first, since sources may have changed: one with "
        + "problems, or one writing to a source that does not allow writing, is refused. Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "202", description = "Queued; the body is the run, and Location its address"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such pipeline or version"),
        @APIResponse(responseCode = "409", description = "The version has problems that stop it running")})
    public Response run(@PathParam("name") String name, PipelineViews.RunRequest body) {
        AccessRole role = identity.hasRole(AccessRole.ADMIN_ROLE) ? AccessRole.ADMIN : AccessRole.STEWARD;
        PipelineViews.RunSummary run = pipelines.queueRun(name, body == null ? null : body.version(), who(), role);
        return Response.accepted(run).location(URI.create("/api/v1/pipeline-runs/" + run.id())).build();
    }

    @GET
    @Path("{name}/runs")
    @Operation(summary = "A pipeline's runs", description = "The latest runs, newest first (20 by default, at most "
        + "200). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The runs"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public List<PipelineViews.RunSummary> runs(@PathParam("name") String name, @QueryParam("limit") Integer limit) {
        return pipelines.runs(name, limit == null ? 20 : limit);
    }

    private static jakarta.ws.rs.core.EntityTag etag(PipelineViews.PipelineDetail d) {
        return new jakarta.ws.rs.core.EntityTag(Integer.toString(d.latestVersion()));
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}
