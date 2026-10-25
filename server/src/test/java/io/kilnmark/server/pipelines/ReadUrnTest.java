package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.plugins.files.FilesConnector;
import io.kilnmark.server.auth.AccessRole;

/**
 * Reads are recorded under the URN the catalogue records, not as typed: a file named
 * {@code a#b%c.csv} is {@code a%23b%25c.csv} in the catalogue, and lineage, the run's read list and
 * its assets for Sights must use that.
 */
class ReadUrnTest {

    static final String CATALOGUED = "urn:kilnmark:share:out/a%23b%25c.csv";

    private static FakeSources share(Path root) {
        FakeSources s = new FakeSources();
        s.connectors.put("files", Connectors.load().get("files").orElseThrow()
            .withContext(new ConnectorContext(Map.of(FilesConnector.ROOTS, root.toString()))));
        s.add("share", "files", Map.of("path", root.toString(), "allow_writing", "true"), null);
        return s;
    }

    private static String yaml(String asset) {
        return """
            kilnmark: pipeline/v1
            name: lin
            steps:
              - id: r
                type: read
                source: share
                asset: "%s"
              - id: w
                type: write
                input: r
                source: share
                path: copy.csv
                mode: replace
            """.formatted(asset);
    }

    @Test
    void readsUseTheCataloguedUrnWhicheverFormIsWritten(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("out"));
        Files.writeString(root.resolve("out/a#b%c.csv"), "id,name\n1,Ann\n");
        FakeSources s = share(root);
        StepFactories steps = StepFactories.discover().register(new ReadStepFactory(s)).register(new WriteStepFactory(s));
        for (String asset : List.of("out/a#b%c.csv", "out/a%23b%25c.csv")) {
            RunContext run = new RunContext("lin", 1, "jo", AccessRole.STEWARD);
            PipelineCompilerPort.Compilation c = run.run(() -> new KilnPipelineCompiler().compile(yaml(asset), steps));
            assertEquals(List.of(), c.problems(), asset);
            assertNotNull(c.graph(), asset);
            PipelineExecution.closeSteps(c.graph());
            assertEquals(Map.of("r", CATALOGUED), PipelineExecution.reads(c, run), asset);
            assertEquals(List.of(CATALOGUED), PipelineExecution.writes(c, run).get(0).inputs(), asset);
        }
    }

    @Test
    void readColumnsAreFoundWhicheverFormIsWritten() throws Exception {
        FakeSources s = new FakeSources().add("share", "files", Map.of(), null).asset(CATALOGUED, "id:int64",
            "name:text");
        ReadStepFactory read = new ReadStepFactory(s);
        for (String asset : List.of("out/a#b%c.csv", "out/a%23b%25c.csv")) {
            assertNotNull(read.outputSchema("out", Map.of(), Map.of("source", "share", "asset", asset)), asset);
        }
    }
}
