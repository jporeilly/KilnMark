package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileStep;

/** Generated test files, and reading an asset through the engine the way a scan does. */
final class TestFiles {

    static final int CSV_ROWS = 3000;
    static final int NDJSON_LINES = 2000;
    static final int JSON_ROWS = 500;
    static final int PARQUET_ROWS = 1000;

    private TestFiles() {
    }

    /** The lab's sample files, in the repository. */
    static Path lab() {
        Path p = Path.of("..", "lab", "files").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(p), "lab files not found at " + p);
        return p;
    }

    /**
     * Writes into {@code dir}: {@code exports/customers.csv} (with a byte-order mark, and one
     * unparseable balance after the sampled rows), {@code raw/transactions.ndjson} (with one
     * malformed line and a nested object), {@code raw/invoices.json} (a JSON array),
     * {@code finance/invoices.parquet} and {@code letters/complaint.txt}.
     */
    static void write(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("exports"));
        Files.createDirectories(dir.resolve("raw"));
        Files.createDirectories(dir.resolve("finance"));
        Files.createDirectories(dir.resolve("letters"));

        StringBuilder csv = new StringBuilder("﻿customer_id,email,phone,date_of_birth,balance,segment,created_at\r\n");
        for (int i = 1; i <= CSV_ROWS; i++) {
            csv.append(i).append(',')
                .append(i % 50 == 0 ? "" : "person" + i + "@example.com").append(',')
                .append(String.format("07700 9%05d", i)).append(',')
                .append(LocalDate.of(1950, 1, 1).plusDays(i)).append(',')
                .append(i == 2500 ? "n/a" : (i % 1000) + "." + (i % 100 < 10 ? "0" : "") + (i % 100)).append(',')
                .append(i % 4 == 0 ? "\"Business, SME\"" : "Retail").append(',')
                .append("2024-01-01 00:00:00+01").append("\r\n");
        }
        Files.writeString(dir.resolve("exports/customers.csv"), csv, StandardCharsets.UTF_8);

        StringBuilder nd = new StringBuilder();
        for (int i = 1; i <= NDJSON_LINES; i++) {
            if (i == 1500) {
                nd.append("{this is not json\n");
                continue;
            }
            nd.append("{\"txn_id\":").append(i).append(",\"customer_id\":").append(1000 + i % 300)
                .append(",\"amount\":").append(i % 7).append('.').append(10 + i % 90)
                .append(",\"currency\":\"GBP\",\"txn_at\":\"2025-09-26T09:56:08.177518+01:00\"")
                .append(",\"meta\":{\"channel\":\"card\",\"tags\":[1,2]}}\n");
        }
        Files.writeString(dir.resolve("raw/transactions.ndjson"), nd);

        StringBuilder json = new StringBuilder("[\n");
        for (int i = 1; i <= JSON_ROWS; i++) {
            json.append("  {\"invoice_id\": ").append(i).append(", \"cust_ref\": \"").append(100 + i)
                .append("\", \"paid\": ").append(i % 3 == 0).append(", \"note\": ")
                .append(i % 10 == 0 ? "null" : "\"ok\"").append('}').append(i < JSON_ROWS ? ",\n" : "\n");
        }
        Files.writeString(dir.resolve("raw/invoices.json"), json.append("]\n"));

        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= PARQUET_ROWS; i++) {
            rows.add(new Object[] {(long) i, i % 25 == 0 ? null : Integer.toString(98 + i),
                LocalDate.of(2024, 1, 1).plusDays(i % 600), new BigDecimal(i).movePointLeft(2), i % 2 == 0,
                i * 1.5, Instant.parse("2025-01-01T00:00:00Z").plusSeconds(i), LocalDateTime.of(2025, 1, 1, 12, 0),
                i % 100});
        }
        ParquetTestWriter.write(dir.resolve("finance/invoices.parquet"), List.of(
            ParquetTestWriter.Col.of("invoice_id", ParquetTestWriter.Type.INT64),
            ParquetTestWriter.Col.of("cust_ref", ParquetTestWriter.Type.STRING),
            ParquetTestWriter.Col.of("issued_on", ParquetTestWriter.Type.DATE),
            new ParquetTestWriter.Col("total", ParquetTestWriter.Type.DECIMAL, 10, 2),
            ParquetTestWriter.Col.of("paid", ParquetTestWriter.Type.BOOLEAN),
            ParquetTestWriter.Col.of("amount", ParquetTestWriter.Type.DOUBLE),
            ParquetTestWriter.Col.of("created_at", ParquetTestWriter.Type.TIMESTAMP_UTC),
            ParquetTestWriter.Col.of("local_at", ParquetTestWriter.Type.TIMESTAMP_LOCAL),
            ParquetTestWriter.Col.of("qty", ParquetTestWriter.Type.INT32)), rows);

        Files.writeString(dir.resolve("letters/complaint.txt"),
            "From: Ella Martin <ella.martin2@example.net>, telephone 07700 900002\n");
    }

    /** What reading one asset produced. */
    record Read(RunResult result, Map<String, String> rowCount, Map<String, Map<String, String>> profile) {
    }

    /** Reads an asset as a scan does: the connector's reader, then the profiler. */
    static Read read(Connector connector, SourceConfig source, AssetInfo asset, long rowLimit)
        throws InterruptedException {
        String urn = "urn:kilnmark:" + source.name() + ":" + asset.path();
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        StepSpec reader = connector.reader(source, asset, new Connector.ReadOptions(urn, rowLimit, 1024));
        RunResult r = new Runner(64L << 20, 4).run(new Pipeline("read", List.of(reader,
            new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", urn)))), events::add);
        Map<String, String> rows = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT)
            .map(MetadataEvent::attributes).findFirst().orElse(Map.of());
        Map<String, Map<String, String>> profile = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> profile.put(e.attributes().get("field"), e.attributes()));
        return new Read(r, rows, profile);
    }

    static Map<String, String> kinds(AssetInfo a) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        a.fields().forEach(f -> out.put(f.name(), f.kind()));
        return out;
    }
}
