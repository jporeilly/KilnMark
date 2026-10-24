package io.kilnmark.server.api;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kilnmark.server.ai.AiException;
import io.kilnmark.server.ai.AiFeature;
import io.kilnmark.server.ai.AiGateway;
import io.kilnmark.server.ai.AiViews;
import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.ai.EgressPolicy;
import io.kilnmark.server.ai.Embeddings;
import io.kilnmark.server.ai.ModelClient;
import io.kilnmark.server.ai.ModelHosts;
import io.kilnmark.server.ai.ModelProfile;
import io.kilnmark.server.ai.ModelProfiles;
import io.kilnmark.server.ai.Provider;
import io.kilnmark.server.ai.UsageLog;
import io.kilnmark.server.auth.AccessRole;
import io.quarkus.security.identity.SecurityIdentity;
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
import jakarta.ws.rs.ext.ExceptionMapper;

/**
 * AI models (ADR 0016): model profiles, which feature uses which, the server-wide rule on hosted
 * models, usage, and embedding progress. Admins only, except {@code GET /api/v1/ai/status}.
 */
@Path("/api/v1/ai")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class AiResource {

    private final ModelProfiles profiles;
    private final AiGateway gateway;
    private final EgressPolicy egress;
    private final Embeddings embeddings;
    private final UsageLog usage;
    private final ModelHosts hosts;
    private final SecurityIdentity identity;

    public AiResource(ModelProfiles profiles, AiGateway gateway, EgressPolicy egress, Embeddings embeddings,
                      UsageLog usage, ModelHosts hosts, SecurityIdentity identity) {
        this.profiles = profiles;
        this.gateway = gateway;
        this.egress = egress;
        this.embeddings = embeddings;
        this.usage = usage;
        this.hosts = hosts;
        this.identity = identity;
    }

    /** The operator's address rules (ADR 0016), checked on every save and again on every call. */
    private ModelProfile.Valid checkAddress(ModelProfile.Valid v) {
        String url = v.effectiveBaseUrl();
        hosts.forbidden(url).ifPresent(reason -> {
            throw new IllegalArgumentException("baseUrl: " + reason);
        });
        if (v.local() && !hosts.mayBeLocal(url)) {
            throw new IllegalArgumentException("local: this server allows only local models, and " + url + " is not on "
                + "one of its local networks (kilnmark.ai.local-hosts, set by whoever runs the server). Use a model "
                + "server on your own network, or leave local unticked.");
        }
        return v;
    }

    /** Which AI search features are on, for any signed-in user. No model details. */
    @GET
    @Path("status")
    @RolesAllowed(AccessRole.VIEWER_ROLE)
    public AiViews.Status status() {
        Map<AiFeature, ModelProfile> routes = profiles.routes();
        return new AiViews.Status(routes.containsKey(AiFeature.EMBED), routes.containsKey(AiFeature.SEARCH_NL),
            embeddings.status().orElse(null));
    }

    @GET
    @Path("providers")
    public List<AiViews.ProviderInfo> providers() {
        return Arrays.stream(Provider.values()).map(AiViews.ProviderInfo::of).toList();
    }

    @GET
    @Path("profiles")
    public List<AiViews.Profile> list() {
        Map<AiFeature, ModelProfile> routes = profiles.routes();
        return profiles.list().stream().map(p -> view(p, routes, egress)).toList();
    }

    @GET
    @Path("profiles/{name}")
    public AiViews.Profile get(@PathParam("name") String name) {
        return view(find(name), profiles.routes(), egress);
    }

    @POST
    @Path("profiles")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(ModelProfile.Input body) {
        ModelProfile p = profiles.create(checkAddress(ModelProfile.validate(body, false)), Callers.of(identity));
        return Response.created(URI.create("/api/v1/ai/profiles/" + p.name())).entity(view(p, profiles.routes(), egress))
            .build();
    }

    /** Changes a profile. Leave {@code apiKey} out to keep the stored key; send "" to remove it. */
    @PUT
    @Path("profiles/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public AiViews.Profile update(@PathParam("name") String name, ModelProfile.Input body) {
        ModelProfile before = find(name);
        ModelProfile.Valid v = checkAddress(ModelProfile.validate(body, before.hasKey()));
        // A stored key goes only where it was entered for: same scheme, host and port.
        if (before.hasKey() && body.apiKey() == null
            && !ModelHosts.origin(before.effectiveBaseUrl()).equals(ModelHosts.origin(v.effectiveBaseUrl()))) {
            throw new IllegalArgumentException("apiKey: the address now points at another server (scheme, host or "
                + "port changed), so enter the API key again (or send an empty key to remove it). A stored key is "
                + "only sent where it was entered for.");
        }
        ModelProfiles.Updated u = profiles.update(before, v, Callers.of(identity));
        if (u.reembed()) {
            embeddings.forgetProfile(u.profile().id());
            if (profiles.route(AiFeature.EMBED).map(p -> p.id() == u.profile().id()).orElse(false)) {
                embeddings.reembedAllInBackground("profile " + u.profile().name() + " changed");
            }
        }
        return view(u.profile(), profiles.routes(), egress);
    }

    @DELETE
    @Path("profiles/{name}")
    public Response delete(@PathParam("name") String name) {
        ModelProfile p = find(name);
        profiles.delete(p);
        embeddings.forgetProfile(p.id());
        return Response.noContent().build();
    }

    /** Sends a tiny request to the model and says, in plain words, whether it works. */
    @POST
    @Path("profiles/{name}/test")
    public AiViews.TestResult test(@PathParam("name") String name) {
        ModelProfile p = find(name);
        String caller = Callers.of(identity);
        try {
            if (p.purpose() == AiFeature.Purpose.EMBEDDING) {
                // A model returning another size than the profile says fails here, with the size to set.
                AiGateway.Result<ModelClient.EmbedAnswer> r = gateway.embedWith(p, "test", null,
                    List.of("Kilnmark connection test: customer orders"), caller, () -> false,
                    AiGateway.Budget.BACKGROUND);
                int dims = r.answer().vectors().get(0).length;
                return new AiViews.TestResult(true, "Model " + p.model() + " answered in " + seconds(r.latencyMs())
                    + " with " + dims + " numbers per text, as the profile says.", r.latencyMs(), dims);
            }
            AiGateway.Result<ModelClient.ChatAnswer> r = gateway.chatWith(p, "test", null, new ModelClient.ChatCall(
                "You are a connection test. Answer with the JSON object {\"ok\": true} and nothing else.",
                "Are you there?", "{\"type\": \"object\", \"properties\": {\"ok\": {\"type\": \"boolean\"}}, "
                    + "\"required\": [\"ok\"], \"additionalProperties\": false}", 20), caller, () -> false,
                AiGateway.Budget.BACKGROUND);
            String text = r.answer().text() == null ? "" : r.answer().text();
            if (text.isBlank()) {
                return new AiViews.TestResult(false, "The model answered with nothing. Check the model name, and that "
                    + "the server has finished loading it.", r.latencyMs(), null);
            }
            boolean json = text.contains("{") && text.contains("ok");
            return new AiViews.TestResult(true, "Model " + p.model() + " answered in " + seconds(r.latencyMs()) + "."
                + (json ? "" : " It did not answer in JSON, so features that need structured answers may be "
                    + "unreliable with it."), r.latencyMs(), null);
        } catch (AiException e) {
            return new AiViews.TestResult(false, e.getMessage(), 0, null);
        }
    }

    private static String seconds(long ms) {
        return ms < 1000 ? ms + " ms" : String.format(java.util.Locale.ROOT, "%.1f seconds", ms / 1000.0);
    }

    @GET
    @Path("features")
    public List<AiViews.Feature> features() {
        Map<AiFeature, ModelProfile> routes = profiles.routes();
        return Arrays.stream(AiFeature.values()).map(f -> new AiViews.Feature(f.id(), f.label(), f.help(),
            f.purpose().id(), routes.containsKey(f) ? routes.get(f).name() : null)).toList();
    }

    /** Chooses the profile a feature uses; a null or empty profile turns the feature off. */
    @PUT
    @Path("features/{feature}")
    @Consumes(MediaType.APPLICATION_JSON)
    public AiViews.Feature route(@PathParam("feature") String id, AiViews.Route body) {
        AiFeature f = AiFeature.parse(id);
        String name = body == null || body.profile() == null || body.profile().isBlank() ? null : body.profile().trim();
        ModelProfile p = name == null ? null : profiles.find(name).orElseThrow(
            () -> new IllegalArgumentException("profile: there is no model profile called " + name));
        Long before = profiles.route(f).map(ModelProfile::id).orElse(null);
        profiles.setRoute(f, p, Callers.of(identity));
        if (f == AiFeature.EMBED && p != null && !Objects.equals(before, p.id())) {
            embeddings.reembedAllInBackground("embedding model set to " + p.name());
        }
        return new AiViews.Feature(f.id(), f.label(), f.help(), f.purpose().id(), name);
    }

    @GET
    @Path("settings")
    public AiViews.Settings settings() {
        return new AiViews.Settings(profiles.setting(ModelProfiles.HOSTED_MODELS).orElse("allowed"),
            egress.hostedLockedOff(), egress.hostedAllowed());
    }

    @PUT
    @Path("settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public AiViews.Settings changeSettings(AiViews.SettingsChange body) {
        String v = body == null || body.hostedModels() == null ? "" : body.hostedModels().trim();
        if (!v.equals("allowed") && !v.equals("forbidden")) {
            throw new IllegalArgumentException("hostedModels: allowed or forbidden");
        }
        profiles.putSetting(ModelProfiles.HOSTED_MODELS, v, Callers.of(identity));
        return settings();
    }

    /** Model calls per day and profile over the last {@code days} days (default 30), and the latest calls. */
    @GET
    @Path("usage")
    public AiViews.Usage usage(@QueryParam("days") Integer days) {
        int d = days == null ? 30 : Math.max(1, Math.min(days, 366));
        return new AiViews.Usage(d, usage.totals(d), usage.recent(50));
    }

    /** Embeds every source again now, skipping unchanged assets: after fixing a model server, for example. */
    @POST
    @Path("embeddings/refresh")
    public Response refresh() {
        if (profiles.route(AiFeature.EMBED).isEmpty()) {
            throw ApiError.of(Response.Status.CONFLICT, "No embedding model is set. Choose one for search by "
                + "meaning first.");
        }
        boolean started = embeddings.reembedAllInBackground("refresh by " + Callers.of(identity));
        return Response.accepted(Map.of("started", started, "message", started
            ? "Embedding every source in the background. Unchanged assets are skipped."
            : "Embedding is already running; it will go round once more when it ends.")).build();
    }

    private ModelProfile find(String name) {
        return profiles.find(name).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "No model profile called " + name));
    }

    private static AiViews.Profile view(ModelProfile p, Map<AiFeature, ModelProfile> routes, EgressPolicy egress) {
        List<String> usedBy = new ArrayList<>();
        routes.forEach((f, r) -> {
            if (r.id() == p.id()) {
                usedBy.add(f.id());
            }
        });
        return AiViews.Profile.of(p, usedBy, egress.local(p));
    }

    /** A clash with what is stored, such as deleting a profile in use: 409. */
    @jakarta.ws.rs.ext.Provider
    public static class ConflictMapper implements ExceptionMapper<ModelProfiles.Conflict> {
        @Override
        public Response toResponse(ModelProfiles.Conflict e) {
            return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON)
                .entity(new ApiError(e.getMessage())).build();
        }
    }
}
