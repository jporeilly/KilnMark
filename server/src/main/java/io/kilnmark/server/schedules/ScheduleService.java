package io.kilnmark.server.schedules;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.pipelines.PipelineService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Schedules of pipelines and flows (ADR 0046, docs/design/schedules.md): checking and saving what
 * a designer or admin sets, and showing schedules to each role. Firing them is {@link Scheduler}.
 */
@ApplicationScoped
public class ScheduleService {

    private static final Logger LOG = Logger.getLogger(ScheduleService.class);

    /** How many fire times a preview gives. */
    static final int PREVIEW = 5;
    /** The roles a scheduled run may act as: never admin, and never a viewer, who cannot run. */
    static final List<AccessRole> RUN_AS = List.of(AccessRole.STEWARD, AccessRole.DESIGNER);

    private final ScheduleStore store;
    private final PipelineService pipelines;
    private final FlowService flows;
    private final ObjectMapper json;
    private final Clock clock;

    /** Records schedules paused when their setter loses the role; null in tests that build this by hand. */
    @jakarta.inject.Inject
    AuditLog audit;

    /** Whether sign-in is off, when a setter may have no identity; null in tests that build this by hand. */
    @jakarta.inject.Inject
    io.kilnmark.server.KilnmarkConfig config;

    @jakarta.inject.Inject
    public ScheduleService(ScheduleStore store, PipelineService pipelines, FlowService flows, ObjectMapper json) {
        this(store, pipelines, flows, json, Clock.systemUTC());
    }

    ScheduleService(ScheduleStore store, PipelineService pipelines, FlowService flows, ObjectMapper json,
                    Clock clock) {
        this.store = store;
        this.pipelines = pipelines;
        this.flows = flows;
        this.json = json;
        this.clock = clock;
    }

    static WebApplicationException fail(Response.Status status, String message) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    /** Who a scheduled run is requested by: the schedule, and who last set it. */
    public static String requestedBy(ScheduleStore.Schedule s) {
        return "schedule " + s.id() + " (set by " + s.updatedBy() + ")";
    }

    // ---- reading ----

    /** Every schedule, or one pipeline's or flow's; with parameter values only when {@code values}. */
    public List<ScheduleViews.Schedule> list(String pipeline, String flow, boolean values) {
        return store.list(blank(pipeline), blank(flow)).stream().map(s -> view(s, values)).toList();
    }

    public ScheduleViews.Schedule get(long id, boolean values) {
        return view(find(id), values);
    }

