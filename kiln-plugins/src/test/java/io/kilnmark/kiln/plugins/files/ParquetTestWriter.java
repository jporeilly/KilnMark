package io.kilnmark.kiln.plugins.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Writes small Parquet files for tests, so test data is generated rather than checked in and no
 * Parquet writer (with its Hadoop dependencies) is needed. One row group, one PLAIN-encoded v1
 * data page per leaf column, every column optional, structs of primitives allowed (no lists).
 * Pages are not compressed, but the codec written in the metadata can be chosen, to test how a
 * reader treats codecs it lacks.
 */
final class ParquetTestWriter {

    enum Type { BOOLEAN, INT32, INT64, DOUBLE, STRING, BYTES, DATE, TIMESTAMP_UTC, TIMESTAMP_LOCAL, DECIMAL, STRUCT }

    /** Parquet's codec numbers. */
    static final int UNCOMPRESSED = 0;
    static final int BROTLI = 4;

    /** A column; a struct's value in a row is an {@code Object[]} of its children's values. */
    record Col(String name, Type type, int precision, int scale, List<Col> children) {

        static Col of(String name, Type type) {
            return new Col(name, type, 0, 0, List.of());
        }

        static Col decimal(String name, int precision, int scale) {
            return new Col(name, Type.DECIMAL, precision, scale, List.of());
        }

        static Col struct(String name, Col... children) {
            return new Col(name, Type.STRUCT, 0, 0, List.of(children));
        }
    }

    /** One leaf column: its path, type, highest definition level and how to find its value in a row. */
    private record Leaf(List<String> path, Col col, int maxDef, Function<Object[], Object[]> valueAndLevel) {
    }

    private ParquetTestWriter() {
    }

    static void write(Path file, List<Col> cols, List<Object[]> rows) throws IOException {
        write(file, cols, rows, UNCOMPRESSED);
    }

