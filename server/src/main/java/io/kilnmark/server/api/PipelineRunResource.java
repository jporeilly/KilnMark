package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** One pipeline run: its steps, what it wrote and the lineage it recorded; and cancelling it. */
@Path("/api/v1/pipeline-runs")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class PipelineRunResource {

    private final PipelineService pipelines;
    private final SecurityIdentity identity;

    /** Who is looking: a failed run's messages may quote values from the data (see PipelineService). */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.Sights sights;

    @jakarta.inject.Inject
    com.fasterxml.jackson.databind.ObjectMapper json;

    public PipelineRunResource(PipelineService pipelines, SecurityIdentity identity) {
        this.pipelines = pipelines;
        this.identity = identity;
    }

    @GET
    @Path("{id}")
    @Operation(summary = "A pipeline run", description = "The run's status and timings and, per step, rows in and out "
        + "per port, peak memory, when it started, had its first row, handed on its last and ended, and the error "
        + "where it failed; the assets its writes wrote, whether they are now catalogued, the assets it read and the "
        + "lineage recorded between them; and, while it runs, where its rows are (progress, every two seconds), with "
        + "the step holding it back. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public PipelineViews.RunDetail get(@PathParam("id") long id) {
        return pipelines.run(id, sights);
    }

    @GET
    @Path("{id}/events")
    @Operation(summary = "A pipeline run's log", description = "What happened in the run, in order: each step "
        + "starting, its first row in and out, its last row out and how it ended, the engine's notes and the run's "
        + "own. Filter by step and by level (info for all, warn for warnings and errors, error for errors); page with "
        + "after (the next value of the page before) and limit (at most 1000). An event's detail may quote values "
        + "from the data and is held back as the run's error is. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "A page of the log"),
        @APIResponse(responseCode = "400", description = "An unknown level"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public PipelineViews.RunEvents events(@PathParam("id") long id, @QueryParam("step") String step,
                                          @QueryParam("level") String level, @QueryParam("after") String after,
                                          @QueryParam("limit") String limit) {
        return pipelines.events(id, step, level, PipelineService.count(after, "after", 0),
            PipelineService.count(limit, "limit", 200), sights);
    }

    @GET
    @Path("{id}/series")
    @Operation(summary = "A pipeline run's rows a second over time", description = "Each step's rows a second "
        + "and how full its output queue was, at each kept snapshot after after (ms from the worker's start on "
        + "the run; 0 or left out for all). At most 900 points; when stride changes the points were merged, so "
        + "read again from 0. Counts only. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The points"),
        @APIResponse(responseCode = "400", description = "after is not a whole number"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public PipelineViews.RunSeries series(@PathParam("id") long id, @QueryParam("after") String after) {
        return pipelines.series(id, after == null || after.isBlank() ? -1 : PipelineService.count(after, "after", 0));
    }

    @GET
    @Path("{id}/events/download")
    @Produces({MediaType.TEXT_PLAIN, "application/x-ndjson"})
    @Operation(summary = "Download a pipeline run's log", description = "The whole log, with the same filters and "
        + "rules as the log itself, as text (format=text, the default: one line an event) or as NDJSON "
        + "(format=ndjson: one JSON object a line), streamed as it is read. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The log as a file"),
        @APIResponse(responseCode = "400", description = "An unknown level or format"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public Response download(@PathParam("id") long id, @QueryParam("step") String step,
                             @QueryParam("level") String level, @QueryParam("format") String format) {
        boolean ndjson = "ndjson".equals(format);
        if (format != null && !ndjson && !"text".equals(format)) {
            throw new IllegalArgumentException("format: text or ndjson");
        }
        PipelineService.LogReader log = pipelines.allEvents(id, step, level, sights); // 404 and 400 before streaming
        jakarta.ws.rs.core.StreamingOutput body = out -> {
            java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(out,
                java.nio.charset.StandardCharsets.UTF_8));
            log.read(e -> {
                w.write(ndjson ? json.writeValueAsString(e) : PipelineService.logLine(e));
                w.write('\n');
            });
            w.flush();
        };
        return Response.ok(body, ndjson ? "application/x-ndjson; charset=utf-8" : "text/plain; charset=utf-8")
            .header("Content-Disposition", "attachment; filename=\"pipeline-run-" + id + "-log."
                + (ndjson ? "ndjson" : "txt") + "\"")
            .build();
    }

    @POST
    @Path("{id}/cancel")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    @Operation(summary = "Cancel a pipeline run", description = "A queued run is cancelled at once; a running one "
        + "stops within a few seconds (cancelRequested is true until it has). Rows already written stay written. "
        + "Role: steward.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run as it now is"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not a steward"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "409", description = "The run has already finished")})
    public PipelineViews.RunSummary cancel(@PathParam("id") long id) {
        String who = identity.isAnonymous() ? "anonymous"
            : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
        return pipelines.cancel(id, who);
    }
}
