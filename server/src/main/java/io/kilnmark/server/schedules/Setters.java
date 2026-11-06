package io.kilnmark.server.schedules;

import java.util.Optional;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.AuthMode;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Whether whoever set a schedule still holds its role (ADR 0046), checked before every fire: the
 * API token they used still works and has the role, or the person signed in through the identity
 * provider has it as the access review reads their sign-ins. A schedule saved while sign-in was off
 * runs only while it is still off.
 */
@ApplicationScoped
public class Setters {

    private final ApiTokens tokens;
    private final AuditLog audit;
    private final KilnmarkConfig config;

    public Setters(ApiTokens tokens, AuditLog audit, KilnmarkConfig config) {
        this.tokens = tokens;
        this.audit = audit;
        this.config = config;
    }

    /** Why whoever set {@code s} may no longer run it with its role, in a few words; null when they may. */
    public String problem(ScheduleStore.Schedule s) {
        AccessRole needed = AccessRole.parse(s.runAs());
        if (s.setByToken() != null) {
            Optional<AccessRole> role = tokens.workingRole(s.setByToken());
            if (role.isEmpty()) {
                return "the token that set it is revoked or expired";
            }
            return role.get().atLeast(needed) ? null : "the token that set it has a lower role";
        }
        if (s.setByPerson() != null) {
            Optional<AccessRole> role = audit.providerRole(s.setByPerson(), s.updatedBy());
            if (role.isEmpty()) {
                return "the person who set it has no role at their latest sign-in";
            }
            return role.get().atLeast(needed) ? null : "the person who set it signed in with a lower role";
        }
        return config.auth().mode() == AuthMode.OFF ? null : "it was set while sign-in was off";
    }
}
