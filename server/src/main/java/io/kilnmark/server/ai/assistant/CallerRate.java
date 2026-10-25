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
        if (seen.size() > 10_000) {
            seen.values().removeIf(Deque::isEmpty);
        }
        return true;
    }
}
