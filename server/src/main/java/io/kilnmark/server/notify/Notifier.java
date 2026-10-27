package io.kilnmark.server.notify;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.scan.ScanFinished;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Records each {@link NotificationEvent} off the caller's thread: firing one only puts it in a
 * queue, so a scan, a rule run or a request never waits for it and never fails because of it. One
 * thread per process takes events in the order they were fired (so a rule's failure is always
 * decided before its recovery) and records each in one short transaction ({@link
 * NotificationStore#record}): dedupe, in-app notifications, queued emails. Nothing is sent here:
 * {@link MailWorker} sends, on the worker role. Events still queued when the process stops are
 * recorded for up to five seconds, then dropped with a warning; so is an event that finds the queue
 * full. Also listens for finished scans.
 */
@ApplicationScoped
public class Notifier {

    private static final Logger LOG = Logger.getLogger(Notifier.class);
    static final int QUEUE = 10_000;

    private final NotificationStore store;
    private final MailService mail;
    private final NotificationPublisher publisher;
    private final GraphStore graph;
    private final KilnmarkConfig config;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(QUEUE);
    private volatile boolean stopping;
    private Thread thread;

    public Notifier(NotificationStore store, MailService mail, NotificationPublisher publisher, GraphStore graph,
                    KilnmarkConfig config) {
        this.store = store;
        this.mail = mail;
        this.publisher = publisher;
        this.graph = graph;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        thread = Thread.ofVirtual().name("kilnmark-notifier").start(this::drain);
    }

    void onStop(@Observes ShutdownEvent event) {
        stopping = true;
        Thread t = thread;
        if (t != null) {
            try {
                t.join(Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!queue.isEmpty()) {
            LOG.warnf("%d notifications were not recorded before the server stopped", queue.size());
        }
    }

    void onEvent(@Observes NotificationEvent e) {
        enqueue(e);
    }

    void onCleared(@Observes NotificationCleared c) {
        enqueue(c);
    }

    private void enqueue(Object o) {
        if (!queue.offer(o)) {
            LOG.warnf("Too many notifications waiting (%d); one was dropped", QUEUE);
        }
    }

    private void drain() {
        while (!stopping || !queue.isEmpty()) {
            Object o;
            try {
                o = queue.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (o != null) {
                handle(o);
            }
        }
    }

    /** Records one event or clears one problem; never throws. */
    void handle(Object o) {
        try {
            if (o instanceof NotificationEvent e) {
                record(e);
            } else if (o instanceof NotificationCleared c) {
                store.clear(c.dedupeKey());
            }
        } catch (RuntimeException ex) {
            // The key only: an event's restricted detail must not reach the log.
            LOG.warnf(ex, "Could not record notification %s", o instanceof NotificationEvent e ? e.dedupeKey()
                : o instanceof NotificationCleared c ? c.dedupeKey() : "?");
        }
    }

    private void record(NotificationEvent e) {
        MailSettings settings = mail.settings();
        NotificationStore.Recorded r = store.record(e, new NotificationStore.Options(settings.ready(),
            settings.requireConfirmation(), settings.allowedDomains(), ZoneId.systemDefault(),
            config.notifications().inAppRetention()), Instant.now());
        if (r.sent()) {
            LOG.debugf("Notification %s (%s): %d in the app, %d emails queued%s", e.dedupeKey(), e.kind().id(),
                r.inApp(), r.deliveries(), settings.ready() ? "" : " (email is not set up or is paused)");
        }
    }

    /** Whether nothing is waiting to be recorded (for tests). */
    boolean idle() {
        return queue.isEmpty();
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
