package io.kilnmark.kiln.plugins.files;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentLimits;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;

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

    /** The largest Parquet footer read; real ones are kilobytes, a few megabytes for very wide files. */
    static final int MAX_FOOTER = 64 << 20;

    /** Asset kind of files read as rows. */
    public static final String KIND_FILE = "file";

    /** Asset kind of files recorded but not read. */
    public static final String KIND_DOCUMENT = "document";

    private FileInspector() {
    }

    /**
     * How to read text files, as a source's settings say; null in a field means detect it.
     *
     * @param header false when the first row is data, not column names
     */
    public record TextOptions(Charset encoding, Character delimiter, Boolean header) {

        /** Detect everything. */
        public static final TextOptions DETECT = new TextOptions(null, null, null);

        boolean noHeader() {
            return Boolean.FALSE.equals(header);
        }
    }

    /** What inspecting a data file found: its columns and the hints the reader needs. */
    record Layout(List<FileColumn> columns, Map<String, String> hints) {
    }

    /**
     * The asset for one file. A file that cannot be inspected (corrupt, unreadable, too deeply
     * nested, compressed with a codec this server lacks) is still recorded, as not readable, with
     * the reason in its description, so one bad file does not stop a catalogue.
     *
     * @param encoding the source's {@code encoding} setting, or null to detect it
     */
    public static AssetInfo inspect(FileStore store, FileStore.Entry e, String container, Charset encoding) {
        return inspect(store, e, container, new TextOptions(encoding, null, null));
    }

    /** As {@link #inspect(FileStore, FileStore.Entry, String, Charset)}, with a source's text options. */
    public static AssetInfo inspect(FileStore store, FileStore.Entry e, String container, TextOptions options) {
        FileFormat format = FileFormat.of(name(e.path()));
        if (!format.readable()) {
            // documents whose text Kilnmark reads are readable: DocumentInput reads them (and says
            // why, when one turns out to be too large, damaged or protected); others are only recorded
            return new AssetInfo(FileSourceConnector.urnPath(e.path()), KIND_DOCUMENT, container, name(e.path()), null,
                List.of(), List.of(), false, DocumentTypes.readable(e.path()), hints(e, format));
        }
        try {
            Layout layout = layout(store, e.path(), e.size(), format, options);
            Map<String, String> hints = hints(e, format);
            hints.putAll(layout.hints());
            List<AssetInfo.FieldInfo> fields = new ArrayList<>();
            for (int i = 0; i < layout.columns().size(); i++) {
                fields.add(layout.columns().get(i).fieldInfo(i + 1));
            }
            return new AssetInfo(FileSourceConnector.urnPath(e.path()), KIND_FILE, container, name(e.path()), null,
                fields, List.of(), false, true, hints);
        } catch (IOException | RuntimeException ex) {
            return unreadable(e, container, String.valueOf(ex.getMessage()));
        } catch (StackOverflowError ex) {
            return unreadable(e, container, "its structure is nested too deeply to read");
        } catch (OutOfMemoryError ex) {
            // what failed to allocate was this file's metadata; it is garbage now
            return unreadable(e, container, "its metadata is too large to read");
        } catch (LinkageError ex) {
            return unreadable(e, container, "a library it needs could not be loaded (" + ex + ")");
        }
    }

    /** A data file recorded as not readable, with the reason as its description. */
    public static AssetInfo unreadable(FileStore.Entry e, String container, String reason) {
        FileFormat format = FileFormat.of(name(e.path()));
        Map<String, String> hints = hints(e, format);
        hints.put("error", reason);
        return new AssetInfo(FileSourceConnector.urnPath(e.path()), KIND_FILE, container, name(e.path()),
            "Kilnmark could not read this " + format.id() + " file: " + reason, List.of(), List.of(), false, false,
            hints);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static Map<String, String> hints(FileStore.Entry e, FileFormat format) {
        Map<String, String> hints = new HashMap<>();
        hints.put("format", format.id());
        hints.put("file", e.path()); // the asset's path is this with % and # escaped (see FileSourceConnector.urnPath)
        hints.put("size", Long.toString(e.size()));
        if (e.modified() != null) {
            hints.put("modified", e.modified().toString());
        }
        if (e.owner() != null) {
            hints.put("owner", e.owner());
        }
        if (e.etag() != null) {
            hints.put("etag", e.etag());
        }
        return hints;
    }

    /** Asset kind of an Excel sheet. */
    public static final String KIND_SHEET = "sheet";

    /**
     * The sheets of a workbook, or why they could not be listed this time.
     *
     * @param problem null when {@code sheets} is the full list; else the reason, and the sheets an
     *                earlier scan found should be kept
     */
    record SheetList(List<AssetInfo> sheets, String problem) {
    }

    /**
     * The sheets of an Excel workbook, as assets inside it: tables where a sheet has a header row,
     * else recorded as not read, with the reason. Listing them runs on a document reader, within
     * the time limit. When it fails (the workbook is too large, damaged, protected, a zip bomb, or
     * the server was busy) the reason is returned instead, so the scan keeps the sheets it knew.
     *
     * @param container where the workbook itself sits
     */
    static SheetList sheets(FileStore store, FileStore.Entry e, String container) {
        DocumentLimits limits = DocumentRuntime.limits();
        long max = DocumentTypes.legacy(e.path()) ? Math.min(limits.maxBytes(), limits.maxLegacyBytes())
            : limits.maxBytes();
        if (e.size() > max) {
            return new SheetList(List.of(), LocalCopy.tooLarge(e.size(), max).getMessage());
        }
        List<Sheets.Layout> layouts;
        try (LocalCopy copy = LocalCopy.of(store, e.path(), max, LocalCopy.version(e))) {
            layouts = DocumentRuntime.run(e.path(), cancelled -> {
                try (Sheets.Workbook wb = Sheets.open(copy.path(), e.path())) {
                    return Sheets.inspect(wb, SAMPLE_ROWS, cancelled);
                }
            });
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new SheetList(List.of(), "the scan was stopped");
        } catch (DocumentException ex) {
            return new SheetList(List.of(), ex.getMessage());
        } catch (Exception | StackOverflowError ex) {
            return new SheetList(List.of(), DocumentRuntime.explain(ex).getMessage());
        }
        String where = (container == null || container.isEmpty() ? "" : container + "/") + name(e.path());
        String workbook = FileSourceConnector.urnPath(e.path());
        List<AssetInfo> out = new ArrayList<>();
        for (Sheets.Layout l : layouts) {
            Map<String, String> hints = hints(e, format(e));
            hints.put("format", KIND_SHEET);
            hints.put("sheet", l.name());
            hints.put("parent", workbook);
            String path = workbook + "#" + FileSourceConnector.urnPath(l.name());
            if (l.problem() != null) {
                hints.put("error", l.problem());
                out.add(new AssetInfo(path, KIND_SHEET, where, l.name(), "Not read as a table: " + l.problem(),
                    List.of(), List.of(), false, false, hints));
                continue;
            }
            List<AssetInfo.FieldInfo> fields = new ArrayList<>();
            for (int i = 0; i < l.columns().size(); i++) {
                fields.add(l.columns().get(i).fieldInfo(i + 1));
            }
            out.add(new AssetInfo(path, KIND_SHEET, where, l.name(), null, fields, List.of(), false, true, hints));
        }
        return new SheetList(out, null);
    }

    private static FileFormat format(FileStore.Entry e) {
        return FileFormat.of(name(e.path()));
    }

    /** Reads the columns and layout hints of a data file. */
    static Layout layout(FileStore store, String path, long size, FileFormat format, TextOptions options)
        throws IOException {
        return switch (format) {
            case CSV, TSV -> {
                byte[] head = store.head(path, SAMPLE_BYTES);
                yield csv(head, format, head.length >= SAMPLE_BYTES, options);
            }
            case JSON, JSON_LINES -> {
                byte[] head = store.head(path, SAMPLE_BYTES);
                Layout json = json(head, format, head.length >= SAMPLE_BYTES, options.encoding());
                Map<String, String> hints = new HashMap<>(json.hints());
                hints.put("records", format == FileFormat.JSON_LINES ? "one object per line"
                    : "the objects of the top-level array (or the single object)");
                yield new Layout(json.columns(), hints);
            }
            case PARQUET -> parquet(store.parquet(path, size));
            case DOCUMENT -> throw new IllegalArgumentException("Documents are not read");
        };
    }

    /** The encoding to read a file in: the setting, else detected from its first bytes. */
    static Charset encoding(byte[] head, boolean truncated, Charset setting) {
        Charset bom = TextInput.bom(head);
        if (bom != null) {
            return bom;
        }
        return setting != null ? setting : TextInput.detect(head, truncated);
    }

    static Layout csv(byte[] head, FileFormat format, boolean truncated, Charset setting) throws IOException {
        return csv(head, format, truncated, new TextOptions(setting, null, null));
    }

    /**
     * The layout of a delimited file: the delimiter and quote found (or the one the options give),
     * and the columns, named from the header row, or {@code column_1...} when the options say the
     * first row is data.
     */
    static Layout csv(byte[] head, FileFormat format, boolean truncated, TextOptions options) throws IOException {
        Charset setting = options.encoding();
        Charset charset = encoding(head, truncated, setting);
        String sample = TextInput.sample(head, charset);
        if (truncated) {
            int nl = sample.lastIndexOf('\n'); // drop the line the sample cut through
            if (nl > 0) {
                sample = sample.substring(0, nl + 1);
            }
        }
        Csv.Dialect d = Csv.detect(sample, format);
        if (options.delimiter() != null) {
            d = new Csv.Dialect(options.delimiter(), d.quote(), d.sepLine());
        }
        Csv.Parser p = new Csv.Parser(new StringReader(sample), d.delimiter(), d.quote());
        if (d.sepLine()) {
            p.next();
        }
        List<String> header = p.next();
        Map<String, String> hints = new HashMap<>(Map.of("delimiter", d.delimiterName(),
            "quote", String.valueOf(d.quote()), "encoding", charset.name(), "header",
            Boolean.toString(!options.noHeader())));
        if (d.sepLine()) {
            hints.put("sep-line", "true");
        }
        if (header == null) {
            return new Layout(List.of(), hints);
        }
        List<String> first = header;
        if (options.noHeader()) {
            header = java.util.Collections.nCopies(first.size(), ""); // named column_1, column_2, ...
        }
        List<String> names = Csv.names(header);
        List<TypeGuess> guesses = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            guesses.add(new TypeGuess());
        }
        if (options.noHeader()) {
            for (int c = 0; c < names.size() && c < first.size(); c++) {
                guesses.get(c).offer(first.get(c), TypeGuess.Origin.TEXT);
            }
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

    static Layout json(byte[] head, FileFormat format, boolean truncated, Charset setting) throws IOException {
        Charset charset = encoding(head, truncated, setting);
        Map<String, TypeGuess> guesses = new LinkedHashMap<>();
        int n = 0;
        long skipped = 0;
        try (JsonRecords records = new JsonRecords(TextInput.reader(new ByteArrayInputStream(head), charset), format)) {
            try {
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
            } catch (IOException e) {
                // a sample cut part-way through a document ends in a syntax error; anything else is real
                if (!truncated || n == 0) {
                    throw truncated ? new IOException("its first record is longer than the " + (SAMPLE_BYTES >> 20)
                        + " MiB Kilnmark samples, or it is not valid JSON (" + e.getMessage() + ")", e) : e;
                }
            }
            skipped = records.skipped();
        }
        if (n == 0 && skipped > 0) {
            throw new IOException(truncated
                ? "no complete JSON record in its first " + (SAMPLE_BYTES >> 20) + " MiB: its lines are longer than "
                    + "Kilnmark reads (" + (JsonRecords.MAX_LINE >> 20) + " MiB), or not valid JSON"
                : "no line is a valid JSON object: check the file is JSON lines");
        }
        List<FileColumn> columns = new ArrayList<>();
        guesses.forEach((name, g) -> columns.add(g.column(name)));
        return new Layout(columns, Map.of("encoding", charset.name()));
    }

    static Layout parquet(InputFile file) throws IOException {
        try (file) {
            checkFooter(file);
            try (ParquetFileReader r = ParquetRuntime.open(file)) {
                String codecs = ParquetRuntime.unsupportedCodecs(r.getFileMetaData());
                if (codecs != null) {
                    throw new IOException(codecs);
                }
                List<FileColumn> columns = ParquetFields.of(r.getFileMetaData(), null).stream()
                    .map(ParquetFields.ParquetField::column).toList();
                return new Layout(columns, Map.of("rows", Long.toString(r.getFileMetaData().numRows())));
            }
        }
    }

    /** Checks a Parquet file's trailer before its footer is read, so a bad length cannot exhaust memory. */
    static void checkFooter(InputFile file) throws IOException {
        file.open();
        long length = file.length();
        if (length < 12) {
            throw new IOException("it is " + length + " bytes long, too short to be Parquet");
        }
        ByteBuffer tail = file.readRange(length - 8, 8).order(ByteOrder.LITTLE_ENDIAN);
        int footer = tail.getInt(tail.position());
        byte[] magic = new byte[4];
        tail.position(tail.position() + 4).get(magic);
        if (!"PAR1".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("it does not end with Parquet's PAR1 marker (encrypted, or not Parquet)");
        }
        if (footer <= 0 || footer > length - 12 || footer > MAX_FOOTER) {
            throw new IOException("its footer length (" + Integer.toUnsignedString(footer) + " bytes) is not valid "
                + "for a file of " + length + " bytes, or larger than the " + (MAX_FOOTER >> 20) + " MiB Kilnmark reads");
        }
    }
}
