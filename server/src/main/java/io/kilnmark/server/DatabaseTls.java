package io.kilnmark.server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import org.postgresql.jdbc.SslMode;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionFactoryConfiguration;
import io.agroal.api.security.AgroalSecurityProvider;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.flyway.runtime.FlywayDataSourceRuntimeConfig;
import io.quarkus.flyway.runtime.FlywayRuntimeConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Stops the server at start unless its own database is reached over TLS (ADR 0032): the connection
 * settings must demand TLS ({@code sslmode} {@code require}, {@code verify-ca} or {@code verify-full}),
 * and the session must be encrypted. Both, as {@code sslmode=prefer} (the driver's default) falls
 * back to plain text without a word, and one encrypted session says nothing about the next. The
 * settings are the pool's own (its URL, its driver properties and what its credentials add, built as
 * the pool builds them) read by the PostgreSQL driver's own parser, so the check sees the mode the
 * driver will use, never another reading of the configuration. It is the first start-up step, and
 * the database migrations run here, after it, through the checked pool: Flyway does nothing at start
 * on its own ({@code quarkus.flyway.migrate-at-start=false}), and with the check on, a start-up job of
 * Flyway's or a Flyway URL or login of its own (which would open connections the check never sees)
 * stops the server. {@code kilnmark.database.require-tls=false} turns the check off, for a
 * developer's own database; the migrations still run.
 */
@ApplicationScoped
public class DatabaseTls {

    private static final Logger LOG = Logger.getLogger(DatabaseTls.class);

    private final KilnmarkConfig config;
    private final AgroalDataSource source;
    private final FlywayRuntimeConfig flywayConfig;
    private final Instance<Flyway> flyway;

    public DatabaseTls(KilnmarkConfig config, AgroalDataSource source, FlywayRuntimeConfig flywayConfig,
                       Instance<Flyway> flyway) {
        this.config = config;
        this.source = source;
        this.flywayConfig = flywayConfig;
        this.flyway = flyway;
    }

    void onStart(@Observes @Priority(1) StartupEvent event) {
        if (config.database().requireTls()) {
            check();
        } else {
            LOG.warn("kilnmark.database.require-tls is off: Kilnmark's own database may be reached without TLS");
        }
        FlywayDataSourceRuntimeConfig f = flywayConfig.datasources().get(DataSourceUtil.DEFAULT_DATASOURCE_NAME);
        boolean active = f == null || f.active().orElse(true);
        if (active && flyway.isResolvable()) {
            // A database with no migration history is new: marked so BEFORE migrating, durably, in
            // a table of its own made first in a transaction of its own, so the first start's mode is
            // recorded (ModeCheck) even when that start is stopped part way through the migrations
            // (each commits on its own) and a later one gets there. The mark goes only when the mode
            // is recorded. A database migrated before, with no mode, never gets it.
            markThenMigrate(source::getConnection, () -> flyway.get().migrate());
        }
    }

    /** A connection to Kilnmark's own database. */
    @FunctionalInterface
    public interface Connect {
        Connection open() throws SQLException;
    }

    /**
     * Marks the database new when it has no migration history yet (committed on its own), and only
     * then migrates it: the order a first start that is stopped part way relies on.
     */
    public static void markThenMigrate(Connect connect, Runnable migrate) {
        try (Connection c = connect.open()) {
            if (isNew(c)) {
                markNew(c, NEW_DATABASE);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Kilnmark's own database could not be read or marked before migrating ("
                + e.getSQLState() + ")", e);
        }
        migrate.run();
    }

    /**
     * Where a new database's mark is kept until its first start records a mode: a schema of its own,
     * apart from Kilnmark's (which Flyway makes, and refuses to migrate once it holds anything).
     */
    public static final String MARKS = "kilnmark_first_start.mark";

    /** The mark of a new database, in {@link #MARKS}, until its first start records a mode. */
    public static final String NEW_DATABASE = "deployment_mode";

