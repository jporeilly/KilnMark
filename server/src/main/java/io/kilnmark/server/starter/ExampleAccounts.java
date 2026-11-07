package io.kilnmark.server.starter;

import java.util.Locale;

/**
 * The Starter Pack's example accounts (ADR 0051): five people at kilnmark.com addresses, which the
 * control script and keycloak-setup make and remove in the bundled Keycloak. Kilnmark never makes or
 * changes them and holds no Keycloak credential; it only tells their sign-ins apart, so the access
 * review and Settings, Starter Pack can show them.
 */
public final class ExampleAccounts {

    private ExampleAccounts() {
    }

    /** Why a run is cancelled on production (ADR 0051), as the run and the audit log say. */
    public static final String RUN_CANCELLED = "Cancelled: this install is marked production, and a Starter Pack "
        + "example account, or a token or schedule of one, asked for it";

    /**
     * Records {@code run.cancelled} for run {@code id} of {@code kind} ("pipeline" or "flow")
     * {@code name}, cancelled because an example account asked for it on production.
     */
    public static void recordCancelled(io.kilnmark.server.audit.AuditLog audit, String kind, String name, long id) {
        if (audit != null) {
            audit.record(io.kilnmark.server.audit.AuditLog.Actor.server("production: no Starter Pack example accounts"),
                io.kilnmark.server.audit.AuditLog.RUN_CANCELLED, kind + " run " + id,
                java.util.Map.of(kind, name, "reason", RUN_CANCELLED));
        }
    }

    /**
     * Whether a sign-in name is a kilnmark.com example account: as the audit log records it,
     * {@code sso:<user name>} and the provider's ID for them in brackets.
     */
    public static boolean isExample(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).strip();
        if (n.startsWith("sso:")) {
            n = n.substring(4);
        }
        int space = n.indexOf(' ');
        if (space >= 0) {
            n = n.substring(0, space); // the provider's ID, " (...)"
        }
        return n.endsWith("@kilnmark.com");
    }
}
