package io.kilnmark.server.starter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.flows.FlowStore;
import io.kilnmark.server.pipelines.PipelineService;
import io.kilnmark.server.pipelines.PipelineStore;
import io.kilnmark.server.schedules.ScheduleService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * At every start: {@code kilnmark.production} (KILNMARK_PRODUCTION) must be true or false, in any
 * case, as the control scripts and keycloak-setup read it (ADR 0051), so no value can mean
 * production to one and not to another; on anything else the server does not start. On an install
 * marked production, what the Starter Pack's example accounts left from a trial stops working:
 * every pipeline and flow run one of them, or a token or schedule of one, asked for that has not
 * finished is cancelled; every API token one of them made, or whose unmask grant one of them
 * approved (ADR 0038), and every token such a token made, is revoked; and every schedule one of
 * them set is paused (ADR 0046). Each is recorded. Their sign-ins are refused by {@code OidcAccess},
 * those tokens by {@code ApiTokens} even before this has run, and a run when it would start by the
 * pipeline and flow services.
 */
@ApplicationScoped
public class ProductionCheck {

    private static final Logger LOG = Logger.getLogger(ProductionCheck.class);

    /** Why a token is revoked or a schedule paused here, as the audit log records it. */
    public static final String TOKEN_REASON = "the install is marked production, and a Starter Pack example account "
        + "made the token, approved its unmask grant, or made the token that made it";

    /** Why a schedule an example account set is paused. */
    public static final String SCHEDULE_REASON = "the install is marked production, and a Starter Pack example "
        + "account set it";

    private final KilnmarkConfig config;
    private final ApiTokens tokens;
    private final ScheduleService schedules;
    private final AuditLog audit;

    /** The runs to cancel. */
    @jakarta.inject.Inject
    PipelineStore pipelineRuns;

    @jakarta.inject.Inject
    PipelineService pipelines;

    @jakarta.inject.Inject
    FlowStore flowRuns;

    @jakarta.inject.Inject
    FlowService flows;

    @jakarta.inject.Inject
    ExampleAccess examples;

    public ProductionCheck(KilnmarkConfig config, ApiTokens tokens, ScheduleService schedules, AuditLog audit) {
        this.config = config;
        this.tokens = tokens;
        this.schedules = schedules;
        this.audit = audit;
    }

    // after the migrations (DatabaseTls, priority 1)
    void onStart(@Observes @Priority(3) StartupEvent event) {
        String problem = problem(ConfigProvider.getConfig().getConfigValue("kilnmark.production").getValue());
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        if (config.production()) {
            Swept s = sweep();
            if (!s.tokens().isEmpty() || s.people() > 0 || !s.runs().isEmpty()) {
                LOG.warnf("Marked production: cancelled %d runs the Starter Pack's example accounts asked for, revoked "
                    + "%d of their API tokens, and paused the schedules of %d of them", s.runs().size(), s.tokens().size(),
                    s.people());
            }
        }
    }

    /** What is wrong with the setting's value, or null: only true and false, in any case, or unset. */
    static String problem(String value) {
        // ASCII case only: equalsIgnoreCase would take falſe (with U+017F) for false, which the
        // scripts and keycloak-setup do not
        String v = value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
        if (v == null || "true".equals(v) || "false".equals(v)) {
            return null;
        }
        return "kilnmark.production (KILNMARK_PRODUCTION) is true or false: the server does not start with any other "
            + "value, so that it means the same here as to the control script and keycloak-setup. Set it to true on "
            + "a production install, or remove it.";
    }

    /**
     * What a sweep did: the runs it cancelled ("pipeline run 4", "flow run 2"), the tokens it
     * revoked, and how many example accounts' schedules it paused.
     */
    public record Swept(List<String> runs, List<Long> tokens, int people) {
    }

    /** Revokes and pauses what the example accounts left (see the class); each recorded. */
    public Swept sweep() {
        AuditLog.Actor actor = AuditLog.Actor.server("production: no Starter Pack example accounts");
        // First what would act: unfinished runs (a run waiting for a place on a source is queued).
        List<String> cancelled = new ArrayList<>();
        for (FlowStore.Run r : flowRuns.unfinishedRuns()) {
            if (examples.actsForExample(r.requestedBy()) && cancel(() -> flows.cancel(r.id(), actor.name(), AccessRole.ADMIN))) {
                ExampleAccounts.recordCancelled(audit, "flow", r.flow(), r.id());
                cancelled.add("flow run " + r.id());
            }
        }
        for (PipelineStore.Run r : pipelineRuns.unfinishedRuns()) {
            if (examples.actsForExample(r.requestedBy()) && cancel(() -> pipelines.cancel(r.id(), actor.name()))) {
                ExampleAccounts.recordCancelled(audit, "pipeline", r.pipeline(), r.id());
                cancelled.add("pipeline run " + r.id());
            }
        }
        List<ApiTokens.Token> all = tokens.list();
        Set<Long> tainted = tainted(all);
        List<Long> revoked = new ArrayList<>();
        for (ApiTokens.Token t : all) {
            if (t.revokedAt() != null || !tainted.contains(t.id())) {
                continue;
            }
            tokens.revoke(t.id(), actor.name()).ifPresent(x -> {
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("name", x.name());
                detail.put("role", x.role().id());
                detail.put("reason", TOKEN_REASON);
                audit.record(actor, AuditLog.TOKEN_REVOKED, "token " + x.id(), detail);
                schedules.tokenRevoked(x.id(), actor);
                revoked.add(x.id());
            });
        }
        int people = 0;
        for (Map.Entry<String, String> p : audit.signedInPeople().entrySet()) {
            if (ExampleAccounts.isExample(p.getValue())) {
                schedules.personBarred(p.getKey(), SCHEDULE_REASON, actor);
                people++;
            }
        }
        return new Swept(cancelled, revoked, people);
    }

    /** Asks a run to stop; false when it finished meanwhile. */
    private static boolean cancel(Runnable ask) {
        try {
            ask.run();
            return true;
        } catch (jakarta.ws.rs.WebApplicationException e) {
            return false; // finished (409) or gone (404) since it was listed
        }
    }

    /**
     * The tokens an example account made or approved the unmask grant of, and those such a token
     * made, however many steps on, revoked or not ({@link ApiTokens#madeByExample}).
     */
    static Set<Long> tainted(List<ApiTokens.Token> all) {
        Map<Long, ApiTokens.Token> byId = new HashMap<>();
        all.forEach(t -> byId.put(t.id(), t));
        Set<Long> out = new HashSet<>();
        for (ApiTokens.Token t : all) {
            if (ApiTokens.madeByExample(t, id -> Optional.ofNullable(byId.get(id)))) {
                out.add(t.id());
            }
        }
        return out;
    }
}
