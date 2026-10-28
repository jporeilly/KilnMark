package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;
import io.kilnmark.kiln.plugins.files.FilesConnector;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.server.auth.AccessRole;

/**
 * The writers' contract as the server meets it (docs/design/pipelines.md, "What a write reports"):
 * a write step's settings come from each writer's {@code Connector.writeOptions()}, the source's
 * profiling settings travel in {@code WriteOptions.profiling}, and a profile failure on
 * {@code ROW_COUNT} becomes a note whose reason is held back. The shipped files writer is run end to
 * end.
 */
class WriteContractTest {

    static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    static Path tmp;

    private static Connector shipped(String kind) {
        return Connectors.load().get(kind).orElseThrow();
    }

    private static Set<String> keys(List<SettingSpec> specs) {
        return specs.stream().map(SettingSpec::key).collect(Collectors.toSet());
    }

    @Test
    void theAllowListIsEachWritersOwnDeclaration() {
        FakeSources s = new FakeSources();
        for (String kind : List.of("postgresql", "sqlserver", "files", "s3")) {
            s.connectors.put(kind, shipped(kind));
        }
        Set<String> offered = keys(new WriteStepFactory(s).settings());
        assertTrue(offered.containsAll(Set.of("commit_every", "batch_size", "bulk_copy", "text_length", "keep_grants",
            "lock_timeout", "allow_dropping_row_security", "overwrite", "allow_unscanned", "delimiter", "compression",
            "profile_output", "keep_values")), offered.toString());
        for (String kind : List.of("postgresql", "sqlserver", "files", "s3")) {
            Connector c = s.connectors.get(kind);
            assertEquals(keys(c.writeOptions()), WriteStepFactory.writerKeys(c),
                kind + ": exactly what its writer declares, nothing from a fallback list");
            assertTrue(WriteStepFactory.writerKeys(c).containsAll(Set.of("profile_output", "keep_values")), kind);
        }
        assertTrue(WriteStepFactory.writerKeys(s.connectors.get("files")).contains("delimiter"));
        assertTrue(WriteStepFactory.writerKeys(s.connectors.get("s3")).contains("delimiter"));
        assertTrue(WriteStepFactory.writerKeys(s.connectors.get("sqlserver")).contains("bulk_copy"));
        assertFalse(WriteStepFactory.writerKeys(s.connectors.get("postgresql")).contains("bulk_copy"));
        assertFalse(WriteStepFactory.writerKeys(s.connectors.get("files")).contains("commit_every"));
        assertFalse(offered.contains("profile") || offered.contains("host") || offered.contains("bucket"));

        s.add("lake", "s3", Map.of("allow_writing", "true", "bucket", "lake"), null);
        WriteStepFactory write = new WriteStepFactory(s);
        assertEquals(List.of(), write.check(Map.of("source", "lake", "path", "out/a.csv", "delimiter", ";")));
        List<String> p = write.check(Map.of("source", "lake", "path", "out/a.csv", "commit_every", 10L));
        assertEquals(1, p.size(), p.toString());
        assertTrue(p.get(0).startsWith("commit_every:"), p.get(0));
    }

