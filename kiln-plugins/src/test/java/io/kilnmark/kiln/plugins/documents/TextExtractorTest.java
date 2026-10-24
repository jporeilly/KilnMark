package io.kilnmark.kiln.plugins.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Text and properties from every format, from small generated documents, and the safety limits. */
class TextExtractorTest {

    @TempDir
    static Path dir;
    static Map<String, Path> files;

    @BeforeAll
    static void write() throws IOException {
        files = DocumentFixtures.all(dir);
    }

    static Extraction extract(String name) throws DocumentException {
        return extract(files.get(name), name, DocumentLimits.DEFAULTS);
    }

    static Extraction extract(Path p, String name, DocumentLimits limits) throws DocumentException {
        return TextExtractor.extract(p, name, -1, limits, new AtomicBoolean());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "policy.pdf         | Staff may work from home up to three days a week | Remote working policy",
        "onboarding.docx    | a safety briefing and a tour of the warehouse    | Onboarding guide",
        "memo.doc           | stationery order for the Harbour Street office   | Legacy memo on stationery orders",
        "budget.xlsx        | Brass lantern                                    | ",
        "budget.xls         | Enamel teapot                                    | ",
        "review.pptx        | Open a second depot in the spring                | ",
        "review.ppt         | The towpath reopens in June                      | ",
        "minutes.odt        | The bike shed will be repainted in green         | Residents meeting minutes",
        "stock.ods          | Lanterns in stock                                | Stock count",
        "pitch.odp          | A faster ferry to the islands                    | Ferry pitch",
        "garden.rtf         | The compost bins will move to the north corner   | Garden committee minutes",
        "page.html          | The reading room opens at nine                   | Opening hours",
        "catalogue.xml      | Enamel teapot                                    | ",
        "guide.epub         | Hawthorn flowers in May and feeds many insects   | Field guide to hedgerows",
        "supplier-query.eml | confirm the delivery date for the brass lanterns | Supplier query about lanterns",
        "supplier-call.msg  | deliver the canal boat fenders next Tuesday      | Call notes about the canal boat order",
        "notes.txt          | Bring a warm coat: the gallery is windy          | ",
        "handbook.md        | Book holidays two weeks ahead                    | Team handbook",
    })
    void readsTextAndTitle(String name, String phrase, String title) throws Exception {
        Extraction x = extract(name);
        Extraction.Part d = x.document();
        assertTrue(d.text().contains(phrase), name + " text: " + d.text());
        if (title != null) {
            assertEquals(title, d.properties().get(Extraction.TITLE), name + " properties: " + d.properties());
        }
        assertEquals(null, d.problem());
        assertFalse(d.truncated());
    }

    @Test
    void pdfHasPagesAuthorAndDates() throws Exception {
        Extraction.Part d = extract("policy.pdf").document();
        assertEquals("2", d.properties().get(Extraction.PAGES));
        assertEquals("Policy Team", d.properties().get(Extraction.AUTHOR));
        assertEquals("2025-03-03T09:30:00Z", d.properties().get(Extraction.CREATED));
        assertEquals(2, d.pages().size());
        int second = d.pages().get(1).start();
        assertTrue(d.text().substring(second).startsWith("Equipment and expenses"), d.text());
        assertEquals("application/pdf", d.contentType());
    }

    @Test
    void workbookPagesAreSheetsNamedByTheirHeading() throws Exception {
        Extraction.Part d = extract("budget.xlsx").document();
        assertEquals(List.of("Orders", "Products", "Notes"), d.pages().stream().map(Extraction.Page::label).toList());
    }

    @Test
    void slidesArePages() throws Exception {
        Extraction.Part d = extract("review.pptx").document();
        assertEquals(2, d.pages().size(), d.text());
    }

    @Test
    void htmlScriptsStylesAndHeadAreNotText() throws Exception {
        String text = extract("page.html").document().text();
        assertFalse(text.contains("alert"), text);
        assertFalse(text.contains("color: red"), text);
        assertFalse(text.contains("steal"), text);
        assertTrue(text.contains("Monday to Friday\nSaturday mornings"), text);
    }

    @Test
    void mailHasHeadersAndAttachmentsAsParts() throws Exception {
        Extraction x = extract("supplier-query.eml");
        Map<String, String> p = x.document().properties();
        assertTrue(p.get(Extraction.FROM).contains("alex@example.com"), p.toString());
        assertTrue(p.get(Extraction.TO).contains("sam@example.org"), p.toString());
        assertTrue(p.get(Extraction.CC).contains("purchasing@example.net"), p.toString());
        assertEquals("Supplier query about lanterns", p.get(Extraction.SUBJECT));
        assertEquals("2025-04-01T09:15:00Z", p.get(Extraction.DATE));
        assertFalse(x.document().text().contains("Invoice queries"), "attachments are not in the body's text");
        assertEquals(List.of("agenda.txt", "logo.png"), x.attachments().stream().map(Extraction.Part::name).toList());
        Extraction.Part agenda = x.attachments().get(0);
        assertTrue(agenda.text().contains("Invoice queries"), agenda.text());
        assertEquals(null, agenda.problem());
        Extraction.Part logo = x.attachments().get(1);
        assertNotNull(logo.problem());
        assertEquals(12, logo.size());
    }

    @Test
    void outlookMessageHasHeaders() throws Exception {
        Map<String, String> p = extract("supplier-call.msg").document().properties();
        assertEquals("Call notes about the canal boat order", p.get(Extraction.SUBJECT), p.toString());
        assertTrue(String.valueOf(p.get(Extraction.FROM)).contains("Robin Example"), p.toString());
    }

    @Test
    void markdownTitleAndLanguage() throws Exception {
        Extraction.Part d = extract("handbook.md").document();
        assertEquals("Team handbook", d.properties().get(Extraction.TITLE));
        assertEquals("en", TextStats.language("Staff may work from home up to three days a week, and managers "
            + "agree the pattern with each member of their team. The company lends every person a laptop and a "
            + "headset, but broadband costs are not repaid. Anyone who works from home should keep their desk tidy, "
            + "take regular breaks and tell their manager when they are unwell."));
        assertEquals("fr", TextStats.language("Le personnel peut travailler depuis la maison trois jours par semaine. "
            + "Les responsables conviennent du rythme avec chaque membre de leur équipe. L'entreprise prête un "
            + "ordinateur portable et un casque, mais les frais de connexion ne sont pas remboursés."));
        assertEquals("es", TextStats.language("El personal puede trabajar desde casa hasta tres días a la semana y los "
            + "responsables acuerdan el horario con cada miembro del equipo. La empresa presta un ordenador y unos "
            + "auriculares, pero no paga la conexión."));
        assertEquals("de", TextStats.language("Die Mitarbeiter dürfen bis zu drei Tage pro Woche von zu Hause "
            + "arbeiten, und die Vorgesetzten vereinbaren das mit jedem Mitglied des Teams. Die Firma leiht einen "
            + "Laptop aus, aber die Kosten für das Internet werden nicht erstattet."));
        assertEquals("ja", TextStats.language("在宅勤務は週に三日まで認められます。上司はチームの各メンバーと勤務の形を決めます。"));
        assertEquals(null, TextStats.language("too short"));
        // the review's probe sentences
        Map<String, String> more = Map.of(
            "pt", "O pessoal pode trabalhar a partir de casa até três dias por semana e os responsáveis acordam o "
                + "horário com cada membro da equipa. A empresa empresta um computador portátil, mas não paga a ligação à "
                + "internet nem as despesas de electricidade.",
            "it", "Il personale può lavorare da casa fino a tre giorni alla settimana e i responsabili concordano "
                + "l'orario con ogni membro della squadra. L'azienda presta un computer portatile, ma non paga la "
                + "connessione a internet né le spese della luce.",
            "nl", "Het personeel mag tot drie dagen per week thuis werken en de leidinggevenden spreken het rooster af "
                + "met elk lid van het team. Het bedrijf leent een laptop uit, maar de kosten van het internet worden niet "
                + "vergoed.",
            "sv", "Personalen får arbeta hemifrån upp till tre dagar i veckan och cheferna kommer överens om schemat med "
                + "varje medlem i teamet. Företaget lånar ut en bärbar dator, men kostnaderna för internet ersätts inte "
                + "av företaget.",
            "pl", "Pracownicy mogą pracować z domu do trzech dni w tygodniu, a kierownicy ustalają harmonogram z każdym "
                + "członkiem zespołu. Firma pożycza laptopa, ale nie zwraca kosztów internetu ani prądu, co jest ważne "
                + "dla wielu osób.",
            "ru", "Сотрудники могут работать из дома до трёх дней в неделю, и руководители согласуют график с каждым "
                + "членом команды. Компания выдаёт ноутбук, но не оплачивает интернет и электричество, что важно для "
                + "многих из них.",
            "tr", "Personel haftada üç güne kadar evden çalışabilir ve yöneticiler her ekip üyesi ile çalışma düzenini "
                + "belirler. Şirket bir dizüstü bilgisayar ve kulaklık verir, ama internet masrafları için bir ödeme "
                + "yapmaz ve bu da çok önemli bir konudur.",
            "fa", "کارکنان می‌توانند تا سه روز در هفته از خانه کار کنند و مدیران برنامه را با هر یک از اعضای تیم "
                + "هماهنگ می‌کنند. شرکت یک لپ‌تاپ قرض می‌دهد اما هزینه اینترنت را پرداخت نمی‌کند.",
            "ca", "El personal pot treballar des de casa fins a tres dies a la setmana i els responsables acorden "
                + "l'horari amb cada membre de l'equip. L'empresa presta un ordinador portàtil, però no paga la connexió "
                + "a internet ni les despeses de llum.",
            "ar", "يمكن للموظفين العمل من المنزل حتى ثلاثة أيام في الأسبوع، ويتفق المديرون على الجدول مع كل عضو في "
                + "الفريق. تقدم الشركة حاسوبا محمولا لكنها لا تدفع تكلفة الإنترنت.");
        more.forEach((lang, text) -> assertEquals(lang, TextStats.language(text), text));
        assertEquals(null, TextStats.language("Name Qty Price\nWidget 10 2.50\nGadget 5 3.75\nSprocket 7 1.20\n"
            + "Bolt 100 0.05\nNut 200 0.02\nWasher 300 0.01\nGear 4 12.00\nCog 9 8.40\nSpring 50 0.30\n"));
        assertEquals(null, TextStats.language("1001 Customer 1 10.5 2025-01-01 FALSE ".repeat(10)));
    }

    @Test
    void characterLimitKeepsTheStart() throws Exception {
        DocumentLimits small = new DocumentLimits(100L << 20, 120, DocumentLimits.DEFAULTS.timeout(), 3, 100, 2, 100, 10,
            30L << 20, 64L << 20);
        Extraction x = extract(files.get("policy.pdf"), "policy.pdf", small);
        assertTrue(x.document().truncated());
        assertTrue(x.document().text().length() <= 120, x.document().text());
        assertTrue(x.document().text().length() > 100, x.document().text());
        assertTrue(x.notes().get(0).contains("120"), x.notes().toString());
    }

    @Test
    void aZipBombIsRefused() throws Exception {
        Path bomb = dir.resolve("bomb.docx");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(files.get("onboarding.docx").toFile())) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                entries.put(e.getName(), z.getInputStream(e).readAllBytes());
            }
        }
        StringBuilder body = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w="
            + "\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>");
        body.append("Start of the bomb.</w:t></w:r></w:p>").append(" ".repeat(40 << 20))
            .append("</w:body></w:document>");
        entries.put("word/document.xml", body.toString().getBytes(StandardCharsets.UTF_8));
        body = null;
        DocumentFixtures.zip(bomb, entries);
        assertTrue(Files.size(bomb) < 200_000, "the bomb is small on disk: " + Files.size(bomb));
        DocumentException e = assertThrows(DocumentException.class, () -> extract(bomb, "bomb.docx",
            DocumentLimits.DEFAULTS));
        assertTrue(e.getMessage().contains("zip bomb"), e.getMessage());
    }

    @Test
    void nestedMessagesStopAtTheDepthLimit() throws Exception {
        String inner = "From: a@example.com\r\nSubject: level 6\r\nContent-Type: text/plain\r\n\r\nThe deepest secret.\r\n";
        for (int level = 5; level >= 1; level--) {
            inner = "From: a@example.com\r\nSubject: level " + level + "\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=\"n" + level + "\"\r\n\r\n--n" + level
                + "\r\nContent-Type: text/plain\r\n\r\nText at level " + level + ".\r\n--n" + level
                + "\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment; filename=\"level" + (level + 1)
                + ".eml\"\r\n\r\n" + inner + "\r\n--n" + level + "--\r\n";
        }
        Path p = DocumentFixtures.write(dir.resolve("nested.eml"), inner);
        Extraction x = extract(p, "nested.eml", DocumentLimits.DEFAULTS);
        assertEquals(1, x.attachments().size());
        String all = x.document().text() + "\n" + x.attachments().get(0).text();
        assertTrue(all.contains("Text at level 2"), all);
        assertFalse(all.contains("The deepest secret"), all);
        assertTrue(String.join(" ", x.notes()).contains("levels deep"), x.notes().toString());
    }

    @Test
    void aFileThatIsNotWhatItsNameSaysIsNotReadable() throws Exception {
        Path p = DocumentFixtures.write(dir.resolve("fake.docx"), "this is not a zip file at all");
        DocumentException e = assertThrows(DocumentException.class, () -> extract(p, "fake.docx",
            DocumentLimits.DEFAULTS));
        assertTrue(e.getMessage().startsWith("it is not a valid file") || e.getMessage().startsWith("it could not"),
            e.getMessage());
    }

    @Test
    void anEncryptedPdfIsExplained() throws Exception {
        Path p = dir.resolve("locked.pdf");
        try (var doc = org.apache.pdfbox.Loader.loadPDF(files.get("policy.pdf").toFile())) {
            var policy = new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner-secret",
                "user-secret", new org.apache.pdfbox.pdmodel.encryption.AccessPermission());
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            doc.save(p.toFile());
        }
        DocumentException e = assertThrows(DocumentException.class, () -> extract(p, "locked.pdf",
            DocumentLimits.DEFAULTS));
        assertTrue(e.getMessage().contains("password") || e.getMessage().contains("could not be read"),
            e.getMessage());
    }

    @Test
    void imagesInsideADocumentDoNotCountTowardsTheLimitOfFilesInside() throws Exception {
        Path p = dir.resolve("pictures.docx");
        try (var d = new org.apache.poi.xwpf.usermodel.XWPFDocument(); var out = Files.newOutputStream(p)) {
            byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
                + "nGNgYGD4DwABBAEAwS2OUAAAAABJRU5ErkJggg==");
            for (int i = 0; i < 3; i++) {
                var run = d.createParagraph().createRun();
                run.setText("Picture " + i);
                run.addPicture(new java.io.ByteArrayInputStream(png), org.apache.poi.common.usermodel.PictureType.PNG,
                    "p" + i + ".png", 9525, 9525);
            }
            d.write(out);
        }
        DocumentLimits one = new DocumentLimits(100L << 20, 1_000_000, DocumentLimits.DEFAULTS.timeout(), 3, 1, 2, 1500,
            150, 30L << 20, 64L << 20);
        Extraction x = extract(p, "pictures.docx", one);
        assertTrue(x.document().text().contains("Picture 2"));
        assertFalse(String.join(" ", x.notes()).contains("Only the first"), x.notes().toString());
    }
}
