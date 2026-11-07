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
 * other. A Starter Pack install never becomes a production one: install Kilnmark afresh. Only a new
 * database records a mode: one made before there were modes (release 0.1.1 and earlier, a preview)
 * is never upgraded, and its server does not start. Production is never recorded on a database
 * server that already has the Starter Pack's database or login. A refusal is recorded in the audit
 * log, when the database answers, before the server stops.
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

    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;

    /**
     * What a first start checks before it records a mode: with {@code anyDatabase}, a database not
     * marked new (DatabaseTls) records one too (quarkusDev only); with {@code samples}, production is
     * refused where the Starter Pack's database or login is already on the server.
     */
    public record FirstStart(boolean anyDatabase, boolean samples) {
    }

    /** Where the install's id, made at its first start, is recorded: a backup carries it. */
    public static final String INSTALL_ID = "install_id";

    // after the migrations (DatabaseTls, priority 1)
    void onStart(@Observes @Priority(3) StartupEvent event) {
        org.eclipse.microprofile.config.Config config = ConfigProvider.getConfig();
        String mode = config.getConfigValue("kilnmark.mode").getValue();
        io.quarkus.runtime.LaunchMode launch = io.quarkus.runtime.LaunchMode.current();
        // quarkusDev keeps a developer's database from before there were modes; the tests' database
        // servers are shared, so the Starter Pack's login may be there from another test
        FirstStart first = new FirstStart(launch == io.quarkus.runtime.LaunchMode.DEVELOPMENT,
            launch == io.quarkus.runtime.LaunchMode.NORMAL);
        String problem = checkFor(mode, RECORDED, first);
        // A running install, not quarkusDev or the tests, which have no Keycloak of their own.
        if (problem == null && io.quarkus.runtime.LaunchMode.current() == io.quarkus.runtime.LaunchMode.NORMAL) {
            problem = signInProblem(mode, config.getOptionalValue("quarkus.oidc.tenant-enabled", Boolean.class)
                .orElse(false), config.getConfigValue("quarkus.oidc.auth-server-url").getValue());
        }
        if (problem != null) {
            refuse(problem);
        }
    }

    /**
     * Stops the start: records why in the audit log first, when the database answers (the row and
     * the line for the SIEM), then throws.
     */
    public void refuse(String problem) {
        try {
            audit.record(new io.kilnmark.server.audit.AuditLog.Actor("server (start)", null),
                io.kilnmark.server.audit.AuditLog.START_REFUSED, "deployment mode", java.util.Map.of("reason", problem));
            audit.awaitStored(3000);
        } catch (RuntimeException e) {
            LOG.warnf("Could not record the refused start in the audit log (%s)", e.getClass().getSimpleName());
        }
        throw new IllegalStateException(problem);
    }

    /** Why a database made before there were modes never starts (ADR 0051). */
    static final String PREDATES = "This install predates deployment modes (release 0.1.1 or earlier, a preview), so "
        + "it cannot be upgraded: install Kilnmark afresh. Back up first anything you mean to keep by hand.";

    /** Why production is refused where the Starter Pack has been. */
    static final String SAMPLES_THERE = "This database server already has the Starter Pack's database or login "
        + "(kilnmark_samples), so a production install is not recorded on it: install Kilnmark afresh, on a server of "
        + "its own, or set KILNMARK_MODE=starter-pack.";

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
            + "of them. The control script sets it at install.";
    }

    /**
     * Checks {@code configured} against the mode recorded in Kilnmark's own database (under {@code
     * key}), recording it when nothing is recorded yet and the database is new (the first start; the
     * first of several servers starting together wins, and the rest are checked against it). Why the
     * server may not start, or null.
     */
    public String checkFor(String configured, String key, FirstStart rules) {
        String problem = problem(configured);
        if (problem != null) {
            return problem;
        }
        String[] refusal = new String[1];
        String recorded = db.tx(c -> {
            boolean first;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    first = !rs.next();
                }
            }
            String newMark = RECORDED.equals(key) ? io.kilnmark.server.DatabaseTls.NEW_DATABASE : key + "_new";
            if (first && !rules.anyDatabase() && !markedNew(c, newMark)) {
                refusal[0] = PREDATES;
                return null;
            }
            if (first && rules.samples() && PRODUCTION.equals(configured) && samplesThere(c)) {
                refusal[0] = SAMPLES_THERE;
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.server_setting (key, value, updated_by) "
                + "VALUES (?, ?, 'server (first start)') ON CONFLICT (key) DO NOTHING")) {
                ps.setString(1, key);
                ps.setString(2, configured);
                if (ps.executeUpdate() == 1) {
                    LOG.infof("Deployment mode recorded at first start: %s", configured);
                }
            }
            // the install's own id, beside the mode: a backup carries both
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.server_setting (key, value, updated_by) "
                + "VALUES (?, ?, 'server (first start)') ON CONFLICT (key) DO NOTHING")) {
                ps.setString(1, RECORDED.equals(key) ? INSTALL_ID : key + "_install_id");
                ps.setString(2, java.util.UUID.randomUUID().toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, newMark);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
        if (refusal[0] != null) {
            return refusal[0];
        }
        return mismatch(configured, recorded);
    }

    /** Whether the database carries the mark of a new one (DatabaseTls, before its first migration). */
    static boolean markedNew(java.sql.Connection c, String mark) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.server_setting WHERE key = ?")) {
            ps.setString(1, mark);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Whether the Starter Pack's database or login is on this database server. */
    static boolean samplesThere(java.sql.Connection c) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pg_roles WHERE rolname = 'kilnmark_samples' "
            + "UNION ALL SELECT 1 FROM pg_database WHERE datname = 'kilnmark_samples'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
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
