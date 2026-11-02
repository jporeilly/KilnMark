package io.kilnmark.server.api;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.scan.ScanService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Data sources: register one or many, list them, edit, test and delete them, list what they hold,
 * start a scan, see its runs. Viewers read; stewards also edit, test, delete and scan; admins also
 * add and import sources.
 */
@Path("/api/v1/sources")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class SourceResource {

    /** Data quality: purges stored samples when a source stops keeping them. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.QualityService quality;

    /** Notifications: a failed connection test is told (docs/design/notifications.md). */
    @jakarta.inject.Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;


    /**
     * Body of {@code POST /api/v1/sources}: a connector kind (default {@code postgresql}), its
     * settings (see {@code GET /api/v1/connectors}) and the secret, encrypted before it is stored.
     */
    public record NewSource(String name, String kind, Map<String, String> settings, String password,
                            String displayName) {
    }

    private final GraphStore store;
    private final SecretBox secrets;
    private final ScanService scans;
    private final SourceImporter importer;
    private final Connectors connectors;
    private final SourceService sources;
    private final io.quarkus.security.identity.SecurityIdentity identity;
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;

    public SourceResource(GraphStore store, SecretBox secrets, ScanService scans, SourceImporter importer,
                          Connectors connectors, SourceService sources,
                          io.quarkus.security.identity.SecurityIdentity identity) {
        this.store = store;
        this.secrets = secrets;
        this.scans = scans;
        this.importer = importer;
        this.connectors = connectors;
        this.sources = sources;
        this.identity = identity;
    }

    @GET
    public List<Views.Source> list() {
        Map<Long, Integer> assets = store.assetCounts();
        Map<Long, Map<String, Integer>> formats = store.fileFormats();
        return store.sources().stream().map(s -> view(s, assets, formats)).toList();
    }

    @GET
    @Path("{name}")
    public Views.Source get(@PathParam("name") String name) {
        return view(find(name));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Response add(NewSource body) {
        if (body == null || body.name() == null) {
            throw new IllegalArgumentException("Send the source as JSON with at least a name");
        }
        SourceService.Draft d = sources.draft(new SourceForm(body.name(), body.kind(), body.settings(),
            body.password(), null, null, body.displayName()), null, true, identity.hasRole(AccessRole.ADMIN_ROLE));
        if (store.source(d.config().name()).isPresent()) {
            throw ApiError.of(Response.Status.CONFLICT, "A source called " + d.config().name() + " already exists");
        }
        byte[] secret = d.newSecret() == null ? null : secrets.seal(d.newSecret());
        Records.Source s = conflicts(() -> store.addSource(new Records.NewSource(d.config().name(), d.config().kind(),
            d.config().settings(), secret, d.displayName())));
        audit.sourceSettings(io.kilnmark.server.audit.AuditLog.actor(identity), s.name(), null, s.settings(), "form");
        if (io.kilnmark.server.sources.CommonSettings.allowsSql(s.settings())) {
            org.jboss.logging.Logger.getLogger(SourceResource.class).infof(
                "Source %s added by %s; SQL a person writes may run on it (allow_sql)", s.name(), who());
        }
        return Response.created(URI.create("/api/v1/sources/" + s.name())).entity(view(s)).build();
    }

    /**
     * Changes a source: its display name, every setting (send them all; leaving one out removes it)
     * and, optionally, its password. A blank password keeps the stored one unless the URL,
     * endpoint or user name changes. The name cannot change: it is part of every asset's URN.
     */
    @PUT
    @Path("{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.Source update(@PathParam("name") String name, SourceForm body) {
        Records.Source existing = find(name);
        if (body == null || body.settings() == null) {
            throw new IllegalArgumentException("Send the source as JSON with all its settings");
        }
        // allow_writing, and where a writable source points: designers and admins (whoever may edit
        // sources at all); admin-only kinds and reading settings of marked sources stay with admins.
        boolean admin = identity.hasRole(AccessRole.ADMIN_ROLE);
        boolean designer = identity.hasRole(AccessRole.DESIGNER_ROLE);
        SourceForm form = SourceService.sqlAsAllowed(SourceService.writingAsAllowed(body, existing, designer), existing,
            designer);
        // Who may change where a writable source points is decided before the form is checked, so a
        // steward is told no (403) rather than asked to re-enter the password for a change they may not make.
        SourceService.whereUnchangedOrAdmin(existing, SourceService.settings(form.settings()), designer);
        SourceService.Draft d = sources.draft(form, existing, true, admin);
        sources.readingUnchangedOrUnmasked(existing, d.config().settings(), "change how this source is read");
        byte[] secret = d.replaceSecret() && d.newSecret() != null ? secrets.seal(d.newSecret()) : null;
        Records.Source s = conflicts(() -> store.updateSource(existing.id(), new Records.SourceChange(d.displayName(),
            d.config().settings(), d.replaceSecret(), secret, d.keepSecretIf()))).orElseThrow(() -> ApiError.of(
                Response.Status.NOT_FOUND, "No source called " + name));
        audit.sourceSettings(io.kilnmark.server.audit.AuditLog.actor(identity), s.name(), existing.settings(), s.settings(), "form");
        boolean sqlBefore = io.kilnmark.server.sources.CommonSettings.allowsSql(existing.settings());
        boolean sqlAfter = io.kilnmark.server.sources.CommonSettings.allowsSql(s.settings());
        org.jboss.logging.Logger.getLogger(SourceResource.class).infof("Source %s changed by %s%s", existing.name(),
            who(), sqlBefore == sqlAfter ? "" : sqlAfter ? "; SQL a person writes may now run on it (allow_sql)"
                : "; SQL a person writes may no longer run on it (allow_sql)");
        quality.settingsChanged(s); // samples go at once when keep_samples is turned off
        return view(s);
    }

    /**
     * Deletes a source and everything recorded about it: runs, assets, fields, profiles and graph
     * nodes, in one transaction. Refused while a scan of it is waiting or running.
     */
    @DELETE
    @Path("{name}")
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.Deleted delete(@PathParam("name") String name) {
        Records.Source existing = find(name);
        // Deleting a source drops its marks with its assets, which is clearing them: admins only, and logged,
        // as clearing a mark is (ADR 0027). Otherwise the same files could be added again, unmarked.
        boolean marked = sources.hasMarks(existing.name());
        if (marked && !identity.hasRole(AccessRole.ADMIN_ROLE)) {
            throw ApiError.of(Response.Status.FORBIDDEN, existing.name() + " has columns marked sensitive, and deleting "
                + "it clears those marks, so only an admin can delete it.");
        }
        try {
            Records.Deleted d = conflicts(() -> store.deleteSource(existing.id())).orElseThrow(() -> ApiError.of(
                Response.Status.NOT_FOUND, "No source called " + name));
            org.jboss.logging.Logger.getLogger(SourceResource.class).infof(
                "Source %s deleted by %s, with %d assets and %d runs%s", d.name(), who(), d.assets(), d.runs(),
                marked ? "; its columns marked sensitive are no longer marked" : "");
            audit.record(io.kilnmark.server.audit.AuditLog.actor(identity), io.kilnmark.server.audit.AuditLog.SOURCE_DELETED, d.name(), java.util.Map.of(
                "assets", d.assets(), "runs", d.runs(), "marked", marked));
            return new Views.Deleted(d.name(), d.assets(), d.runs());
        } catch (GraphStore.SourceBusyException e) {
            throw ApiError.of(Response.Status.CONFLICT, e.getMessage());
        }
    }

    /** Tests the saved source's connection, within the check time limit. Never returns the secret. */
    @POST
    @Path("{name}/test")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Views.TestResult test(@PathParam("name") String name) {
        Records.Source s = find(name);
        SourceConfig config = sources.config(s);
        sources.checkHosts(sources.connector(s.kind()), config);
        Views.TestResult r = sources.test(sources.connector(s.kind()), config);
        notifications.connectionTested(s.name(), r.ok(), r.problem());
        return r;
    }

    /**
     * What the saved source holds, by container (schemas, top-level folders), with counts, and
     * whether a scan would find anything. The web UI asks before every scan.
     */
    @GET
    @Path("{name}/containers")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Views.ContainerList containers(@PathParam("name") String name) {
        Records.Source s = find(name);
        SourceConfig config = sources.config(s);
        sources.checkHosts(sources.connector(s.kind()), config);
        return sources.containers(sources.connector(s.kind()), config);
    }

    /** A page of the tables or files in one of the saved source's containers, for picking one to preview. */
    @GET
    @Path("{name}/assets")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Views.AssetList assets(@PathParam("name") String name, @QueryParam("container") String container,
                                  @QueryParam("offset") Integer offset, @QueryParam("limit") Integer limit) {
        Records.Source s = find(name);
        SourceConfig config = sources.config(s);
        sources.checkHosts(sources.connector(s.kind()), config);
        return sources.assets(sources.connector(s.kind()), config, container, offset == null ? 0 : offset,
            limit == null ? 50 : limit);
    }

    /**
     * The first rows of one table or file of the saved source (100 by default, at most 1,000), read
     * live and never stored.
     */
    @GET
    @Path("{name}/preview")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response preview(@PathParam("name") String name, @QueryParam("asset") String asset,
                                 @QueryParam("rows") Integer rows) {
        Records.Source s = find(name);
        SourceConfig config = sources.config(s);
        sources.checkHosts(sources.connector(s.kind()), config);
        return SourceService.noStore(sources.preview(sources.connector(s.kind()), config, asset, rows));
    }

    /** The source's latest runs with their jobs' attempts and errors, newest first. */
    @GET
    @Path("{name}/history")
    public List<Views.HistoryRun> history(@PathParam("name") String name, @QueryParam("limit") Integer limit) {
        find(name);
        int n = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        return store.runHistory(name, n).stream()
            .map(h -> new Views.HistoryRun(Views.Run.of(h.run()), h.attempts(), h.jobError())).toList();
    }

    /**
     * Runs a change, answering 409 when it collided with another one: a scan in progress, a source
     * changed or deleted meanwhile, a source of the same name added at the same moment; and 404
     * when the source was deleted under it.
     */
    static <T> T conflicts(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (GraphStore.SourceBusyException | GraphStore.SourceChangedException | IllegalStateException e) {
            throw ApiError.of(Response.Status.CONFLICT, e.getMessage());
        } catch (jakarta.ws.rs.WebApplicationException | IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            String state = sqlState(e);
            if ("23505".equals(state)) {
                throw ApiError.of(Response.Status.CONFLICT, "A source of the same name was added at the same moment. "
                    + "Look at the sources, then try again");
            }
            if ("23503".equals(state)) {
                throw ApiError.of(Response.Status.NOT_FOUND, "The source was deleted at the same moment");
            }
            throw e;
        }
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private String who() {
        return identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
    }

    static Map<String, String> clean(Map<String, String> settings) {
        Map<String, String> out = new HashMap<>();
        if (settings != null) {
            settings.forEach((k, v) -> {
                // keys starting with _ are the server's own, set only on its copies (ADR 0017): dropped
                if (k != null && v != null && !v.isBlank() && !k.trim().startsWith("_")) {
                    out.put(k.trim(), v.trim());
                }
            });
        }
        return out;
    }

    /**
     * Registers or updates many sources from a CSV file: a header row with {@code name},
     * {@code kind}, {@code password} or {@code password_ref}, and one column per setting (for
     * example {@code url} or {@code host}, {@code port} and {@code database}, {@code username},
     * {@code schemas}, {@code description}, {@code ai_egress}). Re-runnable: rows create, update or
     * leave sources unchanged, keyed by name. See {@link SourceImporter} for the options.
     */
    @POST
    @Path("import")
    @Consumes({"text/csv", MediaType.TEXT_PLAIN})
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Response importCsv(String body, @QueryParam("dryRun") boolean dryRun, @QueryParam("onError") String onError,
                              @QueryParam("remapHosts") boolean remapHosts, @QueryParam("test") boolean test,
                              @QueryParam("scan") boolean scan) {
        return report(conflicts(() -> importer.run(importer.fromCsv(body == null ? "" : body),
            options(dryRun, onError, remapHosts, test, scan, identity.hasRole(AccessRole.ADMIN_ROLE)), who(),
            io.kilnmark.server.audit.AuditLog.actor(identity))));
    }

    /** The same as a JSON array of sources. */
    @POST
    @Path("import")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Response importJson(String body, @QueryParam("dryRun") boolean dryRun, @QueryParam("onError") String onError,
                               @QueryParam("remapHosts") boolean remapHosts, @QueryParam("test") boolean test,
                               @QueryParam("scan") boolean scan) {
        return report(conflicts(() -> importer.run(importer.fromJson(body == null ? "[]" : body),
            options(dryRun, onError, remapHosts, test, scan, identity.hasRole(AccessRole.ADMIN_ROLE)), who(),
            io.kilnmark.server.audit.AuditLog.actor(identity))));
    }

    private static SourceImporter.Options options(boolean dryRun, String onError, boolean remapHosts, boolean test,
                                                  boolean scan, boolean admin) {
        boolean skip;
        if (onError == null || onError.isBlank() || onError.equalsIgnoreCase("stop")) {
            skip = false;
        } else if (onError.equalsIgnoreCase("skip")) {
            skip = true;
        } else {
            throw new IllegalArgumentException("onError: stop (change nothing if any row is wrong, the default) or "
                + "skip (apply the rows that are right)");
        }
        return new SourceImporter.Options(dryRun, skip, remapHosts, test, scan, admin);
    }

    private static Response report(SourceImporter.Report r) {
        return Response.status(r.failed() > 0 && !r.applied() && !r.dryRun() ? 422 : 200).entity(r).build();
    }

    @POST
    @Path("{name}/scans")
    @RolesAllowed(AccessRole.STEWARD_ROLE)
    public Response scan(@PathParam("name") String name) {
        Records.Source source = find(name);
        Records.Run run = conflicts(() -> scans.request(source));
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
        return sources.find(name);
    }

    private Views.Source view(Records.Source s) {
        return view(s, store.assetCounts(), store.fileFormats());
    }

    private Views.Source view(Records.Source s, Map<Long, Integer> assets, Map<Long, Map<String, Integer>> formats) {
        Records.Run last = store.runs(s.name(), 1).stream().findFirst().orElse(null);
        String category = connectors.get(s.kind()).map(io.kilnmark.kiln.connect.Connector::category).orElse("other");
        return new Views.Source(s.name(), s.kind(), s.settings(), s.hasSecret(), s.createdAt(), Views.Run.of(last),
            s.displayName(), assets.getOrDefault(s.id(), 0), category, formats.getOrDefault(s.id(), Map.of()));
    }
}
