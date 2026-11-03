package io.kilnmark.server;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Stops the server at start unless its own database is reached over TLS (ADR 0032): the connection
 * settings must demand TLS ({@code sslmode} {@code require}, {@code verify-ca} or {@code verify-full}),
 * and the session must be encrypted. Both, as {@code sslmode=prefer} (the driver's default) falls
 * back to plain text without a word, and one encrypted session says nothing about the next.
 * {@code kilnmark.database.require-tls=false} turns the check off, for a developer's own database.
 */
@ApplicationScoped
public class DatabaseTls {

    private static final Logger LOG = Logger.getLogger(DatabaseTls.class);

    /** The sslmode values under which the driver never sends anything in plain text. */
    static final Set<String> DEMANDING = Set.of("require", "verify-ca", "verify-full");

    private final KilnmarkConfig config;
    private final AgroalDataSource source;

    public DatabaseTls(KilnmarkConfig config, AgroalDataSource source) {
        this.config = config;
        this.source = source;
    }

    void onStart(@Observes StartupEvent event) {
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
     * Why the database settings do not demand TLS, or null when they do: the sslmode in the URL, or
     * given as a driver property (which wins, as the driver reads it last).
     */
    static String settingProblem(String url, String property) {
        String mode = property != null && !property.isBlank() ? property : urlParameter(url, "sslmode");
        if (mode != null && DEMANDING.contains(mode.trim().toLowerCase(Locale.ROOT))) {
            return null;
        }
        return "Kilnmark's own database connection does not demand TLS: its sslmode is "
            + (mode == null ? "not set (the driver then falls back to plain text)" : "'" + mode.trim() + "'")
            + ". Add ?sslmode=require to QUARKUS_DATASOURCE_JDBC_URL, or verify-full with the database's CA "
            + "(sslrootcert).";
    }

    /** A query parameter of a JDBC URL, by name ignoring case; the last one wins, as the driver reads it. */
    static String urlParameter(String url, String name) {
        int q = url == null ? -1 : url.indexOf('?');
        if (q < 0) {
            return null;
        }
        String found = null;
        for (String pair : url.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            if (key.equalsIgnoreCase(name)) {
                found = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return found;
    }

    /** Whether this session to PostgreSQL is encrypted, as the server itself says. */
    static boolean encrypted(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT ssl FROM pg_catalog.pg_stat_ssl WHERE pid = pg_catalog.pg_backend_pid()")) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
