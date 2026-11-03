package io.kilnmark.server.auth;

import java.time.Instant;
import java.util.LinkedHashMap;
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

    private final KilnmarkConfig config;
    private final AuditLog audit;

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
        audit.record(new AuditLog.Actor(OidcIdentityAugmentor.actorName(token), null), d.allowed() ? AuditLog.SIGN_IN
            : AuditLog.SIGN_IN_REFUSED, "session", detail);
    }
}
