package io.kilnmark.server.api;

import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

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

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /** Body of {@code POST /api/v1/sources}. The password is encrypted before it is stored. */
    public record NewSource(String name, String kind, String url, String username, String password,
                            List<String> schemas) {
    }

    private final GraphStore store;
    private final SecretBox secrets;
    private final ScanService scans;

    public SourceResource(GraphStore store, SecretBox secrets, ScanService scans) {
        this.store = store;
        this.secrets = secrets;
        this.scans = scans;
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
        if (body.name() == null || !NAME.matcher(body.name()).matches()) {
            throw new IllegalArgumentException(
                "name: 1 to 63 lower-case letters, digits and hyphens, starting with a letter or digit");
        }
        String kind = body.kind() == null ? "postgresql" : body.kind();
        if (!kind.equals("postgresql")) {
            throw new IllegalArgumentException("kind: only postgresql is supported in this version");
        }
        if (body.url() == null || !body.url().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("url: a PostgreSQL JDBC URL, for example jdbc:postgresql://host:5432/db");
        }
        if (store.source(body.name()).isPresent()) {
            throw ApiError.of(Response.Status.CONFLICT, "A source called " + body.name() + " already exists");
        }
        byte[] secret = body.password() == null || body.password().isEmpty() ? null : secrets.seal(body.password());
        Records.Source s = store.addSource(new Records.NewSource(body.name(), kind, body.url(), body.username(), secret,
            body.schemas() == null ? List.of() : body.schemas()));
        return Response.created(URI.create("/api/v1/sources/" + s.name())).entity(view(s)).build();
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
