package io.kilnmark.kiln.plugins.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColumnStatsTest {

    @Test
    void numbersKeepSixPlacesAtAnyMagnitude() {
        assertEquals("1.123457", ColumnStats.number(1.123456789012));
        assertEquals("0.25", ColumnStats.number(0.25));
        assertEquals("10", ColumnStats.number(10.0));
        assertEquals("-3", ColumnStats.number(-3.0));
        assertEquals("9300000000000.5", ColumnStats.number(9.3e12 + 0.5), "above Long.MAX_VALUE / 1e6");
        assertEquals("123456789012345.67", ColumnStats.number(123456789012345.67));
        assertEquals("100000000000000000000", ColumnStats.number(1e20));
        assertEquals("-12345678901234.5", ColumnStats.number(-12345678901234.5));
        assertEquals("0", ColumnStats.number(1e-9));
        assertEquals("NaN", ColumnStats.number(Double.NaN));
    }
}
