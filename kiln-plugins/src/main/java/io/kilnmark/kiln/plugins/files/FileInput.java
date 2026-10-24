package io.kilnmark.kiln.plugins.files;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import dev.hardwood.InputFile;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.reader.RowReader;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

/**
 * Reads one CSV, TSV, JSON, JSON lines or Parquet file into Arrow batches, streaming it.
 *
 * <p>The store and the columns come from the connector; settings: {@code path}, {@code format},
 * {@code size}, {@code encoding} (CSV and JSON; detected when absent), {@code delimiter},
 * {@code quote} and {@code sep-line} (CSV), {@code asset-urn}, {@code batch-size} (default 8192)
 * and {@code row-limit} (default 0, every row).
 *
 * <p>CSV and JSON are read with the column kinds the catalogue inferred; if the header has changed
 * since, or no columns were given, they are inferred again from the start of the file. A value that
 * does not parse as its column's kind is read as null and counted, never a failure. When it
 * finishes the step emits {@code ROW_COUNT} with {@code rows}, {@code complete} (false when the
 * row limit cut the read short), {@code unparsed} (values read as null because they did not parse)
 * and, for JSON lines, {@code skipped} (lines that were not valid JSON).
 */
public final class FileInput implements Step {

    public static final String TYPE = "file-input";

    private final FileStore.Opener opener;
    private final List<FileColumn> catalogued;

    private FileStore store;
    private Rows rows;
    private List<FileColumn> columns;
    private Schema schema;
    private String label;
    private String assetUrn;
    private int batchSize;
    private long rowLimit;
    private long read;
    private boolean done;

    /**
     * @param opener  opens the folder or bucket the file is in, when the step starts
     * @param columns the columns the catalogue found; may be empty
     */
    public FileInput(FileStore.Opener opener, List<FileColumn> columns) {
        this.opener = opener;
        this.catalogued = List.copyOf(columns);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        String path = context.setting("path");
        FileFormat format = FileFormat.byId(context.setting("format", FileFormat.of(path).id()));
        batchSize = Integer.parseInt(context.setting("batch-size", "8192"));
        rowLimit = Long.parseLong(context.setting("row-limit", "0"));
        assetUrn = context.setting("asset-urn");
        long size = Long.parseLong(context.setting("size", "-1"));
        try {
            store = opener.open();
            label = store.describe(path);
            rows = switch (format) {
                case CSV, TSV -> csv(context, path, format);
                case JSON, JSON_LINES -> json(context, path, format);
                case PARQUET -> parquet(path, size);
                case DOCUMENT -> throw new StepException(path + " is a document, not a data file: it is recorded, "
                    + "not read");
            };
        } catch (IOException | RuntimeException e) {
            throw new StepException("Cannot read " + (label == null ? path : label) + ": " + e.getMessage(), e);
        } catch (StackOverflowError e) {
            throw new StepException("Cannot read " + (label == null ? path : label)
                + ": its structure is nested too deeply to read", e);
        }
        List<Field> fields = new ArrayList<>(columns.size());
        for (FileColumn c : columns) {
            fields.add(c.field());
        }
        schema = new Schema(fields);
    }

    /** The encoding given by the {@code encoding} setting, else detected from the file's first bytes. */
    private Charset charset(StepContext context, String path) throws IOException {
        String name = context.settings().get("encoding");
        if (name != null && !name.isBlank()) {
            return Charset.forName(name.strip());
        }
        byte[] head = store.head(path, FileInspector.SAMPLE_BYTES);
        return FileInspector.encoding(head, head.length >= FileInspector.SAMPLE_BYTES, null);
    }

