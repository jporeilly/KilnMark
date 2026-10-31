package io.kilnmark.server;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Switches an admin sets for the whole server (table server_setting, V23). A setting that was never
 * set has the default its caller gives.
 */
@ApplicationScoped
public class ServerSettings {

    private static final Logger LOG = Logger.getLogger(ServerSettings.class);

    /** Whether Add samples, the samples API and the sample data are offered. On by default. */
    public static final String SAMPLES_OFFERED = "samples.offered";

    private final Db db;

    public ServerSettings(Db db) {
        this.db = db;
    }

    public Optional<String> get(String key) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kilnmark.server_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public boolean flag(String key, boolean otherwise) {
        return get(key).map(Boolean::parseBoolean).orElse(otherwise);
    }

    public void put(String key, String value, String by) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.server_setting (key, value, updated_by) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by,
                    updated_at = now()""")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, by);
                ps.executeUpdate();
            }
            return null;
        });
        LOG.infof("Server setting %s set to %s by %s", key, value, by);
    }

    public boolean samplesOffered() {
        return flag(SAMPLES_OFFERED, true);
    }
}
