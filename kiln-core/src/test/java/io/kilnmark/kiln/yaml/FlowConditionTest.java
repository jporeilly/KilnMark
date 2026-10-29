package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FlowConditionTest {

    static final Map<String, FlowCondition.Type> TYPES = Map.of(
        "clean.score", FlowCondition.Type.NUMBER,
        "clean.quarantined_share", FlowCondition.Type.NUMBER,
        "clean.status", FlowCondition.Type.TEXT,
        "full", FlowCondition.Type.BOOLEAN,
        "region", FlowCondition.Type.TEXT);

    static final FlowCondition.Scope SCOPE = new FlowCondition.Scope() {
        @Override
        public FlowCondition.Type typeOf(String name) {
            return TYPES.get(name);
        }

        @Override
        public Collection<String> names() {
            return TYPES.keySet();
        }
    };

    private static FlowCondition ok(String text) {
        FlowCondition.Parsed p = FlowCondition.parse(text, SCOPE);
        assertTrue(p.issues().isEmpty(), text + " -> " + p.issues());
        return p.condition();
    }

    private static String problem(String text) {
        FlowCondition.Parsed p = FlowCondition.parse(text, SCOPE);
        assertNull(p.condition(), text);
        assertFalse(p.issues().isEmpty(), text);
        return p.issues().getFirst().message();
    }

    private static Boolean eval(String text, Object score, Object share) {
        Map<String, Object> v = new HashMap<>();
        v.put("clean.score", score);
        v.put("clean.quarantined_share", share);
        v.put("clean.status", "succeeded");
        v.put("region", "EMEA");
        return ok(text).evaluate(v::get);
    }

    @Test
    void theExampleFromTheBrief() {
        String c = "clean.quarantined_share < 0.02 and clean.score >= good";
        assertEquals(true, eval(c, 0.97, 0.01));
        assertEquals(false, eval(c, 0.97, 0.02));
        assertEquals(false, eval(c, 0.90, 0.0));
        assertEquals(true, eval(c, 0.95, 0.0), "good is 0.95 and a score equal to it is good");
    }

    @Test
    void wordsAreTheLowestScoreOfTheirBand() {
        assertEquals(true, eval("clean.score >= fair", 0.8, 0.0));
        assertEquals(false, eval("clean.score >= fair", 0.79, 0.0));
        assertEquals(true, eval("clean.score >= poor", 0.0, 0.0));
    }

    @Test
    void unknownValuesAreUnknownNotFalse() {
        assertNull(eval("clean.score >= good", null, 0.0));
        assertNull(eval("clean.score >= good and clean.quarantined_share < 1", null, 0.0));
        assertEquals(false, eval("clean.score >= good and clean.quarantined_share < 1", null, 5.0));
        assertEquals(true, eval("clean.score >= good or clean.quarantined_share < 1", null, 0.0));
        assertNull(eval("not clean.score >= good", null, 0.0));
    }

    @Test
    void textAndBracketsAndNot() {
        assertEquals(true, eval("clean.status = 'succeeded' and (clean.score > 0.5 or region = 'APAC')", 0.9, 0.0));
        assertEquals(false, eval("not (clean.status = 'succeeded')", 0.9, 0.0));
        assertEquals(true, eval("region != 'APAC'", 0.9, 0.0));
        assertEquals(true, eval("clean.score > -1", 0.9, 0.0));
    }

    @Test
    void listsWhatItReads() {
        assertEquals(List.of("clean.score", "region"),
            ok("clean.score > 1 or region = 'x' or clean.score < 5").references());
    }

    @Test
    void refusesWhatCannotBeRight() {
        assertTrue(problem("clean.scor >= good").contains("did you mean 'clean.score'"), problem("clean.scor >= good"));
        assertTrue(problem("clean.score = 'high'").contains("Cannot compare a number with a text"));
        assertTrue(problem("full < true").contains("only with = and !="));
        assertTrue(problem("clean.score").contains("must be a comparison"));
        assertTrue(problem("clean.score >=").contains("ends too soon"));
        assertTrue(problem("(clean.score >= 1").contains("not closed"));
        assertTrue(problem("clean.score >= 1 clean.score").contains("Unexpected"));
        assertTrue(problem("clean.score >= 'abc").contains("not closed"));
        assertTrue(problem("clean.score >= 1 && true").contains("Unexpected character"));
        assertTrue(problem("").contains("empty"));
        assertTrue(problem("clean.score >= 1.2.3").contains("not a number"));
        assertTrue(problem("and").contains("keyword"));
        assertTrue(problem("clean.score == 0").contains("'==' is not a comparison"));
        assertTrue(problem("clean.score => 0").contains("Expected a value"));
        assertTrue(problem("clean.score =< 1").contains("Expected a value"));
    }

    @Test
    void limitsSizeAndDepth() {
        assertTrue(problem("true and ".repeat(200) + "true").contains("too long"));
        String deep = "(".repeat(40) + "true" + ")".repeat(40);
        assertTrue(problem(deep).contains("nested too deeply"));
        String nots = "not ".repeat(40) + "true";
        assertTrue(problem(nots).contains("nested too deeply"));
        assertTrue(ok("(".repeat(10) + "true" + ")".repeat(10)).evaluate(n -> null));
    }
}
