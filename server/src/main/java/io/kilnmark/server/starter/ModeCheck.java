package io.kilnmark.server.starter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The deployment mode (ADR 0051), checked at every start: {@code kilnmark.mode} (KILNMARK_MODE) is
 * {@code production} or {@code starter-pack}, in ASCII lower case, and nothing else. At the first
 * start it is recorded in Kilnmark's own database; from then on a setting that differs from the
 * record stops the server, so editing config/.env never moves an install from one mode to the
 * other. A Starter Pack install never becomes a production one: install Kilnmark afresh. An install
 * made before there were modes that has had the example people (any of their five addresses in
 * Kilnmark's own records) is never recorded as production either.
 */
@ApplicationScoped
public class ModeCheck {

    private static final Logger LOG = Logger.getLogger(ModeCheck.class);

    /** Production: no Starter Pack, no samples database, no example people. */
    public static final String PRODUCTION = "production";

    /** For training and development only: the Starter Pack may be turned on. */
    public static final String STARTER_PACK = "starter-pack";

    /** Where the mode is recorded, in kilnmark.server_setting. */
    static final String RECORDED = "deployment_mode";

    private final Db db;

    public ModeCheck(Db db) {
        this.db = db;
    }

    // after the migrations (DatabaseTls, priority 1)
    void onStart(@Observes @Priority(3) StartupEvent event) {
        org.eclipse.microprofile.config.Config config = ConfigProvider.getConfig();
        String mode = config.getConfigValue("kilnmark.mode").getValue();
        String problem = check(mode);
        // A running install, not quarkusDev or the tests, which have no Keycloak of their own.
        if (problem == null && io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.NORMAL) {
            problem = signInProblem(mode, config.getOptionalValue("quarkus.oidc.tenant-enabled", Boolean.class)
                .orElse(false), config.getConfigValue("quarkus.oidc.auth-server-url").getValue());
        }
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
    }

    /** The bundled Keycloak's realm, as the release's compose file reaches it. */
    public static final String BUNDLED_ISSUER = "http:" + "/" + "/keycloak:8080/realms/kilnmark";

    /**
     * Why a Starter Pack install may not start with this sign-in, or null: its example people live
     * in the bundled Keycloak, so sign-in must be on, against its realm, and never an outside
     * identity provider. Production signs in however the site chose.
     */
    public static String signInProblem(String mode, boolean signInOn, String issuer) {
        if (!STARTER_PACK.equals(mode)) {
            return null;
        }
        if (!signInOn) {
            return "A Starter Pack install (KILNMARK_MODE=starter-pack) needs sign-in with the bundled Keycloak, where its "
                + "example people are: turn on the auth profile and KILNMARK_OIDC_ENABLED=true, as install does for a "
                + "Starter Pack install.";
        }
        String at = issuer == null ? "" : issuer.strip();
        while (at.endsWith("/")) {
            at = at.substring(0, at.length() - 1);
        }
        if (!BUNDLED_ISSUER.equals(at)) {
            return "A Starter Pack install (KILNMARK_MODE=starter-pack) signs in with the bundled Keycloak only, where its "
                + "example people are, never an outside identity provider: set KILNMARK_OIDC_ISSUER back to the bundled "
                + "realm, as install wrote it. For your own provider, install Kilnmark afresh in production mode.";
        }
        return null;
    }

    /** What is wrong with the setting's value, or null: only production or starter-pack. */
    static String problem(String value) {
        if (PRODUCTION.equals(value) || STARTER_PACK.equals(value)) {
            return null;
        }
        return "kilnmark.mode (KILNMARK_MODE) is production or starter-pack: the server does not start without one "
            + "of them. The control script sets it at install; an install made before there were modes is production.";
    }

    /**
     * Checks {@code configured} against the mode recorded in Kilnmark's own database, recording it
     * when nothing is recorded yet (the first start; the first of several servers starting together
     * wins, and the rest are checked against it). Why the server may not start, or null.
     */
    public String check(String configured) {
        return checkFor(configured, RECORDED);
    }

    /** As {@link #check(String)}, with the mode recorded under {@code key}: for tests. */
    public String checkFor(String configured, String key) {
        String problem = problem(configured);
        if (problem != null) {
            return problem;
        }
        String[] traces = new String[1];
        String recorded = db.tx(c -> {
            // Nothing recorded yet, and production asked for: an install that has had the example
            // people (an earlier version made them with KILNMARK_KEYCLOAK_DEMO_PASSWORD) may not be it.
            boolean first;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    first = !rs.next();
                }
            }
            if (first && PRODUCTION.equals(configured)) {
                traces[0] = exampleTraces(c);
                if (traces[0] != null) {
                    return null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.server_setting (key, value, updated_by) "
                + "VALUES (?, ?, 'server (first start)') ON CONFLICT (key) DO NOTHING")) {
                ps.setString(1, key);
                ps.setString(2, configured);
                if (ps.executeUpdate() == 1) {
                    LOG.infof("Deployment mode recorded at first start: %s", configured);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        if (traces[0] != null) {
            return "This install has had the Starter Pack's example people (" + traces[0] + " names one of their "
                + "addresses), so it may only be a Starter Pack install: set KILNMARK_MODE=starter-pack. Production "
                + "needs a fresh install.";
        }
        return mismatch(configured, recorded);
    }

    /**
     * Where Kilnmark's own records name one of the five example addresses (sign-ins and everything
     * else in the audit log, tokens, the owners of work, who asked for runs), or null when none does.
     */
    static String exampleTraces(java.sql.Connection c) throws java.sql.SQLException {
        String[][] places = {
            {"the audit log", "SELECT 1 FROM kilnmark.audit_event WHERE lower(actor || ' ' || coalesce(detail::text, '')) "
                + "LIKE ? LIMIT 1"},
            {"an API token", "SELECT 1 FROM kilnmark.api_token WHERE lower(created_by || ' ' || coalesce(unmask_requested_by, "
                + "'') || ' ' || coalesce(unmask_approved_by, '')) LIKE ? LIMIT 1"},
            {"a pipeline", "SELECT 1 FROM kilnmark.pipeline WHERE lower(created_by || ' ' || updated_by) LIKE ? LIMIT 1"},
            {"a flow", "SELECT 1 FROM kilnmark.flow WHERE lower(created_by || ' ' || updated_by) LIKE ? LIMIT 1"},
            {"a schedule", "SELECT 1 FROM kilnmark.schedule WHERE lower(created_by || ' ' || updated_by) LIKE ? LIMIT 1"},
            {"a pipeline run", "SELECT 1 FROM kilnmark.pipeline_run WHERE lower(requested_by) LIKE ? LIMIT 1"},
            {"a flow run", "SELECT 1 FROM kilnmark.flow_run WHERE lower(requested_by) LIKE ? LIMIT 1"},
        };
        for (String person : ExampleAccounts.PEOPLE) {
            for (String[] place : places) {
                try (PreparedStatement ps = c.prepareStatement(place[1])) {
                    ps.setString(1, "%" + person.replace("_", "\\_") + "%");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return place[0];
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Why a setting of {@code configured} may not start an install recorded as {@code recorded}, or null. */
    static String mismatch(String configured, String recorded) {
        if (configured.equals(recorded)) {
            return null;
        }
        if (STARTER_PACK.equals(recorded)) {
            return "This install was made in Starter Pack mode, for training and development, and a Starter Pack "
                + "install never becomes a production one: KILNMARK_MODE=production was refused. Install Kilnmark "
                + "afresh for production, or set KILNMARK_MODE back to starter-pack.";
        }
        if (PRODUCTION.equals(recorded)) {
            return "This install is in production mode, and the Starter Pack never comes to a production install: "
                + "KILNMARK_MODE=starter-pack was refused. Install a separate Kilnmark for training and development, or "
                + "set KILNMARK_MODE back to production.";
        }
        return "The deployment mode recorded in Kilnmark's database is neither production nor starter-pack, so the "
            + "server does not start. Restore the database from a backup.";
    }

    /** Whether this install is a Starter Pack one, by its setting (checked against the record at start). */
    public static boolean starterPack(io.kilnmark.server.KilnmarkConfig config) {
        return STARTER_PACK.equals(config.mode().orElse(null));
    }

    /**
     * Whether the Starter Pack's example data (the samples database, and the samples in the Library)
     * may be turned on here: on a Starter Pack install. Kept apart from {@link #examplePeopleAllowed},
     * which a production install never allows, so the two can be decided on their own.
     */
    public static boolean samplesAllowed(io.kilnmark.server.KilnmarkConfig config) {
        return starterPack(config);
    }

    /** Whether the Starter Pack's example people may be here, and sign in: on a Starter Pack install only. */
    public static boolean examplePeopleAllowed(io.kilnmark.server.KilnmarkConfig config) {
        return starterPack(config);
    }
}
