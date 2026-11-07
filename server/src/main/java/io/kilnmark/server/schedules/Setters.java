package io.kilnmark.server.schedules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;
import io.kilnmark.server.auth.AuthMode;
import io.kilnmark.server.auth.OidcAccess;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Whether whoever set a schedule still holds its role (ADR 0046), checked before every fire, under
 * the schedule's row lock:
 *
 * <ul>
 *   <li>an API token must still work (not revoked, not expired, within the longest lifetime) and
 *       have the role;</li>
 *   <li>a person signed in through the identity provider must have it as the access review reads
 *       their sign-ins, and no more than their groups give under the settings now; and their latest
 *       sign-in must be within {@code kilnmark.schedules.signed-in-within} (30 days), since someone
 *       who has left is never seen to sign in again;</li>
 *   <li>a schedule saved while sign-in was off runs only while it is still off.</li>
 * </ul>
 */
@ApplicationScoped
public class Setters {

    /** Why a fire may not run, and the outcome it records. */
    public record Problem(String outcome, String reason) {
    }

    private final ApiTokens tokens;
    private final AuditLog audit;
    private final KilnmarkConfig config;

    public Setters(ApiTokens tokens, AuditLog audit, KilnmarkConfig config) {
        this.tokens = tokens;
        this.audit = audit;
        this.config = config;
    }

    /** Why whoever set {@code s} may no longer run it with its role; null when they may. */
    public Problem problem(ScheduleStore.Schedule s) {
        return problem(s, Instant.now());
    }

    Problem problem(ScheduleStore.Schedule s, Instant now) {
        AccessRole needed = AccessRole.parse(s.runAs());
        if (s.setByToken() != null) {
            Optional<AccessRole> role = tokens.workingRole(s.setByToken());
            if (role.isEmpty()) {
                return paused("the token that set it is revoked or expired");
            }
            return role.get().atLeast(needed) ? null : paused("the token that set it has a lower role");
        }
        if (s.setByPerson() != null) {
            if (config.production() && io.kilnmark.server.starter.ExampleAccounts.isExample(s.updatedBy())) {
                // ADR 0051: whatever role their last sign-in in a trial gave them
                return paused("the person who set it is a Starter Pack example account, and the install is marked "
                    + "production");
            }
            Optional<AuditLog.Standing> standing = audit.providerStanding(s.setByPerson(), s.updatedBy());
            if (standing.isEmpty()) {
                return paused("the person who set it has no role at their latest sign-in");
            }
            return personProblem(standing.get(), needed, config.auth().oidc(), config.schedules().signedInWithin(),
                now);
        }
        return config.auth().mode() == AuthMode.OFF ? null : paused("it was set while sign-in was off");
    }

    /**
     * Whether a person's standing still allows a schedule needing {@code needed}: the role their
     * latest sign-in recorded, lowered to what their recorded groups give under {@code oidc} now
     * (sign-ins from before groups were recorded keep their role), and a sign-in within
     * {@code within}.
     */
    static Problem personProblem(AuditLog.Standing standing, AccessRole needed, KilnmarkConfig.Oidc oidc,
                                 Duration within, Instant now) {
        AccessRole role = standing.role();
        boolean lowered = false;
        List<String> groups = standing.groups();
        if (groups != null) {
            AccessRole current = OidcAccess.roleOf(oidc, groups);
            if (role != null && (current == null || current.compareTo(role) < 0)) {
                role = current;
                lowered = true;
            }
        }
        if (role == null || !role.atLeast(needed)) {
            return paused(lowered ? "the person's groups no longer give the role" : role == null
                ? "the person who set it has no role at their latest sign-in"
                : "the person who set it signed in with a lower role");
        }
        if (standing.at() == null || standing.at().isBefore(now.minus(within))) {
            return new Problem(notSignedIn(within), "the person who set it has not signed in for " + days(within)
                + " days");
        }
        return null;
    }

    /** The outcome when a person's latest sign-in is older than {@code within}. */
    static String notSignedIn(Duration within) {
        return "paused: the person who set it has not signed in for " + days(within) + " days";
    }

    private static long days(Duration d) {
        return Math.max(1, d.toDays());
    }

    private static Problem paused(String reason) {
        return new Problem(ScheduleStore.PAUSED, reason);
    }
}
