package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

class FilesIngestTest {

    @TempDir
    static Path dir;

    static final FilesConnector FILES = new FilesConnector();
    static SourceConfig source;
    static Map<String, AssetInfo> assets;

    @BeforeAll
    static void catalogue() throws Exception {
        TestFiles.write(dir);
        source = new SourceConfig("share", "files", Map.of("path", dir.toString()), null);
        assets = FILES.catalog(source).stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    @Test
    void theRegistryFindsBothConnectors() {
        assertEquals("Files in a folder", Connectors.load().get("files").orElseThrow().displayName());
        assertEquals("S3 object storage", Connectors.load().get("s3").orElseThrow().displayName());
    }

    @Test
    void catalogueRecordsEveryFileWithItsFields() throws Exception {
        assertEquals(List.of("exports/customers.csv", "finance/invoices.parquet", "letters/complaint.txt",
            "raw/invoices.json", "raw/transactions.ndjson"), assets.keySet().stream().sorted().toList());

        AssetInfo csv = assets.get("exports/customers.csv");
        assertEquals("file", csv.kind());
        assertEquals("exports", csv.container());
        assertEquals("customers.csv", csv.name());
        assertTrue(csv.readable());
        assertFalse(csv.bounded());
        assertEquals(Map.of("customer_id", "int64", "email", "text", "phone", "text", "date_of_birth", "date",
            "balance", "decimal", "segment", "text", "created_at", "timestamp_tz"), TestFiles.kinds(csv));
        assertEquals("decimal(38,2)", csv.fields().get(4).sourceType());
        assertEquals(",", csv.hints().get("delimiter"));
        assertEquals("csv", csv.hints().get("format"));

        AssetInfo nd = assets.get("raw/transactions.ndjson");
        assertEquals(Map.of("txn_id", "int64", "customer_id", "int64", "amount", "decimal", "currency", "text",
            "txn_at", "timestamp_tz", "meta", "text"), TestFiles.kinds(nd));

        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "text", "paid", "boolean", "note", "text"),
            TestFiles.kinds(assets.get("raw/invoices.json")), "quoted numbers stay text");

