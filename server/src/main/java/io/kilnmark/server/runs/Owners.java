package io.kilnmark.server.runs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Owners of a pipeline or a flow (ADR 0053, decision 10): who answers for it, told when its runs fail
 * after their last try, recover or take long. Each entry is a person's sign-in name, as Kilnmark
 * records who did something (an API token's name, or {@code sso:...} for a person signed in through
 * the identity provider), or a team address an admin has added under Notifications. Owning gives no
 * rights and is never the identity a run acts as.
 */
public final class Owners {

    /** The most owners one pipeline or flow may have. */
    public static final int MAX = 10;
    /** The longest entry. */
    public static final int MAX_LENGTH = 200;
    private static final Pattern ADDRESS = Pattern.compile("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");

    private Owners() {
    }

    /** Whether an entry is a team address (an email address), rather than a person's sign-in name. */
    public static boolean team(String entry) {
        return entry != null && !entry.startsWith("sso:") && ADDRESS.matcher(entry).matches();
    }

    /**
     * Checks owners as sent: 0 to {@value #MAX} entries, each a sign-in name (no control characters, at
     * most {@value #MAX_LENGTH} characters) or one of {@code teamAddresses}, the team addresses an admin
     * added; addresses lower-cased, repeats left out, in the order sent. A team address nobody added is
     * refused: owners could otherwise send a pipeline's notices anywhere.
     *
     * @throws IllegalArgumentException saying what is wrong, starting {@code owners:}
     */
    public static List<String> check(List<String> sent, Set<String> teamAddresses) {
        if (sent == null) {
            throw new IllegalArgumentException("owners: send a list of sign-in names and team addresses, such as "
                + "{\"owners\": [\"alice\", \"data-team@example.com\"]}; an empty list for none");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : sent) {
            String e = raw == null ? "" : raw.strip();
            if (e.isEmpty() || e.length() > MAX_LENGTH || e.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("owners: each owner is a sign-in name or a team address of 1 to "
                    + MAX_LENGTH + " characters, without control characters");
            }
            if (team(e)) {
                String address = e.toLowerCase(Locale.ROOT);
                if (teamAddresses.stream().noneMatch(t -> t.equalsIgnoreCase(address))) {
                    throw new IllegalArgumentException("owners: a team address must be one an admin has added under "
                        + "Settings, Email, Team addresses");
                }
                out.add(address);
            } else {
                out.add(e);
            }
        }
        if (out.size() > MAX) {
            throw new IllegalArgumentException("owners: at most " + MAX + " owners");
        }
        return List.copyOf(out);
    }

    /**
     * The owners a new pipeline or flow starts with: whoever first saved it, when that is a person or a
     * token's name; none for the server itself or someone not signed in.
     */
    public static List<String> defaultFor(String by) {
        if (by == null || by.isBlank() || by.equals("anonymous") || by.startsWith("server") || by.length() > MAX_LENGTH
            || by.chars().anyMatch(Character::isISOControl)) {
            return List.of();
        }
        return List.of(by);
    }

    /**
     * One owner as a page shows it: {@code entry} as set (null when the reader may not see a team
     * address: only designers and admins do), whether it is a person or a team, and a label.
     */
    public record View(String entry, String kind, String label) {
    }

    /**
     * Owners as the pipeline and flow pages show them. {@code none} says no one answers for it, so admins
     * are told in their place.
     */
    public record Shown(List<View> owners, boolean none, String note) {
    }

    /** Owners for a reader: team addresses only for designers and admins ({@code addresses}). */
    public static Shown shown(List<String> owners, boolean addresses) {
        List<View> out = new ArrayList<>();
        for (String o : owners) {
            out.add(team(o) ? new View(addresses ? o : null, "team", addresses ? o : "A team address")
                : new View(o, "person", o));
        }
        return new Shown(out, out.isEmpty(), out.isEmpty() ? "No owner: admins are told when its runs fail, recover "
            + "or take long" : null);
    }
}
