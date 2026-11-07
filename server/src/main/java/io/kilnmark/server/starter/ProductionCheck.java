package io.kilnmark.server.starter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.ApiTokens;
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
 * every API token one of them made, or whose unmask grant one of them approved (ADR 0038), and
 * every token such a token made, is revoked; and every schedule one of them set is paused (ADR
 * 0046). Each is recorded. Their sign-ins are refused by {@code OidcAccess}.
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

    /** How a token made by a token names its maker ({@code TokenResource}): "token 12 (its name)". */
    private static final Pattern MADE_BY_TOKEN = Pattern.compile("^token (\\d{1,18}) \\(");

    private final KilnmarkConfig config;
    private final ApiTokens tokens;
    private final ScheduleService schedules;
    private final AuditLog audit;

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
            if (!s.tokens().isEmpty() || s.people() > 0) {
                LOG.warnf("Marked production: revoked %d API tokens of the Starter Pack's example accounts, and paused "
                    + "the schedules of %d of them", s.tokens().size(), s.people());
            }
        }
    }

    /** What is wrong with the setting's value, or null: only true and false, in any case, or unset. */
    static String problem(String value) {
        if (value == null || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return null;
        }
        return "kilnmark.production (KILNMARK_PRODUCTION) is true or false: the server does not start with any other "
            + "value, so that it means the same here as to the control script and keycloak-setup. Set it to true on "
            + "a production install, or remove it.";
    }

    /** What a sweep did: the tokens it revoked, and how many example accounts' schedules it paused. */
    public record Swept(List<Long> tokens, int people) {
    }

    /** Revokes and pauses what the example accounts left (see the class); each recorded. */
    public Swept sweep() {
        AuditLog.Actor actor = AuditLog.Actor.server("production: no Starter Pack example accounts");
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
        return new Swept(revoked, people);
    }

    /**
     * The tokens an example account made or approved the unmask grant of, and those such a token
     * made, however many steps on, revoked or not.
     */
    static Set<Long> tainted(List<ApiTokens.Token> all) {
        Set<Long> out = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ApiTokens.Token t : all) {
                if (out.contains(t.id())) {
                    continue;
                }
                Matcher m = MADE_BY_TOKEN.matcher(t.createdBy() == null ? "" : t.createdBy());
                if (ExampleAccounts.isExample(t.createdBy()) || ExampleAccounts.isExample(t.unmaskApprovedBy())
                    || (m.find() && out.contains(Long.parseLong(m.group(1))))) {
                    out.add(t.id());
                    grew = true;
                }
            }
        }
        return out;
    }
}
