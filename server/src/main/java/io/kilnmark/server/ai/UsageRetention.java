package io.kilnmark.server.ai;

import java.sql.PreparedStatement;
import java.time.Duration;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Deletes usage records older than {@code kilnmark.ai.usage-retention} (default 90 days), shortly
 * after start and then every six hours, on processes with the scheduler role. Running it on two
 * processes at once is harmless.
 */
@ApplicationScoped
public class UsageRetention {

    private static final Logger LOG = Logger.getLogger(UsageRetention.class);
    private static final Duration EVERY = Duration.ofHours(6);

    private final Db db;
    private final KilnmarkConfig config;
    private volatile Thread thread;

    public UsageRetention(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.roles().contains(Role.SCHEDULER)) {
            return;
        }
        thread = Thread.ofVirtual().name("kilnmark-ai-usage-retention").start(() -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                while (!Thread.currentThread().isInterrupted()) {
                    purge();
                    Thread.sleep(EVERY);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** Deletes records older than the retention period; returns how many. */
    public int purge() {
        try {
            int n = db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM kilnmark.ai_usage WHERE at < now() - make_interval(secs => ?)")) {
                    ps.setLong(1, config.ai().usageRetention().toSeconds());
                    return ps.executeUpdate();
                }
            });
            if (n > 0) {
                LOG.infof("Deleted %d AI usage records older than %s", n, config.ai().usageRetention());
            }
            return n;
        } catch (RuntimeException e) {
            LOG.warnf("Could not delete old AI usage records: %s", e.getMessage());
            return 0;
        }
    }
}
