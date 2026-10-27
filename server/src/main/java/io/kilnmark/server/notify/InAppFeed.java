package io.kilnmark.server.notify;

import io.kilnmark.server.auth.AccessRole;

/**
 * Writes an event's in-app notification for one role, by the same rule as emails
 * ({@link Exposure#restricted}): the title and detail hold no values from the data, and the
 * restricted detail is added only where the role may see it. A recovery shows as {@code info},
 * good news, whatever severity it is mailed at.
 */
public final class InAppFeed {

    private InAppFeed() {
    }

    /** The notification {@code role} sees for {@code e}. */
    public static NotificationStore.InAppWriter.Item write(NotificationEvent e, AccessRole role, Exposure exposure) {
        String detail = e.detail();
        if (exposure.restricted(e, role)) {
            detail = detail == null ? e.restrictedDetail() : detail + "\n\n" + e.restrictedDetail();
        }
        Severity severity = e.kind().repeat() == NotificationKind.Repeat.CLOSES ? Severity.INFO : e.severity();
        return new NotificationStore.InAppWriter.Item(severity, e.title(), detail, e.link());
    }
}
