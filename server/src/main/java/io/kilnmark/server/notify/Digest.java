package io.kilnmark.server.notify;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The daily digest: when it is due, and how its events are grouped. Events are grouped by kind (in
 * the order of {@link NotificationKind}), most severe first within a kind, then newest first; a
 * problem that started and recovered within the day shows both.
 */
public final class Digest {

    private Digest() {
    }

    /** The next time the clock in {@code zone} reads {@code hour}:00 after {@code now}. */
    public static Instant nextAt(Instant now, int hour, ZoneId zone) {
        ZonedDateTime local = now.atZone(zone);
        LocalDate day = local.toLocalDate();
        ZonedDateTime at = day.atTime(hour, 0).atZone(zone);
        if (!at.toInstant().isAfter(now)) {
            at = day.plusDays(1).atTime(hour, 0).atZone(zone);
        }
        return at.toInstant();
    }

    /** One kind's events in a digest. */
    public record Group(NotificationKind kind, List<NotificationEvent> events) {
    }

    /** Groups a digest's events by kind, most severe and then newest first. */
    public static List<Group> group(List<NotificationEvent> events) {
        Map<NotificationKind, List<NotificationEvent>> byKind = new EnumMap<>(NotificationKind.class);
        for (NotificationEvent e : events) {
            byKind.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e);
        }
        Comparator<NotificationEvent> order = Comparator.comparing(NotificationEvent::severity).reversed()
            .thenComparing(NotificationEvent::occurredAt, Comparator.reverseOrder());
        List<Group> out = new ArrayList<>();
        byKind.forEach((k, list) -> {
            list.sort(order);
            out.add(new Group(k, List.copyOf(list)));
        });
        return out;
    }

    /** The highest severity among the events, or null when there are none. */
    public static Severity highest(List<NotificationEvent> events) {
        Severity s = null;
        for (NotificationEvent e : events) {
            s = Severity.max(s, e.severity());
        }
        return s;
    }
}
