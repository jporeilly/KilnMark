package io.kilnmark.server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import org.jboss.logging.Logger;

import org.postgresql.jdbc.SslMode;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.AgroalConnectionFactoryConfiguration;
import io.agroal.api.security.AgroalSecurityProvider;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Stops the server at start unless its own database is reached over TLS (ADR 0032): the connection
 * settings must demand TLS ({@code sslmode} {@code require}, {@code verify-ca} or {@code verify-full}),
 * and the session must be encrypted. Both, as {@code sslmode=prefer} (the driver's default) falls
 * back to plain text without a word, and one encrypted session says nothing about the next. The
 * settings are the pool's own (its URL, its driver properties and what its credentials add, built as
 * the pool builds them) read by the PostgreSQL driver's own parser, so the check sees the mode the
 * driver will use, never another reading of the configuration. It runs before Kilnmark's other start-up work; Quarkus runs
 * Flyway's migrations earlier still. {@code kilnmark.database.require-tls=false} turns it off, for a
 * developer's own database.
 */
@ApplicationScoped
public class DatabaseTls {

    private static final Logger LOG = Logger.getLogger(DatabaseTls.class);

    private final KilnmarkConfig config;
    private final AgroalDataSource source;

    public DatabaseTls(KilnmarkConfig config, AgroalDataSource source) {
        this.config = config;
        this.source = source;
    }

    void onStart(@Observes @Priority(1) StartupEvent event) {
        if (!config.database().requireTls()) {
            LOG.warn("kilnmark.database.require-tls is off: Kilnmark's own database may be reached without TLS");
            return;
        }
        // What the pool itself will use, not the configuration keys: Quarkus accepts the same setting
        // under more than one name, and a credentials provider adds driver properties of its own.
        var factory = source.getConfiguration().connectionPoolConfiguration().connectionFactoryConfiguration();
        String problem = settingProblem(factory.jdbcUrl(), driverProperties(factory));
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
     * The driver properties the pool passes with its URL, as Agroal builds them: its JDBC properties,
     * then whatever each security provider makes of each credential (a credentials provider can add
     * {@code sslmode}), later ones winning. The user name and password are left out: they say nothing
     * about TLS, and this never holds a secret longer than it must.
     */
    static Properties driverProperties(AgroalConnectionFactoryConfiguration factory) {
        Properties props = new Properties();
        props.putAll(factory.jdbcProperties());
        for (Object credential : factory.credentials()) {
            for (AgroalSecurityProvider provider : factory.securityProviders()) {
                Properties added = provider.getSecurityProperties(credential);
                if (added != null) {
                    props.putAll(added);
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
