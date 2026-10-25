package io.kilnmark.server.ai.assistant;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * At most {@code perMinute} requests per caller (API token) in any minute, in this process: the
 * same rule as reading documents on demand (DocumentTexts). Not shared between processes.
 */
public final class CallerRate {

    private final int perMinute;
    private final Map<String, Deque<Instant>> seen = new HashMap<>();
    /** When callers silent for a minute were last forgotten: at most once a minute, not on every call. */
    private Instant swept = Instant.EPOCH;

    public CallerRate(int perMinute) {
        this.perMinute = perMinute;
    }

    public int perMinute() {
        return perMinute;
    }

    /** Counts a request; false (and not counted) when the caller already made {@code perMinute} in the last minute. */
    public synchronized boolean allow(String caller, Instant now) {
        Deque<Instant> times = seen.computeIfAbsent(caller, k -> new ArrayDeque<>());
        while (!times.isEmpty() && !times.peekFirst().isAfter(now.minusSeconds(60))) {
            times.pollFirst();
        }
        if (times.size() >= perMinute) {
            return false;
        }
        times.addLast(now);
        if (now.isAfter(swept.plusSeconds(60))) {
            swept = now;
            Instant cutoff = now.minusSeconds(60);
            seen.values().removeIf(d -> d.isEmpty() || !d.peekLast().isAfter(cutoff));
        }
        return true;
    }
}
