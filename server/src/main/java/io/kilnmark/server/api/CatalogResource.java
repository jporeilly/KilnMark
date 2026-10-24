package io.kilnmark.server.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.auth.AccessRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Reading the catalogue: search, assets with their fields and profiles, and runs. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class CatalogResource {

    private final GraphStore store;
    private final ObjectMapper json;
    private final io.kilnmark.kiln.connect.Connectors connectors;

    public CatalogResource(GraphStore store, ObjectMapper json, io.kilnmark.kiln.connect.Connectors connectors) {
        this.store = store;
        this.json = json;
        this.connectors = connectors;
    }

    /** Keyword search over asset names, descriptions and field names. */
    @GET
    @Path("search")
    public Views.SearchResult search(@QueryParam("q") String q, @QueryParam("limit") Integer limit) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q: what to search for");
        }
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return new Views.SearchResult(q.trim(), store.search(q, n).stream().map(Views.SearchHit::of).toList());
    }

    @GET
    @Path("assets/{urn: .+}")
    public Views.Asset asset(@PathParam("urn") String urn) {
        Records.AssetDetail a = store.asset(urn)
            .orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No asset " + urn));
        return new Views.Asset(a.urn(), a.source(), a.kind(), a.container(), a.name(), a.description(), a.primaryKey(),
            a.rowCount(), a.rowCountComplete(), a.firstSeenAt(), a.lastScannedAt(), a.removedAt(),
            a.fields().stream().map(f -> new Views.Field(f.name(), f.position(), f.sourceType(), f.kind(), f.nullable(),
                f.description(), parse(f.profileJson()), f.profiledAt())).toList());
    }

    /** The kinds of source this server can read, with the settings each takes. */
    @GET
    @Path("connectors")
    public java.util.List<Views.Connector> connectors() {
        return connectors.all().stream().map(CatalogResource::view).toList();
    }

    /** A connector as the form needs it: its settings, plus its container setting if it does not list it. */
    static Views.Connector view(io.kilnmark.kiln.connect.Connector c) {
        var kind = c.containerKind();
        java.util.List<Views.Setting> settings = new java.util.ArrayList<>(c.settings().stream()
            .map(s -> new Views.Setting(s.key(), s.label(), s.required(), s.hint(), s.multiple())).toList());
        if (settings.stream().noneMatch(s -> s.key().equals(kind.setting()))) {
            settings.add(new Views.Setting(kind.setting(), kind.label(), false, "Leave empty to scan them all, or "
                + "choose them from a list", true));
        }
        return new Views.Connector(c.kind(), c.displayName(), java.util.List.copyOf(settings), kind.setting(),
            kind.label(), kind.counted());
    }

    @GET
    @Path("runs/{id}")
    public Views.Run run(@PathParam("id") long id) {
        return Views.Run.of(store.run(id).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No run " + id)));
    }

    private JsonNode parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
