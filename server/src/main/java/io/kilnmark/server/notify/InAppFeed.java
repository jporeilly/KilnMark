package io.kilnmark.server.notify;

import java.util.Set;
import java.util.function.Function;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import io.kilnmark.server.quality.Sights;

/**
 * Writes an event's in-app notification for one person's role, by the same rule as emails
 * ({@link Sights#recipient}): the title and detail hold no values from the data, and the restricted
 * detail is added only where the role may see values of that column. A recovery shows as
 * {@code info}, good news, whatever severity it is mailed at.
 */
public final class InAppFeed {

    private InAppFeed() {
    }

    /** The notification {@code role} sees for {@code e}; {@code marks} gives an asset's columns marked sensitive. */
    public static NotificationStore.InAppWriter.Item write(NotificationEvent e, AccessRole role,
                                                           Function<String, Set<String>> marks) {
        String detail = e.detail();
        if (e.restrictedDetail() != null) {
            Set<String> sensitive = e.subjectUrn() == null ? Set.of() : marks.apply(e.subjectUrn());
            Masking.Sight sight = Sights.recipient(role, sensitive);
            if (sight.values(e.field())) {
                detail = detail == null ? e.restrictedDetail() : detail + "\n\n" + e.restrictedDetail();
            }
        }
        Severity severity = e.kind().repeat() == NotificationKind.Repeat.CLOSES ? Severity.INFO : e.severity();
        return new NotificationStore.InAppWriter.Item(severity, e.title(), detail, e.link());
    }
}