    /** Whether the database has no migration history yet: a new one. */
    public static boolean isNew(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT to_regclass('kilnmark.flyway_schema_history') IS NULL")) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    /**
     * Marks the database new under {@code key}, once, in {@link #MARKS} (made here if need be), in a
     * transaction of its own, committed before anything else happens; it stays until a mode is recorded.
     */
    public static void markNew(Connection c, String key) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement();
             java.sql.PreparedStatement ps = c.prepareStatement("INSERT INTO " + MARKS
                 + " (key) VALUES (?) ON CONFLICT (key) DO NOTHING")) {
            st.execute("CREATE SCHEMA IF NOT EXISTS kilnmark_first_start");
            st.execute("CREATE TABLE IF NOT EXISTS " + MARKS + " (key text PRIMARY KEY, marked_at timestamptz NOT NULL "
                + "DEFAULT now())");
            ps.setString(1, key);
            ps.executeUpdate();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
    }

    private void check() {
        // What the pool itself will use, not the configuration keys: Quarkus accepts the same setting
        // under more than one name, and a credentials provider adds driver properties of its own.
        var factory = source.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration();
        String problem = flywayProblem(flywayConfig.datasources().get(DataSourceUtil.DEFAULT_DATASOURCE_NAME));
        if (problem == null) {
            problem = providerProblem(factory.connectionProviderClass());
        }
        if (problem == null) {
            problem = settingProblem(factory.jdbcUrl(), driverProperties(factory));
        }
        if (problem == null) {
            try (Connection c = source.getConnection()) {
                problem = encrypted(c) ? null : "Kilnmark's own database session is not encrypted, though TLS was asked "
                    + "for. Turn TLS on in that PostgreSQL (ssl = on with a certificate).";
            } catch (SQLException e) {
                // Not the message: it could name the host. The state says enough to look further.
                problem = "Kilnmark could not check that its own database is reached over TLS (SQLSTATE "
                    + e.getSQLState() + ").";
            }
        }
        if (problem != null) {
            throw new IllegalStateException(problem + " See INSTALL.md, \"Kilnmark's own database\"; "
                + "kilnmark.database.require-tls=false turns this check off.");
        }
    }

    /**
     * Why Flyway could reach the database other than through the checked pool, or null when it cannot:
     * a job Flyway runs at start (it would run before this check), or a Flyway URL or login of its own
     * (Quarkus then opens Flyway's connections from that URL, or from the pool's URL without the pool's
     * driver properties).
     */
    static String flywayProblem(FlywayDataSourceRuntimeConfig f) {
        if (f == null) {
            return null;
        }
        if (f.migrateAtStart().orElse(false) || f.cleanAtStart().orElse(false) || f.validateAtStart()
            || f.repairAtStart() || f.baselineAtStart()) {
            return "Flyway is set to work on Kilnmark's own database at start, before this check: Kilnmark runs "
                + "its migrations itself, after it. Remove the quarkus.flyway.*-at-start settings.";
        }
        if (f.jdbcUrl().isPresent() || f.username().isPresent() || f.password().isPresent()) {
            return "Flyway is set to reach Kilnmark's own database with a URL or login of its own, which this "
                + "check cannot see: Kilnmark migrates through its own connection pool. Remove "
                + "quarkus.flyway.jdbc-url, username and password.";
        }
        return null;
    }

    /**
     * Why the pool's way of opening connections is one this check cannot vouch for, or null: only the
     * PostgreSQL driver itself reads the settings as the check does (its parser, the URL winning); a
     * DataSource class lets the properties win instead, and another driver may read sslmode its own way.
     */
    static String providerProblem(Class<?> provider) {
        if (provider == null || provider == org.postgresql.Driver.class) {
            return null;
        }
        return "Kilnmark's own database pool opens connections through something other than the PostgreSQL "
            + "driver, which this check cannot vouch for: use the PostgreSQL driver.";
    }

    /**
     * The driver properties the pool passes with its URL, as Agroal builds them: its JDBC properties,
     * then, for each credential, what the first security provider with an answer makes of it (a
     * credentials provider can add {@code sslmode}), later credentials winning. The user name and
     * password are left out: they say nothing about TLS, and this never holds a secret longer than it
     * must.
     */
    static Properties driverProperties(AgroalConnectionFactoryConfiguration factory) {
        Properties props = new Properties();
        props.putAll(factory.jdbcProperties());
        for (Object credential : factory.credentials()) {
            for (AgroalSecurityProvider provider : factory.securityProviders()) {
                Properties added = provider.getSecurityProperties(credential);
                if (added != null) {
                    props.putAll(added);
                    break;
                }
            }
        }
        props.remove("user");
        props.remove("password");
        return props;
    }

    /** {@link #settingProblem(String, Properties)} with at most the one property {@code sslmode}. */
    static String settingProblem(String url, String property) {
        Properties given = new Properties();
        if (property != null) {
            given.setProperty("sslmode", property);
        }
        return settingProblem(url, given);
    }

    /**
     * Why the database settings do not demand TLS, or null when they do: the mode the PostgreSQL driver
     * will use, worked out by the driver itself from the URL and the driver properties (the URL wins,
     * keys are matched exactly, and {@code ssl=true} alone means verify-full, all as the driver reads
     * them).
     */
    static String settingProblem(String url, Properties given) {
        Properties p = url == null ? null : org.postgresql.Driver.parseURL(url, given);
        if (p == null) {
            return "Kilnmark's own database address is not a PostgreSQL JDBC URL.";
        }
        SslMode mode;
        try {
            mode = SslMode.of(p);
        } catch (java.sql.SQLException e) {
            return "Kilnmark's own database connection has an sslmode the driver does not know.";
        }
        if (mode.requireEncryption()) {
            return null;
        }
        return "Kilnmark's own database connection does not demand TLS: the driver would use sslmode="
            + mode.value + ", which falls back to plain text. Add ?sslmode=require to QUARKUS_DATASOURCE_JDBC_URL "
            + "(the name in lower case), or verify-full with the database's CA (sslrootcert).";
    }

    /** Whether this session to PostgreSQL is encrypted, as the server itself says. */
    static boolean encrypted(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid = pg_catalog.pg_backend_pid()")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
