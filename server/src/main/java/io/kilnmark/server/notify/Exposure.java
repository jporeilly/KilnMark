package io.kilnmark.server.notify;

import java.util.Set;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.quality.Masking;
import io.kilnmark.server.quality.Sights;

/**
 * What an event touches that is marked sensitive, looked up once per event: the columns marked on
 * its asset, and whether any asset of its source has a mark. Decides, with the recipient's role,
 * whether its restricted detail may be shown ({@link #restricted}).
 */
public record Exposure(Set<String> marks, boolean sourceMarked) {

    /** Nothing marked. */
    public static final Exposure NONE = new Exposure(Set.of(), false);

    public Exposure {
        marks = io.kilnmark.server.quality.QualityStore.marks(marks);
    }

    /**
     * Whether {@code role} may see {@code e}'s restricted detail: only where the role may see values
     * of its column by the quality views' rule ({@link Sights#recipient}, never unmasked, so never a
     * sensitive column's); and when it is about a whole asset or source that has marks (a scan's or
     * connection's error can quote any column), only for designers and admins, who may see such values.
     */
    public boolean restricted(NotificationEvent e, AccessRole role) {
        if (e.restrictedDetail() == null) {
            return false;
        }
        Masking.Sight sight = Sights.recipient(role, marks);
        if (!sight.values(e.field())) {
            return false;
        }
        boolean whole = e.field() == null && (e.subjectUrn() == null ? sourceMarked : !marks.isEmpty());
        return !whole || role.atLeast(AccessRole.DESIGNER);
    }
}
