package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;
import io.kilnmark.kiln.plugins.documents.DocumentFixtures;
import io.kilnmark.kiln.plugins.documents.DocumentLimits;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.Extraction;

/** Documents and workbooks through the folder connector: catalogue, text, chunks, sheets as tables. */
class DocumentFilesTest {

    @TempDir
    static Path tmp;
    static Path docs;
    static FilesConnector files;
    static SourceConfig source;
    static Map<String, AssetInfo> assets;

    @BeforeAll
    static void catalogue() throws Exception {
        docs = Files.createDirectories(tmp.resolve("docs"));
        DocumentFixtures.all(docs);
        files = TestFiles.files(tmp);
        source = new SourceConfig("docs", "files", Map.of("path", docs.toString()), null);
        assets = files.catalog(source).stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    @AfterEach
    void resetLimits() {
        DocumentRuntime.configure(ConnectorContext.EMPTY);
    }

    static SourceConfig with(String key, String value) {
        Map<String, String> s = new HashMap<>(source.settings());
        s.put(key, value);
        return new SourceConfig(source.name(), source.kind(), s, null);
    }

    @Test
    void documentsAreReadableAndWorkbooksHaveSheets() {
        AssetInfo pdf = assets.get("policy.pdf");
        assertEquals("document", pdf.kind());
        assertTrue(pdf.readable());
        assertEquals("", pdf.container());

        AssetInfo orders = assets.get("budget.xlsx#Orders");
        assertEquals("sheet", orders.kind());
        assertEquals("budget.xlsx", orders.container());
        assertEquals("Orders", orders.name());
        assertEquals("budget.xlsx", orders.hints().get("parent"));
        assertEquals(Map.of("order_id", "int64", "customer", "text", "amount", "float64", "ordered_on", "date",
            "paid", "boolean"), TestFiles.kinds(orders));
        assertTrue(orders.readable());

        assertEquals(Map.of("sku", "text", "name", "text", "price", "float64"),
            TestFiles.kinds(assets.get("budget.xlsx#Products")));
        AssetInfo notes = assets.get("budget.xlsx#Notes");
        assertFalse(notes.readable());
        assertTrue(notes.description().startsWith("Not read as a table: its first row has fewer than two column "
            + "names"), notes.description());

        assertEquals(TestFiles.kinds(orders), TestFiles.kinds(assets.get("budget.xls#Orders")), "legacy .xls too");
        assertFalse(assets.get("budget.xls#Notes").readable());
    }

    @Test
    void sheetsAreReadAndProfiledAsTables() throws Exception {
        TestFiles.Read r = TestFiles.read(files, source, assets.get("budget.xlsx#Orders"), 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "20", "complete", "true", "unparsed", "0"), r.rowCount());
        assertEquals("2025-01-01", r.profile().get("ordered_on").get("min"));
        assertEquals("20", r.profile().get("customer").get("distinct"));

        TestFiles.Read limited = TestFiles.read(files, source, assets.get("budget.xls#Orders"), 5);
        assertEquals(Map.of("rows", "5", "complete", "false", "unparsed", "0"), limited.rowCount());
    }

    @Test
    void aPdfIsChunkedWithPagesAndProfiled() throws Exception {
        DocumentReads.Read r = DocumentReads.read(files, source, assets.get("policy.pdf"));
        assertTrue(r.result().succeeded(), r.result().error());
        Map<String, String> d = r.document();
        assertEquals("true", d.get(DocumentEvents.READABLE));
        assertEquals("application/pdf", d.get(DocumentEvents.CONTENT_TYPE));
        assertEquals("2", d.get(Extraction.PAGES));
        assertEquals("Remote working policy", d.get(Extraction.TITLE));
        assertEquals("Policy Team", d.get(Extraction.AUTHOR));
        assertEquals("en", d.get(DocumentEvents.LANGUAGE));
        assertEquals("pdf", d.get(DocumentEvents.FORMAT));
        assertTrue(Long.parseLong(d.get(DocumentEvents.WORDS)) > 30, d.toString());
        assertTrue(d.get(DocumentEvents.PAGE_LIST).contains("\"number\":2"), d.toString());
        assertFalse(r.chunks().isEmpty());
        assertEquals(Integer.valueOf(1), r.chunks().get(0).page());
        assertEquals(d.get(DocumentEvents.CHUNKS), Integer.toString(r.chunks().size()));
        assertTrue(r.chunks().stream().allMatch(c -> c.urn().equals("urn:kilnmark:docs:policy.pdf")));
    }