    private ScheduleStore.Schedule find(long id) {
        return store.find(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No schedule " + id));
    }

    // ---- changing ----

    /**
     * Who is setting a schedule, as {@code identity} says: {@code name}, and the identity each fire
     * checks the role of: a person signed in through the identity provider by the same opaque key
     * their sign-ins record ({@code OidcIdentityAugmentor.personRef}), else the API token used.
     */
    public static ScheduleStore.Setter setter(io.quarkus.security.identity.SecurityIdentity identity, String name) {
        String person = io.kilnmark.server.auth.OidcIdentityAugmentor.personRef(identity);
        Long token = person != null || identity.isAnonymous() ? null
            : identity.getAttribute(io.kilnmark.server.auth.ApiTokenIdentityProvider.TOKEN_ID);
        return new ScheduleStore.Setter(name, token == null || token == 0 ? null : token, person);
    }

    /** Only designers and admins set schedules (ADR 0046), whatever the API's roles say. */
    static void requireSetter(AccessRole caller) {
        if (caller == null || !caller.atLeast(AccessRole.DESIGNER)) {
            throw fail(Response.Status.FORBIDDEN, "Setting a schedule takes the designer role");
        }
    }

    /**
     * Refuses a setter Kilnmark could not check again at each fire: one with neither a token nor a
     * person's key, unless sign-in is off (a lab).
     */
    void requireIdentity(ScheduleStore.Setter by) {
        if (by.token() == null && by.person() == null
            && (config == null || config.auth().mode() != io.kilnmark.server.auth.AuthMode.OFF)) {
            throw fail(Response.Status.FORBIDDEN, "Kilnmark cannot tell who you are, so it could not check your "
                + "role before each run. Sign in with an API token or your organisation's login.");
        }
    }

    /** Adds a schedule, set by {@code by} with role {@code caller}. */
    public ScheduleStore.Schedule create(ScheduleViews.ScheduleChange body, ScheduleStore.Setter by,
                                         AccessRole caller) {
        requireSetter(caller);
        requireIdentity(by);
        ScheduleStore.Setting setting = setting(body, caller);
        ScheduleStore.Schedule s = store.insert(setting, next(setting), by);
        LOG.infof("Schedule %d of %s set by %s: %s (%s)", s.id(), target(s), by.name(), s.cron(), s.zone());
        return s;
    }

    /**
     * Replaces what schedule {@code id} is set to. Its next fire stays when its times, zone and
     * pausing do; otherwise it is worked out again from the database's clock once the row is locked
     * (ScheduleStore.update), so it is never a fire a scheduler has just claimed.
     */
    public ScheduleStore.Schedule replace(long id, ScheduleViews.ScheduleChange body, ScheduleStore.Setter by,
                                          AccessRole caller) {
        requireSetter(caller);
        requireIdentity(by);
        find(id);
        ScheduleStore.Setting setting = setting(body, caller);
        ScheduleStore.Schedule s = store.update(id, setting, next(setting), by)
            .orElseThrow(() -> fail(Response.Status.NOT_FOUND, "No schedule " + id));
        LOG.infof("Schedule %d of %s changed by %s: %s (%s), %s", s.id(), target(s), by.name(), s.cron(), s.zone(),
            s.enabled() ? "on" : "paused");
        return s;
    }

    /** The first fire after a time, for a setting already checked. */
    private static java.util.function.Function<Instant, Instant> next(ScheduleStore.Setting setting) {
        Cron cron = Cron.parse(setting.cron());
        ZoneId zone = ZoneId.of(setting.zone());
        return at -> cron.next(at, zone);
    }

    /** Deletes schedule {@code id}; returns it as it was. */
    public ScheduleStore.Schedule delete(long id, String by, AccessRole caller) {
        requireSetter(caller);
        ScheduleStore.Schedule s = store.delete(id).orElseThrow(() -> fail(Response.Status.NOT_FOUND,
            "No schedule " + id));
        LOG.infof("Schedule %d of %s deleted by %s", id, target(s), by);
        return s;
    }

    // ---- pausing when the setter loses the role (ADR 0046) ----

    /** Pauses, at once, every schedule token {@code tokenId} set: it was revoked. Each is recorded. */
    public void tokenRevoked(long tokenId, AuditLog.Actor actor) {
        for (ScheduleStore.Schedule s : store.pauseSetBy(tokenId, null, null)) {
            paused(s, "the token that set it was revoked", actor);
        }
    }

    /**
     * Pauses, at once, every schedule {@code person} set whose role is above {@code role}, which
     * their sign-in has just given them (null: none). Each is recorded.
     */
    public void personSignedIn(String person, AccessRole role, AuditLog.Actor actor) {
        for (ScheduleStore.Schedule s : store.pauseSetBy(null, person, role, SIGN_IN_LOCK_TIMEOUT)) {
            paused(s, role == null ? "the person who set it signed in and was given no role"
                : "the person who set it signed in as " + role.id(), actor);
        }
    }

    /** How long a sign-in's pause waits for a schedule a fire holds, before giving up to the fire-time check. */
    static final java.time.Duration SIGN_IN_LOCK_TIMEOUT = java.time.Duration.ofSeconds(2);

    /** Records a schedule paused because its setter no longer holds its role. */
    void paused(ScheduleStore.Schedule s, String reason, AuditLog.Actor actor) {
        LOG.infof("Schedule %d of %s paused: %s", s.id(), target(s), reason);
        if (audit != null) {
            Map<String, Object> detail = auditDetail(s);
            detail.put("enabled", false);
            detail.put("reason", reason);
            audit.record(actor, AuditLog.SCHEDULE_PAUSED, "schedule " + s.id(), detail);
        }
    }

    /** When {@code cron} would fire next in {@code zone}, or why it cannot be used. */
    public ScheduleViews.Preview preview(String cron, String zone) {
        try {
            ZoneId z = zone(zone);
            Cron c = Cron.parse(cron);
            List<Instant> next = new ArrayList<>();
            Instant at = clock.instant();
            for (int i = 0; i < PREVIEW; i++) {
                at = c.next(at, z);
                if (at == null) {
                    break;
                }
                next.add(at);
            }
            if (next.isEmpty()) {
                throw new IllegalArgumentException(never(c));
            }
            return new ScheduleViews.Preview(true, null, c.text(), z.getId(), next);
        } catch (IllegalArgumentException e) {
            return new ScheduleViews.Preview(false, e.getMessage(), cron, zone, List.of());
        }
    }

    /**
     * What {@code body} sets, checked: one pipeline or flow that exists, a cron expression that
     * fires, a zone, values its latest version takes (as Run now checks them), and a role to act as
     * that is steward or designer and not above the caller's.
     */
    ScheduleStore.Setting setting(ScheduleViews.ScheduleChange body, AccessRole caller) {
        if (body == null) {
            throw new IllegalArgumentException("Send {\"pipeline\": \"...\", \"cron\": \"0 2 * * *\"}, or flow in "
                + "place of pipeline");
        }
        String pipeline = blank(body.pipeline());
        String flow = blank(body.flow());
        if ((pipeline == null) == (flow == null)) {
            throw new IllegalArgumentException("Give a pipeline or a flow to run, not both");
        }
        ZoneId zone = zone(body.zone());
        Cron cron = Cron.parse(body.cron());
        AccessRole runAs = runAs(body.runAs(), caller);
        Map<String, Object> parameters = body.parameters() == null ? Map.of() : body.parameters();
        try {
            if (pipeline != null) {
                pipelines.checkParameters(pipeline, parameters);
            } else {
                flows.checkParameters(flow, parameters);
            }
        } catch (WebApplicationException e) {
            if (e.getResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw new IllegalArgumentException(pipeline != null ? "pipeline: there is no pipeline called "
                    + pipeline : "flow: there is no flow called " + flow, e);
            }
            throw e;
        }
        String description = blank(body.description());
        if (description != null && description.length() > 200) {
            throw new IllegalArgumentException("description: at most 200 characters");
        }
        if (description != null && description.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("description: one line, without control characters");
        }
        boolean enabled = !Boolean.FALSE.equals(body.enabled());
        if (cron.next(clock.instant(), zone) == null) {
            throw new IllegalArgumentException(never(cron));
        }
        return new ScheduleStore.Setting(pipeline, flow, cron.text(), zone.getId(), write(parameters),
            runAs.id(), enabled, description);
    }

    /** The zone named, an IANA name such as Europe/London; UTC when none is given. */
    static ZoneId zone(String text) {
        String z = blank(text);
        if (z == null) {
            return ZoneId.of("UTC");
        }
        if (!ZoneId.getAvailableZoneIds().contains(z)) {
            throw new IllegalArgumentException("zone: use a time zone's name, such as Europe/London, "
                + "America/New_York or UTC");
        }
        return ZoneId.of(z);
    }

    /** The role a run acts as: steward by default, or designer; never admin, nor above the caller's. */
    static AccessRole runAs(String text, AccessRole caller) {
        String t = blank(text);
        AccessRole role = null;
        for (AccessRole r : RUN_AS) {
            if (t == null ? r == AccessRole.STEWARD : r.id().equals(t.toLowerCase(Locale.ROOT))) {
                role = r;
            }
        }
        if (role == null) {
            throw new IllegalArgumentException("runAs: steward or designer. A scheduled run never acts as an admin");
        }
        if (!caller.atLeast(role)) {
            throw fail(Response.Status.FORBIDDEN, "runAs: a schedule cannot act as a role above your own");
        }
        return role;
    }

    private static String never(Cron c) {
        return "cron: " + c.text() + " never fires: no date in the next ten years matches it";
    }

    // ---- views ----

    /**
     * A schedule as {@code values} allows: without them, no parameter values, and a failed fire's
     * outcome without the server's message, which can be worked out from those values.
     */
    ScheduleViews.Schedule view(ScheduleStore.Schedule s, boolean values) {
        return new ScheduleViews.Schedule(s.id(), s.pipeline(), s.flow(), s.cron(), s.zone(),
            values ? tree(s.parameters()) : null, s.runAs(), s.enabled(), s.description(), s.createdBy(),
            s.createdAt(), s.updatedBy(), s.updatedAt(), s.nextAt(), s.lastAt(),
            values ? s.lastOutcome() : outcomeWithoutReason(s.lastOutcome()), s.lastPipelineRun(), s.lastFlowRun());
    }

    /** A failed or paused fire's outcome as just {@code failed} or {@code paused}; any other as it is. */
    static String outcomeWithoutReason(String outcome) {
        if (outcome != null && outcome.startsWith(Scheduler.FAILED)) {
            return "failed";
        }
        return outcome != null && outcome.startsWith("paused:") ? "paused" : outcome;
    }

    /** What the audit log records of a schedule (ADR 0029): never its parameter values. */
    public static Map<String, Object> auditDetail(ScheduleStore.Schedule s) {
        return auditDetail(s.pipeline(), s.flow(), s.cron(), s.zone(), s.runAs(), s.enabled());
    }

    /** As {@link #auditDetail(ScheduleStore.Schedule)}, from its parts. */
    public static Map<String, Object> auditDetail(String pipeline, String flow, String cron, String zone,
                                                  String runAs, boolean enabled) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (pipeline != null) {
            d.put("pipeline", pipeline);
        } else {
            d.put("flow", flow);
        }
        d.put("cron", cron);
        d.put("zone", zone);
        d.put("runAs", runAs);
        d.put("enabled", enabled);
        return d;
    }

    /** The values a schedule's runs start with. */
    Map<String, Object> parameters(ScheduleStore.Schedule s) {
        try {
            return json.readValue(s.parameters() == null ? "{}" : s.parameters(),
                new TypeReference<LinkedHashMap<String, Object>>() {
                });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("schedule " + s.id() + "'s parameters could not be read", e);
        }
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text == null ? "{}" : text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String write(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("parameters: could not be read", e);
        }
    }

    static String target(ScheduleStore.Schedule s) {
        return s.pipeline() != null ? "pipeline " + s.pipeline() : "flow " + s.flow();
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
