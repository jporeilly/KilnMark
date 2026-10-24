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

    private final io.kilnmark.server.KilnmarkConfig config;

    public CatalogResource(GraphStore store, ObjectMapper json, io.kilnmark.kiln.connect.Connectors connectors,
                           io.kilnmark.server.KilnmarkConfig config) {
        this.store = store;
        this.json = json;
        this.connectors = connectors;
        this.config = config;
    }

    /**
     * Search over asset names, descriptions and field names, by words and, when an embedding model
     * is set, by meaning (ADR 0016). {@code mode=keyword} matches words only.
     */
    @GET
    @Path("search")
    public io.kilnmark.server.ai.AiViews.SearchResult search(@QueryParam("q") String q,
                                                            @QueryParam("limit") Integer limit,
                                                            @QueryParam("mode") String mode) {
        if (q == null || q.isBlank()) {
            throw new IllegalArgumentException("q: what to search for");
        }
        if (q.length() > 500) {
            throw new IllegalArgumentException("q: 500 characters at most");
        }
        if (mode != null && !mode.equals("keyword") && !mode.equals("hybrid")) {
            throw new IllegalArgumentException("mode: hybrid (the default) or keyword");
        }
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 100));
        return io.kilnmark.server.ai.AiViews.SearchResult.of(q.trim(), hybrid.search(q.trim(),
            io.kilnmark.server.ai.HybridSearch.Filters.NONE, n, io.kilnmark.server.ai.Callers.of(identity),
            !"keyword".equals(mode)));
    }

    // Search by words and meaning (ADR 0016); field injection keeps the constructor as it is.
    @jakarta.inject.Inject
    io.kilnmark.server.ai.HybridSearch hybrid;

    @jakarta.inject.Inject
    io.quarkus.security.identity.SecurityIdentity identity;

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
            .map(s -> new Views.Setting(s.key(), s.label(), s.required(), s.hint(), s.multiple(), c.example(s.key())))
            .toList());
        if (settings.stream().noneMatch(s -> s.key().equals(kind.setting()))) {
            settings.add(new Views.Setting(kind.setting(), kind.label(), false, "Leave empty to scan them all, or "
                + "choose them from a list", true, null));
        }
        java.util.List<String> capabilities = java.util.Arrays.stream(io.kilnmark.kiln.connect.Connector.Capability
            .values()).filter(c.capabilities()::contains).map(x -> x.name().toLowerCase(java.util.Locale.ROOT)).toList();
        return new Views.Connector(c.kind(), c.displayName(), java.util.List.copyOf(settings), kind.setting(),
            kind.label(), kind.counted(), c.category(), c.family(), capabilities, c.formats(), c.icon(),
            c.docsAnchor(), !c.addressSettings("db.example.com", null, "db").isEmpty(), c.driver() == null);
    }

    /**
     * The catalogue for the "Add connection" gallery: every category in order, each with its built
     * connectors and, unless {@code kilnmark.connectors.show-planned} is false, the planned ones.
     */
    @GET
    @Path("connectors/catalogue")
    public Views.ConnectorCatalogue catalogue() {
        boolean planned = config.connectors().showPlanned();
        java.util.List<Views.ConnectorCategory> out = new java.util.ArrayList<>();
        for (String[] cat : PlannedConnectors.CATEGORIES) {
            java.util.List<Views.Connector> built = connectors.all().stream()
                .filter(c -> c.category().equals(cat[0])).map(CatalogResource::view).toList();
            java.util.List<Views.PlannedConnector> later = planned
                ? PlannedConnectors.ALL.stream().filter(p -> p.category().equals(cat[0])).toList() : java.util.List.of();
            if (cat[0].equals("other") && built.isEmpty()) {
                continue;
            }
            out.add(new Views.ConnectorCategory(cat[0], cat[1], built, later));
        }
        return new Views.ConnectorCatalogue(planned, out);
    }

    /**
     * The settings that point a source of {@code kind} at a host, port and database (for example a
     * JDBC URL), as the connection panel's host fields build them.
     */
    @GET
    @Path("connectors/{kind}/address")
    public java.util.Map<String, String> address(@PathParam("kind") String kind, @QueryParam("host") String host,
                                                 @QueryParam("port") String port,
                                                 @QueryParam("database") String database) {
        io.kilnmark.kiln.connect.Connector c = connectors.get(kind).orElseThrow(() -> ApiError.of(
            Response.Status.NOT_FOUND, "No connector of kind " + kind));
        return SourceService.address(c, host, port, database);
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