    private Rows csv(StepContext context, String path, FileFormat format) throws IOException {
        char delimiter = Csv.Dialect.parse(context.settings().get("delimiter"), format == FileFormat.TSV ? '\t' : ',');
        char quote = Csv.Dialect.parse(context.settings().get("quote"), '"');
        boolean sepLine = Boolean.parseBoolean(context.settings().get("sep-line"));
        Charset charset = charset(context, path);
        columns = catalogued;
        Reader reader = null;
        try {
            reader = TextInput.reader(store.open(path), charset);
            Csv.Parser parser = new Csv.Parser(reader, delimiter, quote);
            if (sepLine) {
                parser.next();
            }
            List<String> header = parser.next();
            List<String> names = header == null ? List.of() : Csv.names(header);
            if (columns.isEmpty() || !names.equals(columns.stream().map(FileColumn::name).toList())) {
                // no catalogue, or the file changed since: look again
                reader.close();
                reader = null;
                byte[] head = store.head(path, FileInspector.SAMPLE_BYTES);
                FileInspector.Layout layout = FileInspector.csv(head, format, head.length >= FileInspector.SAMPLE_BYTES,
                    charset);
                columns = layout.columns();
                reader = TextInput.reader(store.open(path), charset);
                parser = new Csv.Parser(reader, Csv.Dialect.parse(layout.hints().get("delimiter"), delimiter),
                    Csv.Dialect.parse(layout.hints().get("quote"), quote));
                if (Boolean.parseBoolean(layout.hints().get("sep-line"))) {
                    parser.next();
                }
                parser.next();
            }
            return new CsvRows(reader, parser, columns);
        } catch (IOException | RuntimeException e) {
            closeQuietly(reader); // stops the transfer of a remote file
            throw e;
        }
    }

