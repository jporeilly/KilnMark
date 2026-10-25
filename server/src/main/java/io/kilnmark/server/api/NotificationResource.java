package io.kilnmark.server.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.notify.MailService;
import io.kilnmark.server.notify.MailSettings;
import io.kilnmark.server.notify.NotificationKind;
import io.kilnmark.server.notify.NotificationStore;
import io.kilnmark.server.notify.Subscription;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Each signed-in person's own notifications: the in-app feed (the bell and the home page's
 * Notifications panel) and their choices of what they hear about, in the app and by email. A
 * person is the API token they signed in with; nobody can read or mark another's notifications.
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class NotificationResource {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int MAX_IDS = 500;

    private final NotificationStore store;
    private final MailService mail;
    private final SecurityIdentity identity;

    public NotificationResource(NotificationStore store, MailService mail, SecurityIdentity identity) {
        this.store = store;
        this.mail = mail;
        this.identity = identity;
    }

    // ---- the feed ----

    /** How a notification reached its person: {@code in_app}, or {@code email} with its status and time. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Channel(String type, String status, Instant at) {
    }

    /** A team address's email about the same event: the team's name, never its address. */
    public record TeamEmail(String name, String status) {
    }

    /**
     * One notification. {@code teamEmails} only for admins. {@code id} is text, so a client never
     * has to care how large it grows.
     */
    public record Item(String id, String kind, String severity, String title, String detail, String link,
                       Instant createdAt, boolean read, List<Channel> channels,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<TeamEmail> teamEmails) {
    }

    public record Feed(List<Item> items, long unread) {
    }

    public record Unread(long unread) {
    }

    /** {@code {"ids": ["12", "13"]}} or {@code {"all": true}}. */
    public record ReadBody(List<String> ids, Boolean all) {
    }

    /** The caller's notifications, newest first: {@code limit} (default 20, at most 100), only unread with {@code unread=true}. */
    @GET
    @Path("notifications")
    public Feed feed(@QueryParam("limit") Integer limit, @QueryParam("unread") Boolean unread) {
        Long me = tokenId();
        if (me == null) {
            return new Feed(List.of(), 0);
        }
        int n = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(limit, MAX_LIMIT));
        List<NotificationStore.InboxRow> rows = store.inbox(me, Boolean.TRUE.equals(unread), n);
        List<Long> events = rows.stream().map(NotificationStore.InboxRow::eventId).filter(e -> e != null).toList();
        Map<Long, NotificationStore.EmailStatus> emails = store.emails(me, events);
        boolean admin = identity.hasRole(AccessRole.ADMIN_ROLE);
        Map<Long, List<NotificationStore.TeamEmail>> teams = admin ? store.teamEmails(events) : Map.of();
        List<Item> items = new ArrayList<>(rows.size());
        for (NotificationStore.InboxRow r : rows) {
            items.add(item(r, r.eventId() == null ? null : emails.get(r.eventId()),
                admin ? teams.getOrDefault(r.eventId(), List.of()) : null));
        }
        return new Feed(items, store.unread(me));
    }

    /** The feed's item for one stored notification: its channels, and team emails (admins; null for others). */
    static Item item(NotificationStore.InboxRow r, NotificationStore.EmailStatus email,
                     List<NotificationStore.TeamEmail> teams) {
        List<Channel> channels = new ArrayList<>(2);
        channels.add(new Channel("in_app", null, null));
        if (email != null) {
            channels.add(new Channel("email", email.status(), email.at()));
        }
        return new Item(Long.toString(r.id()), r.kind(), r.severity(), r.title(), r.detail(), r.link(), r.createdAt(),
            r.read(), channels, teams == null ? null : teams.stream().map(t -> new TeamEmail(t.name(), t.status())).toList());
    }

    /** How many of the caller's notifications are unread: cheap, for a bell that asks every minute. */
    @GET
    @Path("notifications/unread-count")
    public Unread unreadCount() {
        Long me = tokenId();
        return new Unread(me == null ? 0 : store.unread(me));
    }

    /** Marks some of the caller's notifications read, or all of them. Others' ids are ignored. */
    @POST
    @Path("notifications/read")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response read(ReadBody body) {
        if (body == null || (body.ids() == null && !Boolean.TRUE.equals(body.all()))) {
            throw new IllegalArgumentException("Send {\"ids\": [\"...\"]} or {\"all\": true}");
        }
        Long me = tokenId();
        if (me != null) {
            if (Boolean.TRUE.equals(body.all())) {
                store.markAllRead(me);
            } else {
                store.markRead(me, ids(body.ids()));
            }
        }
        return Response.noContent().build();
    }

    static List<Long> ids(List<String> ids) {
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("ids: at most " + MAX_IDS + " at a time, or send {\"all\": true}");
        }
        List<Long> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            try {
                out.add(Long.parseLong(id == null ? "" : id.strip()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ids: '" + String.valueOf(id).replaceAll("\\p{Cntrl}", "?")
                    + "' is not a notification id", e);
            }
        }
        return out;
    }

    // ---- the caller's own choices ----

    /** A kind of event, for the settings page. */
    public record KindChoice(String id, String label, String description) {
    }

    /**
     * The caller's choices (or the defaults for their role, {@code saved} false), with what the page
     * needs to show them: the kinds of event, the server's time zone for the digest hour, and whether
     * email can go at all.
     */
    public record MySettings(boolean available, String unavailable, boolean saved, String email, boolean enabled,
                             List<String> kinds, String minSeverity, String mode, int digestHour, List<String> sources,
                             List<String> inAppKinds, String timeZone, boolean mailReady, String mailNote,
                             List<KindChoice> kindChoices) {
    }

    @GET
    @Path("me/notifications")
    public MySettings mine() {
        Long me = tokenId();
        if (me == null) {
            return view(false, Subscription.defaults(role()));
        }
        return store.forToken(me).map(s -> view(true, s.subscription())).orElseGet(() -> view(false,
            Subscription.defaults(role())));
    }

    /** Saves the caller's choices. Sources null means every source. */
    @PUT
    @Path("me/notifications")
    @Consumes(MediaType.APPLICATION_JSON)
    public MySettings saveMine(Subscription.Input body) {
        Long me = tokenId();
        if (me == null) {
            throw ApiError.of(Response.Status.CONFLICT, "Sign-in is off on this server, so there is no one to keep "
                + "choices for. Sign in with an API token to choose your notifications.");
        }
        Subscription base = store.forToken(me).map(NotificationStore.Subscriber::subscription)
            .orElseGet(() -> Subscription.defaults(role()));
        return view(true, store.saveForToken(me, Subscription.from(body, base), Callers.of(identity)).subscription());
    }

    private MySettings view(boolean saved, Subscription s) {
        MailSettings m = mail.settings();
        boolean available = tokenId() != null;
        return new MySettings(available, available ? null : "Sign-in is off on this server, so notifications have "
            + "no one to go to.", saved, s.email(), s.enabled(), ids(s.kinds()), s.minSeverity().id(), s.mode().id(),
            s.digestHour(), s.sources(), ids(s.inAppKinds()), ZoneId.systemDefault().getId(), m.ready(), m.notReady(),
            kindChoices());
    }

    static List<String> ids(java.util.Set<NotificationKind> kinds) {
        return kinds.stream().sorted().map(NotificationKind::id).toList();
    }

    static List<KindChoice> kindChoices() {
        return Arrays.stream(NotificationKind.values()).map(k -> new KindChoice(k.id(), k.label(), k.description()))
            .toList();
    }

    private Long tokenId() {
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        return id == null || id == 0 ? null : id;
    }

    private AccessRole role() {
        AccessRole r = identity.getAttribute(ApiTokenIdentityProvider.ROLE);
        return r == null ? AccessRole.VIEWER : r;
    }
}
