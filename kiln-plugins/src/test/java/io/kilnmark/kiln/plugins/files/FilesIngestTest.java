package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
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
    static Path tmp;

    static FilesConnector files;
    static Path share;
    static SourceConfig source;
    static Map<String, AssetInfo> assets;

    @BeforeAll
    static void catalogue() throws Exception {
        share = Files.createDirectories(tmp.resolve("share"));
        TestFiles.write(share);
        files = TestFiles.files(tmp, TestFiles.lab());
        source = new SourceConfig("share", "files", Map.of("path", share.toString()), null);
        assets = files.catalog(source).stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    /** A new folder inside the test's own temporary folder, and a source for it. */
    static SourceConfig folder(String name) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(name));
        return new SourceConfig(name, "files", Map.of("path", d.toString()), null);
    }

    static Path path(SourceConfig s) {
        return Path.of(s.setting("path"));
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
        assertEquals("UTF-8", csv.hints().get("encoding"));

        AssetInfo nd = assets.get("raw/transactions.ndjson");
        assertEquals(Map.of("txn_id", "int64", "customer_id", "int64", "amount", "decimal", "currency", "text",
            "txn_at", "timestamp_tz", "meta", "text"), TestFiles.kinds(nd));

        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "text", "paid", "boolean", "note", "text"),
            TestFiles.kinds(assets.get("raw/invoices.json")), "quoted numbers stay text");

        AssetInfo pq = assets.get("finance/invoices.parquet");
        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "text", "issued_on", "date", "total", "decimal",
            "paid", "boolean", "amount", "float64", "created_at", "timestamp_tz", "local_at", "timestamp",
            "qty", "int32", "address", "text"), TestFiles.kinds(pq));
        assertEquals("INT64 DECIMAL(10,2)", pq.fields().get(3).sourceType());
        assertEquals("1000", pq.hints().get("rows"));

        AssetInfo txt = assets.get("letters/complaint.txt");
        assertEquals("document", txt.kind());
        assertTrue(txt.readable(), "its text is read (DocumentFilesTest)");
        assertTrue(txt.fields().isEmpty());
        assertEquals(Long.toString(Files.size(share.resolve("letters/complaint.txt"))), txt.hints().get("size"));
        assertTrue(txt.hints().containsKey("modified"));
    }

    @Test
    void csvReadsEveryRowAndCountsTheValueThatDoesNotParse() throws Exception {
        TestFiles.Read r = TestFiles.read(files, source, assets.get("exports/customers.csv"), 0);
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
        TestFiles.Read r = TestFiles.read(files, source, assets.get("raw/transactions.ndjson"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "1999", "complete", "true", "unparsed", "0", "skipped", "1"), r.rowCount());
        assertEquals("number", r.profile().get("amount").get("shape"));
        assertTrue(r.profile().get("meta").get("top").contains("{\\\"channel\\\":\\\"card\\\",\\\"tags\\\":[1,2]}"),
            r.profile().get("meta").get("top"));
    }

    @Test
    void jsonArraysAreRead() throws Exception {
        TestFiles.Read r = TestFiles.read(files, source, assets.get("raw/invoices.json"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("500", r.rowCount().get("rows"));
        assertEquals("166", r.profile().get("paid").get("true"));
        assertEquals("50", r.profile().get("note").get("nulls"));
    }

    @Test
    void parquetIsReadWithItsOwnTypesAndNestedValuesAsJson() throws Exception {
        TestFiles.Read r = TestFiles.read(files, source, assets.get("finance/invoices.parquet"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "1000", "complete", "true", "unparsed", "0"), r.rowCount());
        assertEquals("40", r.profile().get("cust_ref").get("nulls"));
        assertEquals("0.01", r.profile().get("total").get("min"));
        assertEquals("10", r.profile().get("total").get("max"));
        assertEquals("500", r.profile().get("paid").get("true"));
        assertEquals("2025-01-01T00:00:01Z", r.profile().get("created_at").get("min"));
        assertEquals("2024-01-01", r.profile().get("issued_on").get("min"));
        assertEquals("99", r.profile().get("qty").get("max"));
        Map<String, String> address = r.profile().get("address");
        assertEquals("100", address.get("nulls"));
        assertTrue(address.get("top").contains("{\\\"city\\\":\\\"Leeds\\\",\\\"raw\\\":\\\"0xCAFE\\\"}"),
            "a struct is JSON, bytes are 0x hex: " + address.get("top"));
        assertTrue(address.get("top").contains("{\\\"city\\\":null,\\\"raw\\\":\\\"0xCAFE\\\"}"), address.get("top"));
        assertEquals("false", System.getProperty("hardwood.uselibdeflate"));
        assertEquals(Integer.toString(8 << 20), System.getProperty("hardwood.internal.sequentialChunkSize"));
    }

    @Test
    void aRowLimitReadsASampleAndSaysSo() throws Exception {
        for (String path : List.of("exports/customers.csv", "raw/transactions.ndjson", "raw/invoices.json",
            "finance/invoices.parquet")) {
            TestFiles.Read r = TestFiles.read(files, source, assets.get(path), 100);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("100", r.rowCount().get("rows"), path);
            assertEquals("false", r.rowCount().get("complete"), path);
        }
        TestFiles.Read all = TestFiles.read(files, source, assets.get("raw/invoices.json"), 500);
        assertEquals(Map.of("rows", "500", "complete", "true", "unparsed", "0"), all.rowCount(),
            "a limit equal to the row count reads everything");
    }

    @Test
    void anEmptyFileSendsNoRowsAndDoesNotFail() throws Exception {
        SourceConfig s = folder("empty");
        Files.writeString(path(s).resolve("empty.csv"), "");
        Files.writeString(path(s).resolve("header-only.csv"), "a,b\n");
        for (AssetInfo a : files.catalog(s)) {
            TestFiles.Read r = TestFiles.read(files, s, a, 0);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("0", r.rowCount().get("rows"), a.path());
        }
    }

    @Test
    void aFileThatChangedSinceTheCatalogueIsInferredAgain() throws Exception {
        SourceConfig s = folder("changed");
        Files.writeString(path(s).resolve("t.csv"), "a,b\n1,2\n");
        AssetInfo before = files.catalog(s).get(0);
        Files.writeString(path(s).resolve("t.csv"), "a;b;c\n1;x;2025-01-01\n");
        TestFiles.Read r = TestFiles.read(files, s, before, 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(3, r.profile().size());
        assertEquals("date", r.profile().get("c").get("shape"));
    }

    @Test
    void aMissingFileOrFolderFailsWithAClearMessage() throws Exception {
        AssetInfo gone = new AssetInfo("exports/gone.csv", "file", "exports", "gone.csv", null, List.of(), List.of(),
            false, true, Map.of("format", "csv"));
        TestFiles.Read r = TestFiles.read(files, source, gone, 0);
        assertEquals(RunResult.Status.FAILED, r.result().status());
        assertTrue(r.result().error().contains("no longer exists"), r.result().error());

        ConnectorException e = assertThrows(ConnectorException.class, () -> files.catalog(
            new SourceConfig("nope", "files", Map.of("path", tmp.resolve("nope").toString()), null)));
        assertTrue(e.getMessage().contains("does not exist on this server"), e.getMessage());
    }

    @Test
    void badParquetFilesAreRecordedNotFatal() throws Exception {
        SourceConfig s = folder("corrupt");
        Path d = path(s);
        Files.writeString(d.resolve("a-bad.parquet"), "PAR1 this is not parquet PAR1");
        ParquetTestWriter.writeDeeplyNested(d.resolve("b-deep.parquet"), 200_000);
        ParquetTestWriter.write(d.resolve("c-brotli.parquet"), List.of(ParquetTestWriter.Col.of("x",
            ParquetTestWriter.Type.INT64)), List.<Object[]>of(new Object[] {1L}), ParquetTestWriter.BROTLI);
        Files.write(d.resolve("d-huge-footer.parquet"), new byte[] {'P', 'A', 'R', '1', 0, 0, 0, 0, (byte) 0xFF,
            (byte) 0xFF, (byte) 0xFF, 0x7F, 'P', 'A', 'R', '1'});
        Files.writeString(d.resolve("e-good.csv"), "a\n1\n");
        List<AssetInfo> found = files.catalog(s);
        assertEquals(5, found.size());
        for (AssetInfo bad : found.subList(0, 4)) {
            assertFalse(bad.readable(), bad.path());
            assertTrue(bad.description().startsWith("Kilnmark could not read this parquet file"), bad.description());
        }
        assertTrue(found.get(1).description().contains("nested too deeply"), found.get(1).description());
        assertTrue(found.get(2).description().contains("BROTLI"), found.get(2).description());
        assertTrue(found.get(3).description().contains("footer length"), found.get(3).description());
        assertTrue(found.get(4).readable());
    }

    @Test
    void lz4IsAmongTheCodecsThisServerReads() {
        assertTrue(ParquetRuntime.supportedCodecs().contains(dev.hardwood.metadata.CompressionCodec.LZ4_RAW));
        assertTrue(ParquetRuntime.supportedCodecs().contains(dev.hardwood.metadata.CompressionCodec.ZSTD));
        assertFalse(ParquetRuntime.supportedCodecs().contains(dev.hardwood.metadata.CompressionCodec.BROTLI));
    }

    @Test
    void anApostropheAtTheStartOfAValueIsNotAQuote() throws Exception {
        SourceConfig s = folder("apostrophes");
        Files.writeString(path(s).resolve("phones.csv"),
            "name,phone\nann,'+44 7700 900001\nbob,'+44 7700 900002\ncat,07700 900003\n");
        Files.writeString(path(s).resolve("notes.csv"), "name,note\na,'tis fine\nb,ok\nc,rock 'n' roll\nd,end");
        Map<String, String> rows = Map.of("notes.csv", "4", "phones.csv", "3");
        for (AssetInfo a : files.catalog(s)) {
            assertEquals("\"", a.hints().get("quote"), a.path());
            TestFiles.Read r = TestFiles.read(files, s, a, 0);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals(rows.get(a.path()), r.rowCount().get("rows"), a.path());
        }
    }

    @Test
    void excelSeparatorLinesAndWindowsEncodingsAreHonoured() throws Exception {
        SourceConfig s = folder("excel");
        Charset cp1252 = Charset.forName("windows-1252");
        Files.write(path(s).resolve("eu.csv"), "sep=;\r\nname;city\r\nZoë;Málaga\r\nJosé;Cádiz\r\n".getBytes(cp1252));
        AssetInfo a = files.catalog(s).get(0);
        assertEquals(";", a.hints().get("delimiter"));
        assertEquals("true", a.hints().get("sep-line"));
        assertEquals("windows-1252", a.hints().get("encoding"), "not valid UTF-8, so windows-1252");
        assertEquals(List.of("name", "city"), a.fields().stream().map(AssetInfo.FieldInfo::name).toList());
        TestFiles.Read r = TestFiles.read(files, s, a, 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("2", r.rowCount().get("rows"));
        assertEquals("4", r.profile().get("name").get("max_length"), "Zoë and José decoded as 4 characters");

        // the encoding setting overrides detection
        SourceConfig latin9 = new SourceConfig("excel", "files", Map.of("path", path(s).toString(),
            "encoding", "ISO-8859-15"), null);
        assertEquals("ISO-8859-15", files.catalog(latin9).get(0).hints().get("encoding"));
        assertTrue(files.validate(new SourceConfig("x", "files", Map.of("path", path(s).toString(),
            "encoding", "klingon"), null)).get(0).startsWith("encoding:"));
    }

    @Test
    void overLongJsonLinesAreSkippedAndAFileOfThemIsUnreadable() throws Exception {
        SourceConfig s = folder("long-json");
        String huge = "{\"blob\":\"" + "x".repeat(1_600_000) + "\"}\n";
        Files.writeString(path(s).resolve("a-first-line-huge.ndjson"), huge + "{\"a\":1}\n");
        Files.writeString(path(s).resolve("b-middle-line-huge.ndjson"), "{\"a\":1}\n" + huge + "{\"a\":2}\n");
        List<AssetInfo> found = files.catalog(s);
        AssetInfo first = found.get(0);
        assertFalse(first.readable());
        assertTrue(first.description().contains("no complete JSON record"), first.description());
        AssetInfo middle = found.get(1);
        assertTrue(middle.readable());
        TestFiles.Read r = TestFiles.read(files, s, middle, 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "2", "complete", "true", "unparsed", "0", "skipped", "1"), r.rowCount());
    }

    @Test
    void theLabFilesReadWell() throws Exception {
        SourceConfig lab = new SourceConfig("lab", "files", Map.of("path", TestFiles.lab().toString()), null);
        Map<String, AssetInfo> found = files.catalog(lab).stream()
            .collect(Collectors.toMap(AssetInfo::path, Function.identity()));
        List<String> docs = List.of("2025-04-delivery-schedule.eml", "depot-budget-2025.xlsx",
            "depot-budget-2025.xlsx#Expenses", "depot-budget-2025.xlsx#Headcount", "depot-budget-2025.xlsx#Notes",
            "onboarding-guide.docx", "remote-working-policy.pdf", "team-handbook.md");
        List<String> listed = new java.util.ArrayList<>(List.of("bucket/exports/customers_export_2025.csv",
            "bucket/finance/invoices_2024_2025.parquet", "bucket/raw/transactions/transactions_2025-q1.ndjson",
            "share/complaints/2025-03-complaint-ref-4471.txt", "share/marketing/2024-q4-campaign-plan.md"));
        docs.forEach(d -> listed.add("bucket/documents/" + d));
        docs.forEach(d -> listed.add("share/documents/" + d));
        assertEquals(listed.stream().sorted().toList(), found.keySet().stream().sorted().toList());
        assertEquals(Map.of("month", "text", "depot", "text", "category", "text", "amount", "float64",
            "approved_on", "date", "approved", "boolean"),
            TestFiles.kinds(found.get("share/documents/depot-budget-2025.xlsx#Expenses")));
        assertEquals(Map.of("depot", "text", "team", "text", "staff", "int64", "contractors", "int64"),
            TestFiles.kinds(found.get("share/documents/depot-budget-2025.xlsx#Headcount")));
        assertFalse(found.get("share/documents/depot-budget-2025.xlsx#Notes").readable());

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
            TestFiles.Read r = TestFiles.read(files, lab, found.get(e.getKey()), 0);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals(Map.of("rows", e.getValue(), "complete", "true", "unparsed", "0"), r.rowCount(), e.getKey());
        }
    }
}
