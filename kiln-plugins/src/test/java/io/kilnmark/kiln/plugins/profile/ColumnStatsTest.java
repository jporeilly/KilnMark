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

    @Test
    void histogramsHaveEqualWidthBinsOrOneBinPerSmallWholeNumber() {
        ColumnStats wide = new ColumnStats("n", ColumnStats.Shape.INTEGER);
        for (long v = 1; v <= 100; v++) {
            wide.addLong(v);
        }
        String h = wide.histogram();
        assertEquals(10, h.split("\\{").length - 1, h);
        assertEquals(true, h.startsWith("[{\"from\":\"1\",\"to\":\"10.9\",\"count\":10}"), h);
        assertEquals(true, h.endsWith("\"to\":\"100\",\"count\":10}]"), h);

        ColumnStats small = new ColumnStats("s", ColumnStats.Shape.INTEGER);
        for (long v : new long[] {1, 1, 2, 3, 3, 3}) {
            small.addLong(v);
        }
        assertEquals("[{\"from\":\"1\",\"to\":\"1\",\"count\":2},{\"from\":\"2\",\"to\":\"2\",\"count\":1},"
            + "{\"from\":\"3\",\"to\":\"3\",\"count\":3}]", small.histogram());

        ColumnStats same = new ColumnStats("c", ColumnStats.Shape.NUMBER);
        same.addDouble(2.5);
        same.addDouble(2.5);
        assertEquals("[{\"from\":\"2.5\",\"to\":\"2.5\",\"count\":2}]", same.histogram());
        assertEquals(false, same.attributes().containsKey("hll"), "no sketch unless asked for");
        same.keepSketch = true;
        assertEquals(true, same.attributes().containsKey("hll"), "number columns carry their sketch when asked");
    }

    @Test
    void histogramsSurviveRangesAFewUlpsWideAndRangesThatOverflow() {
        ColumnStats ulps = new ColumnStats("u", ColumnStats.Shape.NUMBER);
        ulps.addDouble(0.3);
        ulps.addDouble(0.1 + 0.2);
        String h = ulps.histogram();
        assertEquals(true, h != null && h.contains("\"count\""), String.valueOf(h));
        assertEquals(true, ulps.attributes().containsKey("min"), "the rest of the profile stands");

        ColumnStats huge = new ColumnStats("h", ColumnStats.Shape.NUMBER);
        huge.addDouble(-Double.MAX_VALUE);
        huge.addDouble(Double.MAX_VALUE);
        String hh = huge.histogram();
        assertEquals(true, hh != null && hh.startsWith("[{"), String.valueOf(hh));
        assertEquals(true, huge.attributes().containsKey("max"));
    }

    @Test
    void sketchFieldNamesSurviveCommas() {
        String s = ProfileStep.sketchFields(java.util.List.of("id", "a,b", "naïve id"));
        assertEquals(3, s.split(",").length);
    }
}