    @Test
    void keepTextOffKeepsTheProfileButNoText() throws Exception {
        DocumentReads.Read r = DocumentReads.read(files, with("keep_text", "false"), assets.get("onboarding.docx"));
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("Onboarding guide", r.document().get(Extraction.TITLE));
        assertEquals("false", r.document().get(DocumentEvents.TEXT_KEPT));
        assertTrue(Long.parseLong(r.document().get(DocumentEvents.WORDS)) > 10);
        assertEquals(List.of(), r.chunks());
        assertEquals(List.of(), files.validate(with("keep_text", "false")));
        assertEquals(List.of("keep_text: true or false, not 'maybe'"), files.validate(with("keep_text", "maybe")));
    }

    @Test
    void mailAttachmentsAreDocumentsOfTheirOwn() throws Exception {
        DocumentReads.Read r = DocumentReads.read(files, source, assets.get("supplier-query.eml"));
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(3, r.documents().size());
        Map<String, String> mail = r.document();
        assertEquals("Supplier query about lanterns", mail.get(Extraction.SUBJECT));
        Map<String, String> agenda = r.documents().get(1).attributes();
        String urn = "urn:kilnmark:docs:supplier-query.eml";
        assertEquals(urn + "#attachments/1/agenda.txt", r.documents().get(1).assetUrn());
        assertEquals(urn, agenda.get(DocumentEvents.PARENT));
        assertEquals("agenda.txt", agenda.get(DocumentEvents.NAME));
        Map<String, String> logo = r.documents().get(2).attributes();
        assertEquals("false", logo.get(DocumentEvents.READABLE));
        assertTrue(r.chunks().stream().anyMatch(c -> c.urn().endsWith("agenda.txt") && c.text().contains("Invoice")));
    }

    @Test
    void aDocumentOverTheLimitIsNotReadAndSaysWhy() throws Exception {
        Path big = Files.createDirectories(tmp.resolve("big"));
        Files.writeString(big.resolve("huge.txt"), "word ".repeat(300_000)); // 1.5 MB
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_FILE_MB, "1")));
        SourceConfig s = new SourceConfig("big", "files", Map.of("path", big.toString()), null);
        AssetInfo a = files.catalog(s).get(0);
        DocumentReads.Read r = DocumentReads.read(files, s, a);
        assertTrue(r.result().succeeded(), "one bad document never fails the read: " + r.result().error());
        assertEquals("false", r.document().get(DocumentEvents.READABLE));
        assertTrue(r.document().get(DocumentEvents.PROBLEM).contains("over the 1 MB limit"),
            r.document().get(DocumentEvents.PROBLEM));
        assertEquals(List.of(), r.chunks());
    }

    @Test
    void aDamagedDocumentIsNotReadableNotAFailure() throws Exception {
        Path bad = Files.createDirectories(tmp.resolve("bad"));
        Files.writeString(bad.resolve("broken.pdf"), "%PDF-1.4 this is not really a PDF");
        SourceConfig s = new SourceConfig("bad", "files", Map.of("path", bad.toString()), null);
        DocumentReads.Read r = DocumentReads.read(files, s, files.catalog(s).get(0));
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals("false", r.document().get(DocumentEvents.READABLE));
        assertNull(r.document().get(DocumentEvents.WORDS));
    }

    @Test
    void imagesAreRecordedNotRead() throws Exception {
        Path img = Files.createDirectories(tmp.resolve("img"));
        Files.write(img.resolve("photo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        SourceConfig s = new SourceConfig("img", "files", Map.of("path", img.toString()), null);
        AssetInfo a = files.catalog(s).get(0);
        assertEquals("document", a.kind());
        assertFalse(a.readable());
    }
}
