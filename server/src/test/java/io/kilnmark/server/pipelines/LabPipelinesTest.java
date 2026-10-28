package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.connect.Connectors;

/**
 * The demo pipelines in lab/pipelines check clean against the lab as lab/sources.csv registers it,
 * with the columns a scan of the lab finds. The lab is never shipped: this only keeps its demos
 * working as the steps change.
 */
class LabPipelinesTest {

    private static Path lab() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("lab");
    }

    private static FakeSources labSources() {
        FakeSources s = new FakeSources();
        Connectors all = Connectors.load();
        for (String kind : List.of("postgresql", "s3", "mariadb")) {
            s.connectors.put(kind, all.get(kind).orElseThrow(() -> new AssertionError("no connector " + kind)));
        }
        s.add("lab-db", "postgresql", Map.of("host", "localhost", "port", "5433", "database", "lab",
            "username", "lab", "schemas", "crm;billing"), "lab");
        s.add("lab-mariadb", "mariadb", Map.of("host", "localhost", "port", "3307", "username", "kiln_reader",
            "schemas", "shop;stock"), "Lab-Reader-2026");
        s.add("lab-warehouse", "s3", Map.of("endpoint", "http://localhost:9020", "bucket", "lab-warehouse",
            "access_key_id", "labadmin", "path_style", "true", "allow_writing", "true"), "labadmin123");
        // Columns as lab/postgres/01-schema.sql and lab/mariadb/01-shop.sql declare them.
        s.asset("urn:kilnmark:lab-db:crm.customers", "customer_id:int64", "first_name:text", "last_name:text",
            "email:text", "phone:text", "date_of_birth:date", "postcode:text", "ni_number:text", "segment:text",
            "marketing_opt_in:boolean", "created_at:timestamp_tz");
        s.asset("urn:kilnmark:lab-db:billing.transactions", "txn_id:int64", "account_id:int64", "customer_id:int64",
            "amount:decimal", "currency:text", "txn_at:timestamp_tz", "merchant:text", "card_last4:text");
        s.asset("urn:kilnmark:lab-mariadb:stock.supplier_feed", "sku:text", "supplier:text", "cost_price:decimal",
            "lead_time_days:int16", "in_stock:boolean", "received_on:date");
        return s;
    }

    @Test
    void everyDemoPipelineChecksWithoutProblems() throws Exception {
        FakeSources sources = labSources();
        StepFactories steps = StepFactories.discover().register(new ReadStepFactory(sources))
            .register(new WriteStepFactory(sources)).register(new GateStepFactory(sources));
        List<Path> files;
        try (Stream<Path> list = Files.list(lab().resolve("pipelines"))) {
            files = list.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
        }
        assertFalse(files.isEmpty(), "lab/pipelines has the demo pipelines");
        for (Path file : files) {
            PipelineCompilerPort.Compilation c = new KilnPipelineCompiler().check(Files.readString(file), steps);
            assertEquals(List.of(), c.problems(), file.getFileName().toString());
            assertTrue(c.name().startsWith("demo-"), file + ": demo pipelines are named demo-...");
            for (PipelineCompilerPort.StepOutline step : c.steps()) {
                if (!"sink".equals(step.kind())) {
                    assertTrue(step.columns().get("out") != null, file + ": the columns leaving " + step.id()
                        + " are known");
                }
            }
        }
    }
}
