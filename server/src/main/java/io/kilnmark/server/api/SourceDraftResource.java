package io.kilnmark.server.api;

import java.util.List;

import io.kilnmark.graph.store.Records;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.sources.CommonSettings;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Checks of a source not yet saved, as the add and edit forms hold it: test its connection, list
 * what it holds. Nothing is stored. When editing, {@code source} names the saved source, and a
 * blank password means its stored one (unless the URL, endpoint or user name changed). Also the
 * settings every source has, for forms.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class SourceDraftResource {

    private final SourceService sources;
    private final ImportBatches batches;
    private final io.quarkus.security.identity.SecurityIdentity identity;

    public SourceDraftResource(SourceService sources, ImportBatches batches,
                               io.quarkus.security.identity.SecurityIdentity identity) {
        this.sources = sources;
        this.batches = batches;
        this.identity = identity;
    }

    /** The form as a draft, refused (403) to all but admins for kinds only admins set up. */
    private SourceService.Draft draft(SourceForm form, Records.Source saved) {
        // before the form is checked: checking a URL already hands it to the driver
        String kind = saved != null ? saved.kind() : form == null || form.kind() == null || form.kind().isBlank()
            ? "postgresql" : form.kind().trim();
        java.util.Map<String, String> settings = form == null || form.settings() == null ? java.util.Map.of()
            : SourceService.settings(form.settings());
        boolean admin = identity.hasRole(AccessRole.ADMIN_ROLE);
        try {
            SourceService.adminOnlyKind(sources.connector(kind), saved, settings, admin);
        } catch (IllegalArgumentException e) {
            // an unknown kind: the draft says so
        }
        return sources.draft(form, saved, false);
    }

    /** A bulk import's follow-up tests and scans, row by row, for the web UI to poll. */
    @GET
    @Path("imports/{id}")
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.ImportBatch importBatch(@jakarta.ws.rs.PathParam("id") long id) {
        return batches.get(id).orElseThrow(() -> ApiError.of(jakarta.ws.rs.core.Response.Status.NOT_FOUND,
            "No import batch " + id + ". Batches are kept for a week"));
    }

    /** The settings every source has, whatever its kind, in the order forms show them. */
    @GET
    @Path("common-settings")
    public List<Views.CommonSetting> commonSettings() {
        return CommonSettings.definitions().stream().map(d -> new Views.CommonSetting(d.key(), d.label(), d.hint(),
            d.multiple(), d.choices(), d.defaultValue(), d.kind().name().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    /** Tests the connection of a source as a form describes it, within the check time limit. */
    @POST
    @Path("drafts/test")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.TestResult test(SourceForm form) {
        SourceService.Draft d = draft(form, existing(form));
        return sources.test(d.connector(), d.config());
    }

    /** What a source as a form describes it holds, by container, with counts. */
    @POST
    @Path("drafts/containers")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.ContainerList containers(SourceForm form) {
        SourceService.Draft d = draft(form, existing(form));
        return sources.containers(d.connector(), d.config());
    }

    /** A page of the tables or files in one container of a source as a form describes it. */
    @POST
    @Path("drafts/assets")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public Views.AssetList assets(SourceForm form, @jakarta.ws.rs.QueryParam("container") String container,
                                  @jakarta.ws.rs.QueryParam("offset") Integer offset,
                                  @jakarta.ws.rs.QueryParam("limit") Integer limit) {
        SourceService.Draft d = draft(form, existing(form));
        return sources.assets(d.connector(), d.config(), container, offset == null ? 0 : offset,
            limit == null ? 50 : limit);
    }

    /** The first rows of one table or file of a source as a form describes it, read live and never stored. */
    @POST
    @Path("drafts/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed(AccessRole.DESIGNER_ROLE)
    public jakarta.ws.rs.core.Response preview(SourceForm form, @jakarta.ws.rs.QueryParam("asset") String asset,
                                 @jakarta.ws.rs.QueryParam("rows") Integer rows) {
        Records.Source saved = existing(form);
        SourceService.Draft d = draft(form, saved);
        sources.previewDraftAllowed(saved, d.config());
        return SourceService.noStore(sources.preview(d.connector(), d.config(), asset, rows));
    }

    private Records.Source existing(SourceForm form) {
        return form == null || form.source() == null || form.source().isBlank() ? null : sources.find(form.source());
    }
}
