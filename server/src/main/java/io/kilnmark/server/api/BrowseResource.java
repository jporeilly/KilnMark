package io.kilnmark.server.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.BrowseStore;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.RelationshipStore;
import io.kilnmark.server.auth.AccessRole;
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

/**
 * Browsing the catalogue as a tree (source, container, asset), schema diagrams, and
 * relationships: viewers read; stewards accept or reject inferred relationships.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class BrowseResource {

    /** Tables a diagram shows by default, and at most. */
    static final int DIAGRAM_DEFAULT = 60;
    static final int DIAGRAM_MAX = 150;
    /** Related tables outside a container added to its diagram at most. */
    static final int EXTERNAL_MAX = 20;
    /** Relationships a diagram reads at most. */
    static final int DIAGRAM_RELATIONSHIPS = 1_000;

    private final BrowseStore browse;
    private final RelationshipStore relationships;
    private final GraphStore store;
    private final ObjectMapper json;
    private final SecurityIdentity identity;

    public BrowseResource(BrowseStore browse, RelationshipStore relationships, GraphStore store, ObjectMapper json,
                          SecurityIdentity identity) {
        this.browse = browse;
        this.relationships = relationships;
        this.store = store;
        this.json = json;
        this.identity = identity;
    }

    @GET
    @Path("browse/sources")
    public List<BrowseViews.Source> sources() {
        return browse.sources().stream().map(BrowseViews.Source::of).toList();
    }

    /** The containers (schemas, folders, buckets) of a source, with asset counts; {@code q} filters. */
    @GET
    @Path("browse/sources/{name}/containers")
    public BrowseViews.Page<BrowseViews.Container> containers(@PathParam("name") String name, @QueryParam("q") String q,
                                                              @QueryParam("offset") Integer offset,
                                                              @QueryParam("limit") Integer limit) {
        requireSource(name);
        filter(q);
        int o = offset(offset);
        int n = limit(limit, 100, 500);
        var page = browse.containers(name, q, o, n);
        return new BrowseViews.Page<>(page.items().stream().map(BrowseViews.Container::of).toList(), page.total(), o, n);
    }

    /** The assets of one container; {@code container} may be empty (files at the top of a folder). */
    @GET
    @Path("browse/sources/{name}/assets")
    public BrowseViews.Page<BrowseViews.Asset> assets(@PathParam("name") String name,
                                                      @QueryParam("container") String container,
                                                      @QueryParam("q") String q, @QueryParam("offset") Integer offset,
                                                      @QueryParam("limit") Integer limit) {
        if (container == null) {
            throw new IllegalArgumentException("container: the schema, folder or bucket to list (empty for the top)");
        }
        requireSource(name);
        filter(q);
        int o = offset(offset);
        int n = limit(limit, 100, 500);
        var page = browse.assets(name, container, q, o, n);
        return new BrowseViews.Page<>(page.items().stream().map(BrowseViews.Asset::of).toList(), page.total(), o, n);
    }

    /**
     * A diagram: of a container ({@code source} and {@code container}, optionally filtered by
     * {@code q}), of an asset and its neighbours ({@code asset}, {@code depth} 1 or 2), or of chosen
     * assets ({@code urn}, repeated). At most {@code limit} tables (default 60, at most 150).
     */
    @GET
    @Path("diagram")
    public BrowseViews.Diagram diagram(@QueryParam("source") String source, @QueryParam("container") String container,
                                       @QueryParam("q") String q, @QueryParam("asset") String asset,
                                       @QueryParam("depth") Integer depth, @QueryParam("urn") List<String> urns,
                                       @QueryParam("limit") Integer limit) {
        int n = limit(limit, DIAGRAM_DEFAULT, DIAGRAM_MAX);
        filter(q);
        if (asset != null && !asset.isBlank()) {
            if (store.asset(asset).isEmpty()) {
                throw ApiError.of(Response.Status.NOT_FOUND, "No asset " + asset);
            }
            int d = depth == null ? 1 : depth;
            if (d < 1 || d > 2) {
                throw new IllegalArgumentException("depth: 1 or 2");
            }
            return neighbourhood(asset, d, n);
        }
        if (urns != null && !urns.isEmpty()) {
            List<String> chosen = urns.stream().filter(u -> u != null && !u.isBlank()).distinct().toList();
            List<BrowseStore.Table> tables = browse.tables(chosen.subList(0, Math.min(n, chosen.size())));
            return diagram(tables, Set.of(), relationships.among(urnsOf(tables), false, DIAGRAM_RELATIONSHIPS),
                chosen.size(), n);
        }
        if (source == null || container == null) {
            throw new IllegalArgumentException(
                "Ask for a diagram of a container (source and container), an asset (asset) or chosen assets (urn)");
        }
        requireSource(source);
        BrowseStore.Page<BrowseStore.Table> page = browse.containerTables(source, container, q, n);
        List<BrowseStore.Table> tables = new ArrayList<>(page.items());
        List<String> inside = urnsOf(tables);
        List<RelationshipStore.Relationship> rels = relationships.among(inside, true, DIAGRAM_RELATIONSHIPS);
        // Tables in other containers or sources that these refer to, or that refer to them.
        Set<String> outside = new LinkedHashSet<>();
        for (RelationshipStore.Relationship r : rels) {
            if (!r.status().equals("rejected")) {
                for (String u : List.of(r.from().urn(), r.to().urn())) {
                    if (!inside.contains(u) && outside.size() < EXTERNAL_MAX) {
                        outside.add(u);
                    }
                }
            }
        }
        tables.addAll(browse.tables(outside));
        Set<String> shown = new HashSet<>(urnsOf(tables));
        rels = rels.stream().filter(r -> shown.contains(r.from().urn()) && shown.contains(r.to().urn())).toList();
        return diagram(tables, outside, rels, page.total(), n);
    }

    /** {@code start} and the assets related to it, level by level: one query per level, capped. */
    private BrowseViews.Diagram neighbourhood(String start, int depth, int limit) {
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        List<String> frontier = List.of(start);
        boolean more = false;
        for (int level = 0; level < depth && !frontier.isEmpty(); level++) {
            List<String> next = new ArrayList<>();
            for (RelationshipStore.Relationship r : relationships.among(frontier, true, DIAGRAM_RELATIONSHIPS)) {
                if (r.status().equals("rejected")) {
                    continue;
                }
                for (String other : List.of(r.from().urn(), r.to().urn())) {
                    if (seen.contains(other)) {
                        continue;
                    }
                    if (seen.size() >= limit) {
                        more = true;
                        continue;
                    }
                    seen.add(other);
                    next.add(other);
                }
            }
            frontier = next;
        }
        List<BrowseStore.Table> tables = browse.tables(seen);
        int total = more ? seen.size() + 1 : seen.size();
        return diagram(tables, Set.of(), relationships.among(seen, false, DIAGRAM_RELATIONSHIPS), total, limit);
    }

    private BrowseViews.Diagram diagram(List<BrowseStore.Table> tables, Set<String> external,
                                        List<RelationshipStore.Relationship> rels, int total, int limit) {
        Set<String> foreign = new HashSet<>();
        for (RelationshipStore.Relationship r : rels) {
            if (!r.status().equals("rejected")) {
                r.from().fields().forEach(f -> foreign.add(r.from().urn() + '\u0000' + f));
            }
        }
        List<BrowseViews.Table> boxes = tables.stream().map(t -> new BrowseViews.Table(t.urn(), t.source(),
            t.container(), t.name(), t.kind(), BrowseViews.format(t.kind(), t.name()), t.rowCount(), t.primaryKey(),
            external.contains(t.urn()), t.columns().stream().map(c -> new BrowseViews.Column(c.name(), c.sourceType(),
                c.kind(), c.nullable(), t.primaryKey().contains(c.name()), foreign.contains(t.urn() + '\u0000' + c.name())))
                .toList())).toList();
        return new BrowseViews.Diagram(boxes, rels.stream().map(this::view).toList(), total, limit, total > limit);
    }

    /**
     * A page of relationships, filtered by {@code asset} (into and out of it), {@code source}
     * (either end), {@code status} and {@code kind}. 100 to a page for an asset, 50 otherwise; at most 500.
     */
    @GET
    @Path("relationships")
    public BrowseViews.Page<BrowseViews.Relationship> relationships(@QueryParam("asset") String asset,
                                                                    @QueryParam("source") String source,
                                                                    @QueryParam("status") String status,
                                                                    @QueryParam("kind") String kind,
                                                                    @QueryParam("offset") Integer offset,
                                                                    @QueryParam("limit") Integer limit) {
        if (status != null && !List.of("proposed", "accepted", "rejected").contains(status)) {
            throw new IllegalArgumentException("status: proposed, accepted or rejected");
        }
        if (kind != null && !List.of("declared", "inferred").contains(kind)) {
            throw new IllegalArgumentException("kind: declared or inferred");
        }
        boolean byAsset = asset != null && !asset.isBlank();
        if (byAsset && store.asset(asset).isEmpty()) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No asset " + asset);
        }
        if (source != null) {
            requireSource(source);
        }
        int o = offset(offset);
        int n = limit(limit, byAsset ? 100 : 50, 500);
        RelationshipStore.Page page = relationships.list(byAsset ? asset : null, source, status, kind, o, n);
        return new BrowseViews.Page<>(page.items().stream().map(this::view).toList(), page.total(), o, n);
    }

    @GET
    @Path("relationships/{id}")
    public BrowseViews.Relationship relationship(@PathParam("id") long id) {
        return view(relationships.get(id).orElseThrow(() -> notFound(id)));
    }

    /** Accepts an inferred relationship: a steward confirms the join is real. */
    @POST
    @Path("relationships/{id}/accept")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public BrowseViews.Relationship accept(@PathParam("id") long id) {
        return decide(id, "accepted");
    }

    /** Rejects an inferred relationship: it is hidden from diagrams and not proposed again. */
    @POST
    @Path("relationships/{id}/reject")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public BrowseViews.Relationship reject(@PathParam("id") long id) {
        return decide(id, "rejected");
    }

    private BrowseViews.Relationship decide(long id, String status) {
        try {
            return view(relationships.decide(id, status, identity.getPrincipal().getName())
                .orElseThrow(() -> notFound(id)));
        } catch (IllegalStateException e) {
            throw ApiError.of(Response.Status.CONFLICT, e.getMessage());
        }
    }

    private static jakarta.ws.rs.WebApplicationException notFound(long id) {
        return ApiError.of(Response.Status.NOT_FOUND, "No relationship " + id);
    }

    private BrowseViews.Relationship view(RelationshipStore.Relationship r) {
        return new BrowseViews.Relationship(r.id(), end(r.from()), end(r.to()), r.kind(), r.status(), r.name(),
            r.confidence(), parse(r.evidenceJson()), r.decidedBy(), r.decidedAt(), r.foundAt(), r.refreshedAt());
    }

    private static BrowseViews.End end(RelationshipStore.End e) {
        return new BrowseViews.End(e.urn(), e.source(), e.container(), e.name(), e.kind(), e.fields());
    }

    private JsonNode parse(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void requireSource(String name) {
        if (store.source(name).isEmpty()) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No source called " + name);
        }
    }

    private static List<String> urnsOf(List<BrowseStore.Table> tables) {
        return tables.stream().map(BrowseStore.Table::urn).toList();
    }

    /** A name filter: plain text, at most 200 characters. */
    private static void filter(String q) {
        if (q != null && q.length() > 200) {
            throw new IllegalArgumentException("q: at most 200 characters");
        }
    }

    private static int offset(Integer offset) {
        if (offset != null && offset < 0) {
            throw new IllegalArgumentException("offset: 0 or more");
        }
        return offset == null ? 0 : offset;
    }

    private static int limit(Integer limit, int byDefault, int max) {
        if (limit != null && (limit < 1 || limit > max)) {
            throw new IllegalArgumentException("limit: 1 to " + max);
        }
        return limit == null ? byDefault : limit;
    }
}