    private Rows json(StepContext context, String path, FileFormat format) throws IOException {
        Charset charset = charset(context, path);
        if (catalogued.isEmpty()) {
            byte[] head = store.head(path, FileInspector.SAMPLE_BYTES);
            columns = FileInspector.json(head, format, head.length >= FileInspector.SAMPLE_BYTES, charset).columns();
        } else {
            columns = catalogued;
        }
        Reader reader = null;
        try {
            reader = TextInput.reader(store.open(path), charset);
            return new JsonRows(new JsonRecords(reader, format), columns);
        } catch (IOException | RuntimeException e) {
            closeQuietly(reader);
            throw e;
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                // already failing; the first failure is the one to report
            }
        }
    }

    private Rows parquet(String path, long size) throws IOException {
        InputFile file = store.parquet(path, size);
        ParquetFileReader reader = null;
        try {
            FileInspector.checkFooter(file);
            reader = ParquetRuntime.open(file);
            String codecs = ParquetRuntime.unsupportedCodecs(reader.getFileMetaData());
            if (codecs != null) {
                throw new IOException(codecs);
            }
            List<ParquetFields.ParquetField> fields = ParquetFields.of(reader.getFileMetaData(), reader.getFileSchema());
            columns = fields.stream().map(ParquetFields.ParquetField::column).toList();
            RowReader rr = rowLimit > 0 ? reader.buildRowReader().head(rowLimit + 1).build() : reader.rowReader();
            return new ParquetRows(file, reader, rr, fields);
        } catch (IOException | RuntimeException e) {
            closeQuietly(reader);
            closeQuietly(file);
            throw e;
        }
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot batch = VectorSchemaRoot.create(schema, context.allocator());
        boolean limitReached = false;
        try {
            for (FieldVector v : batch.getFieldVectors()) {
                v.setInitialCapacity(batchSize);
            }
            batch.allocateNew();
            List<FieldVector> vectors = batch.getFieldVectors();
            int n = 0;
            while (n < batchSize) {
                if (rowLimit > 0 && read + n >= rowLimit) {
                    limitReached = true;
                    break;
                }
                if (!rows.advance()) {
                    done = true;
                    break;
                }
                rows.write(vectors, n);
                n++;
            }
            batch.setRowCount(n);
            read += n;
        } catch (IOException | RuntimeException e) {
            batch.close();
            throw new StepException("Reading " + label + " failed after " + read + " rows" + rows.where() + ": "
                + e.getMessage(), e);
        }
        try {
            if (batch.getRowCount() > 0 || read == 0) {
                context.output().put(batch); // an empty file still sends its schema once
            } else {
                batch.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (limitReached) {
            boolean more;
            try {
                more = rows.advance();
            } catch (IOException | RuntimeException e) {
                more = true; // something is there, even if it does not read
            }
            finish(context, !more);
            return false;
        }
        if (done) {
            finish(context, true);
            return false;
        }
        return true;
    }

    private void finish(StepContext context, boolean complete) {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("rows", Long.toString(read));
        attributes.put("complete", Boolean.toString(complete));
        attributes.put("unparsed", Long.toString(rows.unparsed()));
        if (rows instanceof JsonRows j && j.records.skipped() > 0) {
            attributes.put("skipped", Long.toString(j.records.skipped()));
        }
        context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, attributes, Instant.now()));
    }

    @Override
    public void close() {
        if (store != null) {
            store.cancel(); // stop downloads still running for this read before waiting on anything
        }
        try {
            if (rows != null) {
                rows.close();
            }
        } catch (IOException | RuntimeException e) {
            // closing a read; nothing to keep
        }
        try {
            if (store != null) {
                store.close();
            }
        } catch (IOException | RuntimeException e) {
            // as above
        }
    }

    /** Rows of one format, one at a time. */
    private abstract static class Rows implements Closeable {

        long unparsed;

        /** Moves to the next row; false at the end. */
        abstract boolean advance() throws IOException;

        /** Writes the current row at {@code row}. */
        abstract void write(List<FieldVector> vectors, int row);

        long unparsed() {
            return unparsed;
        }

        /** Where in the file the read is, for messages: empty or " (near line N)". */
        String where() {
            return "";
        }
    }

    private static final class CsvRows extends Rows {

        private final Reader reader;
        private final Csv.Parser parser;
        private final List<FileColumn> columns;
        private List<String> current;

        CsvRows(Reader reader, Csv.Parser parser, List<FileColumn> columns) {
            this.reader = reader;
            this.parser = parser;
            this.columns = columns;
        }

        @Override
        boolean advance() throws IOException {
            current = parser.next();
            return current != null;
        }

        @Override
        void write(List<FieldVector> vectors, int row) {
            for (int c = 0; c < columns.size(); c++) {
                String text = c < current.size() ? current.get(c) : null;
                if (!TextValues.write(columns.get(c), vectors.get(c), row, text)) {
                    unparsed++;
                }
            }
        }

        @Override
        String where() {
            return " (near line " + parser.line() + ")";
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class JsonRows extends Rows {

        final JsonRecords records;
        private final List<FileColumn> columns;
        private Map<String, JsonRecords.Value> current;

        JsonRows(JsonRecords records, List<FileColumn> columns) {
            this.records = records;
            this.columns = columns;
        }

        @Override
        boolean advance() throws IOException {
            current = records.next();
            return current != null;
        }

        @Override
        void write(List<FieldVector> vectors, int row) {
            for (int c = 0; c < columns.size(); c++) {
                JsonRecords.Value v = current.get(columns.get(c).name());
                if (!TextValues.write(columns.get(c), vectors.get(c), row, v == null ? null : v.text())) {
                    unparsed++;
                }
            }
        }

        @Override
        public void close() throws IOException {
            records.close();
        }
    }

    private static final class ParquetRows extends Rows {

        private final InputFile file;
        private final ParquetFileReader reader;
        private final RowReader rows;
        private final List<ParquetFields.ParquetField> fields;

        ParquetRows(InputFile file, ParquetFileReader reader, RowReader rows, List<ParquetFields.ParquetField> fields) {
            this.file = file;
            this.reader = reader;
            this.rows = rows;
            this.fields = fields;
        }

        @Override
        boolean advance() {
            if (!rows.hasNext()) {
                return false;
            }
            rows.next();
            return true;
        }

        @Override
        void write(List<FieldVector> vectors, int row) {
            for (int c = 0; c < fields.size(); c++) {
                try {
                    ParquetFields.copy(fields.get(c), rows, c, vectors.get(c), row);
                } catch (RuntimeException e) {
                    vectors.get(c).setNull(row);
                    unparsed++;
                }
            }
        }

        @Override
        public void close() throws IOException {
            try (file; reader) {
                rows.close();
            }
        }
    }
}
