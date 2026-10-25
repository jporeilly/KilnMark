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
import io.kilnmark.server.quality.QualityStore;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Sends the queued emails, on processes with the worker role, from a thread of its own: never on
 * a request. Each pass puts back emails a stopped worker was sending, sends the ones due on their
 * own (each recipient at most {@code rate-limit-per-hour} an hour; the rest wait for their daily
 * digest), then the digests due. A failed send is tried again after 1, 3, 9 and 27 minutes, then
 * hourly, up to {@code max-attempts}; a mail server that refuses the message outright (a 5xx
 * answer) is not asked again. Every attempt is logged for admins. Several workers share the queue
 * safely ({@code FOR UPDATE SKIP LOCKED}).
 */
@ApplicationScoped
public class MailWorker {

    private static final Logger LOG = Logger.getLogger(MailWorker.class);
    private static final int BATCH = 20;
    /** An email claimed longer ago than this was being sent by a worker that stopped. */
    private static final Duration STALE = Duration.ofMinutes(10);

    private final NotificationStore store;
    private final MailService mail;
    private final QualityStore quality;
    private final KilnmarkConfig config;
    private final String workerId;
    private volatile boolean stopping;
    private Thread thread;

    public MailWorker(NotificationStore store, MailService mail, QualityStore quality, KilnmarkConfig config) {
        this.store = store;
        this.mail = mail;
        this.quality = quality;
        this.config = config;
        byte[] suffix = new byte[3];
        new SecureRandom().nextBytes(suffix);
        this.workerId = Optional.ofNullable(System.getenv("HOSTNAME")).filter(h -> !h.isBlank()).orElse("worker") + ":"
            + ProcessHandle.current().pid() + ":mail:" + HexFormat.of().formatHex(suffix);
    }

    void onStart(@Observes StartupEvent event) {
        if (config.roles().contains(Role.WORKER)) {
            new RateLimit(config.notifications().rateLimitPerHour(), RateLimit.HOUR); // fails start on a bad setting
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
        if (!settings.ready()) {
            return 0; // not set up or paused: emails wait in the queue
        }
        SmtpMailer.Server server = mail.server();
        MailMessages messages = new MailMessages(settings, ZoneId.systemDefault(), quality::sensitive);
        int sent = 0;
        List<NotificationStore.Claimed> claimed = store.claimImmediate(workerId, BATCH);
        Map<Long, NotificationEvent> events = store.events(claimed.stream().map(NotificationStore.Claimed::eventId).toList());
        for (NotificationStore.Claimed c : claimed) {
            if (stopping) {
                store.release(List.of(c.id()));
                continue;
            }
            sent += single(c, events.get(c.eventId()), server, messages) ? 1 : 0;
        }
        for (long subscriber : store.digestsDue(BATCH)) {
            if (stopping) {
                break;
            }
            List<NotificationStore.Claimed> group = store.claimDigest(workerId, subscriber);
            if (!group.isEmpty()) {
                sent += digest(subscriber, group, server, messages) ? 1 : 0;
            }
        }
        return sent;
    }

    private boolean single(NotificationStore.Claimed c, NotificationEvent event, SmtpMailer.Server server,
                           MailMessages messages) {
        Optional<NotificationStore.Subscriber> sub = store.subscriber(c.subscriberId());
        if (event == null || sub.isEmpty()) {
            store.skipped(List.of(c.id()), "The event or the recipient no longer exists");
            return false;
        }
        String why = unreachable(sub.get());
        if (why != null) {
            store.skipped(List.of(c.id()), why);
            return false;
        }
        NotificationStore.Subscriber s = sub.get();
        String to = s.subscription().email();
        Instant now = Instant.now();
        RateLimit limit = new RateLimit(config.notifications().rateLimitPerHour(), RateLimit.HOUR);
        if (!limit.allows(store.sentSince(to, now.minus(limit.window())))) {
            Instant due = Digest.nextAt(now, s.subscription().digestHour(), ZoneId.systemDefault());
            store.toDigest(c.id(), due);
            store.log(to, s.id(), event.kind().id(), List.of(c.eventId()), null, "rate_limited", c.attempts(),
                "More than " + limit.limit() + " emails to this address in an hour: this one waits for the daily "
                    + "digest at " + String.format("%02d:00", s.subscription().digestHour()));
            return false;
        }
        MailMessages.Mail m = messages.single(event, s.recipient());
        return send(server, to, m, s.id(), event.kind().id(), List.of(c.eventId()), List.of(c.id()), c.attempts());
    }

    private boolean digest(long subscriber, List<NotificationStore.Claimed> group, SmtpMailer.Server server,
                           MailMessages messages) {
        List<Long> ids = group.stream().map(NotificationStore.Claimed::id).toList();
        Optional<NotificationStore.Subscriber> sub = store.subscriber(subscriber);
        String why = sub.map(MailWorker::unreachable).orElse("The recipient no longer exists");
        if (why != null) {
            store.skipped(ids, why);
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
            store.skipped(ids, "The events no longer exist");
            return false;
        }
        int attempt = group.stream().mapToInt(NotificationStore.Claimed::attempts).max().orElse(1);
        NotificationStore.Subscriber s = sub.get();
        MailMessages.Mail m = messages.digest(list, s.recipient(), Instant.now());
        return send(server, s.subscription().email(), m, s.id(), "digest", eventIds, ids, attempt);
    }

    /** Why email cannot go to a subscriber now, or null when it can. */
    static String unreachable(NotificationStore.Subscriber s) {
        if (!s.reachable()) {
            return "The recipient's API token was revoked or has expired";
        }
        if (!s.subscription().enabled() || s.subscription().email() == null) {
            return "The recipient turned email off";
        }
        return null;
    }

    private boolean send(SmtpMailer.Server server, String to, MailMessages.Mail m, long subscriber, String kind,
                         List<Long> events, List<Long> deliveries, int attempt) {
        try {
            mail.mailer().send(server, to, m);
            store.sent(deliveries);
            store.log(to, subscriber, kind, events, m.subject(), "sent", attempt, null);
            return true;
        } catch (SmtpMailer.Failure f) {
            int max = Math.max(1, config.notifications().maxAttempts());
            if (f.permanent() || attempt >= max) {
                store.failed(deliveries, f.getMessage());
                store.log(to, subscriber, kind, events, m.subject(), "failed", attempt, f.getMessage());
                LOG.warnf("An email (%s, subscriber %d) was given up after %d attempts: %s", kind, subscriber, attempt,
                    f.getMessage());
            } else {
                store.retry(deliveries, Instant.now().plus(backoff(attempt)), f.getMessage());
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
