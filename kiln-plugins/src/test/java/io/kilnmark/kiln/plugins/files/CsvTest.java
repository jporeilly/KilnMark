package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    private static List<List<String>> parse(String text, char delimiter) throws IOException {
        Csv.Parser p = new Csv.Parser(new StringReader(text), delimiter, '"');
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
        assertEquals("id,name\n1,Zoë\n", TextInput.sample(utf8));
        byte[] utf16 = "﻿id,name\n".getBytes(StandardCharsets.UTF_16LE);
        try (Reader r = TextInput.reader(new ByteArrayInputStream(utf16))) {
            char[] buf = new char[20];
            int n = r.read(buf);
            assertEquals("id,name\n", new String(buf, 0, n));
        }
        FileInspector.Layout layout = FileInspector.csv(utf8, FileFormat.CSV);
        assertEquals("id", layout.columns().get(0).name(), "the BOM is not part of the first name");
    }

    @Test
    void inspectionInfersEachColumn() throws IOException {
        String csv = """
            customer_id,phone,date_of_birth,balance,created_at,opted_in
            1,07700 900001,1968-01-09,10.5,2023-03-29 11:14:08.325687+01,true
            2,07700 900002,1962-01-23,-3,2019-02-08 23:01:28.839127+00,false
            """;
        FileInspector.Layout layout = FileInspector.csv(csv.getBytes(StandardCharsets.UTF_8), FileFormat.CSV);
        assertEquals(List.of("int64", "text", "date", "decimal(38,1)", "timestamp_tz", "boolean"),
            layout.columns().stream().map(FileColumn::sourceType).toList());
        assertEquals(",", layout.hints().get("delimiter"));
    }
}
