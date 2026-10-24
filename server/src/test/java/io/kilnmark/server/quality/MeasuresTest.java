package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Each automatic pre-analysis measure on synthetic profiles. */
class MeasuresTest {

    private static final Duration DAY = Duration.ofDays(1);

    private static Measures.Measure measure(List<Measures.Measure> ms, String field, Dimension d) {
        return ms.stream().filter(m -> m.field().equals(field) && m.dimension() == d).findFirst().orElse(null);
    }

    private static List<Measures.Measure> of(Facts f) {
        return Measures.of(f.build(), List.of(), List.of(), DAY);
    }

    @Test
    void completenessCountsNullsAndBlanks() {
        List<Measures.Measure> ms = of(Facts.version()
            .field("email", "text", "{'rows':1000,'nulls':50,'blanks':'50','patterns':[]}")
            .field("id", "int64", "{'rows':1000,'nulls':0,'distinct':1000}"));
        Measures.Measure email = measure(ms, "email", Dimension.COMPLETENESS);
        assertEquals(0.9, email.score(), 1e-9);
        assertEquals(50, email.evidence().get("blanks").asLong());
        assertTrue(email.reason().contains("100 of 1,000 rows are null or blank"), email.reason());
        assertEquals(1.0, measure(ms, "id", Dimension.COMPLETENESS).score());
    }

    @Test
    void uniquenessForDeclaredKeysAndColumnsThatLookLikeKeys() {
        List<Measures.Measure> ms = of(Facts.version().key("id")
            .field("id", "int64", "{'rows':1000,'nulls':0,'distinct':990,'distinct_exact':true}")
            .field("customer_code", "text", "{'rows':1000,'nulls':0,'distinct':950,'distinct_exact':true}")
            .field("segment", "text", "{'rows':1000,'nulls':0,'distinct':2,'distinct_exact':true}"));
        Measures.Measure id = measure(ms, "id", Dimension.UNIQUENESS);
        assertEquals(0.99, id.score(), 1e-9);
        assertTrue(id.reason().startsWith("The declared key has about 10 repeated"), id.reason());
        Measures.Measure code = measure(ms, "customer_code", Dimension.UNIQUENESS);
        assertEquals(0.95, code.score(), 1e-9);
        assertTrue(code.reason().startsWith("Looks like a key but"));
        assertNull(measure(ms, "segment", Dimension.UNIQUENESS), "not a key: no uniqueness measure");

        // An estimated distinct count a little short of the values is not evidence of duplicates.
        List<Measures.Measure> big = of(Facts.version().key("id")
            .field("id", "int64", "{'rows':1000000,'nulls':0,'distinct':995000,'distinct_exact':false}"));
        assertEquals(1.0, measure(big, "id", Dimension.UNIQUENESS).score());
    }

    @Test
    void validityFromAcceptedPatternsOrADetectedFormat() {
        String postcodes = "{'rows':100,'nulls':0,'patterns':[{'shape':'AA9A 9AA','count':80},{'shape':'A9 9AA','count':15},"
            + "{'shape':'aaaa','count':5}],'formats':[{'format':'uk_postcode','count':95}]}";
        List<Measures.Measure> detected = of(Facts.version().field("postcode", "text", postcodes));
        Measures.Measure v = measure(detected, "postcode", Dimension.VALIDITY);
        assertEquals(0.95, v.score(), 1e-9);
        assertEquals("uk_postcode", v.evidence().get("format").asText());

        List<Measures.Measure> accepted = of(Facts.version().field("postcode", "text", postcodes, "AA9A 9AA"));
        Measures.Measure a = measure(accepted, "postcode", Dimension.VALIDITY);
        assertEquals(0.8, a.score(), 1e-9);
        assertEquals("accepted patterns", a.evidence().get("basis").asText());
        assertEquals("A9 9AA", a.evidence().get("nonMatching").get(0).get("shape").asText());

        List<Measures.Measure> names = of(Facts.version().field("name", "text",
            "{'rows':100,'nulls':0,'patterns':[{'shape':'Aa+ Aa+','count':100}],'formats':[]}"));
        assertNull(measure(names, "name", Dimension.VALIDITY), "no format to judge free text by");
    }

