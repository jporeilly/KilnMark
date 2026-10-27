package io.kilnmark.server.notify;

/** A problem ended without anything to tell (a scan that works again): its next start is told. */
public record NotificationCleared(String dedupeKey) {
}
