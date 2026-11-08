package io.kilnmark.server.api;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.notify.MailService;
import io.kilnmark.server.notify.MailPolicy;
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
    /** The unread count stops at this: the API says 99 and {@code more: true}, the UI "99+". */
    static final int UNREAD_CAP = 100;
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

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

    /** {@code more} is there, true, when there are more unread than {@code unread} says (99+). */
    public record Feed(List<Item> items, long unread, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean more) {
    }

    /** {@code more} is there, true, when there are more unread than {@code unread} says (99+). */
    public record Unread(long unread, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean more) {
        static Unread of(long counted) {
            return counted >= UNREAD_CAP ? new Unread(UNREAD_CAP - 1, true) : new Unread(counted, null);
        }
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
            return new Feed(List.of(), 0, null);
        }
        store.seen(me);
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
        Unread u = Unread.of(store.unread(me, UNREAD_CAP));
        return new Feed(items, u.unread(), u.more());
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
        if (me == null) {
            return new Unread(0, null);
        }
        store.seen(me);
        return Unread.of(store.unread(me, UNREAD_CAP));
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
                             List<KindChoice> kindChoices, boolean confirmationRequired, boolean confirmed,
                             Instant confirmationSentAt, String paused, List<String> allowedDomains) {
    }

    /** {@code {"code": "..."}}: the code from the confirmation email. */
    public record ConfirmBody(String code) {
    }

    @GET
    @Path("me/notifications")
    public MySettings mine() {
        Long me = tokenId();
        if (me == null) {
            return view(null, Subscription.defaults(role()));
        }
        store.seen(me);
        return store.forToken(me).map(s -> view(s, s.subscription())).orElseGet(() -> view(null,
            Subscription.defaults(role())));
    }

    /**
     * Saves the caller's choices. A field left out keeps its value; {@code allSources: true} for every
     * source. A new address gets a confirmation email, and nothing else until it is confirmed.
     */
    @PUT
    @Path("me/notifications")
    @Consumes(MediaType.APPLICATION_JSON)
    public MySettings saveMine(Subscription.Input body) {
        Long me = me();
        store.seen(me);
        Optional<NotificationStore.Subscriber> before = store.forToken(me);
        Subscription base = before.map(NotificationStore.Subscriber::subscription)
            .orElseGet(() -> Subscription.defaults(role()));
        Subscription next = Subscription.from(body, base);
        MailSettings m = mail.settings();
        MailPolicy.checkDomain("email", next.email(), m.allowedDomains());
        NotificationStore.Subscriber saved = store.saveForToken(me, next, Callers.of(identity));
        boolean newAddress = next.email() != null && !next.email().equals(base.email());
        if (m.requireConfirmation() && next.email() != null && saved.confirmedAt() == null
            && (newAddress || saved.confirmSentAt() == null)) {
            sendCode(saved);
            saved = store.forToken(me).orElse(saved);
        }
        return view(saved, saved.subscription());
    }

    /** Confirms the caller's address with the code from the confirmation email. */
    @POST
    @Path("me/notifications/confirm")
    @Consumes(MediaType.APPLICATION_JSON)
    public MySettings confirm(ConfirmBody body) {
        Long me = me();
        String code = body == null || body.code() == null ? "" : body.code().strip();
        if (code.isEmpty() || code.length() > 100 || !store.confirm(me, hash(code))) {
            throw new IllegalArgumentException("code: that code does not confirm your address. Codes work for 7 days, "
                + "and only the latest one sent works; ask for a new one if needed.");
        }
        NotificationStore.Subscriber s = store.forToken(me).orElseThrow();
        return view(s, s.subscription());
    }

    /** Sends the caller a new confirmation code (at most one a minute). */
    @POST
    @Path("me/notifications/confirm/resend")
    public MySettings resend() {
        Long me = me();
        NotificationStore.Subscriber s = store.forToken(me).filter(x -> x.subscription().email() != null)
            .orElseThrow(() -> new IllegalArgumentException("email: save an address first"));
        if (s.confirmedAt() != null) {
            return view(s, s.subscription());
        }
        if (s.confirmSentAt() != null && s.confirmSentAt().isAfter(Instant.now().minusSeconds(60))) {
            throw ApiError.of(Response.Status.TOO_MANY_REQUESTS, "A code was sent less than a minute ago. Check your "
                + "inbox, and spam folder, before asking again.");
        }
        if (!mail.settings().ready()) {
            throw ApiError.of(Response.Status.CONFLICT, mail.settings().notReady());
        }
        sendCode(s);
        NotificationStore.Subscriber after = store.forToken(me).orElseThrow();
        return view(after, after.subscription());
    }

    private void sendCode(NotificationStore.Subscriber s) {
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String code = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        store.confirmationSent(s.tokenId(), hash(code));
        mail.sendConfirmation(s.id(), s.subscription().email(), code);
    }

    static byte[] hash(String code) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("This Java runtime has no SHA-256", e);
        }
    }

    private Long me() {
        Long me = tokenId();
        if (me == null) {
            throw ApiError.of(Response.Status.CONFLICT, provider()
                ? PROVIDER_NONE + " There are no choices to keep."
                : "Sign-in is off on this server, so there is no one to keep choices for. Sign in with an API token "
                    + "to choose your notifications.");
        }
        return me;
    }

    private MySettings view(NotificationStore.Subscriber saved, Subscription s) {
        MailSettings m = mail.settings();
        boolean available = tokenId() != null;
        return new MySettings(available, available ? null : unavailable(provider()), saved != null, s.email(), s.enabled(), ids(s.kinds()), s.minSeverity().id(),
            s.mode().id(), s.digestHour(), s.sources(), ids(s.inAppKinds()), ZoneId.systemDefault().getId(), m.ready(),
            m.notReady(), kindChoices(), m.requireConfirmation(), saved != null && saved.confirmedAt() != null,
            saved == null ? null : saved.confirmSentAt(), saved == null ? null : saved.paused(), m.allowedDomains());
    }

    /** What a provider sign-in is told: notifications go to API tokens only, for now. */
    static final String PROVIDER_NONE = "People who sign in through your identity provider get no notifications yet.";

    /**
     * Why the caller has no notification choices: they signed in through the identity provider, which
     * notifications do not reach yet, or sign-in is off, so there is no one to send them to.
     */
    static String unavailable(boolean provider) {
        return provider ? PROVIDER_NONE + " There is nothing to choose here."
            : "Sign-in is off on this server, so notifications have no one to go to.";
    }

    private boolean provider() {
        return io.kilnmark.server.auth.OidcIdentityAugmentor.fromProvider(identity);
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