    @Test
    void consistencyFindsCompetingDateFormatsAndImpossibleValues() {
        List<Measures.Measure> ms = of(Facts.version()
            .field("ordered", "text", "{'rows':100,'nulls':0,'patterns':[{'shape':'9999-99-99','count':70},"
                + "{'shape':'99/99/9999','count':30}],'formats':[{'format':'iso_date','count':70}]}")
            .field("quantity", "int32", "{'rows':100,'nulls':0,'negatives':'4','outliers':'0'}")
            .field("date_of_birth", "date", "{'rows':100,'nulls':0,'after_now':'2','max':'2031-01-01'}")
            .field("amount", "decimal", "{'rows':100,'nulls':0,'negatives':'40','outliers':'0'}"));
        Measures.Measure dates = measure(ms, "ordered", Dimension.CONSISTENCY);
        assertEquals(0.7, dates.score(), 1e-9);
        assertTrue(dates.reason().contains("several formats"));
        assertEquals(0.96, measure(ms, "quantity", Dimension.CONSISTENCY).score(), 1e-9);
        assertEquals(0.98, measure(ms, "date_of_birth", Dimension.CONSISTENCY).score(), 1e-9);
        assertNull(measure(ms, "amount", Dimension.CONSISTENCY), "refunds make negative amounts normal");

        List<Measures.Measure> same = of(Facts.version().field("d", "text", "{'rows':100,'nulls':0,'patterns':"
            + "[{'shape':'9/9/9999','count':40},{'shape':'99/99/9999','count':60}],'formats':[]}"));
        assertEquals(1.0, measure(same, "d", Dimension.CONSISTENCY).score(), "one format with varying run lengths");
    }

    @Test
    void accuracyFromQuartileFencesAndSmallDomains() {
        List<Measures.Measure> ms = of(Facts.version()
            .field("price", "decimal", "{'rows':1000,'nulls':0,'outliers':'60','fence_low':'-5','fence_high':'95',"
                + "'min':'1','max':'9999'}")
            .field("status", "text", "{'rows':1000,'nulls':0,'distinct':4,'top':[{'value':'open','count':600},"
                + "{'value':'closed','count':390}],'patterns':[]}"));
        Measures.Measure price = measure(ms, "price", Dimension.ACCURACY);
        assertEquals(0.9, price.score(), 1e-9, "6% beyond the fences: 1 - (0.06 - 0.01) * 2");
        assertTrue(price.reason().contains("-5 to 95"));
        Measures.Measure status = measure(ms, "status", Dimension.ACCURACY);
        assertEquals(0.99, status.score(), 1e-9);
        assertTrue(status.reason().startsWith("10 values lie outside"));
    }

    @Test
    void timelinessAgainstTheCadenceFromHistory() {
        Facts now = Facts.version().at("2026-10-10T00:00:00Z")
            .field("updated_at", "timestamp_tz", "{'rows':10,'nulls':0,'max':'2026-10-05T00:00:00Z'}")
            .field("birth_date", "date", "{'rows':10,'nulls':0,'max':'2026-10-09'}");
        Measures.Measure fresh = measure(Measures.of(now.build(), List.of(), List.of(), Duration.ofDays(10)), "",
            Dimension.TIMELINESS);
        assertEquals(1.0, fresh.score(), "five days old, expected every ten");
        assertEquals("updated_at", fresh.evidence().get("field").asText(), "a date of birth says nothing of recency");

        List<ProfileFacts> history = List.of(
            Facts.version().id(3).field("updated_at", "timestamp_tz", "{'rows':10,'nulls':0,'max':'2026-10-04T00:00:00Z'}")
                .build(),
            Facts.version().id(2).field("updated_at", "timestamp_tz", "{'rows':10,'nulls':0,'max':'2026-10-03T00:00:00Z'}")
                .build());
        Measures.Measure stale = measure(Measures.of(now.build(), history, List.of(), Duration.ofDays(10)), "",
            Dimension.TIMELINESS);
        assertEquals(24.0, stale.evidence().get("cadenceHours").asDouble(), "daily, from history");
        assertEquals(0.0, stale.score(), 1e-9, "five days old, expected daily: no score left");
    }

    @Test
    void volumeAgainstThePreviousScanAndTheUsualRange() {
        ProfileFacts prev = Facts.version().id(1).rows(1000).build();
        Measures.Measure doubled = measure(Measures.of(Facts.version().id(2).rows(2000).build(), List.of(prev), List.of(),
            DAY), "", Dimension.VOLUME);
        assertEquals(0.5, doubled.score(), 1e-9, "100% change against a 50% band: 1 - 0.5 / 1");
        Measures.Measure steady = measure(Measures.of(Facts.version().id(2).rows(1100).build(), List.of(prev), List.of(),
            DAY), "", Dimension.VOLUME);
        assertEquals(1.0, steady.score());
        assertNull(measure(of(Facts.version().rows(5)), "", Dimension.VOLUME), "nothing to compare with");
        // A steady history narrows the band to at least 10%.
        List<ProfileFacts> history = List.of(Facts.version().rows(1000).build(), Facts.version().rows(1000).build(),
            Facts.version().rows(1000).build(), Facts.version().rows(1000).build());
        assertEquals(0.1, Measures.band(history), 1e-9);
    }

    @Test
    void referentialIntegrityFromOrphans() {
        List<Measures.Measure> ms = Measures.of(Facts.version().build(), List.of(),
            List.of(new Measures.Orphans("customer_id", "urn:kilnmark:test:shop.customers", "id", 0.02, true)), DAY);
        Measures.Measure m = measure(ms, "customer_id", Dimension.INTEGRITY);
        assertEquals(0.98, m.score(), 1e-9);
        assertFalse(m.reason().isEmpty());
    }
}
