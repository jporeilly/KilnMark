package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.server.auth.AccessRole;

/** A run of a compiled pipeline with a fake graph runner: lineage, profiling writes, records, scrubbing and stopping. */
class PipelineExecutionTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String READ_URN = "urn:kilnmark:lab-db:crm.customers";
    static final String WRITE_URN = "urn:kilnmark:warehouse:crm.customer_emails";

    /** read customers -> keep emails -> write out (replace); and a second write appending the rejected rows. */
    static PipelineCompilerPort.Compilation compiled(String mode) {
        List<PipelineCompilerPort.StepOutline> steps = List.of(
            new PipelineCompilerPort.StepOutline("customers", "read", "source", 5, List.of(), List.of("out"), Map.of()),
            new PipelineCompilerPort.StepOutline("emails", "keep", "transform", 10, List.of("in"),
                List.of("out", "rejected"), Map.of()),
            new PipelineCompilerPort.StepOutline("out", "write", "sink", 15, List.of("in"), List.of(), Map.of()),
            new PipelineCompilerPort.StepOutline("odd", "write", "sink", 22, List.of("in"), List.of(), Map.of()));
        List<PipelineCompilerPort.EdgeOutline> edges = List.of(
            new PipelineCompilerPort.EdgeOutline("customers", "out", "emails", "in"),
            new PipelineCompilerPort.EdgeOutline("emails", "out", "out", "in"),
            new PipelineCompilerPort.EdgeOutline("emails", "rejected", "odd", "in"));
        Map<String, Map<String, Object>> configs = Map.of(
            "customers", Map.of("source", "lab-db", "asset", "crm.customers"),
            "emails", Map.of("columns", List.of("email")),
            "out", Map.of("source", "warehouse", "table", "crm.customer_emails", "mode", mode),
            "odd", Map.of("source", "warehouse", "path", "quarantine/odd.csv", "mode", "append"));
        return new PipelineCompilerPort.Compilation("customer-copy", null, List.of(), List.of(), steps, edges, configs,
            graph());
    }

    static PipelineGraph graph() {
        KilnPipelineCompilerTest.Nothing n = new KilnPipelineCompilerTest.Nothing("x");
        return new PipelineGraph("customer-copy", List.of(new GraphStep("customers", n, Map.of()),
            new GraphStep("emails", n, Map.of()), new GraphStep("out", n, Map.of()), new GraphStep("odd", n, Map.of())),
            List.of(new Edge(new PortRef("customers", "out"), new PortRef("emails", "in")),
                new Edge(new PortRef("emails", "out"), new PortRef("out", "in")),
                new Edge(new PortRef("emails", "rejected"), new PortRef("odd", "in"))));
    }

    @Test
    void writesKnowTheirTargetsAndTheAssetsReadUpstream() {
        List<PipelineExecution.WriteTarget> writes = PipelineExecution.writes(compiled("replace"));
        assertEquals(2, writes.size());
        PipelineExecution.WriteTarget out = writes.get(0);
        assertEquals(WRITE_URN, out.urn());
        assertEquals(List.of(READ_URN), out.inputs());
        assertTrue(out.wholeTarget());
        assertEquals("urn:kilnmark:warehouse:quarantine/odd.csv", writes.get(1).urn());
        assertFalse(writes.get(1).wholeTarget(), "an append adds to the target");
        assertEquals(Map.of("customers", READ_URN), PipelineExecution.reads(compiled("replace")));
    }

    /** Answers with a fixed result, or waits to be interrupted. */
    static final class FakeRunner implements GraphRunner {
        final AtomicReference<PipelineGraph> got = new AtomicReference<>();
        RunResult answer;
        boolean block;

        @Override
        public RunResult run(PipelineGraph graph, io.kilnmark.kiln.MetadataEmitter metadata) throws InterruptedException {
            got.set(graph);
            metadata.emit(new MetadataEvent(WRITE_URN, MetadataEvent.Kind.PROFILE, Map.of("field", "email"),
                Instant.now()));
            metadata.emit(new MetadataEvent(READ_URN, MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "9"), Instant.now()));
            if (block) {
                Thread.sleep(60_000);
            }
            return answer;
        }
    }

    private static RunResult.StepResult step(String name, long in, long out, Map<String, Long> ins,
                                             Map<String, Long> outs, String error) {
        return new RunResult.StepResult(name, "t", out, 1024, error, in, ins, outs);
    }

    @Test
    void eachStepIsRecordedWithRowsPerPortAndSecretsScrubbed() throws Exception {
        PipelineCompilerPort.Compilation c = compiled("replace");
        List<PipelineExecution.WriteTarget> writes = PipelineExecution.writes(c);
        PipelineGraph g = c.graph();
        FakeRunner runner = new FakeRunner();
        runner.answer = new RunResult("customer-copy", RunResult.Status.FAILED, Instant.now(), Instant.now(), List.of(
            step("customers", 0, 10, Map.of(), Map.of("out", 10L), null),
            step("emails", 10, 10, Map.of("in", 10L), Map.of("out", 8L, "rejected", 2L), null),
            step("out", 8, 0, Map.of("in", 8L), Map.of(), "Step out (write) failed: login hunter22 refused"),
            new RunResult.StepResult("odd", "t", 0, 1024, null, 2, Map.of("in", 2L), Map.of(),
                RunResult.Outcome.STOPPED)), List.of(), "Step out failed: login hunter22 refused");
        RunContext ctx = new RunContext("customer-copy", 3, "jo", AccessRole.STEWARD);
        ctx.secret("hunter22");
        PipelineExecution.Outcome o = new PipelineExecution(JSON).run(runner, g, writes, ctx, () -> false, () -> false);
        assertEquals("failed", o.status());
        assertEquals("Step out failed: login ******** refused", o.error());
        assertEquals(java.util.Set.of("customers", "emails", "out", "odd"), o.steps().stream()
            .map(PipelineStore.StepRun::stepId).collect(java.util.stream.Collectors.toSet()),
            "every step of the pipeline, and only those");
        Map<String, PipelineStore.StepRun> byId = new java.util.HashMap<>();
        o.steps().forEach(s -> byId.put(s.stepId(), s));
        assertEquals(0, byId.get("customers").position());
        PipelineStore.StepRun emails = byId.get("emails");
        assertEquals(10L, emails.rowsIn());
        assertEquals(10L, emails.rowsOut());
        JsonNode ports = JSON.readTree(emails.portRows());
        assertEquals(8, ports.path("out").path("out").asLong());
        assertEquals(2, ports.path("out").path("rejected").asLong());
        PipelineStore.StepRun out = byId.get("out");
        assertEquals(8L, out.rowsIn());
        assertNull(out.rowsOut(), "a write's rows go to its target, not a port");
        assertTrue(out.error().contains("********") && !out.error().contains("hunter22"), out.error());
        assertEquals(1024L, out.peakMemory());
        assertEquals("failed", out.outcome());
        assertEquals("finished", byId.get("emails").outcome(), "the engine said how each step ended");
        assertEquals("stopped", byId.get("odd").outcome(), "a writer the engine stopped is not a clean finish");
        assertEquals(Map.of("emails", 10L, "out", 8L, "odd", 2L), o.rowsIn());
        assertEquals(List.of(WRITE_URN), o.events().stream().map(MetadataEvent::assetUrn).toList(),
            "only what is said about written assets is kept");
    }

    @Test
    void cancellingInterruptsTheRun() {
        PipelineCompilerPort.Compilation c = compiled("replace");
        FakeRunner runner = new FakeRunner();
        runner.block = true;
        AtomicBoolean cancel = new AtomicBoolean();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            cancel.set(true);
        });
        long start = System.nanoTime();
        PipelineExecution.Outcome o = new PipelineExecution(JSON).run(runner, c.graph(), List.of(),
            new RunContext("customer-copy", 4, "jo", AccessRole.STEWARD), cancel::get, () -> false);
        assertEquals("cancelled", o.status());
        assertTrue(System.nanoTime() - start < 10_000_000_000L, "stopped within seconds");
        assertFalse(Thread.currentThread().isInterrupted(), "the interrupt meant for the run is cleared");
    }

    @Test
    void aWorkerStoppingFailsTheRun() {
        FakeRunner runner = new FakeRunner();
        runner.block = true;
        PipelineExecution.Outcome o = new PipelineExecution(JSON).run(runner, graph(), List.of(),
            new RunContext("customer-copy", 5, "jo", AccessRole.STEWARD), () -> false, () -> true);
        assertEquals("failed", o.status());
        assertTrue(o.error().contains("worker running this pipeline was stopped"), o.error());
    }

    @Test
    void onlyWhatTheWriterReportsIsKept() {
        assertTrue(PipelineExecution.writerEvent(new MetadataEvent(WRITE_URN, MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", "8", "complete", "true", "written", "true"), Instant.now())));
        assertFalse(PipelineExecution.writerEvent(new MetadataEvent(WRITE_URN, MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", "9", "complete", "true"), Instant.now())), "a reader's count of the same asset");
        assertTrue(PipelineExecution.writerEvent(new MetadataEvent(WRITE_URN, MetadataEvent.Kind.SCHEMA,
            Map.of("columns", "[]"), Instant.now())));
    }

    @Test
    void eachStepKeepsItsTimesAndDuration() {
        PipelineCompilerPort.Compilation c = compiled("replace");
        FakeRunner runner = new FakeRunner();
        Instant t = Instant.parse("2026-10-28T10:00:00Z");
        List<RunResult.StepResult> steps = new java.util.ArrayList<>();
        for (String s : List.of("customers", "emails", "out", "odd")) {
            steps.add(new RunResult.StepResult(s, "t", 0, 0, null, 0, Map.of(), Map.of(), RunResult.Outcome.FINISHED,
                t, t.plusMillis(20), t.plusMillis(900), t.plusMillis(1250)));
        }
        runner.answer = new RunResult("customer-copy", RunResult.Status.SUCCEEDED, t, t.plusSeconds(2), steps, null);
        PipelineExecution.Outcome o = new PipelineExecution(JSON).run(runner, c.graph(), PipelineExecution.writes(c),
            new RunContext("customer-copy", 3, "jo", AccessRole.STEWARD), () -> false, () -> false);
        PipelineStore.StepRun out = o.steps().stream().filter(s -> s.stepId().equals("out")).findFirst().orElseThrow();
        assertEquals(1250L, out.durationMs());
        assertEquals(t, out.startedAt());
        assertEquals(t.plusMillis(20), out.firstRowAt());
        assertEquals(t.plusMillis(900), out.lastRowAt());
        assertEquals(t.plusMillis(1250), out.finishedAt());
    }
}
