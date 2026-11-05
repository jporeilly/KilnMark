package io.kilnmark.kiln.plugins.files;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.plugins.write.TextForm;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * CSV as RFC 4180 describes it: UTF-8 without a byte order mark, a header row, records ending in
 * CRLF, and a field quoted with {@code "} (quotes inside doubled) when it holds the delimiter, a
 * quote, CR or LF. A null is an empty field; an empty string is {@code ""}, so Kilnmark's reader
 * tells them apart. Values are written as {@link TextForm} gives them.
 */
final class CsvEncoder implements RowEncoder {

    private final List<WriteColumn> columns;
    private final char delimiter;
    private final Writer out;

    CsvEncoder(List<WriteColumn> columns, OutputStream stream, char delimiter) throws IOException {
        this.columns = columns;
        this.delimiter = delimiter;
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 1 << 16);
        for (int c = 0; c < columns.size(); c++) {
            if (c > 0) {
                out.write(delimiter);
            }
            field(columns.get(c).name());
        }
        out.write("\r\n");
    }

    /** The delimiter a setting names: one character, or {@code \t} for a tab; a comma by default. */
    static char delimiter(String setting) throws io.kilnmark.kiln.connect.ConnectorException {
        if (setting == null || setting.isEmpty()) {
            return ',';
        }
        char d = setting.equals("\\t") ? '\t' : setting.charAt(0);
        if ((setting.length() != 1 && !setting.equals("\\t")) || d == '"' || d == '\r' || d == '\n') {
            throw new io.kilnmark.kiln.connect.ConnectorException("delimiter: one character other than a quote, "
                + "such as ; or |, or \\t for a tab; not '" + setting + "'");
        }
        return d;
    }

    @Override
    public void write(VectorSchemaRoot batch) throws IOException {
        int n = batch.getRowCount();
        for (int row = 0; row < n; row++) {
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    out.write(delimiter);
                }
                String v = TextForm.of(columns.get(c).value(batch.getVector(c), row));
                if (v != null) {
                    field(v);
                }
            }
            out.write("\r\n");
        }
    }

    private void field(String v) throws IOException {
        boolean quote = v.isEmpty();
        for (int i = 0; i < v.length() && !quote; i++) {
            char ch = v.charAt(i);
            quote = ch == delimiter || ch == '"' || ch == '\r' || ch == '\n';
        }
        if (!quote) {
            out.write(v);
            return;
        }
        out.write('"');
        out.write(v.replace("\"", "\"\""));
        out.write('"');
    }

    @Override
    public void finish() throws IOException {
        out.flush();
    }

    @Override
    public List<String> types() {
        return columns.stream().map(WriteColumn::kindName).toList();
    }
}
