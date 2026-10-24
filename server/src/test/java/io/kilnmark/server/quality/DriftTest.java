package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Drift between two profile versions, with nothing read again. */
class DriftTest {

    private static Drift.Event find(List<Drift.Event> events, String change, String field) {
        return events.stream().filter(e -> e.change().equals(change)
            && (field == null ? e.field() == null : field.equals(e.field()))).findFirst().orElse(null);
    }

    private static final String QUANTILES_0_100 = quantiles(0, 100);

    private static String quantiles(double from, double to) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i <= 20; i++) {
            b.append(i == 0 ? "" : ",").append(from + (to - from) * i / 20);
        }
        return b.append(']').toString();
    }

    @Test
    void schemaChangesAddedRemovedRenamedAndRetyped() {
        ProfileFacts before = Facts.version()
            .field("id", "int64", "{'rows':100,'nulls':0,'distinct':100}")
            .field("mail", "text", "{'rows':100,'nulls':10,'distinct':90}")
            .field("legacy", "text", "{'rows':100,'nulls':100,'distinct':0}")
            .field("amount", "int32", "{'rows':100,'nulls':0,'distinct':50}").build();
        ProfileFacts after = Facts.version()
            .field("id", "int64", "{'rows':100,'nulls':0,'distinct':100}")
            .field("email", "text", "{'rows':100,'nulls':11,'distinct':89}")
            .field("amount", "decimal", "{'rows':100,'nulls':0,'distinct':50}")
            .field("added", "text", "{'rows':100,'nulls':0,'distinct':3}").build();
        List<Drift.Event> events = Drift.compare(after, before, Duration.ofDays(1));
        Drift.Event renamed = find(events, "column_renamed", "email");
        assertNotNull(renamed, events.toString());
        assertEquals("medium", renamed.severity());
        assertEquals("high", find(events, "column_removed", "legacy").severity());
        assertNull(find(events, "column_removed", "mail"), "a rename is not also a removal");
        assertEquals("high", find(events, "column_retyped", "amount").severity());
        assertNotNull(find(events, "column_added", "added"));
    }

    @Test
    void distributionChangesNullsDistinctQuantilesTopValuesAndPatterns() {
        ProfileFacts before = Facts.version().rows(1000)
            .field("score", "float64", "{'rows':1000,'nulls':0,'distinct':900,'median':'50','quantiles':"
                + QUANTILES_0_100 + "}")
            .field("country", "text", "{'rows':1000,'nulls':0,'distinct':40,'top':[{'value':'GB','count':500},"
                + "{'value':'FR','count':300}],'patterns':[{'shape':'AA','count':1000}],'patterns_collapsed':'false'}")
            .field("note", "text", "{'rows':1000,'nulls':100,'distinct':900}").build();
        ProfileFacts after = Facts.version().rows(1000)
            .field("score", "float64", "{'rows':1000,'nulls':0,'distinct':910,'median':'80','quantiles':"
                + quantiles(30, 130) + "}")
            .field("country", "text", "{'rows':1000,'nulls':0,'distinct':41,'top':[{'value':'US','count':500},"
                + "{'value':'CA','count':300}],'patterns':[{'shape':'AA','count':700},{'shape':'Aaaaa','count':300}],"
                + "'patterns_collapsed':'false'}")
            .field("note", "text", "{'rows':1000,'nulls':400,'distinct':400}").build();
        List<Drift.Event> events = Drift.compare(after, before, Duration.ofDays(1));
        Drift.Event shift = find(events, "quantile_shift", "score");
        assertNotNull(shift, events.toString());
        assertEquals("high", shift.severity());
        assertEquals(0.3, shift.evidence().get("ks").asDouble(), 0.01);
        assertEquals("medium", find(events, "top_values", "country").severity());
        Drift.Event pattern = find(events, "pattern_share", "country");
        assertEquals("high", pattern.severity());
        assertTrue(pattern.message().contains("A new value shape Aaaaa makes up 30%"), pattern.message());
        assertEquals("high", find(events, "null_rate", "note").severity());
        assertEquals("medium", find(events, "distinct_count", "note").severity());
        assertNull(find(events, "row_count", null), "row count unchanged");
        assertNull(find(events, "quantile_shift", "note"));
    }

    @Test
    void volumeAndFreshness() {
        ProfileFacts before = Facts.version().rows(1000).at("2026-10-01T00:00:00Z")
            .field("loaded_at", "timestamp_tz", "{'rows':1000,'nulls':0,'max':'2026-09-30T23:00:00Z'}").build();
        ProfileFacts after = Facts.version().rows(400).at("2026-10-03T00:00:00Z")
            .field("loaded_at", "timestamp_tz", "{'rows':400,'nulls':0,'max':'2026-09-30T23:00:00Z'}").build();
        List<Drift.Event> events = Drift.compare(after, before, Duration.ofDays(1));
        Drift.Event volume = find(events, "row_count", null);
        assertEquals("high", volume.severity());
        assertTrue(volume.message().contains("-60%"), volume.message());
        Drift.Event fresh = find(events, "no_new_data", null);
        assertNotNull(fresh, events.toString());
        assertEquals("medium", fresh.severity(), "49 hours old, daily cadence: beyond two days, within three");
    }

    @Test
    void identicalVersionsDoNotDrift() {
        ProfileFacts v = Facts.version().rows(10).field("x", "int64", "{'rows':10,'nulls':1,'distinct':9,'quantiles':"
            + QUANTILES_0_100 + "}").build();
        assertEquals(List.of(), Drift.compare(v, v, Duration.ofDays(1)));
    }

    @Test
    void ksDistanceFromQuantiles() {
        double[] a = new double[21];
        double[] b = new double[21];
        for (int i = 0; i <= 20; i++) {
            a[i] = i * 5;
            b[i] = i * 5 + 50;
        }
        assertEquals(0.5, Drift.ks(a, b), 1e-9);
        assertEquals(0.0, Drift.ks(a, a), 1e-9);
        assertEquals(0.5, Drift.cdf(a, 50), 1e-9);
        double[] constant = new double[21];
        java.util.Arrays.fill(constant, 7);
        assertEquals(1.0, Drift.cdf(constant, 7), 1e-9);
        assertEquals(0.0, Drift.cdf(constant, 6.9), 1e-9);
    }
}
