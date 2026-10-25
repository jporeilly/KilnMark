package io.kilnmark.server.notify;

import java.time.Duration;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Deletes in-app notifications older than {@code kilnmark.notifications.in-app-retention} (90
 * days), and send log entries, closed problems and events with nothing left to send or show older
 * than {@code kilnmark.notifications.log-retention} (30 days): shortly after start, then every six
 * hours, on processes with the scheduler role. Running it on two processes at once is harmless.
 */
@ApplicationScoped
public class NotificationRetention {

    private static final Logger LOG = Logger.getLogger(NotificationRetention.class);
    private static final Duration EVERY = Duration.ofHours(6);

    private final NotificationStore store;
    private final KilnmarkConfig config;
    private volatile Thread thread;

    public NotificationRetention(NotificationStore store, KilnmarkConfig config) {
        this.store = store;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.roles().contains(Role.SCHEDULER)) {
            return;
        }
        thread = Thread.ofVirtual().name("kilnmark-notification-retention").start(() -> {
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

    /** Deletes what is past its time; returns how many rows. */
    public int purge() {
        try {
            int n = store.purge(config.notifications().logRetention(), config.notifications().inAppRetention());
            if (n > 0) {
                LOG.infof("Deleted %d old notifications, send log entries and events", n);
            }
            return n;
        } catch (RuntimeException e) {
            LOG.warnf("Could not delete old notifications: %s", e.getMessage());
            return 0;
        }
    }
}
