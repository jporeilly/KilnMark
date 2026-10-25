package io.kilnmark.server.api;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletionStage;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.notify.MailService;
import io.kilnmark.server.notify.MailSettings;
import io.kilnmark.server.notify.NotificationStore;
import io.kilnmark.server.notify.Subscription;
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

/**
 * Email, for admins: the outgoing mail server (its password is write-only), a test email, team
 * addresses for distribution lists, and the log of every email sent or tried.
 */
@Path("/api/v1/mail")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class MailResource {

    private final MailService mail;
    private final NotificationStore store;
    private final SecurityIdentity identity;

    public MailResource(MailService mail, NotificationStore store, SecurityIdentity identity) {
        this.mail = mail;
        this.store = store;
        this.identity = identity;
    }

    /** The settings in force, never with the password: {@code hasPassword} says whether one is kept. */
    public record SettingsView(boolean enabled, String host, Integer port, int effectivePort, String security,
                               String username, boolean hasPassword, String fromAddress, String fromName, String baseUrl,
                               boolean saved, String updatedBy, Instant updatedAt, boolean ready, String notReady,
                               String timeZone) {
        static SettingsView of(MailSettings s) {
            return new SettingsView(s.enabled(), s.host(), s.port(), s.effectivePort(), s.security().id(), s.username(),
                s.hasPassword(), s.fromAddress(), s.fromName(), s.baseUrl(), s.saved(), s.updatedBy(), s.updatedAt(),
                s.ready(), s.notReady(), ZoneId.systemDefault().getId());
        }
    }

    @GET
    @Path("settings")
    public SettingsView settings() {
        return SettingsView.of(mail.settings());
    }

    /** Saves the settings. Leave {@code password} out to keep the stored one; send "" to remove it. */
    @PUT
    @Path("settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public SettingsView save(MailSettings.Input body) {
        return SettingsView.of(mail.save(MailSettings.validate(body), Callers.of(identity)));
    }

    public record TestBody(String to) {
    }

    /** Sends a test email with the saved settings and says, in plain words, whether it went. */
    @POST
    @Path("test")
    @Consumes(MediaType.APPLICATION_JSON)
    public CompletionStage<MailService.TestResult> test(TestBody body) {
        return mail.test(body == null ? null : body.to(), Callers.of(identity));
    }

    // ---- team addresses ----

    /** A team address and what it gets (by email only, written for a viewer). */
    public record Team(long id, String name, String email, boolean enabled, List<String> kinds, String minSeverity,
                       String mode, int digestHour, List<String> sources, String createdBy, Instant createdAt,
                       Instant updatedAt) {
        static Team of(NotificationStore.Subscriber s) {
            Subscription x = s.subscription();
            return new Team(s.id(), s.teamName(), x.email(), x.enabled(), NotificationResource.ids(x.kinds()),
                x.minSeverity().id(), x.mode().id(), x.digestHour(), x.sources(), s.createdBy(), s.createdAt(),
                s.updatedAt());
        }
    }

    /** A team address as sent: its name, and the subscription's fields. */
    public record TeamBody(String name, String email, Boolean enabled, List<String> kinds, String minSeverity,
                           String mode, Integer digestHour, List<String> sources) {
        Subscription.Input input() {
            return new Subscription.Input(email, enabled, kinds, minSeverity, mode, digestHour, sources, null);
        }
    }

    @GET
    @Path("teams")
    public List<Team> teams() {
        return store.teams().stream().map(Team::of).toList();
    }

    @POST
    @Path("teams")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response addTeam(TeamBody body) {
        if (body == null) {
            throw new IllegalArgumentException("Send the team address as JSON: name, email and what it gets");
        }
        Subscription s = Subscription.from(body.input(), Subscription.teamDefaults(null));
        if (s.email() == null) {
            throw new IllegalArgumentException("email: give the team's address, such as data-team@example.com");
        }
        Team t = Team.of(store.addTeam(teamName(body.name()), s, Callers.of(identity)));
        return Response.created(URI.create("/api/v1/mail/teams/" + t.id())).entity(t).build();
    }

    @PUT
    @Path("teams/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Team updateTeam(@PathParam("id") long id, TeamBody body) {
        NotificationStore.Subscriber before = store.subscriber(id).filter(NotificationStore.Subscriber::team)
            .orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No team address " + id));
        if (body == null) {
            throw new IllegalArgumentException("Send the team address as JSON: name, email and what it gets");
        }
        Subscription s = Subscription.from(body.input(), before.subscription());
        if (s.email() == null) {
            throw new IllegalArgumentException("email: give the team's address, such as data-team@example.com");
        }
        String name = body.name() == null ? before.teamName() : teamName(body.name());
        return store.updateTeam(id, name, s).map(Team::of)
            .orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No team address " + id));
    }

    @DELETE
    @Path("teams/{id}")
    public Response deleteTeam(@PathParam("id") long id) {
        if (!store.deleteTeam(id)) {
            throw ApiError.of(Response.Status.NOT_FOUND, "No team address " + id);
        }
        return Response.noContent().build();
    }

    static String teamName(String text) {
        String n = text == null ? "" : text.strip();
        if (n.isEmpty() || n.length() > 100 || n.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("name: give the team address a name of 1 to 100 characters, such as "
                + "Data team");
        }
        return n;
    }

    // ---- the log ----

    /**
     * Every email sent or tried, newest first ({@code limit} default 100, at most 500), optionally
     * only one status: sent, retrying, failed or rate_limited. Kept for 30 days.
     */
    @GET
    @Path("log")
    public List<NotificationStore.LogEntry> log(@QueryParam("status") String status, @QueryParam("limit") Integer limit) {
        String s = status == null || status.isBlank() || status.equals("all") ? null : status.strip();
        if (s != null && !List.of("sent", "retrying", "failed", "rate_limited").contains(s)) {
            throw new IllegalArgumentException("status: use sent, retrying, failed, rate_limited or all");
        }
        return store.log(s, limit == null ? 100 : Math.max(1, Math.min(limit, 500)));
    }
}
