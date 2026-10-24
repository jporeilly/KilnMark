package io.kilnmark.kiln.connect;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepSpec;

/**
 * The first rows of an asset, read live through its connector's reader, for people to look at
 * before they scan. Values are text: at most {@value #MAX_VALUE} characters each (longer ones are
 * cut and marked), bytes as hex with their length, nulls as null. Nothing here is stored, logged
 * or sent anywhere; the caller shows it and forgets it.
 *
 * @param columns   the asset's columns with their kinds
 * @param rows      each row's values, in column order; a null value is null
 * @param truncated true when the byte cap stopped the rows short of what was asked for
 * @param details   what the connector detected about the asset (for files: format, delimiter,
 *                  header, encoding); empty when it has nothing to say
 */
public record Preview(List<Column> columns, List<List<String>> rows, boolean truncated, Map<String, String> details) {

    /** The most characters shown of one value. */
    public static final int MAX_VALUE = 500;
    /** The most rows a preview reads. */
    public static final int MAX_ROWS = 1000;
    /** The most bytes (UTF-8) of values one preview holds: 2 MB. */
    public static final long MAX_BYTES = 2_000_000;

    public Preview {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
        details = Map.copyOf(details == null ? Map.of() : details);
    }

    /** One column: its name, the engine's kind for it, and the source's own type name if known. */
    public record Column(String name, String kind, String sourceType) {
    }

    /**
     * Reads up to {@code rows} rows of {@code asset} through {@code connector}'s reader, as
     * {@link Connector#preview} does by default.
     *
     * @throws ConnectorException when the read fails, with the reader's own message
     */
    public static Preview read(Connector connector, SourceConfig source, AssetInfo asset, int rows)
        throws ConnectorException {
        if (!asset.readable()) {
            throw new ConnectorException(asset.path() + " is recorded but not read as rows"
                + (asset.description() == null ? " (it is a document)" : ": " + asset.description()));
        }
        int limit = Math.max(1, Math.min(rows, MAX_ROWS));
        String urn = "urn:kilnmark:" + source.name() + ":" + asset.path(); // readers expect a URN; nothing is stored under it
        StepSpec reader = connector.reader(source, asset, new Connector.ReadOptions(urn, limit,
            Math.min(limit, 1000)));
        Collector collector = new Collector(limit);
        Pipeline p = new Pipeline("preview", List.of(reader, new StepSpec("preview", collector, Map.of())));
        RunResult result;
        try {
            result = new Runner(128L << 20, 2).run(p, event -> { });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConnectorException("The preview was stopped before it finished");
        }
        if (!result.succeeded()) {
            throw new ConnectorException("Cannot read " + asset.path() + ": " + result.error());
        }
        List<Column> columns = new ArrayList<>();
        if (!asset.fields().isEmpty()) {
            asset.fields().forEach(f -> columns.add(new Column(f.name(), f.kind(), f.sourceType())));
        } else {
            collector.names.forEach(n -> columns.add(new Column(n, null, null)));
        }
        return new Preview(columns, collector.rows, collector.truncated, Map.of());
    }

    /** The last step: keeps the first rows as text, and drains the rest so the reader can finish. */
    private static final class Collector implements Step {
        private final int limit;
        private final List<List<String>> rows = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private long bytes;
        private boolean truncated;

        Collector(int limit) {
            this.limit = limit;
        }

        @Override
        public String type() {
            return "preview";
        }

        @Override
        public void init(StepContext context) {
            // nothing to prepare
        }

        @Override
        public boolean process(StepContext context) throws StepException {
            VectorSchemaRoot batch;
            try {
                batch = context.input().take(context.allocator());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepException("Interrupted", e);
            }
            if (batch == null) {
                return false;
            }
            try (batch) {
                if (names.isEmpty()) {
                    batch.getSchema().getFields().forEach(f -> names.add(f.getName()));
                }
                List<FieldVector> vectors = batch.getFieldVectors();
                for (int r = 0; r < batch.getRowCount() && rows.size() < limit && !truncated; r++) {
                    List<String> row = new ArrayList<>(vectors.size());
                    for (FieldVector v : vectors) {
                        String text = text(v.isNull(r) ? null : v.getObject(r));
                        bytes += text == null ? 4 : utf8Length(text);
                        row.add(text);
                    }
                    if (bytes > MAX_BYTES) {
                        truncated = true;
                    } else {
                        rows.add(row);
                    }
                }
            }
            return true;
        }
    }

    /** The length of a string in UTF-8, without encoding it. */
    static long utf8Length(String s) {
        long n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                n++;
            } else if (c < 0x800) {
                n += 2;
            } else if (Character.isHighSurrogate(c)) {
                n += 4;
                i++;
            } else {
                n += 3;
            }
        }
        return n;
    }

    /** A value as people read it: text cut at {@value #MAX_VALUE} characters, bytes as hex with their length. */
    static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            int shown = Math.min(bytes.length, MAX_VALUE / 2 - 8);
            String hex = "0x" + HexFormat.of().withUpperCase().formatHex(bytes, 0, shown);
            return hex + (shown < bytes.length ? "… " : " ") + "(" + bytes.length + " bytes)";
        }
        String s = String.valueOf(value);
        return s.length() <= MAX_VALUE ? s : s.substring(0, MAX_VALUE) + "… (" + s.length() + " characters)";
    }
}