    @Test
    void aWriteWarningOnRowCountBecomesANoteAndIsNotStored() {
        String urn = "urn:kilnmark:warehouse:shop.orders";
        Instant now = Instant.now();
        String warning = "The new rows are in shop.orders, but the old table could not be dropped and is kept as "
            + "shop.kiln_old_1f: ask a database administrator to drop it";
        MetadataEvent count = new MetadataEvent(urn, MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "3", "complete",
            "true", "written", "true", WriteReport.WRITE_WARNING, warning), now);
        assertEquals(warning, PipelineService.writeWarning(List.of(count), urn));
        assertNull(PipelineService.writeWarning(List.of(count), "urn:kilnmark:warehouse:shop.other"));
        assertNull(PipelineService.writeWarning(List.of(new MetadataEvent(urn, MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", "3"), now)), urn));
        assertEquals(Map.of("rows", "3", "complete", "true", "written", "true"),
            PipelineService.reported(List.of(count), urn).get(0).attributes());
    }

    @Test
    void aProfileFailureOnRowCountBecomesANoteWhoseReasonIsHeldBack() {
        String urn = "urn:kilnmark:warehouse:crm.copy";
        Instant now = Instant.now();
        MetadataEvent schema = new MetadataEvent(urn, MetadataEvent.Kind.SCHEMA, Map.of("columns", "[]"), now);
        MetadataEvent failed = new MetadataEvent(urn, MetadataEvent.Kind.ROW_COUNT, Map.of("rows", "3", "complete",
            "true", "written", "true", WriteReport.PROFILED, "false", WriteReport.PROFILE_ERROR,
            "cannot read the value 'jo@example.com'"), now);
        MetadataEvent other = new MetadataEvent("urn:kilnmark:warehouse:crm.other", MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", "1", WriteReport.PROFILED, "false", WriteReport.PROFILE_ERROR, "elsewhere"), now);
        List<MetadataEvent> events = List.of(schema, failed, other);

        assertEquals("cannot read the value 'jo@example.com'", PipelineService.profileFailure(events, urn));
        assertNull(PipelineService.profileFailure(List.of(schema, new MetadataEvent(urn, MetadataEvent.Kind.ROW_COUNT,
            Map.of("rows", "3", "complete", "true"), now)), urn), "profiled: no failure");

        List<MetadataEvent> stored = PipelineService.reported(events, urn);
        assertEquals(2, stored.size(), "only this asset's events");
        assertEquals(Map.of("rows", "3", "complete", "true", "written", "true"), stored.get(1).attributes(),
            "the stored row count keeps no reason, which may quote the data");

        ArrayNode notes = JSON.createArrayNode();
        PipelineService.profileFailureNote(notes, new PipelineExecution.WriteTarget("out", "warehouse", "crm.copy",
            "replace", urn, List.of(), List.of()), PipelineService.profileFailure(events, urn));
        String text = "out: what it wrote could not be profiled; scan warehouse to profile crm.copy";
        assertEquals(text + " (cannot read the value 'jo@example.com')", PipelineService.noteText(notes.get(0), null));
        assertEquals(text + ". " + PipelineService.FOR_STEWARDS,
            PipelineService.noteText(notes.get(0), PipelineService.FOR_STEWARDS), "held back as engine messages are");
    }

    @Test
    void aFilesWriteTakesItsDelimiterProfilingAndProfileOutputEndToEnd() throws Exception {
        Path root = Files.createDirectories(tmp.resolve("share"));
        FakeSources s = new FakeSources();
        s.connectors.put("files", shipped("files").withContext(new ConnectorContext(Map.of(FilesConnector.ROOTS,
            root.toString()))));
        s.add("share", "files", Map.of("path", root.toString(), "allow_writing", "true"), null);
        s.profiling = Map.of("keep-values", "true", "examples", "5", "sample-size", "10", "sample-mode", "reservoir",
            "sample-seed", "1");
        WriteStepFactory write = new WriteStepFactory(s);

        List<MetadataEvent> events = run(write, Map.of("source", "share", "path", "out/people.csv", "delimiter", ";"));
        List<String> lines = Files.readAllLines(root.resolve("out/people.csv"), StandardCharsets.UTF_8);
        assertEquals("id;name", lines.get(0));
        assertEquals("1;Person 1", lines.get(1));
        String urn = "urn:kilnmark:share:out/people.csv";
        assertTrue(kinds(events, urn).containsAll(Set.of(MetadataEvent.Kind.SCHEMA, MetadataEvent.Kind.ROW_COUNT,
            MetadataEvent.Kind.PROFILE, MetadataEvent.Kind.SAMPLE)), kinds(events, urn).toString());

        s.profiling = Map.of("keep-values", "false");
        events = run(write, Map.of("source", "share", "path", "out/private.csv"));
        assertTrue(kinds(events, "urn:kilnmark:share:out/private.csv").contains(MetadataEvent.Kind.PROFILE));
        assertFalse(kinds(events, "urn:kilnmark:share:out/private.csv").contains(MetadataEvent.Kind.SAMPLE),
            "the source keeps no values, so the writer keeps none");

        events = run(write, Map.of("source", "share", "path", "out/quiet.csv", "profile_output", false));
        assertEquals(Set.of(MetadataEvent.Kind.SCHEMA, MetadataEvent.Kind.ROW_COUNT),
            kinds(events, "urn:kilnmark:share:out/quiet.csv"), "profile_output: false writes no profile");
    }

    private static Set<MetadataEvent.Kind> kinds(List<MetadataEvent> events, String urn) {
        return events.stream().filter(e -> e.assetUrn().equals(urn)).map(MetadataEvent::kind)
            .collect(Collectors.toSet());
    }

    /** Three people into the write step {@code config} describes, as a run does. */
    private static List<MetadataEvent> run(WriteStepFactory write, Map<String, Object> config) throws Exception {
        RunContext ctx = new RunContext("people-out", 1, "jo", AccessRole.STEWARD);
        Step step = ctx.run(() -> {
            try {
                return write.create(config);
            } catch (StepException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
        PipelineGraph g = new PipelineGraph("people-out", List.of(
            new GraphStep("people", new MemoryConnector.People(3, "t"), Map.of("token", "t", "asset-urn",
                "urn:kilnmark:lab-db:crm.people")),
            new GraphStep("out", step, config)),
            List.of(new Edge(new PortRef("people", "out"), new PortRef("out", "in"))));
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        RunResult r = new KilnGraphRunner(64L << 20, 4, Duration.ofSeconds(30)).run(g, events::add);
        assertTrue(r.succeeded(), r.error());
        return events;
    }
}
