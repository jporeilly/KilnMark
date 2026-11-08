package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.microprofile.jwt.JsonWebToken;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.schedules.ScheduleService;
import io.kilnmark.server.audit.AuditLog;
import io.quarkus.oidc.SecurityEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records each sign-in with the identity provider in the audit log (ADR 0029, ADR 0036): the role it
 * was given and whether it holds the unmask grant, or why it was given none. Renewing a session with
 * the refresh token is no sign-in, and is not recorded (the OIDC client announces it as one too).
 */
@ApplicationScoped
public class SignInEvents {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(SignInEvents.class);

    private final KilnmarkConfig config;
    private final AuditLog audit;

    /** Pauses the schedules a person set when a sign-in gives them less of a role (ADR 0046). */
    @jakarta.inject.Inject
    ScheduleService schedules;

    public SignInEvents(KilnmarkConfig config, AuditLog audit) {
        this.config = config;
        this.audit = audit;
    }

    void onEvent(@Observes SecurityEvent event) {
        if (event.getEventType() != SecurityEvent.Type.OIDC_LOGIN || event.getSecurityIdentity() == null
            || !(event.getSecurityIdentity().getPrincipal() instanceof JsonWebToken token)) {
            return;
        }
        if (OidcIdentityAugmentor.renewal(event.getSecurityIdentity())) {
            return; // a renewal, handled by renewed(); recording it would add a sign-in every few minutes
        }
        OidcAccess.Decision d = OidcAccess.decide(config, token, Instant.now());
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
        // only the groups the role settings name, so a schedule the person set is checked against
        // today's mapping (ADR 0046); never their other groups
        detail.put("groups", OidcAccess.mappedGroups(config.auth().oidc(), token::getClaim).stream()
            .map(ApiTokens::loggable).toList());
        AuditLog.Actor actor = new AuditLog.Actor(OidcIdentityAugmentor.actorName(token), null);
        audit.record(actor, d.allowed() ? AuditLog.SIGN_IN : AuditLog.SIGN_IN_REFUSED, "session", detail);
        ScheduleService eager = schedules;
        if (person != null && eager != null) {
            // as the access review reads it: no group takes the role away, other refusals do not
            AccessRole role = d.allowed() ? d.role() : null;
            if (d.allowed() || OidcAccess.NO_GROUP.equals(d.refusal())) {
                // off the sign-in's thread (it can be an event loop): the sign-in never waits on the
                // database for this, and each schedule is checked again before it runs regardless
                Thread.ofVirtual().name("kilnmark-sign-in-pause").start(() -> pause(eager, person, role, actor));
            }
        }
    }

    /**
     * A session renewed with the refresh token, as {@link OidcAccess} decided on the provider's fresh
     * ID token: not a sign-in, so nothing is recorded, but a role the provider has lowered since (or
     * taken away: no group) pauses the schedules the person set above it, as a sign-in would. With the
     * role unchanged nothing is above it, so nothing pauses. Never throws. Unlike a sign-in's, what a
     * renewal finds is not recorded, so if this pause fails (a fire holds the schedule past the lock
     * timeout, the database is down) the fire-time check still reads the latest sign-in's groups,
     * until the person next signs in (at most the longest session later, while they use Kilnmark).
     */
    void renewed(io.quarkus.security.identity.SecurityIdentity identity, JsonWebToken token, OidcAccess.Decision d) {
        String person = OidcIdentityAugmentor.personRef(identity);
        ScheduleService eager = schedules;
        if (person == null || eager == null || !(d.allowed() || OidcAccess.NO_GROUP.equals(d.refusal()))) {
            return;
        }
        AccessRole role = d.allowed() ? d.role() : null;
        AuditLog.Actor actor = new AuditLog.Actor(OidcIdentityAugmentor.actorName(token), null);
        Thread.ofVirtual().name("kilnmark-renewal-pause").start(() -> {
            try {
                eager.personSignedIn(person, role, actor);
            } catch (RuntimeException e) {
                LOG.errorf("Could not pause the schedules of a person whose renewed sign-in gives less of a role "
                    + "(%s); they stand on the person's latest sign-in until the next one", e.getClass().getSimpleName());
            }
        });
    }

    /** Pauses what the person set above {@code role}; never throws, since nothing waits for it. */
    static void pause(ScheduleService schedules, String person, AccessRole role, AuditLog.Actor actor) {
        try {
            schedules.personSignedIn(person, role, actor);
        } catch (RuntimeException e) {
            LOG.errorf("Could not pause the schedules of a person who signed in with less of a role (%s); "
                + "each is checked again before it runs", e.getClass().getSimpleName());
        }
    }
}