        AssetInfo pq = assets.get("finance/invoices.parquet");
        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "text", "issued_on", "date", "total", "decimal",
            "paid", "boolean", "amount", "float64", "created_at", "timestamp_tz", "local_at", "timestamp",
            "qty", "int32"), TestFiles.kinds(pq));
        assertEquals("INT64 DECIMAL(10,2)", pq.fields().get(3).sourceType());
        assertEquals("1000", pq.hints().get("rows"));

        AssetInfo txt = assets.get("letters/complaint.txt");
        assertEquals("document", txt.kind());
        assertFalse(txt.readable());
        assertTrue(txt.fields().isEmpty());
        assertEquals(Long.toString(Files.size(dir.resolve("letters/complaint.txt"))),
            txt.hints().get("size"));
        assertTrue(txt.hints().containsKey("modified"));
    }

    @Test
    void csvReadsEveryRowAndCountsTheValueThatDoesNotParse() throws Exception {
        TestFiles.Read r = TestFiles.read(FILES, source, assets.get("exports/customers.csv"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(TestFiles.CSV_ROWS, r.result().steps().get(0).rowsOut());
        assertEquals(Map.of("rows", "3000", "complete", "true", "unparsed", "1"), r.rowCount());
        assertEquals("1", r.profile().get("balance").get("nulls"), "the 'n/a' balance is read as null");
        assertEquals("60", r.profile().get("email").get("nulls"));
        assertEquals("2", r.profile().get("segment").get("distinct"), "a quoted comma stays in the value");
        assertEquals("1950-01-02", r.profile().get("date_of_birth").get("min"));
        assertEquals("2023-12-31T23:00:00Z", r.profile().get("created_at").get("min"));
    }

    @Test
    void jsonLinesSkipsTheMalformedLine() throws Exception {
        TestFiles.Read r = TestFiles.read(FILES, source, assets.get("raw/transactions.ndjson"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "1999", "complete", "true", "unparsed", "0", "skipped", "1"), r.rowCount());
        assertEquals("number", r.profile().get("amount").get("shape"));
        assertTrue(r.profile().get("meta").get("top").contains("{\\\"channel\\\":\\\"card\\\",\\\"tags\\\":[1,2]}"),
            r.profile().get("meta").get("top"));
    }

    @Test
    void jsonArraysAreRead() throws Exception {
        TestFiles.Read r = TestFiles.read(FILES, source, assets.get("raw/invoices.json"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("500", r.rowCount().get("rows"));
        assertEquals("166", r.profile().get("paid").get("true"));
        assertEquals("50", r.profile().get("note").get("nulls"));
    }

    @Test
    void parquetIsReadWithItsOwnTypes() throws Exception {
        TestFiles.Read r = TestFiles.read(FILES, source, assets.get("finance/invoices.parquet"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "1000", "complete", "true", "unparsed", "0"), r.rowCount());
        assertEquals("40", r.profile().get("cust_ref").get("nulls"));
        assertEquals("0.01", r.profile().get("total").get("min"));
        assertEquals("10", r.profile().get("total").get("max"));
        assertEquals("500", r.profile().get("paid").get("true"));
        assertEquals("2025-01-01T00:00:01Z", r.profile().get("created_at").get("min"));
        assertEquals("2024-01-01", r.profile().get("issued_on").get("min"));
        assertEquals("99", r.profile().get("qty").get("max"));
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        for (String path : List.of("exports/customers.csv", "raw/transactions.ndjson", "raw/invoices.json",
            "finance/invoices.parquet")) {
            TestFiles.Read r = TestFiles.read(FILES, source, assets.get(path), 100);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("100", r.rowCount().get("rows"), path);
            assertEquals("false", r.rowCount().get("complete"), path);
        }
        TestFiles.Read all = TestFiles.read(FILES, source, assets.get("raw/invoices.json"), 500);
        assertEquals(Map.of("rows", "500", "complete", "true", "unparsed", "0"), all.rowCount(),
            "a limit equal to the row count reads everything");
    }

    @Test
    void anEmptyFileSendsNoRowsAndDoesNotFail() throws Exception {
        Path empty = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-empty"));
        Files.writeString(empty.resolve("empty.csv"), "");
        Files.writeString(empty.resolve("header-only.csv"), "a,b\n");
        SourceConfig s = new SourceConfig("empty", "files", Map.of("path", empty.toString()), null);
        for (AssetInfo a : FILES.catalog(s)) {
            TestFiles.Read r = TestFiles.read(FILES, s, a, 0);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("0", r.rowCount().get("rows"), a.path());
        }
    }

    @Test
    void aFileThatChangedSinceTheCatalogueIsInferredAgain() throws Exception {
        Path d = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-changed"));
        Files.writeString(d.resolve("t.csv"), "a,b\n1,2\n");
        SourceConfig s = new SourceConfig("changed", "files", Map.of("path", d.toString()), null);
        AssetInfo before = FILES.catalog(s).get(0);
        Files.writeString(d.resolve("t.csv"), "a;b;c\n1;x;2025-01-01\n");
        TestFiles.Read r = TestFiles.read(FILES, s, before, 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(3, r.profile().size());
        assertEquals("date", r.profile().get("c").get("shape"));
    }

    @Test
    void aMissingFileOrFolderFailsWithAClearMessage() throws Exception {
        AssetInfo gone = new AssetInfo("exports/gone.csv", "file", "exports", "gone.csv", null, List.of(), List.of(),
            false, true, Map.of("format", "csv"));
        TestFiles.Read r = TestFiles.read(FILES, source, gone, 0);
        assertEquals(RunResult.Status.FAILED, r.result().status());
        assertTrue(r.result().error().contains("no longer exists"), r.result().error());

        ConnectorException e = assertThrows(ConnectorException.class, () -> FILES.catalog(
            new SourceConfig("nope", "files", Map.of("path", dir.resolve("nope").toString()), null)));
        assertTrue(e.getMessage().contains("does not exist on this server"), e.getMessage());
    }

    @Test
    void aCorruptParquetFileIsRecordedNotFatal() throws Exception {
        Path d = Files.createDirectories(dir.resolveSibling(dir.getFileName() + "-corrupt"));
        Files.writeString(d.resolve("bad.parquet"), "PAR1 this is not parquet PAR1");
        Files.writeString(d.resolve("good.csv"), "a\n1\n");
        List<AssetInfo> found = FILES.catalog(new SourceConfig("c", "files", Map.of("path", d.toString()), null));
        assertEquals(2, found.size());
        AssetInfo bad = found.get(0);
        assertFalse(bad.readable());
        assertTrue(bad.description().startsWith("Kilnmark could not read this parquet file"), bad.description());
        assertTrue(found.get(1).readable());
    }

    @Test
    void theLabFilesReadWell() throws Exception {
        SourceConfig lab = new SourceConfig("lab", "files", Map.of("path", TestFiles.lab().toString()), null);
        Map<String, AssetInfo> found = FILES.catalog(lab).stream()
            .collect(Collectors.toMap(AssetInfo::path, Function.identity()));
        assertEquals(List.of("bucket/exports/customers_export_2025.csv", "bucket/finance/invoices_2024_2025.parquet",
            "bucket/raw/transactions/transactions_2025-q1.ndjson", "share/complaints/2025-03-complaint-ref-4471.txt",
            "share/marketing/2024-q4-campaign-plan.md"), found.keySet().stream().sorted().toList());

        AssetInfo csv = found.get("bucket/exports/customers_export_2025.csv");
        assertEquals(Map.of("customer_id", "int64", "first_name", "text", "last_name", "text", "email", "text",
            "phone", "text", "date_of_birth", "date", "postcode", "text", "segment", "text",
            "created_at", "timestamp_tz"), TestFiles.kinds(csv));
        AssetInfo nd = found.get("bucket/raw/transactions/transactions_2025-q1.ndjson");
        assertEquals("decimal", TestFiles.kinds(nd).get("amount"));
        assertEquals("timestamp_tz", TestFiles.kinds(nd).get("txn_at"));
        AssetInfo pq = found.get("bucket/finance/invoices_2024_2025.parquet");
        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "int64", "issued_on", "date", "total", "float64",
            "paid", "text"), TestFiles.kinds(pq));
        assertEquals("document", found.get("share/marketing/2024-q4-campaign-plan.md").kind());

        Map<String, String> expected = Map.of(csv.path(), "2000", nd.path(), "5000", pq.path(), "20000");
        for (var e : expected.entrySet()) {
            TestFiles.Read r = TestFiles.read(FILES, lab, found.get(e.getKey()), 0);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals(Map.of("rows", e.getValue(), "complete", "true", "unparsed", "0"), r.rowCount(), e.getKey());
        }
    }
}
