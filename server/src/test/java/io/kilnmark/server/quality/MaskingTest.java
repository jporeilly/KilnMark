package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Values of a column marked sensitive reach nobody who may not unmask them, by any route: measure
 * and drift evidence and detail, rule parameters, failing-rows queries and database error text,
 * recommendations, the profile's histogram and exports. Shapes, counts and shares stay.
 */
class MaskingTest {

    private static final String URN = "urn:kilnmark:shop:sales.customers";
    /** A steward looking at an asset whose {@code postcode} column is sensitive. */
    private static final Masking.Sight STEWARD = new Masking.Sight(true, Set.of("postcode"), false);
    private static final Masking.Sight ADMIN_UNMASKED = new Masking.Sight(true, Set.of("postcode"), true);
    private static final Masking.Sight VIEWER = new Masking.Sight(false, Set.of("postcode"), false);

    private static Rule rule(String field, RuleType type, String params) throws Exception {
        return new Rule(7, URN, field, "r", type, type.dimension(), (ObjectNode) Facts.JSON.readTree(params.replace('\'', '"')),
            "share", null, 0.0, new Rule.Actions("https://hooks.example.com/k/x", true), true, "kilnmark", null, "t",
            Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void stewardsGetEvidenceAndDriftOfASensitiveColumnWithoutItsValues() {
        ObjectNode e = Facts.JSON.createObjectNode().put("values", 10).put("min", "AB1 2CD").put("max", "ZE3 9XY")
            .put("detail", "Quartile fences AB1 to ZE3");
        e.putArray("domain").add("SW1A 1AA");
        JsonNode masked = Masking.evidence(e, "postcode", STEWARD);
        assertEquals(1, masked.size(), masked.toString());
        assertEquals(e, Masking.evidence(e, "postcode", ADMIN_UNMASKED));
        assertEquals(e, Masking.evidence(e, "quantity", STEWARD), "other columns keep their values");
        assertEquals(1, Masking.evidence(e, "quantity", VIEWER).size(), "viewers never get values");

        // Timeliness and freshness are about the asset, but name the column their values come from.
        ObjectNode t = Facts.JSON.createObjectNode().put("field", "postcode").put("latest", "2026-10-01T00:00:00Z")
            .put("ageHours", 3);
        assertFalse(Masking.evidence(t, "", STEWARD).has("latest"));
        assertTrue(Masking.evidence(t, "", ADMIN_UNMASKED).has("latest"));

        QualityStore.DriftRow d = new QualityStore.DriftRow(1, URN, "postcode", "distribution", "top_values", "low",
            "The most common values of postcode changed, including the commonest", Facts.JSON.createObjectNode()
            .put("overlap", 0.2).put("medianFrom", "M1 1AE"), 3, 2L, "previous", Instant.EPOCH,
            "The commonest is now SW1A 1AA (was M1 1AE)");
        QualityStore.DriftRow seen = Masking.drift(d, STEWARD);
        assertNull(seen.detail());
        assertFalse(seen.evidence().has("medianFrom"));
        assertEquals(d.message(), seen.message(), "the words stay");
        assertSame(d, Masking.drift(d, ADMIN_UNMASKED));
        QualityStore.DriftRow other = new QualityStore.DriftRow(1, URN, "quantity", "distribution", "quantile_shift",
            "low", "shifted", Facts.JSON.createObjectNode(), 3, 2L, "previous", Instant.EPOCH, "Median 4, was 2");
        assertSame(other, Masking.drift(other, STEWARD));
    }

    @Test
    void rulesResultsAndRecommendationsHideASensitiveColumnsValues() throws Exception {
        Rule allowed = rule("postcode", RuleType.ALLOWED_VALUES, "{'values':['SW1A 1AA','M1 1AE']}");
        Rule seen = Masking.rule(allowed, STEWARD);
        assertFalse(seen.params().toString().contains("SW1A"), seen.params().toString());
        assertTrue(seen.params().path("hidden").asBoolean());
        assertEquals("https://hooks.example.com/k/x", seen.actions().webhook(), "stewards still see the webhook");
        assertSame(allowed, Masking.rule(allowed, ADMIN_UNMASKED));
        Rule range = rule("quantity", RuleType.IN_RANGE, "{'min':'0','max':'99'}");
        assertSame(range, Masking.rule(range, STEWARD), "a column that is not sensitive");
        Rule pattern = rule("postcode", RuleType.MATCHES_PATTERN, "{'regex':'^[A-Z]{1,2}[0-9]'}");
        assertSame(pattern, Masking.rule(pattern, STEWARD), "a pattern is a shape, not a value");

        QualityStore.Result res = new QualityStore.Result(1, 7, 1L, URN, Rule.Status.ERROR, null, null, "sql",
            "SELECT t.* FROM t WHERE postcode NOT IN ('SW1A 1AA')", null, null, Instant.EPOCH, "kilnmark",
            "invalid input: \"SW1A 1AA\"", false);
        QualityStore.Result r = Masking.result(res, allowed, STEWARD);
        assertNull(r.failingQuery());
        assertNull(r.errorDetail());
        assertEquals(res.failingQuery(), Masking.result(res, allowed, ADMIN_UNMASKED).failingQuery());
        assertEquals(res.errorDetail(), Masking.result(res, range, STEWARD).errorDetail());
        Rule custom = rule(null, RuleType.CUSTOM_SQL, "{'sql':'SELECT * FROM ${table}'}");
        assertNull(Masking.result(res, custom, STEWARD).errorDetail(), "custom SQL can read any column");
        assertEquals(res.errorDetail(), Masking.result(res, custom, new Masking.Sight(true, Set.of(), false))
            .errorDetail());

        ProfileFacts facts = Facts.version().rows(100)
            .field("postcode", "text", "{'rows':100,'nulls':0,'distinct':3,'top':[{'value':'SW1A 1AA','count':40},"
                + "{'value':'M1 1AE','count':30},{'value':'EC1A 1BB','count':30}]}")
            .field("quantity", "int32", "{'rows':100,'nulls':0,'distinct':90,'min':'1','max':'99'}")
            .build();
        List<Recommendations.Recommendation> all = Recommendations.of(facts, List.of(), Set.of(), Duration.ofDays(1));
        for (Recommendations.Recommendation x : all) {
            String shown = Masking.recommendation(x, STEWARD).toString();
            if ("postcode".equals(x.field())) {
                assertFalse(shown.contains("SW1A") || shown.contains("M1 1AE"), shown);
            }
        }
        assertTrue(all.stream().anyMatch(x -> x.type().equals("allowed_values") && "postcode".equals(x.field())),
            "the test needs a recommendation drawn from the data");
        assertTrue(all.stream().map(x -> Masking.recommendation(x, STEWARD)).anyMatch(x -> x.type().equals("in_range")
            && x.params().toString().contains("99")), "other columns keep their values");
    }

    @Test
    void theProfileHidesASensitiveColumnsHistogramBounds() throws Exception {
        JsonNode p = Facts.JSON.readTree("{\"rows\":10,\"min\":\"1\",\"max\":\"9\",\"histogram\":[{\"from\":\"1\","
            + "\"to\":\"5\",\"count\":4},{\"from\":\"5\",\"to\":\"9\",\"count\":6}]}");
        JsonNode masked = Masking.profile(p, true, false);
        assertFalse(masked.toString().matches(".*\"(from|to)\":\"[0-9].*"), masked.toString());
        assertEquals(6, masked.get("histogram").get(1).get("count").asInt(), "counts stay");
        assertEquals(p, Masking.profile(p, true, true));
    }

    @Test
    void scoreReasonsAndDriftMessagesHoldNoValues() {
        ProfileFacts before = Facts.version().id(1).rows(100)
            .field("postcode", "text", "{'rows':100,'nulls':0,'distinct':20,'top':[{'value':'QQ1 1QQ','count':60},"
                + "{'value':'QQ2 2QQ','count':40}],'quantiles':[1,2,3]}")
            .field("price", "float64", "{'rows':100,'nulls':0,'distinct':90,'median':'1234.5','quantiles':[1000,1234.5,"
                + "1500],'min':'1000','max':'1500','outliers':0,'fence_low':'900','fence_high':'1600'}")
            .build();
        ProfileFacts now = Facts.version().id(2).at("2026-10-02T12:00:00Z").rows(100)
            .field("postcode", "text", "{'rows':100,'nulls':30,'distinct':20,'top':[{'value':'XX9 9XX','count':70},"
                + "{'value':'QQ2 2QQ','count':30}]}")
            .field("price", "float64", "{'rows':100,'nulls':0,'distinct':90,'median':'9876.5','quantiles':[9000,9876.5,"
                + "9999],'min':'9000','max':'9999','outliers':40,'fence_low':'8900','fence_high':'9100'}")
            .build();
        List<Drift.Event> drift = Drift.compare(now, before, null);
        List<Measures.Measure> measures = Measures.of(now, List.of(before), List.of(), Duration.ofDays(1));
        List<Scores.Score> scores = Scores.of(measures, List.of(), drift);
        StringBuilder words = new StringBuilder();
        drift.forEach(e -> words.append(e.message()).append('\n'));
        measures.forEach(m -> words.append(m.reason()).append('\n'));
        scores.forEach(s -> words.append(s.reasons()).append('\n'));
        assertFalse(drift.isEmpty() || measures.isEmpty());
        for (String value : List.of("QQ1", "XX9", "1234.5", "9876.5", "8900", "9100", "9999")) {
            assertFalse(words.toString().contains(value), value + " in: " + words);
        }
    }
}
