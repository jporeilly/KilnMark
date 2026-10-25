package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** At most so many emails of their own per address in a sliding hour. */
class RateLimitTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Test
    void allowsUpToTheLimitInTheWindow() {
        RateLimit limit = new RateLimit(3, RateLimit.HOUR);
        List<Instant> sent = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            assertTrue(limit.allows(sent, NOW), "email " + (i + 1));
            sent.add(NOW.minusSeconds(60L * i));
        }
        assertFalse(limit.allows(sent, NOW), "the fourth in the hour waits for the digest");
    }

    @Test
    void theWindowSlides() {
        RateLimit limit = new RateLimit(2, RateLimit.HOUR);
        List<Instant> sent = List.of(NOW.minus(Duration.ofMinutes(61)), NOW.minus(Duration.ofMinutes(30)));
        assertTrue(limit.allows(sent, NOW), "one of the two is older than an hour");
        assertFalse(limit.allows(List.of(NOW.minus(Duration.ofMinutes(59)), NOW.minusSeconds(1)), NOW));
        assertTrue(limit.allows(List.of(NOW.plusSeconds(5), NOW.plusSeconds(6)), NOW), "the future does not count");
    }

    @Test
    void countsTakenByTheDatabaseAreComparedTheSameWay() {
        RateLimit limit = new RateLimit(20, RateLimit.HOUR);
        assertTrue(limit.allows(19));
        assertFalse(limit.allows(20));
        assertEquals(RateLimit.HOUR, limit.window());
    }

    @Test
    void aLimitBelowOneIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimit(0, RateLimit.HOUR));
    }

    @Test
    void retriesBackOff() {
        assertEquals(Duration.ofMinutes(1), MailWorker.backoff(1));
        assertEquals(Duration.ofMinutes(3), MailWorker.backoff(2));
        assertEquals(Duration.ofMinutes(9), MailWorker.backoff(3));
        assertEquals(Duration.ofMinutes(27), MailWorker.backoff(4));
        assertEquals(Duration.ofMinutes(60), MailWorker.backoff(5));
        assertEquals(Duration.ofMinutes(60), MailWorker.backoff(40));
    }
}
