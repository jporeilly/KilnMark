package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.quarkus.oidc.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records each sign-in with the identity provider in the audit log (ADR 0029, ADR 0036): the role it
 * was given and whether it holds the unmask grant, or why it was given none.
 */
@ApplicationScoped
public class SignInEvents {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SignInEvents.class);

    private final KilnmarkConfig config;
    private final AuditLog audit;

    /** Pauses the schedules a person set when a sign-in gives them less of a role (ADR 0046). */
    @jakarta.inject.Inject
    io.kilnmark.server.schedules.ScheduleService schedules;

    public SignInEvents(KilnmarkConfig config, AuditLog audit) {
        this.config = config;
        this.audit = audit;
    }

    void onEvent(@Observes SecurityEvent event) {
        if (event.getEventType() != SecurityEvent.Type.OIDC_LOGIN || event.getSecurityIdentity() == null
            || !(event.getSecurityIdentity().getPrincipal() instanceof JsonWebToken token)) {
            return;
        }
        OidcAccess.Decision d = OidcAccess.decide(config.auth().oidc(), token::getClaim, Instant.now());
        Map<String, Object> detail = new LinkedHashMap<>();
        if (d.allowed()) {
            detail.put("role", d.role().id());
            detail.put("unmask", d.unmask());
        } else {
            detail.put("reason", d.refusal());
        }
        String person = OidcIdentityAugmentor.personRef(event.getSecurityIdentity());
        if (person != null) {
            detail.put("person", person); // opaque: what a schedule set by this person is checked by
        }
        // the group names, so a schedule the person set is checked against today's mapping (ADR 0046)
        detail.put("groups", groups(OidcAccess.groupsOf(config.auth().oidc(), token::getClaim)));
        AuditLog.Actor actor = new AuditLog.Actor(OidcIdentityAugmentor.actorName(token), null);
        audit.record(actor, d.allowed() ? AuditLog.SIGN_IN : AuditLog.SIGN_IN_REFUSED, "session", detail);
        if (person != null && schedules != null) {
            try {
                // as the access review reads it: no group takes the role away, other refusals do not
                if (d.allowed()) {
                    schedules.personSignedIn(person, d.role(), actor);
                } else if (OidcAccess.NO_GROUP.equals(d.refusal())) {
                    schedules.personSignedIn(person, null, actor);
                }
            } catch (RuntimeException e) {
                // never fail a sign-in for this: each schedule is checked again before it runs
                LOG.errorf("Could not pause the schedules of a person who signed in with less of a role (%s); "
                    + "each is checked again before it runs", e.getClass().getSimpleName());
            }
        }
    }

    /** At most this many group names are recorded with a sign-in, each at most this long. */
    static final int MAX_GROUPS = 200;
    static final int MAX_GROUP_NAME = 200;

    /** The group names as recorded: sorted, without blanks or control characters, limited. */
    static List<String> groups(List<String> names) {
        return names.stream().filter(n -> n != null && !n.isBlank()).map(String::strip)
            .map(n -> n.replaceAll("\\p{Cntrl}", "?"))
            .map(n -> n.length() > MAX_GROUP_NAME ? n.substring(0, MAX_GROUP_NAME) : n)
            .distinct().sorted().limit(MAX_GROUPS).toList();
    }
}
