package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

    private static Csv.Dialect detect(String sample) {
        return Csv.detect(sample, FileFormat.CSV);
    }

    @Test
    void findsTheDelimiter() {
        assertEquals(',', detect("a,b,c\n1,2,3\n4,5,6\n").delimiter());
        assertEquals(';', detect("name;amount\n\"Smith, J\";1,50\n\"Jones, K\";2,75\n").delimiter(),
            "decimal commas and commas in quotes do not fool it");
        assertEquals('\t', detect("a\tb\n1\t2\n").delimiter());
        assertEquals('|', detect("a|b|c\nx|y|z\n").delimiter());
        assertEquals('\t', Csv.detect("a,b\n1,2\n", FileFormat.TSV).delimiter(), "TSV files are tab-separated");
        assertEquals(',', detect("single\nvalue\n").delimiter(), "one column falls back to a comma");
    }

    @Test
    void findsTheQuote() {
        assertEquals('"', detect("a,b\n\"x\",1\n").quote());
        assertEquals('\'', detect("a,b\n'x, y',1\n'z',2\n").quote());
        assertEquals('"', detect("name,note\nO'Brien,it's\n").quote(), "apostrophes inside fields are not quotes");
    }

    @Test
    void anApostropheThatOpensAValueIsNotAQuote() throws IOException {
        String phones = "name,phone\nann,'+44 7700 900001\nbob,'+44 7700 900002\ncat,07700 900003\n";
        Csv.Dialect d = detect(phones);
        assertEquals('"', d.quote());
        assertEquals(4, parse(phones, d.delimiter(), d.quote()).size(), "a header and 3 rows");
        assertEquals("'+44 7700 900001", parse(phones, ',', '"').get(1).get(1));

        String notes = "name,note\na,'tis fine\nb,ok\nc,rock 'n' roll\nd,end";
        d = detect(notes);
        assertEquals('"', d.quote());
        assertEquals(5, parse(notes, d.delimiter(), d.quote()).size(), "a header and 4 rows");
    }

    @Test
    void excelsSepLineNamesTheDelimiter() {
        Csv.Dialect d = detect("sep=;\r\na,b;c\n1,5;2\n");
        assertEquals(';', d.delimiter());
        assertTrue(d.sepLine());
        assertFalse(detect("separator,b\n1,2\n").sepLine());
        assertEquals('|', Csv.excelSeparator("SEP=|\nx|y\n"));
    }

    @Test
    void textThatIsNotUtf8IsWindows1252() {
        assertEquals(StandardCharsets.UTF_8, TextInput.detect("Zoë".getBytes(StandardCharsets.UTF_8), false));
        assertEquals(TextInput.WINDOWS_1252, TextInput.detect("Zoë".getBytes(TextInput.WINDOWS_1252), false));
        byte[] cut = java.util.Arrays.copyOf("abcé".getBytes(StandardCharsets.UTF_8), 4);
        assertEquals(StandardCharsets.UTF_8, TextInput.detect(cut, true), "a character cut by the sample's end is fine");
        assertEquals(TextInput.WINDOWS_1252, TextInput.detect(cut, false));
    }

    private static List<List<String>> parse(String text, char delimiter) throws IOException {
        return parse(text, delimiter, '"');
    }

    private static List<List<String>> parse(String text, char delimiter, char quote) throws IOException {
        Csv.Parser p = new Csv.Parser(new StringReader(text), delimiter, quote);
        List<List<String>> rows = new java.util.ArrayList<>();
        List<String> r;
        while ((r = p.next()) != null) {
            rows.add(r);
        }
        return rows;
    }

    @Test
    void readsQuotedFieldsNewlinesAndDoubledQuotes() throws IOException {
        List<List<String>> rows = parse("a,b,c\r\n\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n\n1,,\"\"\n", ',');
        assertEquals(3, rows.size(), "blank lines are skipped");
        assertEquals(List.of("x, y", "say \"hi\"", "two\nlines"), rows.get(1));
        assertEquals("1", rows.get(2).get(0));
        assertNull(rows.get(2).get(1), "an empty field is null");
        assertEquals("", rows.get(2).get(2), "a quoted empty field is an empty string");
    }

    @Test
    void lastLineWithoutNewlineAndOldMacLineEnds() throws IOException {
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), parse("a,b\r1,2", ','));
    }

    @Test
    void headerNamesAreMadeUniqueAndNeverBlank() {
        assertEquals(List.of("id", "column_2", "id_2", "name"), Csv.names(Arrays.asList("id", "", "id", " name ")));
    }

    @Test
    void byteOrderMarksAreSkipped() throws IOException {
        byte[] utf8 = "﻿id,name\n1,Zoë\n".getBytes(StandardCharsets.UTF_8);
        assertEquals("id,name\n1,Zoë\n", TextInput.sample(utf8, StandardCharsets.UTF_8));
        byte[] utf16 = "﻿id,name\n".getBytes(StandardCharsets.UTF_16LE);
        try (Reader r = TextInput.reader(new ByteArrayInputStream(utf16), StandardCharsets.UTF_8)) {
            char[] buf = new char[20];
            int n = r.read(buf);
            assertEquals("id,name\n", new String(buf, 0, n));
        }
        FileInspector.Layout layout = FileInspector.csv(utf8, FileFormat.CSV, false, (java.nio.charset.Charset) null);
        assertEquals("id", layout.columns().get(0).name(), "the BOM is not part of the first name");
    }

    @Test
    void inspectionInfersEachColumn() throws IOException {
        String csv = """
            customer_id,phone,date_of_birth,balance,created_at,opted_in
            1,07700 900001,1968-01-09,10.5,2023-03-29 11:14:08.325687+01,true
            2,07700 900002,1962-01-23,-3,2019-02-08 23:01:28.839127+00,false
            """;
        FileInspector.Layout layout = FileInspector.csv(csv.getBytes(StandardCharsets.UTF_8), FileFormat.CSV, false, (java.nio.charset.Charset) null);
        assertEquals(List.of("int64", "text", "date", "decimal(38,1)", "timestamp_tz", "boolean"),
            layout.columns().stream().map(FileColumn::sourceType).toList());
        assertEquals(",", layout.hints().get("delimiter"));
    }
}
