package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.scan.ScanService;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Data sources: register one, list them, start a scan, see its runs. */
@Path("/api/v1/sources")
@Produces(MediaType.APPLICATION_JSON)
public class SourceResource {

    /** Body of {@code POST /api/v1/sources}. The password is encrypted before it is stored. */
    public record NewSource(String name, String kind, String url, String username, String password,
                            List<String> schemas) {
    }

    private final GraphStore store;
    private final SecretBox secrets;
    private final ScanService scans;
    private final SourceImporter importer;

    public SourceResource(GraphStore store, SecretBox secrets, ScanService scans, SourceImporter importer) {
        this.store = store;
        this.secrets = secrets;
        this.scans = scans;
        this.importer = importer;
    }

    @GET
    public List<Views.Source> list() {
        return store.sources().stream().map(this::view).toList();
    }

    @GET
    @Path("{name}")
    public Views.Source get(@PathParam("name") String name) {
        return view(find(name));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response add(NewSource body) {
        if (body == null) {
            throw new IllegalArgumentException("Send the source as JSON");
        }
        String kind = body.kind() == null ? "postgresql" : body.kind();
        String problem = SourceRules.check(body.name(), kind, body.url());
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        if (store.source(body.name()).isPresent()) {
            throw ApiError.of(Response.Status.CONFLICT, "A source called " + body.name() + " already exists");
        }
        byte[] secret = body.password() == null || body.password().isEmpty() ? null : secrets.seal(body.password());
        Records.Source s = store.addSource(new Records.NewSource(body.name(), kind, body.url(), body.username(), secret,
            body.schemas() == null ? List.of() : body.schemas()));
        return Response.created(URI.create("/api/v1/sources/" + s.name())).entity(view(s)).build();
    }

    /**
     * Registers many sources from a CSV file (header row: name, url, username, password,
     * password_ref, schemas). All or nothing; {@code dryRun=true} only checks.
     */
    @POST
    @Path("import")
    @Consumes({"text/csv", MediaType.TEXT_PLAIN})
    public Response importCsv(String body, @QueryParam("dryRun") boolean dryRun) {
        return report(importer.run(importer.fromCsv(body == null ? "" : body), dryRun));
    }

    /** The same as a JSON array of sources. */
    @POST
    @Path("import")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importJson(String body, @QueryParam("dryRun") boolean dryRun) {
        return report(importer.run(importer.fromJson(body == null ? "[]" : body), dryRun));
    }

    private static Response report(SourceImporter.Report r) {
        return Response.status(r.errors() > 0 ? 422 : 200).entity(r).build();
    }

    @POST
    @Path("{name}/scans")
    public Response scan(@PathParam("name") String name) {
        Records.Run run = scans.request(find(name));
        return Response.accepted(Views.Run.of(run)).location(URI.create("/api/v1/runs/" + run.id())).build();
    }

    @GET
    @Path("{name}/runs")
    public List<Views.Run> runs(@PathParam("name") String name, @QueryParam("limit") Integer limit) {
        find(name);
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        return store.runs(name, n).stream().map(Views.Run::of).toList();
    }

    private Records.Source find(String name) {
        return store.source(name).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No source called " + name));
    }

    private Views.Source view(Records.Source s) {
        Records.Run last = store.runs(s.name(), 1).stream().findFirst().orElse(null);
        return new Views.Source(s.name(), s.kind(), s.jdbcUrl(), s.username(), s.schemas(), s.hasSecret(),
            s.createdAt(), Views.Run.of(last));
    }
}
