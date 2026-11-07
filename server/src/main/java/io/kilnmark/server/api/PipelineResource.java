package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.kilnmark.server.pipelines.PredictionViews;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
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
 * they are written, and run by workers. Viewers read; designers add, change and delete them;
 * stewards run them. A pipeline that writes also needs each target source to allow writing, which a
 * designer or an admin sets.
 */
@Path("/api/v1/pipelines")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class PipelineResource {

    private final PipelineService pipelines;
    private final SecurityIdentity identity;

    /** Previews at a node: the first rows leaving one node, read live and never kept. */
    @jakarta.inject.Inject
    io.kilnmark.server.pipelines.PipelinePreviews previews;

    /** The audit log, for a deleted pipeline's schedules. */
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;

    /** The team addresses an admin added, which owners may name (ADR 0053). */
    @jakarta.inject.Inject
    io.kilnmark.server.notify.NotificationStore notificationStore;

    /** Who is looking: a failed run's messages may quote values from the data (see PipelineService). */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    /** Starting and reading runs for callers that wait, with request keys (ADR 0047). */
    @jakarta.inject.Inject
    RunRequests runRequests;

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
        return pipelines.list(sights);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Add a pipeline", description = "Saves a new pipeline with its YAML as version 1. Names are a "
        + "small letter followed by small letters, digits and dashes, at most 63 characters, and cannot change. A "
        + "pipeline with problems is saved as a draft (valid false) and cannot run until they are fixed. The ETag is "
        + "the latest version, to send back in If-Match when saving the next. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Added; the body is the pipeline with its version and problems"),
        @APIResponse(responseCode = "400", description = "No YAML, a name that cannot be used, or YAML over the size "
            + "limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or a read runs a query and the caller may "
            + "not see unmasked values"),
        @APIResponse(responseCode = "409", description = "A pipeline with that name exists")})
    public Response create(PipelineViews.NewPipeline body) {
        PipelineViews.PipelineDetail d = pipelines.create(body, who(), sights);
        return Response.created(URI.create("/api/v1/pipelines/" + d.name())).tag(etag(d)).entity(d).build();
    }

    @POST
    @Path("validate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Check pipeline YAML", description = "Checks YAML without saving or running it: the problems, "
        + "each with its line, column and node, and each node's ports and the columns leaving each port where they "
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

    @POST
    @Path("predict")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Predict a pipeline's quality", description = "Estimates the quality of each write and gate "
        + "from the catalogue's metadata about what the pipeline reads (profiles, value shapes, relationships and "
        + "rule results), carried through its nodes, without reading any data: each one's score overall and per "
        + "dimension, what could not be told and what was assumed, and the rows expected on each node's ports. "
        + "Every figure is an estimate. For YAML being edited, valid or not. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The prediction"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in")})
    public PredictionViews.Prediction predict(PredictionViews.PredictRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return pipelines.predict(body.yaml(), sights);
    }

    @POST
    @Path("suggest")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Suggest improvements", description = "Nodes that would raise the predicted quality of a "
        + "write, each with its predicted effect (an estimate) and where it goes. Kilnmark's own come from the "
        + "metadata with no model; when a model is set for pipeline suggestions and ai is not false, it ranks, "
        + "explains and adds to them, seeing only the metadata of sources whose AI rule allows it, never values. "
        + "A model that is off, refused or failing leaves Kilnmark's own, and ai says why. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The suggestions, best first"),
        @APIResponse(responseCode = "400", description = "No YAML, or YAML over the size limit"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer")})
    public PredictionViews.Advice suggest(PredictionViews.SuggestRequest body) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return pipelines.suggest(body.yaml(), !Boolean.FALSE.equals(body.ai()), who(), sights);
    }

    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Preview a node", description = "The first rows leaving one output of one node of pipeline "
        + "YAML as it stands, saved or not: its columns with their kinds, the rows (100 by default, at most 1,000), "
        + "how long it took and, when a node before it reads all its rows first (an aggregate, a lookup's lookup "
        + "side), that in words. Only the node and the nodes upstream of it run, and nothing is written: a preview "
        + "needing a write, or a node that could change anything, is refused. Read live and never kept. For all but "
        + "admins, columns marked sensitive may only pass through unchanged and are masked; a preview in which any "
        + "node looks at their values (an expression, a key, a cast or a gate's rule) is refused. At most 30 "
        + "seconds and 50 nodes; one preview per person at a time, six a minute. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The rows"),
        @APIResponse(responseCode = "400", description = "No YAML or node, YAML over the size limit or with problems "
            + "in the part that would run, an unknown node or port, rows out of range, or a node that cannot run in "
            + "a preview"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or a node would look at the values of a "
            + "column marked sensitive"),
        @APIResponse(responseCode = "422", description = "The preview ran but could not give rows: a node failed, "
            + "it took too long, or a source could not be reached"),
        @APIResponse(responseCode = "429", description = "A preview of yours is running, or too many previews")})
    public Response preview(PipelineViews.PreviewRequest body,
                            @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\", \"node\": \"...\"}");
        }
        return noStore(previews.preview(body.yaml(), body.node(), body.port(), body.rows(), who(), role(), sights,
            abandoned(request)));
    }

    @POST
    @Path("{name}/versions/{n}/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Preview a node of a version", description = "As POST /api/v1/pipelines/preview, for a saved "
        + "version: send node, and port and rows if wanted. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The rows"),
        @APIResponse(responseCode = "400", description = "No node, an unknown node or port, rows out of range, or a "
            + "node that cannot run in a preview"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or a node would look at the values of a "
            + "column marked sensitive"),
        @APIResponse(responseCode = "404", description = "No such pipeline or version"),
        @APIResponse(responseCode = "422", description = "The preview ran but could not give rows"),
        @APIResponse(responseCode = "429", description = "A preview of yours is running, or too many previews")})
    public Response previewVersion(@PathParam("name") String name, @PathParam("n") int n,
                                   PipelineViews.PreviewRequest body,
                                   @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"node\": \"...\"}");
        }
        return noStore(previews.previewVersion(name, n, body.node(), body.port(), body.rows(), who(), role(), sights,
            abandoned(request)));
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
        PipelineViews.PipelineDetail d = pipelines.get(name, sights);
        return Response.ok(d).tag(etag(d)).build();
    }

    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Save a new version", description = "Saves the YAML as the pipeline's next version, with a "
        + "message saying what changed; earlier versions stay as they were. With If-Match set to the latest version "
        + "as you read it (the ETag), the save is refused (409) when someone has saved another since. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Saved; the body is the pipeline with the new version"),
        @APIResponse(responseCode = "400", description = "No YAML, YAML over the size limit, or an If-Match that is "
            + "not a version number"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer, or a read runs a query and the caller may "
            + "not see unmasked values"),
        @APIResponse(responseCode = "404", description = "No such pipeline"),
        @APIResponse(responseCode = "409", description = "Someone else saved a version after the one in If-Match")})
    public Response save(@PathParam("name") String name, @HeaderParam("If-Match") String ifMatch,
                         PipelineViews.PipelineChange body) {
        PipelineViews.PipelineDetail d = pipelines.save(name, PipelineService.readVersion(ifMatch), body, who(),
            sights);
        return Response.ok(d).tag(etag(d)).build();
    }

    @DELETE
    @Path("{name}")
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Delete a pipeline", description = "Deletes the pipeline with its versions, runs and schedules. "
        + "What its runs wrote, and the lineage they recorded, stay in the catalogue. Refused while a run is queued "
        + "or running. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer"),
        @APIResponse(responseCode = "404", description = "No such pipeline"),
        @APIResponse(responseCode = "409", description = "A run is queued or running, or the pipeline or one of its "
            + "schedules was busy for a moment (nothing was deleted; try again)")})
    public Response delete(@PathParam("name") String name) {
        // its schedules are deleted with it, in the same transaction: each is recorded (ADR 0029, ADR 0046)
        io.kilnmark.server.schedules.ScheduleService.recordDeletedWith(audit,
            io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.schedules.ScheduleStore.Target.PIPELINE,
            name, pipelines.deleteWithSchedules(name, who()));
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

    @GET
    @Path("{name}/versions/{n}/prediction")
    @Operation(summary = "Predict a version's quality", description = "The predicted quality of a saved version, as "
        + "POST /api/v1/pipelines/predict works it out, from the catalogue as it is now. Estimates only; no data is "
        + "read. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The prediction"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline or version")})
    public PredictionViews.Prediction prediction(@PathParam("name") String name, @PathParam("n") int n) {
        return pipelines.predictVersion(name, n, sights);
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
        pipelines.get(name, sights);
        if (body == null) {
            throw new IllegalArgumentException("Send {\"yaml\": \"...\"}");
        }
        return pipelines.validate(body.yaml(), name);
    }

    @POST
    @Path("{name}/runs")
    @Consumes({MediaType.APPLICATION_JSON, MediaType.WILDCARD})
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @RunOnVirtualThread
    @Operation(summary = "Run a pipeline", description = "Queues a run of one version (the latest when version is "
        + "left out) for a worker. The version is checked again first, since sources may have changed: one with "
        + "problems, or one writing to a source that does not allow writing, is refused. With wait (whole seconds, "
        + "at most kilnmark.api.max-wait, 15 minutes by default) the answer comes when the run has ended (200) or "
        + "when the wait is up (202): read the run with wait to go on waiting. A start is never refused for want of "
        + "a wait place: with as many waiting as allowed, it answers at once (202). With an Idempotency-Key header "
        + "(1 to 200 printable characters), the same key from the same token or person for the same request (this "
        + "pipeline, version and parameter values) within 24 hours gives the run the first request started (200, "
        + "with Idempotent-Replayed: true) instead of another, so a retry never runs the work twice; the key and "
        + "its run are kept together or not at all. With "
        + "Accept: text/plain the run comes as name: value lines (id, kind, name, status, ended, page, message). "
        + "Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run ended within the wait, or the key had started it; "
            + "the body is the run, and Location its address"),
        @APIResponse(responseCode = "202", description = "Queued (and, with wait, still going when the wait was "
            + "up); the body is the run, and Location its address"),
        @APIResponse(responseCode = "400", description = "A wait that is not a whole number of seconds, or a key "
            + "that is not 1 to 200 printable characters"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such pipeline or version"),
        @APIResponse(responseCode = "409", description = "The version has problems that stop it running, or a run "
            + "is already queued or running"),
        @APIResponse(responseCode = "422", description = "The key started a different request in the last 24 "
            + "hours: another pipeline or flow, version or parameter values")})
    public Response run(@PathParam("name") String name, @QueryParam("wait") String wait,
                        @HeaderParam(RunWaits.KEY_HEADER) String key, PipelineViews.RunRequest body,
                        @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                        @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        AccessRole role = AccessRole.highest(identity::hasRole);
        Integer version = body == null ? null : body.version();
        Map<String, Object> parameters = body == null || body.parameters() == null ? Map.of() : body.parameters();
        return runRequests.start("pipeline", name, version, parameters, wait, key, identity, headers, request,
            () -> pipelines.checkRun(name, version, parameters),
            checked -> pipelines.queueChecked(checked, io.kilnmark.server.audit.AuditLog.requester(identity), role).id(), pipelines::runStatus, id -> {
                PipelineViews.RunSummary run = pipelines.runSummary(id, sights);
                return new RunRequests.Shown(run, run.pipeline(), run.status(), run.error());
            });
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
        return pipelines.runs(name, limit == null ? 20 : limit, sights);
    }

    /** A preview as a response no browser or proxy keeps: the rows are the sources' data. */
    private static Response noStore(PipelineViews.NodePreviewView preview) {
        jakarta.ws.rs.core.CacheControl none = new jakarta.ws.rs.core.CacheControl();
        none.setNoStore(true);
        none.setNoCache(true);
        return Response.ok(preview).cacheControl(none).build();
    }

    /** Whether the caller has gone: the connection carrying this request was closed. */
    private static java.util.function.BooleanSupplier abandoned(io.vertx.core.http.HttpServerRequest request) {
        return () -> request != null && request.response().closed();
    }

    private AccessRole role() {
        return AccessRole.highest(identity::hasRole);
    }

    private static jakarta.ws.rs.core.EntityTag etag(PipelineViews.PipelineDetail d) {
        return new jakarta.ws.rs.core.EntityTag(Integer.toString(d.latestVersion()));
    }

    @GET
    @Path("{name}/owners")
    @Operation(summary = "A pipeline's owners", description = "Who answers for the pipeline (ADR 0053): told when its runs "
        + "fail after their last try, recover, or take longer than expected. Each is a person's sign-in name or a "
        + "team address; team addresses are shown to designers and admins only. Owners have no rights through "
        + "owning. With none, admins are told in their place. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The owners"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public io.kilnmark.server.runs.Owners.Shown owners(@PathParam("name") String name) {
        return pipelines.owners(name, AccessRole.highest(identity::hasRole));
    }

    @PUT
    @Path("{name}/owners")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    @Operation(summary = "Set a pipeline's owners", description = "Replaces the owners: 0 to 10 sign-in names and team "
        + "addresses (a team address must be one an admin added under Notifications). Owners are kept outside the "
        + "YAML, so this makes no new version; each change is an audit event. Role: designer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Set; the body is the owners"),
        @APIResponse(responseCode = "400", description = "Too many, an entry that is not a sign-in name, or a team "
            + "address no admin added"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a designer"),
        @APIResponse(responseCode = "404", description = "No such pipeline")})
    public io.kilnmark.server.runs.Owners.Shown setOwners(@PathParam("name") String name, PipelineViews.OwnersChange body) {
        AccessRole role = AccessRole.highest(identity::hasRole);
        java.util.Set<String> teams = new java.util.HashSet<>();
        notificationStore.teams().forEach(t -> {
            if (t.subscription().email() != null) {
                teams.add(t.subscription().email());
            }
        });
        java.util.List<String> before = pipelines.setOwners(name, body == null ? null : body.owners(), teams, role);
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", pipelines.owners(name, AccessRole.ADMIN).owners().stream()
            .map(io.kilnmark.server.runs.Owners.View::entry).toList());
        audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.PIPELINE_OWNERS_SET, name,
            detail);
        return pipelines.owners(name, role);
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }
}
