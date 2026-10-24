package io.kilnmark.kiln.plugins.documents;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextBox;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

/**
 * Small synthetic documents of every format Kilnmark reads, written by the libraries themselves
 * (POI, PDFBox) or by hand (OpenDocument, EPUB, mail). The legacy Word file cannot be written by
 * any library here, so it is a fixture made by {@code lab/make-documents.py} with LibreOffice.
 */
public final class DocumentFixtures {

    private DocumentFixtures() {
    }

    /** Writes one of each into {@code dir}; returns the files by name. */
    public static Map<String, Path> all(Path dir) throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        out.put("policy.pdf", pdf(dir.resolve("policy.pdf")));
        out.put("onboarding.docx", docx(dir.resolve("onboarding.docx")));
        out.put("memo.doc", doc(dir.resolve("memo.doc")));
        out.put("budget.xlsx", xlsx(dir.resolve("budget.xlsx")));
        out.put("budget.xls", xls(dir.resolve("budget.xls")));
        out.put("review.pptx", pptx(dir.resolve("review.pptx")));
        out.put("review.ppt", ppt(dir.resolve("review.ppt")));
        out.put("minutes.odt", odt(dir.resolve("minutes.odt")));
        out.put("stock.ods", ods(dir.resolve("stock.ods")));
        out.put("pitch.odp", odp(dir.resolve("pitch.odp")));
        out.put("garden.rtf", write(dir.resolve("garden.rtf"), RTF));
        out.put("page.html", write(dir.resolve("page.html"), HTML));
        out.put("catalogue.xml", write(dir.resolve("catalogue.xml"), XML));
        out.put("guide.epub", epub(dir.resolve("guide.epub")));
        out.put("supplier-query.eml", write(dir.resolve("supplier-query.eml"), eml()));
        out.put("supplier-call.msg", msg(dir.resolve("supplier-call.msg")));
        out.put("notes.txt", write(dir.resolve("notes.txt"), "Plain notes about the lighthouse tour.\n\n"
            + "Bring a warm coat: the gallery is windy.\n"));
        out.put("handbook.md", write(dir.resolve("handbook.md"), "# Team handbook\n\nWelcome to the team. "
            + "This handbook explains how we plan our work.\n\n## Holidays\n\nBook holidays two weeks ahead.\n"));
        return out;
    }

    public static final String RTF = "{\\rtf1\\ansi{\\fonttbl\\f0\\fswiss Helvetica;}{\\info{\\title Garden "
        + "committee minutes}{\\author Committee Secretary}}\\f0 Rich text minutes of the garden committee.\\par "
        + "The compost bins will move to the north corner.\\par}";

    public static final String HTML = "<!DOCTYPE html><html><head><title>Opening hours</title>"
        + "<style>body { color: red }</style><script>alert('do not run me')</script></head>"
        + "<body><h1>Library opening hours</h1><p>The reading room opens at nine.</p>"
        + "<ul><li>Monday to Friday</li><li>Saturday mornings</li></ul>"
        + "<p onclick=\"steal()\">Closed on public holidays.</p></body></html>";

    public static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<catalogue>"
        + "<item><name>Brass lantern</name><price>24.50</price></item>"
        + "<item><name>Enamel teapot</name><price>18.00</price></item></catalogue>";

    public static Path write(Path p, String text) throws IOException {
        return Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    public static Path pdf(Path p) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Remote working policy");
            info.setAuthor("Policy Team");
            Calendar created = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            created.clear();
            created.set(2025, Calendar.MARCH, 3, 9, 30, 0);
            info.setCreationDate(created);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            page(doc, font, List.of("Remote working policy", "Staff may work from home up to three days a week.",
                "Managers agree the pattern with each team member."));
            page(doc, font, List.of("Equipment and expenses", "The company lends a laptop and a headset.",
                "Broadband costs are not repaid."));
            doc.save(p.toFile());
        }
        return p;
    }

    private static void page(PDDocument doc, PDType1Font font, List<String> lines) throws IOException {
        PDPage page = new PDPage();
        doc.addPage(page);
        try (PDPageContentStream s = new PDPageContentStream(doc, page)) {
            float y = 700;
            for (String line : lines) {
                s.beginText();
                s.setFont(font, 12);
                s.newLineAtOffset(72, y);
                s.showText(line);
                s.endText();
                y -= 40; // a paragraph gap
            }
        }
    }

    public static Path docx(Path p) throws IOException {
        try (XWPFDocument d = new XWPFDocument(); OutputStream out = Files.newOutputStream(p)) {
            d.getProperties().getCoreProperties().setTitle("Onboarding guide");
            d.getProperties().getCoreProperties().setCreator("People Team");
            d.getProperties().getCoreProperties().setCreated(Optional.of(new java.util.Date(1735725600000L)));
            d.createParagraph().createRun().setText("Welcome to Harbour Logistics.");
            d.createParagraph().createRun().setText("Your first week includes a safety briefing and a tour of the "
                + "warehouse.");
            d.createParagraph().createRun().setText("Ask your buddy about the canteen rota.");
            d.write(out);
        }
        return p;
    }

    /** The legacy Word fixture made with LibreOffice by lab/make-documents.py. */
    public static Path doc(Path p) throws IOException {
        try (var in = DocumentFixtures.class.getResourceAsStream("/documents/memo.doc")) {
            if (in == null) {
                throw new IOException("Missing test resource documents/memo.doc: run lab/make-documents.py");
            }
            Files.copy(in, p);
        }
        return p;
    }

    public static Path xlsx(Path p) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(p)) {
            fill(wb);
            wb.write(out);
        }
        return p;
    }

    public static Path xls(Path p) throws IOException {
        try (HSSFWorkbook wb = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(p)) {
            fill(wb);
            wb.write(out);
        }
        return p;
    }

    /** Two tables and a notes sheet. */
    private static void fill(Workbook wb) {
        CellStyle date = wb.createCellStyle();
        date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        Sheet orders = wb.createSheet("Orders");
        row(orders, 0, "order_id", "customer", "amount", "ordered_on", "paid");
        for (int i = 1; i <= 20; i++) {
            Row r = orders.createRow(i);
            r.createCell(0).setCellValue(1000 + i);
            r.createCell(1).setCellValue("Customer " + i);
            r.createCell(2).setCellValue(10.5 * i);
            r.createCell(3).setCellValue(LocalDate.of(2025, 1, i));
            r.getCell(3).setCellStyle(date);
            r.createCell(4).setCellValue(i % 2 == 0);
        }
        Sheet products = wb.createSheet("Products");
        row(products, 0, "sku", "name", "price");
        row(products, 1, "K-01", "Brass lantern", null);
        products.getRow(1).createCell(2).setCellValue(24.5);
        row(products, 2, "T-07", "Enamel teapot", null);
        products.getRow(2).createCell(2).setCellValue(18);
        Sheet notes = wb.createSheet("Notes");
        row(notes, 0, "These figures are a draft for the spring budget meeting.");
        row(notes, 2, "Amounts are in pounds, before tax.");
    }

    private static void row(Sheet s, int n, String... values) {
        Row r = s.createRow(n);
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                r.createCell(i).setCellValue(values[i]);
            }
        }
    }

    public static Path pptx(Path p) throws IOException {
        try (XMLSlideShow show = new XMLSlideShow(); OutputStream out = Files.newOutputStream(p)) {
            for (String[] s : new String[][] {{"Quarterly review", "Sales rose in the northern region."},
                {"Next steps", "Open a second depot in the spring."}}) {
                XSLFSlide slide = show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setText(s[0]);
                box.addNewTextParagraph().addNewTextRun().setText(s[1]);
            }
            show.write(out);
        }
        return p;
    }

    public static Path ppt(Path p) throws IOException {
        try (HSLFSlideShow show = new HSLFSlideShow(); OutputStream out = Files.newOutputStream(p)) {
            for (String s : new String[] {"Legacy deck about the canal project", "The towpath reopens in June"}) {
                HSLFSlide slide = show.createSlide();
                HSLFTextBox box = slide.createTextBox();
                box.setText(s);
            }
            show.write(out);
        }
        return p;
    }

    private static final String ODF_META = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-meta "
        + "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
        + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
        + " office:version=\"1.2\"><office:meta><dc:title>%s</dc:title><dc:creator>Open Office Writer</dc:creator>"
        + "</office:meta></office:document-meta>";

    private static final String ODF_NS = "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
        + "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" "
        + "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" "
        + "xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\" "
        + "xmlns:presentation=\"urn:oasis:names:tc:opendocument:xmlns:presentation:1.0\" office:version=\"1.2\"";

    private static Path odf(Path p, String mime, String title, String body) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("mimetype", mime.getBytes(StandardCharsets.US_ASCII));
        entries.put("content.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document-content " + ODF_NS
            + "><office:body>" + body + "</office:body></office:document-content>").getBytes(StandardCharsets.UTF_8));
        entries.put("meta.xml", String.format(ODF_META, title).getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/manifest.xml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><manifest:manifest "
            + "xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\"><manifest:file-entry "
            + "manifest:full-path=\"/\" manifest:media-type=\"" + mime + "\"/><manifest:file-entry "
            + "manifest:full-path=\"content.xml\" manifest:media-type=\"text/xml\"/></manifest:manifest>")
            .getBytes(StandardCharsets.UTF_8));
        return zip(p, entries);
    }

    public static Path odt(Path p) throws IOException {
        return odf(p, "application/vnd.oasis.opendocument.text", "Residents meeting minutes",
            "<office:text><text:h text:outline-level=\"1\">Residents meeting</text:h><text:p>The bike shed will be "
                + "repainted in green.</text:p><text:p>Next meeting in the community hall.</text:p></office:text>");
    }

    public static Path ods(Path p) throws IOException {
        return odf(p, "application/vnd.oasis.opendocument.spreadsheet", "Stock count",
            "<office:spreadsheet><table:table table:name=\"Stock\"><table:table-row><table:table-cell><text:p>"
                + "Lanterns in stock</text:p></table:table-cell><table:table-cell><text:p>42</text:p></table:table-cell>"
                + "</table:table-row></table:table></office:spreadsheet>");
    }

    public static Path odp(Path p) throws IOException {
        return odf(p, "application/vnd.oasis.opendocument.presentation", "Ferry pitch",
            "<office:presentation><draw:page draw:name=\"one\"><draw:frame><draw:text-box><text:p>A faster ferry to "
                + "the islands</text:p></draw:text-box></draw:frame></draw:page></office:presentation>");
    }

    public static Path epub(Path p) throws IOException {
        Map<String, byte[]> e = new LinkedHashMap<>();
        e.put("mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
        e.put("META-INF/container.xml", ("<?xml version=\"1.0\"?><container version=\"1.0\" "
            + "xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile "
            + "full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
            .getBytes(StandardCharsets.UTF_8));
        e.put("OEBPS/content.opf", ("<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" "
            + "version=\"2.0\" unique-identifier=\"id\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
            + "<dc:title>Field guide to hedgerows</dc:title><dc:creator>Nature Club</dc:creator>"
            + "<dc:identifier id=\"id\">guide-1</dc:identifier><dc:language>en</dc:language></metadata><manifest>"
            + "<item id=\"c1\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine>"
            + "<itemref idref=\"c1\"/></spine></package>").getBytes(StandardCharsets.UTF_8));
        e.put("OEBPS/chapter1.xhtml", ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><html "
            + "xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter one</title></head><body><h1>Hawthorn</h1>"
            + "<p>Hawthorn flowers in May and feeds many insects.</p></body></html>").getBytes(StandardCharsets.UTF_8));
        return zip(p, e);
    }

    /** A zip whose first entry ({@code mimetype}) is stored, as OpenDocument and EPUB require. */
    public static Path zip(Path p, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(p))) {
            for (var en : entries.entrySet()) {
                ZipEntry ze = new ZipEntry(en.getKey());
                if (en.getKey().equals("mimetype")) {
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(en.getValue().length);
                    CRC32 crc = new CRC32();
                    crc.update(en.getValue());
                    ze.setCrc(crc.getValue());
                }
                z.putNextEntry(ze);
                z.write(en.getValue());
                z.closeEntry();
            }
        }
        return p;
    }

    /** A message with a plain body, a text attachment and an image attachment. */
    public static String eml() {
        String agenda = Base64.getMimeEncoder().encodeToString(("Agenda for the supplier call\n\n"
            + "1. Delivery dates for the brass lanterns\n2. Invoice queries\n").getBytes(StandardCharsets.UTF_8));
        String png = Base64.getMimeEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10,
            0, 0, 0, 0});
        return "From: Alex Example <alex@example.com>\r\n"
            + "To: Sam Example <sam@example.org>\r\n"
            + "Cc: Purchasing <purchasing@example.net>\r\n"
            + "Subject: Supplier query about lanterns\r\n"
            + "Date: Tue, 01 Apr 2025 10:15:00 +0100\r\n"
            + "MIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n"
            + "--b1\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
            + "Hello Sam,\r\n\r\nCould you confirm the delivery date for the brass lanterns?\r\n\r\nThanks, Alex\r\n"
            + "--b1\r\nContent-Type: text/plain; charset=UTF-8; name=\"agenda.txt\"\r\n"
            + "Content-Disposition: attachment; filename=\"agenda.txt\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + agenda + "\r\n"
            + "--b1\r\nContent-Type: image/png; name=\"logo.png\"\r\n"
            + "Content-Disposition: attachment; filename=\"logo.png\"\r\nContent-Transfer-Encoding: base64\r\n\r\n"
            + png + "\r\n--b1--\r\n";
    }

    /**
     * A minimal Outlook message: an OLE2 file holding the MAPI streams Outlook writes for the subject,
     * sender, recipients and body (Unicode strings, type 001F).
     */
    public static Path msg(Path p) throws IOException {
        try (POIFSFileSystem fs = new POIFSFileSystem(); OutputStream out = Files.newOutputStream(p)) {
            var root = fs.getRoot();
            root.createDocument("__substg1.0_0037001F", new java.io.ByteArrayInputStream(
                utf16("Call notes about the canal boat order")));
            root.createDocument("__substg1.0_0C1A001F", new java.io.ByteArrayInputStream(utf16("Robin Example")));
            root.createDocument("__substg1.0_0E04001F", new java.io.ByteArrayInputStream(utf16("Jo Example")));
            root.createDocument("__substg1.0_1000001F", new java.io.ByteArrayInputStream(
                utf16("We agreed to deliver the canal boat fenders next Tuesday.")));
            root.createDocument("__substg1.0_001A001F", new java.io.ByteArrayInputStream(utf16("IPM.Note")));
            ByteArrayOutputStream props = new ByteArrayOutputStream();
            props.write(new byte[32]); // the header of a message's property stream, with no fixed properties
            root.createDocument("__properties_version1.0", new java.io.ByteArrayInputStream(props.toByteArray()));
            fs.writeFilesystem(out);
        }
        return p;
    }

    private static byte[] utf16(String s) {
        return s.getBytes(StandardCharsets.UTF_16LE);
    }
}
