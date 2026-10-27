package io.kilnmark.server.notify;

import java.util.Locale;

/**
 * How much an event matters; subscribers choose the least they want by email. {@code INFO} is good
 * news, such as a recovery, as the in-app feed shows it.
 */
public enum Severity {
    INFO, LOW, MEDIUM, HIGH;

    /** {@code info}, {@code low}, {@code medium} or {@code high}: as stored and as the API shows it. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code Low}, {@code Medium} or {@code High}: as email subjects show it. */
    public String label() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** Whether this is at least {@code least}. */
    public boolean atLeast(Severity least) {
        return compareTo(least) >= 0;
    }

    /** The higher of two; null counts as nothing. */
    public static Severity max(Severity a, Severity b) {
        if (a == null) {
            return b;
        }
        return b == null || a.compareTo(b) >= 0 ? a : b;
    }

    /** Reads {@code info}, {@code low}, {@code medium} or {@code high}, in any case. */
    public static Severity parse(String text) {
        if (text != null) {
            for (Severity s : values()) {
                if (s.id().equalsIgnoreCase(text.trim())) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("severity: use info, low, medium or high");
    }
}
