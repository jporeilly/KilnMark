package io.kilnmark.server;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.Properties;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import org.postgresql.jdbc.SslMode;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Stops the server at start unless its own database is reached over TLS (ADR 0032): the connection
 * settings must demand TLS ({@code sslmode} {@code require}, {@code verify-ca} or {@code verify-full}),
 * and the session must be encrypted. Both, as {@code sslmode=prefer} (the driver's default) falls
 * back to plain text without a word, and one encrypted session says nothing about the next. The
 * settings are read by the PostgreSQL driver's own parser, so the check sees the mode the driver will
 * use, never another reading of the URL. It runs before Kilnmark's other start-up work; Quarkus runs
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
        var cfg = ConfigProvider.getConfig();
        String url = cfg.getOptionalValue("quarkus.datasource.jdbc.url", String.class).orElse("");
        Optional<String> property = cfg.getOptionalValue(
            "quarkus.datasource.jdbc.additional-jdbc-properties.sslmode", String.class);
        String problem = settingProblem(url, property.orElse(null));
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
     * Why the database settings do not demand TLS, or null when they do: the mode the PostgreSQL driver
     * will use, worked out by the driver itself from the URL and the driver property {@code sslmode}
     * (the URL wins, keys are matched exactly, and {@code ssl=true} alone means verify-full, all as the
     * driver reads them).
     */
    static String settingProblem(String url, String property) {
        Properties given = new Properties();
        if (property != null) {
            given.setProperty("sslmode", property);
        }
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
