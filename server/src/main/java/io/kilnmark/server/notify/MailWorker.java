package io.kilnmark.server.notify;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Sends the queued emails, on processes with the worker role, from a thread of its own: never on
 * a request. Each pass puts back emails a stopped worker was sending, gives up those tried too
 * often, moves or drops emails not sent within the undelivered time (also while email is paused),
 * then sends the ones due on their own, one claim at a time (each address at most the hourly limit;
 * the rest wait for their daily digest), then the digests due. A failed send is tried again after
 * 1, 3, 9 and 27 minutes, then hourly, up to the maximum attempts; a mail server that refuses the
 * message outright (a 5xx answer) is not asked again. Every attempt is logged for admins. Several
 * workers share the queue ({@code FOR UPDATE SKIP LOCKED}); every change to a claim checks it is
 * still this worker's. Delivery is at least once: a worker stopped between the server accepting an
 * email and marking it sent sends it again after {@link #STALE}.
 */
@ApplicationScoped
public class MailWorker {

    private static final Logger LOG = Logger.getLogger(MailWorker.class);
    private static final int BATCH = 20;
    /** An email claimed longer ago than this was being sent by a worker that stopped. */
    static final Duration STALE = Duration.ofMinutes(10);

    private final NotificationStore store;
    private final MailService mail;
    private final KilnmarkConfig config;
    private final String workerId;
    private volatile boolean stopping;
    private Thread thread;

    public MailWorker(NotificationStore store, MailService mail, KilnmarkConfig config) {
        this.store = store;
        this.mail = mail;
        this.config = config;
        byte[] suffix = new byte[3];
        new SecureRandom().nextBytes(suffix);
        this.workerId = Optional.ofNullable(System.getenv("HOSTNAME")).filter(h -> !h.isBlank()).orElse("worker") + ":"
            + ProcessHandle.current().pid() + ":mail:" + HexFormat.of().formatHex(suffix);
    }

    void onStart(@Observes StartupEvent event) {
        if (config.roles().contains(Role.WORKER)) {
            thread = Thread.ofVirtual().name("kilnmark-mail-worker").start(this::loop);
        }
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
    }

    private void loop() {
        while (!stopping) {
            try {
                pass();
            } catch (RuntimeException e) {
                LOG.warnf("Could not send queued emails: %s. Trying again shortly.", e.getMessage());
            }
            long until = System.nanoTime() + config.notifications().pollInterval().toNanos();
            while (!stopping && System.nanoTime() < until) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** One pass over the queue; returns how many emails went. */
    int pass() {
        int requeued = store.requeueStale(STALE);
        if (requeued > 0) {
            LOG.infof("%d emails left by a stopped worker go back in the queue", requeued);
        }
        MailSettings settings = mail.settings();
        store.failExhausted(settings.maxAttempts());
        int expired = store.expireUndelivered(Duration.ofHours(settings.undeliveredHours()), settings.undeliveredAction(),
            ZoneId.systemDefault(), Instant.now());
        if (expired > 0) {
            LOG.infof("%d emails not sent within %d hours were %s", expired, settings.undeliveredHours(),
                "drop".equals(settings.undeliveredAction()) ? "dropped" : "moved to their recipients' digests");
        }
        if (!settings.ready()) {
            return 0; // not set up or paused: emails wait in the queue
        }
        SmtpMailer.Server server = mail.server();
        MailMessages messages = new MailMessages(settings, ZoneId.systemDefault(), store::exposure);
        int sent = 0;
        for (int i = 0; i < BATCH && !stopping; i++) {
            Optional<NotificationStore.Claimed> c = store.claimImmediate(workerId, settings.maxAttempts());
            if (c.isEmpty()) {
                break;
            }
            try {
                sent += single(c.get(), settings, server, messages) ? 1 : 0;
            } catch (RuntimeException e) {
                // One bad row must not stop the others: it is given up, with the reason.
                LOG.warnf(e, "Email delivery %d could not be sent", c.get().id());
                store.failed(workerId, List.of(c.get().id()), "Could not be written or sent: " + e.getMessage());
            }
        }
        for (long subscriber : store.digestsDue(BATCH)) {
            if (stopping) {
                break;
            }
            List<NotificationStore.Claimed> group = store.claimDigest(workerId, subscriber, settings.maxAttempts());
            if (group.isEmpty()) {
                continue;
            }
            try {
                sent += digest(subscriber, group, settings, server, messages) ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.warnf(e, "The digest for subscriber %d could not be sent", subscriber);
                store.failed(workerId, group.stream().map(NotificationStore.Claimed::id).toList(),
                    "Could not be written or sent: " + e.getMessage());
            }
        }
        return sent;
    }

    private boolean single(NotificationStore.Claimed c, MailSettings settings, SmtpMailer.Server server,
                           MailMessages messages) {
        NotificationEvent event = store.events(List.of(c.eventId())).get(c.eventId());
        Optional<NotificationStore.Subscriber> sub = store.subscriber(c.subscriberId());
        if (event == null || sub.isEmpty()) {
            store.skipped(workerId, List.of(c.id()), "The event or the recipient no longer exists");
            return false;
        }
        NotificationStore.Subscriber s = sub.get();
        String why = s.undeliverable(settings.requireConfirmation(), settings.allowedDomains());
        if (why != null) {
            store.skipped(workerId, List.of(c.id()), why);
            return false;
        }
        String to = s.subscription().email();
        Instant now = Instant.now();
        RateLimit limit = new RateLimit(settings.rateLimitPerHour(), RateLimit.HOUR);
        if (!limit.allows(store.sentSince(to, now.minus(limit.window())))) {
            Instant due = Digest.nextAt(now, s.subscription().digestHour(), ZoneId.systemDefault());
            store.toDigest(workerId, c.id(), due);
            store.log(to, s.id(), event.kind().id(), List.of(c.eventId()), null, "rate_limited", c.attempts(),
                "More than " + limit.limit() + " emails to this address in an hour: this one waits for the daily "
                    + "digest at " + String.format("%02d:00", s.subscription().digestHour()));
            return false;
        }
        MailMessages.Mail m = messages.single(event, s.recipient());
        return send(settings, server, to, m, s.id(), event.kind().id(), List.of(c.eventId()), List.of(c.id()),
            c.attempts());
    }

    private boolean digest(long subscriber, List<NotificationStore.Claimed> group, MailSettings settings,
                           SmtpMailer.Server server, MailMessages messages) {
        List<Long> ids = group.stream().map(NotificationStore.Claimed::id).toList();
        Optional<NotificationStore.Subscriber> sub = store.subscriber(subscriber);
        String why = sub.map(s -> s.undeliverable(settings.requireConfirmation(), settings.allowedDomains()))
            .orElse("The recipient no longer exists");
        if (why != null) {
            store.skipped(workerId, ids, why);
            return false;
        }
        Map<Long, NotificationEvent> events = store.events(group.stream().map(NotificationStore.Claimed::eventId).toList());
        List<NotificationEvent> list = new ArrayList<>();
        List<Long> eventIds = new ArrayList<>();
        events.forEach((id, e) -> {
            list.add(e);
            eventIds.add(id);
        });
        if (list.isEmpty()) {
            store.skipped(workerId, ids, "The events no longer exist");
            return false;
        }
        int attempt = group.stream().mapToInt(NotificationStore.Claimed::attempts).max().orElse(1);
        NotificationStore.Subscriber s = sub.get();
        MailMessages.Mail m = messages.digest(list, s.recipient(), Instant.now());
        return send(settings, server, s.subscription().email(), m, s.id(), "digest", eventIds, ids, attempt);
    }

    private boolean send(MailSettings settings, SmtpMailer.Server server, String to, MailMessages.Mail m,
                         long subscriber, String kind, List<Long> events, List<Long> deliveries, int attempt) {
        try {
            mail.mailer().send(server, to, m);
            store.sent(workerId, deliveries);
            store.log(to, subscriber, kind, events, m.subject(), "sent", attempt, null);
            return true;
        } catch (SmtpMailer.Failure f) {
            if (f.permanent() || attempt >= settings.maxAttempts()) {
                store.failed(workerId, deliveries, f.getMessage());
                store.log(to, subscriber, kind, events, m.subject(), "failed", attempt, f.getMessage());
                LOG.warnf("An email (%s, subscriber %d) was given up after %d attempts: %s", kind, subscriber, attempt,
                    f.getMessage());
            } else {
                store.retry(workerId, deliveries, Instant.now().plus(backoff(attempt)), f.getMessage());
                store.log(to, subscriber, kind, events, m.subject(), "retrying", attempt, f.getMessage());
            }
            return false;
        }
    }

    /** How long to wait after failed attempt {@code attempt} (1 for the first): 1, 3, 9, 27 minutes, then an hour. */
    static Duration backoff(int attempt) {
        long minutes = 1;
        for (int i = 1; i < attempt && minutes < 60; i++) {
            minutes *= 3;
        }
        return Duration.ofMinutes(Math.min(60, minutes));
    }
}
