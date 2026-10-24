package io.kilnmark.kiln.plugins.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;

import io.kilnmark.kiln.connect.AssetInfo;

/**
 * Catalogues one file: its format, and for data files the fields with their kinds, inferred from
 * the first {@value #SAMPLE_ROWS} rows within the first {@value #SAMPLE_BYTES} bytes for CSV and
 * JSON, or read from the footer for Parquet. Only those bytes are fetched. Documents are recorded
 * with their size and time, not read.
 */
public final class FileInspector {

    /** Bytes read from the start of a text file to find its layout and types. */
    public static final int SAMPLE_BYTES = 1 << 20;

    /** Rows sampled for type inference. */
    public static final int SAMPLE_ROWS = 1000;

    /** Asset kind of files read as rows. */
    public static final String KIND_FILE = "file";

    /** Asset kind of files recorded but not read. */
    public static final String KIND_DOCUMENT = "document";

    private FileInspector() {
    }

    /** What inspecting a data file found: its columns and the hints the reader needs. */
    record Layout(List<FileColumn> columns, Map<String, String> hints) {
    }

    /**
     * The asset for one file. A file that cannot be inspected (corrupt, unreadable) is still
     * recorded, as not readable, with the reason in its description, so one bad file does not
     * stop a catalogue.
     */
    public static AssetInfo inspect(FileStore store, FileStore.Entry e, String container) {
        String name = e.path().substring(e.path().lastIndexOf('/') + 1);
        FileFormat format = FileFormat.of(name);
        Map<String, String> hints = new HashMap<>();
        hints.put("format", format.id());
        hints.put("size", Long.toString(e.size()));
        if (e.modified() != null) {
            hints.put("modified", e.modified().toString());
        }
        if (!format.readable()) {
            return new AssetInfo(e.path(), KIND_DOCUMENT, container, name, null, List.of(), List.of(), false, false,
                hints);
        }
        try {
            Layout layout = layout(store, e.path(), e.size(), format);
            hints.putAll(layout.hints());
            List<AssetInfo.FieldInfo> fields = new ArrayList<>();
            for (int i = 0; i < layout.columns().size(); i++) {
                fields.add(layout.columns().get(i).fieldInfo(i + 1));
            }
            return new AssetInfo(e.path(), KIND_FILE, container, name, null, fields, List.of(), false, true, hints);
        } catch (IOException | RuntimeException ex) {
            hints.put("error", String.valueOf(ex.getMessage()));
            return new AssetInfo(e.path(), KIND_FILE, container, name,
                "Kilnmark could not read this " + format.id() + " file: " + ex.getMessage(), List.of(), List.of(),
                false, false, hints);
        }
    }

    /** Reads the columns and layout hints of a data file. */
    static Layout layout(FileStore store, String path, long size, FileFormat format) throws IOException {
        return switch (format) {
            case CSV, TSV -> csv(store.head(path, SAMPLE_BYTES), format);
            case JSON, JSON_LINES -> json(store.head(path, SAMPLE_BYTES), format);
            case PARQUET -> parquet(store.parquet(path, size));
            case DOCUMENT -> throw new IllegalArgumentException("Documents are not read");
        };
    }

    static Layout csv(byte[] head, FileFormat format) throws IOException {
        String sample = TextInput.sample(head);
        if (head.length >= SAMPLE_BYTES) {
            int nl = sample.lastIndexOf('\n'); // drop the line the sample cut through
            if (nl > 0) {
                sample = sample.substring(0, nl + 1);
            }
        }
        Csv.Dialect d = Csv.detect(sample, format);
        Csv.Parser p = new Csv.Parser(new StringReader(sample), d.delimiter(), d.quote());
        List<String> header = p.next();
        Map<String, String> hints = Map.of("delimiter", d.delimiterName(), "quote", String.valueOf(d.quote()));
        if (header == null) {
            return new Layout(List.of(), hints);
        }
        List<String> names = Csv.names(header);
        List<TypeGuess> guesses = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            guesses.add(new TypeGuess());
        }
        List<String> row;
        for (int n = 0; n < SAMPLE_ROWS && (row = p.next()) != null; n++) {
            for (int c = 0; c < names.size() && c < row.size(); c++) {
                guesses.get(c).offer(row.get(c), TypeGuess.Origin.TEXT);
            }
        }
        List<FileColumn> columns = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            columns.add(guesses.get(i).column(names.get(i)));
        }
        return new Layout(columns, hints);
    }

    static Layout json(byte[] head, FileFormat format) throws IOException {
        boolean truncated = head.length >= SAMPLE_BYTES;
        Map<String, TypeGuess> guesses = new LinkedHashMap<>();
        int n = 0;
        try (JsonRecords records = new JsonRecords(TextInput.reader(new ByteArrayInputStream(head)), format)) {
            Map<String, JsonRecords.Value> r;
            while (n < SAMPLE_ROWS && (r = records.next()) != null) {
                n++;
                for (var e : r.entrySet()) {
                    JsonRecords.Value v = e.getValue();
                    TypeGuess g = guesses.computeIfAbsent(e.getKey(), k -> new TypeGuess());
                    if (v != null) {
                        g.offer(v.text(), v.origin());
                    }
                }
            }
            if (n == 0 && records.skipped() > 0 && !truncated) {
                throw new IOException("No line is a valid JSON object: check the file is JSON lines");
            }
        } catch (IOException e) {
            if (!truncated || n == 0) {
                throw e; // a sample cut part-way through a document ends in a syntax error; anything else is real
            }
        }
        List<FileColumn> columns = new ArrayList<>();
        guesses.forEach((name, g) -> columns.add(g.column(name)));
        return new Layout(columns, Map.of());
    }

    static Layout parquet(InputFile file) throws IOException {
        try (file; ParquetFileReader r = ParquetFileReader.open(file)) {
            List<FileColumn> columns = ParquetFields.of(r.getFileMetaData()).stream()
                .map(ParquetFields.ParquetField::column).toList();
            return new Layout(columns, Map.of("rows", Long.toString(r.getFileMetaData().numRows())));
        }
    }
}
