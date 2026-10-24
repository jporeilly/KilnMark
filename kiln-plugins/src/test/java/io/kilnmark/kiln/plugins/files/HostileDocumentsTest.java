package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentFixtures;
import io.kilnmark.kiln.plugins.documents.DocumentLimits;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.Extraction;
import io.kilnmark.kiln.plugins.documents.TextExtractor;

/**
 * Hostile files, made here at run time (after the review's probes): external entities and network
 * references in every XML-based format, entity expansion, archives that lie about their parts'
 * sizes, rows wider than Excel allows, zip bombs on the sheet reader, and names carrying control
 * characters. None may leak a file of the server's, reach the network, exhaust memory or fail a
 * catalogue.
 */
class HostileDocumentsTest {

    static final String SECRET = "SECRET-HOSTFILE-8c1f";

    @TempDir
    static Path dir;
    static Path secret;
    static ServerSocket listener;
    static final AtomicInteger connections = new AtomicInteger();
    static String net;

    @BeforeAll
    static void start() throws IOException {
        secret = Files.writeString(dir.resolve("secret.txt"), SECRET);
        listener = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        net = "http://127.0.0.1:" + listener.getLocalPort() + "/";
        Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try {
                    listener.accept().close();
                    connections.incrementAndGet();
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    @AfterAll
    static void stop() throws IOException {
        listener.close();
    }

    @AfterEach
    void resetLimits() {
        DocumentRuntime.configure(ConnectorContext.EMPTY);
    }

    /** A DOCTYPE declaring an entity that reads the secret file and one fetched from the network. */
    static String dtd(String root) {
        return "<?xml version=\"1.0\"?>\n<!DOCTYPE " + root + " [<!ENTITY xxe SYSTEM \"" + secret.toUri()
            + "\"><!ENTITY % p SYSTEM \"" + net + root + ".dtd\"> %p;]>\n";
    }

    static final String CT = "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/"
        + "content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package."
        + "relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>";
    static final String NS = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
    static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    static Path docx(String name, String document) throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("[Content_Types].xml", (CT + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd."
            + "openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>").getBytes());
        e.put("_rels/.rels", ("<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/"
            + "2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\"word/"
            + "document.xml\"/></Relationships>").getBytes());
        e.put("word/document.xml", document.getBytes(StandardCharsets.UTF_8));
        return DocumentFixtures.zip(dir.resolve(name), e);
    }

    /** A one-sheet workbook; {@code sheet} writes the sheet's XML, {@code sst} the shared strings (or null). */
    static Path xlsx(String name, String workbook, Writer sheet, Writer sst) throws IOException {
        return xlsx(name, workbook, sheet, sst, "sharedStrings.xml", 0);
    }

    /**
     * @param sstName shared strings part under xl/, named in the content types and relationships
     * @param dummies small entries written before everything else
     */
    static Path xlsx(String name, String workbook, Writer sheet, Writer sst, String sstName, int dummies)
        throws IOException {
        Path p = dir.resolve(name);
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (int i = 0; i < dummies; i++) {
                put(z, "docProps/d" + i + ".txt", "x");
            }
            put(z, "[Content_Types].xml", CT + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd."
                + "openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/"
                + "sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+"
                + "xml\"/>" + (sst == null ? "" : "<Override PartName=\"/xl/" + sstName + "\" ContentType=\""
                + "application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>") + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/"
                + "package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + R + "/officeDocument\" Target=\""
                + "xl/workbook.xml\"/></Relationships>");
            put(z, "xl/workbook.xml", workbook != null ? workbook : "<?xml version=\"1.0\"?><workbook " + NS
                + " xmlns:r=\"" + R + "\"><sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                + "</workbook>");
            put(z, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas."
                + "openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"" + R + "/worksheet\""
                + " Target=\"worksheets/sheet1.xml\"/>" + (sst == null ? "" : "<Relationship Id=\"rId2\" Type=\"" + R
                + "/sharedStrings\" Target=\"" + sstName + "\"/>") + "</Relationships>");
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            sheet.write(z);
            z.closeEntry();
            if (sst != null) {
                z.putNextEntry(new ZipEntry("xl/" + sstName));
                sst.write(z);
                z.closeEntry();
            }
        }
        return p;
    }

    @FunctionalInterface
    interface Writer {
        void write(OutputStream out) throws IOException;
    }

    static Writer text(String s) {
        return out -> out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    static void put(ZipOutputStream z, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    static final String SHEET_OK = "<?xml version=\"1.0\"?><worksheet " + NS + "><sheetData><row r=\"1\"><c r=\"A1\" "
        + "t=\"inlineStr\"><is><t>a</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>b</t></is></c></row><row r=\"2\">"
        + "<c r=\"A2\"><v>1</v></c><c r=\"B2\"><v>2</v></c></row></sheetData></worksheet>";

    /** Reads a file as a scan would; returns its text, or the reason it was refused. */
    static String read(Path p) {
        try {
            long size = Files.size(p);
            Extraction x = DocumentRuntime.run(p.toString(), c -> TextExtractor.extract(p, p.getFileName().toString(),
                size, DocumentRuntime.limits(), c));
            StringBuilder all = new StringBuilder(x.document().text()).append(x.document().properties());
            x.attachments().forEach(a -> all.append(a.text()).append(a.name()));
            return all.toString();
        } catch (DocumentException e) {
            return "refused: " + e.getMessage();
        } catch (InterruptedException | IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void noFormatReadsALocalFileOrTheNetwork() throws Exception {
        String odfContent = dtd("office:document-content") + "<office:document-content xmlns:office=\"urn:oasis:"
            + "names:tc:opendocument:xmlns:office:1.0\" xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\">"
            + "<office:body><office:text><text:p>odt &xxe; text</text:p></office:text></office:body>"
            + "</office:document-content>";
        Map<String, byte[]> odt = new LinkedHashMap<>();
        odt.put("mimetype", "application/vnd.oasis.opendocument.text".getBytes());
        odt.put("content.xml", odfContent.getBytes());
        Map<String, byte[]> epub = new LinkedHashMap<>();
        epub.put("mimetype", "application/epub+zip".getBytes());
        epub.put("META-INF/container.xml", ("<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:"
            + "tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\""
            + "application/oebps-package+xml\"/></rootfiles></container>").getBytes());
        epub.put("OEBPS/content.opf", (dtd("package") + "<package xmlns=\"http://www.idpf.org/2007/opf\" version="
            + "\"2.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>E &xxe;</dc:title></metadata>"
            + "<manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine>"
            + "<itemref idref=\"c1\"/></spine></package>").getBytes());
        epub.put("OEBPS/c1.xhtml", (dtd("html") + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>epub &xxe; "
            + "body</p></body></html>").getBytes());
        String attachment = Base64.getMimeEncoder().encodeToString((dtd("r") + "<r>att &xxe;</r>").getBytes());
        List<Path> files = List.of(
            DocumentFixtures.write(dir.resolve("xxe.xml"), dtd("r") + "<r>before &xxe; after</r>"),
            DocumentFixtures.write(dir.resolve("xxe.xhtml"), dtd("html") + "<html xmlns=\"http://www.w3.org/1999/"
                + "xhtml\"><body><p>x &xxe; y</p></body></html>"),
            DocumentFixtures.write(dir.resolve("xxe.html"), "<!DOCTYPE html [<!ENTITY xxe SYSTEM \"" + secret.toUri()
                + "\">]><html><body><p>x &xxe; y</p><script>alert(1)</script><img src=\"" + net + "img.png\"></body>"
                + "</html>"),
            docx("xxe.docx", dtd("w:document") + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/"
                + "wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>hello &xxe; world</w:t></w:r></w:p></w:body>"
                + "</w:document>"),
            xlsx("xxe-sst.xlsx", null, text(SHEET_OK.replace("t=\"inlineStr\"><is><t>a</t></is>", "t=\"s\"><v>0</v>")),
                text(dtd("sst") + "<sst " + NS + " count=\"1\" uniqueCount=\"1\"><si><t>name &xxe;</t></si></sst>")),
            xlsx("xxe-sheet.xlsx", null, text(dtd("worksheet") + SHEET_OK.substring(SHEET_OK.indexOf("<worksheet"))
                .replace("<t>a</t>", "<t>a &xxe;</t>")), null),
            xlsx("xxe-wb.xlsx", dtd("workbook") + "<workbook " + NS + " xmlns:r=\"" + R + "\"><sheets><sheet name=\""
                + "D &xxe;\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>", text(SHEET_OK), null),
            DocumentFixtures.zip(dir.resolve("xxe.odt"), odt),
            DocumentFixtures.zip(dir.resolve("xxe.epub"), epub),
            DocumentFixtures.write(dir.resolve("xxe.eml"), "From: a@example.com\nTo: b@example.com\nSubject: test\n"
                + "MIME-Version: 1.0\nContent-Type: multipart/mixed; boundary=\"B\"\n\n--B\nContent-Type: text/html; "
                + "charset=utf-8\n\n<html><body><p>Hello</p><img src=\"" + net + "pixel\"></body></html>\n--B\n"
                + "Content-Type: application/xml; name=\"a.xml\"\nContent-Disposition: attachment; filename=\"a.xml\"\n"
                + "Content-Transfer-Encoding: base64\n\n" + attachment + "\n--B--\n"),
            xmpPdf(dir.resolve("xmp.pdf")));
        for (Path f : files) {
            String got = read(f);
            assertFalse(got.contains(SECRET), f.getFileName() + " leaked the file: " + got);
        }
        Thread.sleep(200);
        assertEquals(0, connections.get(), "no document may make the server connect anywhere");

        String html = read(dir.resolve("xxe.html"));
        assertFalse(html.contains("]>"), "a DOCTYPE's internal subset is not text: " + html);
        assertFalse(html.contains("alert"), html);
        assertFalse(read(dir.resolve("xxe.xhtml")).contains("%p;"));
    }

    /** A PDF whose XMP metadata declares an entity reading the secret file. */
    static Path xmpPdf(Path p) throws IOException {
        String body = "BT /F1 12 Tf 72 720 Td (Hello pdf) Tj ET";
        String xmp = "<?xml version=\"1.0\"?><!DOCTYPE x:xmpmeta [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
            + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
            + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title><rdf:Alt>"
            + "<rdf:li xml:lang=\"x-default\">T &xxe;</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF>"
            + "</x:xmpmeta>";
        List<String> objects = List.of("<< /Type /Catalog /Pages 2 0 R /Metadata 5 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 6 0 R >> "
                + ">> >>",
            "<< /Length " + body.length() + " >>\nstream\n" + body + "\nendstream",
            "<< /Type /Metadata /Subtype /XML /Length " + xmp.length() + " >>\nstream\n" + xmp + "\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
        StringBuilder pdf = new StringBuilder("%PDF-1.7\n");
        List<Integer> offsets = new java.util.ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.length());
            pdf.append(i + 1).append(" 0 obj\n").append(objects.get(i)).append("\nendobj\n");
        }
        int xref = pdf.length();
        pdf.append("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        offsets.forEach(o -> pdf.append(String.format("%010d 00000 n \n", o)));
        pdf.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
            .append("\n%%EOF\n");
        return Files.writeString(p, pdf.toString(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void entityExpansionIsRefused() throws Exception {
        StringBuilder lol = new StringBuilder("<?xml version=\"1.0\"?>\n<!DOCTYPE lolz [<!ENTITY lol \"lol\">");
        for (int i = 1; i < 10; i++) {
            lol.append("<!ENTITY lol").append(i).append(" \"").append((i > 1 ? "&lol" + (i - 1) + ";" : "&lol;")
                .repeat(10)).append("\">");
        }
        lol.append("]>\n<lolz>&lol9;</lolz>");
        String got = read(DocumentFixtures.write(dir.resolve("lol.xml"), lol.toString()));
        assertTrue(got.startsWith("refused:"), got);
    }

    /** Sets the declared uncompressed size of {@code entry}, in its local header and the central directory. */
    static void lie(Path zip, String entry, int size) throws IOException {
        byte[] b = Files.readAllBytes(zip);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        byte[] name = entry.getBytes(StandardCharsets.UTF_8);
        int patched = 0;
        for (int i = 0; i + 46 < b.length; i++) {
            int sig = buf.getInt(i);
            if (sig == 0x04034b50 && matches(b, i + 30, buf.getShort(i + 26), name)) {
                buf.putInt(i + 22, size);
                patched++;
            } else if (sig == 0x02014b50 && matches(b, i + 46, buf.getShort(i + 28), name)) {
                buf.putInt(i + 24, size);
                patched++;
            }
        }
        assertEquals(2, patched);
        Files.write(zip, b);
    }

    private static boolean matches(byte[] b, int at, int length, byte[] name) {
        if (length != name.length || at + length > b.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (b[at + i] != name[i]) {
                return false;
            }
        }
        return true;
    }

    /** Shared strings of about {@code mb} MB once expanded. */
    static Writer manyStrings(int mb) {
        return out -> {
            out.write(("<?xml version=\"1.0\"?><sst " + NS + ">").getBytes());
            java.util.Random r = new java.util.Random(2);
            long written = 0;
            while (written < ((long) mb << 20)) {
                byte[] si = ("<si><t>w" + r.nextInt(1_000_000_000) + "</t></si>").getBytes();
                out.write(si);
                written += si.length;
            }
            out.write("</sst>".getBytes());
        };
    }

    @Test
    void aPartThatLiesAboutItsSizeIsRefusedBeforeAnyParserHoldsIt() throws Exception {
        FilesConnector files = TestFiles.files(dir); // before the limit: setting up a connector sets the limits
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_PART_MB, "2")));
        Path p = xlsx("liar.xlsx", null, text(SHEET_OK.replace("t=\"inlineStr\"><is><t>a</t></is>",
            "t=\"s\"><v>0</v>")), manyStrings(5));
        lie(p, "xl/sharedStrings.xml", 1000);
        String got = read(p);
        assertTrue(got.startsWith("refused: its part xl/sharedStrings.xml expands to more than 2 MB"), got);
        IOException e = assertThrows(IOException.class, () -> Sheets.open(p, "liar.xlsx"));
        assertTrue(e.getMessage().contains("more than 2 MB"), e.getMessage());

        // and through the catalogue: no sheets this time, with the reason
        Path folder = Files.createDirectories(dir.resolve("liar"));
        Files.copy(p, folder.resolve("liar.xlsx"));
        List<AssetInfo> found = files.catalog(new SourceConfig("liar", "files", Map.of("path", folder.toString()),
            null));
        assertEquals(1, found.size());
        assertTrue(found.get(0).hints().get("sheets-problem").contains("more than 2 MB"));
    }

    @Test
    void aRowWiderThanExcelAllowsStopsTheSheet() throws Exception {
        Path p = xlsx("wide.xlsx", null, out -> {
            out.write(("<?xml version=\"1.0\"?><worksheet " + NS + "><sheetData><row r=\"1\"><c t=\"inlineStr\"><is>"
                + "<t>a</t></is></c><c t=\"inlineStr\"><is><t>b</t></is></c></row><row r=\"2\">").getBytes());
            for (int i = 0; i < 20_000; i++) {
                out.write(("<c><v>" + i + "</v></c>").getBytes());
            }
            out.write("</row></sheetData></worksheet>".getBytes());
        }, null);
        try (Sheets.Workbook wb = Sheets.open(p, "wide.xlsx")) {
            IOException e = assertThrows(IOException.class, () -> Sheets.inspect(wb, 1000));
            assertTrue(e.getMessage().contains("more than 16,384 cells"), e.getMessage());
        }
    }

    @Test
    void aZipBombSheetIsRefusedOnTheSheetPath() throws Exception {
        byte[] row = "<row><c><v>1</v></c></row>".repeat(10_000).getBytes();
        Path p = xlsx("bomb.xlsx", null, out -> {
            out.write(("<?xml version=\"1.0\"?><worksheet " + NS + "><sheetData>").getBytes());
            for (int i = 0; i < 400; i++) {
                out.write(row); // 100 MB of rows, a few hundred KB compressed
            }
            out.write("</sheetData></worksheet>".getBytes());
        }, null);
        assertTrue(Files.size(p) < 2_000_000, "small on disk: " + Files.size(p));
        try (Sheets.Workbook wb = Sheets.open(p, "bomb.xlsx"); Sheets.SheetRows rows = wb.rows("Data")) {
            // sampling stops after a few rows; reading the whole sheet is refused once it expands too far
            IOException e = assertThrows(IOException.class, () -> {
                while (rows.next() != null) {
                    // read on
                }
            });
            assertTrue(e.getMessage().toLowerCase().contains("zip bomb"), e.getMessage());
        }
    }

    @Test
    void controlCharactersInNamesAreReplaced() throws Exception {
        Path xls = dir.resolve("nul.xls");
        try (var wb = new org.apache.poi.hssf.usermodel.HSSFWorkbook()) {
            var s = wb.createSheet("QQZZ1");
            var r = s.createRow(0);
            r.createCell(0).setCellValue("id");
            r.createCell(1).setCellValue("name");
            var r2 = s.createRow(1);
            r2.createCell(0).setCellValue(1);
            r2.createCell(1).setCellValue("x");
            try (var out = Files.newOutputStream(xls)) {
                wb.write(out);
            }
        }
        byte[] b = Files.readAllBytes(xls);
        int n = 0;
        for (int i = 0; i + 5 <= b.length; i++) {
            if (b[i] == 'Q' && b[i + 1] == 'Q' && b[i + 2] == 'Z' && b[i + 3] == 'Z' && b[i + 4] == '1') {
                b[i + 2] = 0; // a NUL in the sheet's name, which PostgreSQL cannot store
                b[i + 3] = '#';
                n++;
            }
        }
        assertTrue(n > 0);
        Files.write(xls, b);
        try (Sheets.Workbook wb = Sheets.open(xls, "nul.xls")) {
            assertEquals(List.of("QQ_#1"), wb.sheetNames());
            assertEquals(1, Sheets.inspect(wb, 10).size());
        }

        String eml = "From: a@example.com\nTo: b@example.com\nSubject: names\nMIME-Version: 1.0\nContent-Type: "
            + "multipart/mixed; boundary=\"B\"\n\n--B\nContent-Type: text/plain\n\nbody text\n--B\nContent-Type: "
            + "text/plain\nContent-Disposition: attachment; filename*=UTF-8''a%E2%80%AEtxt.exe\n\n"
            + "payload\n--B\nContent-Type: text/plain\nContent-Disposition: attachment; filename=\"../../x#y\u0001.txt\""
            + "\n\nsecond\n--B--\n";
        Path mail = Files.write(dir.resolve("names.eml"), eml.getBytes(StandardCharsets.UTF_8));
        Extraction x = TextExtractor.extract(mail, "names.eml", -1, DocumentLimits.DEFAULTS, new AtomicBoolean());
        assertEquals(List.of("a_txt.exe", "x#y_.txt"), x.attachments().stream().map(Extraction.Part::name).toList());
        assertEquals("urn:kilnmark:s:m.eml#attachments/2/x%23y_.txt",
            DocumentInput.attachmentUrn("urn:kilnmark:s:m.eml", 2, x.attachments().get(1).name()));
    }

    @Test
    void fileNamesWithHashesCannotPassForSheetsOrAttachments() throws Exception {
        Path folder = Files.createDirectories(dir.resolve("hashes"));
        DocumentFixtures.xlsx(folder.resolve("book.xlsx"));
        Files.writeString(folder.resolve("book.xlsx#Orders"), "not a sheet");
        Files.writeString(folder.resolve("100%23.txt"), "a percent in a name");
        Path deep = Files.createDirectories(folder.resolve("m.eml#attachments/1"));
        Files.writeString(deep.resolve("a.txt"), "not an attachment");
        FilesConnector files = TestFiles.files(dir);
        SourceConfig s = new SourceConfig("hashes", "files", Map.of("path", folder.toString()), null);
        Map<String, AssetInfo> found = new LinkedHashMap<>();
        for (AssetInfo a : files.catalog(s)) {
            assertEquals(null, found.put(a.path(), a), "asset paths are unique: " + a.path());
        }
        assertEquals("sheet", found.get("book.xlsx#Orders").kind());
        assertEquals("document", found.get("book.xlsx%23Orders").kind());
        assertEquals("book.xlsx#Orders", found.get("book.xlsx%23Orders").hints().get("file"));
        assertNotNull(found.get("100%2523.txt"));
        assertNotNull(found.get("m.eml%23attachments/1/a.txt"));

        // and each reads its own file
        DocumentReads.Read r = DocumentReads.read(files, s, found.get("100%2523.txt"));
        assertTrue(r.chunks().get(0).text().contains("a percent in a name"));
        DocumentReads.Read fake = DocumentReads.read(files, s, found.get("m.eml%23attachments/1/a.txt"));
        assertTrue(fake.chunks().get(0).text().contains("not an attachment"));
    }

    static Writer sstRef() {
        return text(SHEET_OK.replace("t=\"inlineStr\"><is><t>a</t></is>", "t=\"s\"><v>0</v>"));
    }

    @Test
    void aRenamedSharedStringsPartIsStillMeasured() throws Exception {
        TestFiles.files(dir);
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_PART_MB, "2")));
        Path p = xlsx("renamed.xlsx", null, sstRef(), manyStrings(5), "strings.xml", 0);
        lie(p, "xl/strings.xml", 1000);
        String got = read(p);
        assertTrue(got.startsWith("refused: its part xl/strings.xml expands to more than 2 MB"), got);
        assertThrows(IOException.class, () -> Sheets.open(p, "renamed.xlsx"));
    }

    @Test
    void anArchiveTheCheckCannotReadIsRefusedNotLetThrough() throws Exception {
        TestFiles.files(dir);
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_PART_MB, "2")));
        // an entry name flagged UTF-8 that is not valid UTF-8 (the JDK's reader refuses it)
        Path book = xlsx("badname-book.xlsx", null, sstRef(), manyStrings(5));
        // copy the book's entries in after the bad one
        Path both = dir.resolve("badname2.xlsx");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(both));
             java.util.zip.ZipFile src = new java.util.zip.ZipFile(book.toFile())) {
            put(z, "docProps/BADNAME.txt", "x");
            for (ZipEntry e : java.util.Collections.list(src.entries())) {
                z.putNextEntry(new ZipEntry(e.getName()));
                src.getInputStream(e).transferTo(z);
                z.closeEntry();
            }
        }
        byte[] b = Files.readAllBytes(both);
        byte[] name = "docProps/BADNAME.txt".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int patched = 0;
        for (int i = 0; i + 46 < b.length; i++) {
            int sig = buf.getInt(i);
            int flag = sig == 0x04034b50 ? 6 : sig == 0x02014b50 ? 8 : -1;
            int nameAt = sig == 0x04034b50 ? 30 : 46;
            int lenAt = sig == 0x04034b50 ? 26 : 28;
            if (flag > 0 && matches(b, i + nameAt, buf.getShort(i + lenAt), name)) {
                buf.putShort(i + flag, (short) (buf.getShort(i + flag) | 0x800));
                b[i + nameAt + 9] = (byte) 0xff;
                b[i + nameAt + 10] = (byte) 0xfe;
                b[i + nameAt + 11] = (byte) 0xfd;
                patched++;
            }
        }
        assertEquals(2, patched);
        Files.write(both, b);
        String got = read(both);
        assertTrue(got.startsWith("refused:"), "either the check reads it and finds the big part, or refuses it: "
            + got);
        assertThrows(IOException.class, () -> Sheets.open(both, "badname2.xlsx"));
    }

    @Test
    void tooManyEntriesAreRefused() throws Exception {
        Path p = xlsx("dummies.xlsx", null, text(SHEET_OK), null, "sharedStrings.xml", 10_001);
        String got = read(p);
        assertTrue(got.startsWith("refused: it holds more than 10,000 parts"), got);
    }

    @Test
    void anOfficeAttachmentIsCheckedWhateverItIsCalled() throws Exception {
        TestFiles.files(dir);
        DocumentRuntime.configure(new ConnectorContext(Map.of(DocumentLimits.MAX_PART_MB, "2")));
        Path book = xlsx("attached.xlsx", null, sstRef(), manyStrings(5));
        lie(book, "xl/sharedStrings.xml", 1000);
        String eml = "From: a@example.com\nTo: b@example.com\nSubject: nested\nMIME-Version: 1.0\n"
            + "Content-Type: multipart/mixed; boundary=\"B\"\n\n--B\nContent-Type: text/plain\n\nbody\n--B\n"
            + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet; name=\"data.bin\"\n"
            + "Content-Disposition: attachment; filename=\"data.bin\"\nContent-Transfer-Encoding: base64\n\n"
            + Base64.getMimeEncoder().encodeToString(Files.readAllBytes(book)) + "\n--B--\n";
        Path mail = Files.writeString(dir.resolve("nested.eml"), eml);
        Extraction x = TextExtractor.extract(mail, "nested.eml", -1, DocumentRuntime.limits(), new AtomicBoolean());
        assertEquals(1, x.attachments().size());
        String problem = x.attachments().get(0).problem();
        assertNotNull(problem);
        assertTrue(problem.contains("more than 2 MB"), problem);
    }
}
