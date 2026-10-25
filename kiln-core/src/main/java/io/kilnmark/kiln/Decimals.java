package io.kilnmark.kiln;

import java.math.BigDecimal;

/** Decimals as text, for settings and messages. */
public final class Decimals {

    /** Longest decimal written out in full; longer ones use scientific form. */
    static final int MAX_PLAIN = 100;

    private Decimals() {
    }

    /**
     * {@code d} as text: written out in full when that is short ({@code 2.50}, {@code 1000}),
     * otherwise in scientific form ({@code 1E-999999999}), so a number can never become a string of
     * a billion zeros.
     */
    public static String text(BigDecimal d) {
        long plainLength = d.scale() >= 0 ? Math.max(d.precision(), d.scale()) : d.precision() - (long) d.scale();
        return plainLength <= MAX_PLAIN ? d.toPlainString() : d.toString();
    }
}
