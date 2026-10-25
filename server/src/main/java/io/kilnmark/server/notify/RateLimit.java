package io.kilnmark.server.notify;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;

/**
 * At most {@code limit} emails of their own to one address in any {@code window} (an hour by
 * default). An email over the limit is not dropped: it waits for the recipient's next daily digest,
 * so a burst of alerts becomes one summary. Digests themselves are never held back.
 */
public record RateLimit(int limit, Duration window) {

    public static final Duration HOUR = Duration.ofHours(1);

    public RateLimit {
        if (limit < 1) {
            throw new IllegalArgumentException("kilnmark.notifications.rate-limit-per-hour must be at least 1");
        }
    }

    /** Whether one more email may go now, given when the recent ones to the same address went. */
    public boolean allows(Collection<Instant> sent, Instant now) {
        Instant from = now.minus(window);
        long recent = sent.stream().filter(t -> t.isAfter(from) && !t.isAfter(now)).count();
        return recent < limit;
    }

    /** The same, from a count already taken over the window. */
    public boolean allows(long sentInWindow) {
        return sentInWindow < limit;
    }
}
