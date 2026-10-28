package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.plugins.write.WriteReport;

/** Measured quality of a run's outputs, from what their writers reported, and of its gates. */
class RunQualityTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String URN = "urn:kilnmark:warehouse:crm.customers_clean";

    /** As the scan stores a profile: counts as numbers, the rest as text. */
    static String profileJson(MetadataEvent e) {
        ObjectNode o = JSON.createObjectNode();
        e.attributes().forEach((k, v) -> {
            if (!k.equals("field")) {
                if (v.matches("-?[0-9]+")) {
                    o.put(k, Long.parseLong(v));
                } else {
                    o.put(k, v);
                }
            }
        });
        return o.toString();
    }

    static PipelineExecution.WriteTarget write(String mode) {
        return new PipelineExecution.WriteTarget("clean", "warehouse", "crm.customers_clean", mode, URN, List.of(),
            List.of());
    }

    static List<MetadataEvent> events(long rows, long emailNulls) {
        Instant now = Instant.now();
        return List.of(
            new MetadataEvent(URN, MetadataEvent.Kind.SCHEMA, Map.of("columns", "[{\"name\":\"customer_id\",\"kind\":"
                + "\"int64\",\"type\":\"bigint\"},{\"name\":\"email\",\"kind\":\"text\",\"type\":\"text\"}]"), now),
            new MetadataEvent(URN, MetadataEvent.Kind.ROW_COUNT, Map.of("rows", Long.toString(rows), "complete", "true",
                "written", "true"), now),
            new MetadataEvent(URN, MetadataEvent.Kind.PROFILE, Map.of("field", "customer_id", "rows",
                Long.toString(rows), "nulls", "0", "distinct", Long.toString(rows)), now),
            new MetadataEvent(URN, MetadataEvent.Kind.PROFILE, Map.of("field", "email", "rows", Long.toString(rows),
                "nulls", Long.toString(emailNulls), "distinct", Long.toString(rows - emailNulls)), now),
            new MetadataEvent("urn:kilnmark:warehouse:other", MetadataEvent.Kind.PROFILE, Map.of("field", "x", "rows",
                "5", "nulls", "5"), now));
    }

    @Test
    void anOutputIsScoredFromItsWritersProfile() {
        RunQuality.StepQuality q = RunQuality.output(write("replace"), events(1000, 100), RunQualityTest::profileJson,
            JSON);
        assertEquals("output", q.role());
        assertEquals(URN, q.asset());
        assertNotNull(q.measured(), q.toString());
        assertNull(q.predicted(), "predicted scores come with design-time prediction");
        assertNull(q.note());
        // email is 90% complete, customer_id 100%: completeness 95%; the key is unique.
        assertEquals(0.95, q.measured().dimensions().get("completeness"), 1e-9);
        assertEquals(1.0, q.measured().dimensions().get("uniqueness"), 1e-9);
        assertTrue(q.measured().overall() < 1 && q.measured().overall() > 0.9, q.toString());
        assertTrue(q.measured().reasons().get(0).startsWith("Completeness 95%"), q.measured().reasons().toString());
    }

    @Test
    void anOutputWithNothingToScoreSaysWhy() {
        RunQuality.StepQuality appended = RunQuality.output(write("append"), List.of(new MetadataEvent(URN,
            MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "5", "appended", "true", "written", "true"), Instant.now())),
            RunQualityTest::profileJson, JSON);
        assertNull(appended.measured());
        assertTrue(appended.note().startsWith("The append added rows to what was there"), appended.note());
        RunQuality.StepQuality broken = RunQuality.output(write("replace"), List.of(new MetadataEvent(URN,
            MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "5", WriteReport.PROFILED, "false"), Instant.now())),
            RunQualityTest::profileJson, JSON);
        assertEquals("What it wrote could not be profiled, so it has no score", broken.note());
        RunQuality.StepQuality off = RunQuality.output(write("create"), List.of(new MetadataEvent(URN,
            MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "5"), Instant.now())), RunQualityTest::profileJson, JSON);
        assertTrue(off.note().contains("profile_output: false"), off.note());
        assertTrue(RunQuality.output(write("create"), List.of(), RunQualityTest::profileJson, JSON).note()
            .contains("reported nothing"));
    }

    @Test
    void theRunsScoreIsItsLowestOutput() {
        RunQuality.StepQuality a = new RunQuality.StepQuality("a", "output", "u", new RunQuality.Score(0.97, Map.of(),
            List.of()), null, null, null);
        RunQuality.StepQuality b = new RunQuality.StepQuality("b", "output", "u", new RunQuality.Score(0.91, Map.of(),
            List.of()), null, null, null);
        RunQuality.StepQuality gate = new RunQuality.StepQuality("g", "gate", "u", new RunQuality.Score(0.5, Map.of(),
            List.of()), null, null, null);
        RunQuality.StepQuality none = new RunQuality.StepQuality("n", "output", "u", null, null, "appended", null);
        assertEquals(0.91, RunQuality.lowestOutput(List.of(a, b, gate, none)));
        assertEquals("b", RunQuality.lowestOutputStep(List.of(a, b, gate, none)).step());
        assertNull(RunQuality.lowestOutput(List.of(gate, none)), "gates are not outputs");
        assertNull(RunQuality.lowestOutputStep(List.of()));
    }

    @Test
    void aGatesQualityComesFromItsOutcome() {
        QualityGate.Outcome o = new QualityGate.Outcome("quarantine", "quarantined", null, 10, 8, 2, 2, 0.8,
            Map.of("completeness", 0.8), List.of("Completeness 80%"), List.of(new QualityGate.RuleOutcome("e", "not_null",
                "email", "completeness", "inline", "fail", 2, 10, null)), null, null, null);
        RunQuality.StepQuality q = RunQuality.gate("check", o);
        assertEquals(0.8, q.measured().overall());
        assertEquals(o, q.gate());
        QualityGate.Outcome empty = new QualityGate.Outcome("stop", "passed", null, 10, 10, 0, 0, null, Map.of(),
            List.of(), List.of(), null, null, null);
        assertEquals("The gate had no rules to check", RunQuality.gate("check", empty).note());
    }
}
