package io.kilnmark.server.notify;

import java.time.Instant;
import java.time.ZoneId;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.quality.QualityStore;
import io.kilnmark.server.scan.ScanFinished;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records each {@link NotificationEvent} as it is fired: decides with {@link Dedupe} whether it is
 * worth telling, then writes everyone's in-app notifications and queues the emails, all in one
 * short transaction. Nothing is sent here: {@link MailWorker} sends, on the worker role. Also
 * listens for finished scans.
 */
@ApplicationScoped
public class Notifier {

    private static final Logger LOG = Logger.getLogger(Notifier.class);

    private final NotificationStore store;
    private final MailService mail;
    private final QualityStore quality;
    private final NotificationPublisher publisher;
    private final GraphStore graph;

    public Notifier(NotificationStore store, MailService mail, QualityStore quality, NotificationPublisher publisher,
                    GraphStore graph) {
        this.graph = graph;
        this.store = store;
        this.mail = mail;
        this.quality = quality;
        this.publisher = publisher;
    }

    void onEvent(@Observes NotificationEvent e) {
        try {
            boolean deliver = mail.settings().ready();
            NotificationStore.Recorded r = store.record(e, deliver, ZoneId.systemDefault(), Instant.now(),
                (event, role) -> InAppFeed.write(event, role, quality::sensitive));
            if (r.sent()) {
                LOG.debugf("Notification %s (%s): %d in the app, %d emails queued%s", e.dedupeKey(), e.kind().id(),
                    r.inApp(), r.deliveries(), deliver ? "" : " (email is not set up or is paused)");
            }
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not record the %s notification %s", e.kind().id(), e.dedupeKey());
        }
    }

    void onCleared(@Observes NotificationCleared c) {
        try {
            store.clear(c.dedupeKey());
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not clear the notification state %s", c.dedupeKey());
        }
    }

    /** A scan's final status (fired on the worker, which still holds the source's lock): must not throw. */
    void onScan(@Observes ScanFinished s) {
        try {
            String detail = null;
            if ("failed".equals(s.status())) {
                detail = graph.run(s.runId()).map(Records.Run::detail).orElse(null);
            }
            publisher.scanFinished(s.sourceName(), s.runId(), s.status(), detail);
        } catch (RuntimeException ex) {
            LOG.warnf(ex, "Could not tell of scan %d of %s", s.runId(), s.sourceName());
        }
    }
}