    static void write(Path file, List<Col> cols, List<Object[]> rows, int codec) throws IOException {
        List<Leaf> leaves = new ArrayList<>();
        for (int c = 0; c < cols.size(); c++) {
            Col col = cols.get(c);
            int index = c;
            if (col.type() == Type.STRUCT) {
                for (int k = 0; k < col.children().size(); k++) {
                    int child = k;
                    leaves.add(new Leaf(List.of(col.name(), col.children().get(k).name()), col.children().get(k), 2,
                        row -> {
                            Object[] s = (Object[]) row[index];
                            if (s == null) {
                                return new Object[] {null, 0};
                            }
                            return new Object[] {s[child], s[child] == null ? 1 : 2};
                        }));
                }
            } else {
                leaves.add(new Leaf(List.of(col.name()), col, 1,
                    row -> new Object[] {row[index], row[index] == null ? 0 : 1}));
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("PAR1".getBytes(StandardCharsets.US_ASCII));
        long[] offsets = new long[leaves.size()];
        long[] sizes = new long[leaves.size()];
        for (int c = 0; c < leaves.size(); c++) {
            byte[] body = page(leaves.get(c), rows);
            Compact h = new Compact();
            h.begin();
            h.i32(1, 0); // DATA_PAGE
            h.i32(2, body.length);
            h.i32(3, body.length);
            h.struct(5);
            h.i32(1, rows.size());
            h.i32(2, 0); // PLAIN
            h.i32(3, 3); // RLE
            h.i32(4, 3);
            h.end();
            h.end();
            offsets[c] = out.size();
            byte[] header = h.bytes();
            out.writeBytes(header);
            out.writeBytes(body);
            sizes[c] = header.length + body.length;
        }
        Compact f = new Compact();
        f.begin();
        f.i32(1, 1);
        int elements = 1;
        for (Col col : cols) {
            elements += 1 + col.children().size();
        }
        f.list(2, Compact.STRUCT, elements);
        f.begin();
        f.binary(4, "schema");
        f.i32(5, cols.size());
        f.end();
        for (Col col : cols) {
            if (col.type() == Type.STRUCT) {
                f.begin();
                f.i32(3, 1); // OPTIONAL
                f.binary(4, col.name());
                f.i32(5, col.children().size());
                f.end();
                for (Col child : col.children()) {
                    schemaElement(f, child);
                }
            } else {
                schemaElement(f, col);
            }
        }
        f.i64(3, rows.size());
        f.list(4, Compact.STRUCT, 1);
        f.begin();
        f.list(1, Compact.STRUCT, leaves.size());
        long total = 0;
        for (int c = 0; c < leaves.size(); c++) {
            Leaf leaf = leaves.get(c);
            total += sizes[c];
            f.begin();
            f.i64(2, offsets[c]);
            f.struct(3);
            f.i32(1, physical(leaf.col().type()));
            f.list(2, Compact.I32, 2);
            f.rawI32(0);
            f.rawI32(3);
            f.list(3, Compact.BINARY, leaf.path().size());
            for (String p : leaf.path()) {
                f.rawBinary(p);
            }
            f.i32(4, codec);
            f.i64(5, rows.size());
            f.i64(6, sizes[c]);
            f.i64(7, sizes[c]);
            f.i64(9, offsets[c]);
            f.end();
            f.end();
        }
        f.i64(2, total);
        f.i64(3, rows.size());
        f.end();
        f.binary(6, "kilnmark test writer");
        f.end();
        finish(file, out, f.bytes());
    }

    /**
     * A file with no rows whose schema nests {@code depth} groups inside each other: valid Parquet,
     * built to find readers that walk schemas recursively.
     */
    static void writeDeeplyNested(Path file, int depth) throws IOException {
        Compact f = new Compact();
        f.begin();
        f.i32(1, 1);
        f.list(2, Compact.STRUCT, depth + 2);
        f.begin();
        f.binary(4, "schema");
        f.i32(5, 1);
        f.end();
        for (int i = 0; i < depth; i++) {
            f.begin();
            f.i32(3, 1);
            f.binary(4, "g" + i);
            f.i32(5, 1);
            f.end();
        }
        schemaElement(f, Col.of("leaf", Type.INT32));
        f.i64(3, 0);
        f.list(4, Compact.STRUCT, 0);
        f.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("PAR1".getBytes(StandardCharsets.US_ASCII));
        finish(file, out, f.bytes());
    }

    private static void finish(Path file, ByteArrayOutputStream out, byte[] footer) throws IOException {
        out.writeBytes(footer);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(footer.length).array());
        out.writeBytes("PAR1".getBytes(StandardCharsets.US_ASCII));
        Files.write(file, out.toByteArray());
    }

    private static void schemaElement(Compact f, Col col) {
        f.begin();
        f.i32(1, physical(col.type()));
        f.i32(3, 1); // OPTIONAL
        f.binary(4, col.name());
        switch (col.type()) {
            case STRING -> {
                f.i32(6, 0); // UTF8
                f.struct(10);
                f.struct(1);
                f.end();
                f.end();
            }
            case DATE -> {
                f.i32(6, 6);
                f.struct(10);
                f.struct(6);
                f.end();
                f.end();
            }
            case DECIMAL -> {
                f.i32(6, 5);
                f.i32(7, col.scale());
                f.i32(8, col.precision());
                f.struct(10);
                f.struct(5);
                f.i32(1, col.scale());
                f.i32(2, col.precision());
                f.end();
                f.end();
            }
            case TIMESTAMP_UTC, TIMESTAMP_LOCAL -> {
                f.struct(10);
                f.struct(8);
                f.bool(1, col.type() == Type.TIMESTAMP_UTC);
                f.struct(2);
                f.struct(2); // MICROS
                f.end();
                f.end();
                f.end();
                f.end();
            }
            default -> { }
        }
        f.end();
    }

    private static int physical(Type t) {
        return switch (t) {
            case BOOLEAN -> 0;
            case INT32, DATE -> 1;
            case INT64, TIMESTAMP_UTC, TIMESTAMP_LOCAL, DECIMAL -> 2;
            case DOUBLE -> 5;
            case STRING, BYTES -> 6;
            case STRUCT -> throw new IllegalArgumentException("a struct is not a leaf");
        };
    }

    private static byte[] page(Leaf leaf, List<Object[]> rows) {
        int[] levels = new int[rows.size()];
        List<Object> present = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Object[] vl = leaf.valueAndLevel().apply(rows.get(i));
            levels[i] = (Integer) vl[1];
            if (levels[i] == leaf.maxDef()) {
                present.add(vl[0]);
            }
        }
        // definition levels: RLE runs, one byte per value (bit widths 1 and 2)
        ByteArrayOutputStream rle = new ByteArrayOutputStream();
        int i = 0;
        while (i < levels.length) {
            int level = levels[i];
            int run = 0;
            while (i < levels.length && levels[i] == level) {
                run++;
                i++;
            }
            Compact.varint(rle, (long) run << 1);
            rle.write(level);
        }
        int text = 0;
        for (Object v : present) {
            text += v instanceof String s ? s.getBytes(StandardCharsets.UTF_8).length : v instanceof byte[] b ? b.length : 0;
        }
        ByteBuffer values = ByteBuffer.allocate(64 + present.size() * 16 + text).order(ByteOrder.LITTLE_ENDIAN);
        int bits = 0;
        int bitCount = 0;
        for (Object v : present) {
            switch (leaf.col().type()) {
                case BOOLEAN -> {
                    if ((Boolean) v) {
                        bits |= 1 << bitCount;
                    }
                    if (++bitCount == 8) {
                        values.put((byte) bits);
                        bits = 0;
                        bitCount = 0;
                    }
                }
                case INT32 -> values.putInt((Integer) v);
                case DATE -> values.putInt((int) ((LocalDate) v).toEpochDay());
                case INT64 -> values.putLong((Long) v);
                case DOUBLE -> values.putDouble((Double) v);
                case STRING -> {
                    byte[] b = ((String) v).getBytes(StandardCharsets.UTF_8);
                    values.putInt(b.length).put(b);
                }
                case BYTES -> values.putInt(((byte[]) v).length).put((byte[]) v);
                case TIMESTAMP_UTC -> values.putLong(ChronoUnit.MICROS.between(Instant.EPOCH, (Instant) v));
                case TIMESTAMP_LOCAL -> values.putLong(ChronoUnit.MICROS.between(Instant.EPOCH,
                    ((LocalDateTime) v).toInstant(ZoneOffset.UTC)));
                case DECIMAL -> values.putLong(((BigDecimal) v).setScale(leaf.col().scale()).unscaledValue()
                    .longValueExact());
                case STRUCT -> throw new IllegalArgumentException("a struct is not a leaf");
            }
        }
        if (bitCount > 0) {
            values.put((byte) bits);
        }
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        byte[] l = rle.toByteArray();
        page.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(l.length).array());
        page.writeBytes(l);
        page.write(values.array(), 0, values.position());
        return page.toByteArray();
    }

    /** Just enough of Thrift's compact protocol for a Parquet footer and page headers. */
    private static final class Compact {

        static final int I32 = 5;
        static final int BINARY = 8;
        static final int STRUCT = 12;

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final Deque<Integer> stack = new ArrayDeque<>();
        private int last;

        void begin() {
            stack.push(last);
            last = 0;
        }

        void end() {
            out.write(0);
            last = stack.pop();
        }

        private void header(int id, int type) {
            int delta = id - last;
            if (delta > 0 && delta <= 15) {
                out.write(delta << 4 | type);
            } else {
                out.write(type);
                varint(out, ((id << 1) ^ (id >> 31)) & 0xFFFFFFFFL);
            }
            last = id;
        }

        void i32(int id, int v) {
            header(id, I32);
            rawI32(v);
        }

        void rawI32(int v) {
            varint(out, ((v << 1) ^ (v >> 31)) & 0xFFFFFFFFL);
        }

        void i64(int id, long v) {
            header(id, 6);
            varint(out, (v << 1) ^ (v >> 63));
        }

        void bool(int id, boolean v) {
            header(id, v ? 1 : 2);
        }

        void binary(int id, String s) {
            header(id, BINARY);
            rawBinary(s);
        }

        void rawBinary(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            varint(out, b.length);
            out.writeBytes(b);
        }

        /** A struct field; call {@link #end} after its fields. */
        void struct(int id) {
            header(id, STRUCT);
            begin();
        }

        void list(int id, int elementType, int size) {
            header(id, 9);
            if (size < 15) {
                out.write(size << 4 | elementType);
            } else {
                out.write(0xF0 | elementType);
                varint(out, size);
            }
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        static void varint(ByteArrayOutputStream out, long v) {
            while ((v & ~0x7FL) != 0) {
                out.write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            out.write((int) v);
        }
    }
}
