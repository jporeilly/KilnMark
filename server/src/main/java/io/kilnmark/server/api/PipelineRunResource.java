package io.kilnmark.server.api;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineViews;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** One pipeline run: its nodes, what it wrote and the lineage it recorded; and cancelling it. */
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

    /** Starting and reading runs for callers that wait, with request keys (ADR 0047). */
    @jakarta.inject.Inject
    RunRequests runRequests;

    public PipelineRunResource(PipelineService pipelines, SecurityIdentity identity) {
        this.pipelines = pipelines;
        this.identity = identity;
    }

    @GET
    @Path("{id}")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN})
    @RunOnVirtualThread
    @Operation(summary = "A pipeline run", description = "The run's status and timings and, per node, rows in and out "
        + "per port, peak memory, when it started, had its first row, handed on its last and ended, and the error "
        + "where it failed; the assets its writes wrote, whether they are now catalogued, the assets it read and the "
        + "lineage recorded between them; and, while it runs, where its rows are (progress, every two seconds), with "
        + "the node holding it back. With wait (whole seconds, at most kilnmark.api.max-wait, 15 minutes by "
        + "default) the answer comes when the run has ended or when the wait is up, whichever is first; the status "
        + "says which. With Accept: text/plain the run comes as name: value lines (id, kind, name, status, ended, "
        + "page, message). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The run", content = @Content(
            mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = PipelineViews.RunDetail.class))),
        @APIResponse(responseCode = "400", description = "A wait that is not a whole number of seconds"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run"),
        @APIResponse(responseCode = "503", description = "With wait, on a run still going: as many requests as "
            + "allowed are waiting, on this server or for this caller; ask again after Retry-After seconds, or "
            + "without wait")})
    public Response get(@PathParam("id") long id, @QueryParam("wait") String wait,
                        @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers,
                        @jakarta.ws.rs.core.Context io.vertx.core.http.HttpServerRequest request) {
        return runRequests.read("pipeline", id, wait, identity, headers, request, pipelines::runStatus, runId -> {
            PipelineViews.RunSummary run = pipelines.runSummary(runId, sights);
            return new RunRequests.Shown(run, run.pipeline(), run.status(), run.error());
        }, () -> pipelines.run(id, sights));
    }

    @GET
    @Path("{id}/events")
    @Operation(summary = "A pipeline run's log", description = "What happened in the run, in order: each node "
        + "starting, its first row in and out, its last row out and how it ended, the engine's notes and the run's "
        + "own. Filter by node and by level (info for all, warn for warnings and errors, error for errors); page with "
        + "after (the next value of the page before) and limit (at most 1000). An event's detail may quote values "
        + "from the data and is held back as the run's error is. Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "A page of the log"),
        @APIResponse(responseCode = "400", description = "An unknown level"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public PipelineViews.RunEvents events(@PathParam("id") long id, @QueryParam("node") String node,
                                          @QueryParam("level") String level, @QueryParam("after") String after,
                                          @QueryParam("limit") String limit) {
        return pipelines.events(id, node, level, PipelineService.count(after, "after", 0),
            PipelineService.count(limit, "limit", 200), sights);
    }

    @GET
    @Path("{id}/series")
    @Operation(summary = "A pipeline run's rows a second over time", description = "Each node's rows a second "
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
    public Response download(@PathParam("id") long id, @QueryParam("node") String node,
                             @QueryParam("level") String level, @QueryParam("format") String format) {
        boolean ndjson = "ndjson".equals(format);
        if (format != null && !ndjson && !"text".equals(format)) {
            throw new IllegalArgumentException("format: text or ndjson");
        }
        PipelineService.LogReader log = pipelines.allEvents(id, node, level, sights); // 404 and 400 before streaming
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

    /** The files runs wrote, and handing them back (ADR 0055). */
    @jakarta.inject.Inject
    io.kilnmark.server.pipelines.WrittenFiles writtenFiles;

    @GET
    @Path("{id}/files")
    @Operation(summary = "The files a pipeline run wrote", description = "Each file its write nodes wrote to a source "
        + "of files, by its index in the run's record (what the download takes): the node, source, path in the "
        + "source, format, rows, and the size and time the write recorded. state says whether it is still there as "
        + "the run wrote it (ready; unrecorded when the run recorded no size, so it may have changed; changed; "
        + "deleted; unavailable), downloadable whether the caller may download it now, and reason why not. Empty "
        + "while the run goes (finished false). Role: viewer.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The files", content = @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = io.kilnmark.server.pipelines.WrittenFiles.Listing.class))),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "404", description = "No such run")})
    public io.kilnmark.server.pipelines.WrittenFiles.Listing files(@PathParam("id") long id) {
        return writtenFiles.list(id, sights);
    }

    @GET
    @Path("{id}/files/{index}")
    @Produces(MediaType.WILDCARD)
    @Operation(summary = "Download a file a pipeline run wrote", description = "The file at index in the run's list "
        + "of files (GET .../files), streamed from the source's folder on this server where its write put it, "
        + "and only while it is still the file the run wrote (its place, size and time as the write recorded "
        + "them). No path is taken from the caller. A file holding a column marked sensitive, or whose columns are "
        + "not in the catalogue, is only for a designer with the unmask grant (ADR 0027, 0038); other files for "
        + "stewards and above, who may see values. Every download and every refusal is in the audit log "
        + "(file.downloaded, file.download_refused). A browser signed in with the identity provider can follow a "
        + "plain link: GET changes nothing. Role: viewer to ask; see above for who gets the file.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The file, as an attachment (text/csv or "
            + "application/vnd.apache.parquet)"),
        @APIResponse(responseCode = "401", description = "Not signed in"),
        @APIResponse(responseCode = "403", description = "Not for this caller: it holds sensitive columns (or "
            + "unknown ones) and the caller may not see sensitive values unmasked, or the caller is a viewer"),
        @APIResponse(responseCode = "404", description = "No such run, or the run wrote no file at that index"),
        @APIResponse(responseCode = "409", description = "The run has not finished, the file has changed since the "
            + "run wrote it, or it cannot be reached (not in a folder on this server, or the way to it changed)"),
        @APIResponse(responseCode = "410", description = "The file, or the source it was written to, has been "
            + "deleted")})
    public Response file(@PathParam("id") long id, @PathParam("index") String index,
                         @jakarta.ws.rs.HeaderParam("Sec-Fetch-Site") String fetchSite,
                         @jakarta.ws.rs.core.Context jakarta.ws.rs.core.HttpHeaders headers) {
        // A page on another site can send the browser here with the session cookie (it is SameSite=Lax):
        // it could not read the file, but would save it on the person's computer and in the audit log as
        // theirs. Browsers say where a request came from (Kilnmark's own pages: same-origin; a typed
        // address: none); scripts send nothing, and are signed in by their token.
        String authorization = headers.getHeaderString("Authorization");
        boolean bearer = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        boolean session = headers.getCookies().keySet().stream().anyMatch(n -> n.startsWith("q_session"));
        io.kilnmark.server.audit.AuditLog.Actor actor = io.kilnmark.server.audit.AuditLog.actor(identity);
        io.kilnmark.server.pipelines.WrittenFiles.Download d = writtenFiles.download(id, index, sights, actor,
            io.kilnmark.server.pipelines.WrittenFiles.crossSite(fetchSite, session, bearer));
        jakarta.ws.rs.core.StreamingOutput body = out -> {
            try {
                io.kilnmark.server.pipelines.WrittenFiles.copy(d.file(), out);
            } catch (java.io.IOException | RuntimeException e) {
                org.jboss.logging.Logger.getLogger(PipelineRunResource.class).warnf("Pipeline run %d's file %s was "
                    + "not sent whole: %s", id, index, e.getMessage());
                writtenFiles.notSent(actor, id, d);
                throw e;
            }
        };
        return Response.ok(body, d.contentType())
            .header("Content-Disposition", "attachment; filename=\"" + d.filename() + "\"")
            .header("Content-Length", d.file().size())
            .header("X-Content-Type-Options", "nosniff")
            .header("Cache-Control", "no-store")
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .build();
    }

    /** HEAD would run the download without sending it, and record it: not offered. */
    @jakarta.ws.rs.HEAD
    @Path("{id}/files/{index}")
    @Operation(hidden = true)
    public Response fileHead(@PathParam("id") long id, @PathParam("index") String index) {
        return Response.status(Response.Status.METHOD_NOT_ALLOWED).header("Allow", "GET").build();
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
