package io.kilnmark.kiln.plugins.files;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.write.WriteColumn;

/**
 * Writes Apache Parquet from Arrow batches, streaming: row groups go to the stream as they fill
 * and the footer at the end, so the same code writes to a local file and to an S3 multipart
 * upload. Hardwood, which reads Parquet here, cannot write it, and parquet-java needs Hadoop
 * (ADR 0015), so this is Kilnmark's own writer for the flat tables pipelines produce.
 *
 * <p>What it writes, from the Apache Parquet format specification: format version 1, every column
 * {@code OPTIONAL}, data pages v1 of about 1 MiB with {@code PLAIN} values and {@code RLE}
 * definition levels, row groups of about {@value #ROW_GROUP_BYTES_DEFAULT} bytes of encoded pages,
 * and no dictionaries, statistics or indexes. Pages are compressed with Snappy (the default,
 * through snappy-java, which reading already uses), ZSTD (zstd-jni), GZIP (the JDK) or not at all.
 *
 * <p>Types, by the engine's kind: boolean as {@code BOOLEAN}; int16 as {@code INT32} with
 * {@code INT(16, signed)}; int32 as {@code INT32}; int64 as {@code INT64}; float32 and float64 as
 * {@code FLOAT} and {@code DOUBLE}; decimal as {@code DECIMAL(p, s)} on {@code INT32} up to 9 digits,
 * {@code INT64} up to 18 and a fixed-length byte array beyond; text as {@code BYTE_ARRAY} with
 * {@code STRING}; binary as {@code BYTE_ARRAY}; date as {@code INT32 DATE}; time as
 * {@code INT64 TIME(MICROS)}; timestamps as {@code INT64 TIMESTAMP(MICROS)}, adjusted to UTC for
 * timestamp_tz. The legacy converted types are written alongside where they mean the same, for
 * older readers.
 */
final class ParquetEncoder implements RowEncoder {

    /** Encoded page bytes a row group gathers before it is written out. */
    static final long ROW_GROUP_BYTES_DEFAULT = 64L << 20;

    /** Uncompressed page size at which a page is closed. */
    static final int PAGE_BYTES = 1 << 20;

    /** Parquet's codec numbers. */
    enum Codec {
        NONE(0), SNAPPY(1), GZIP(2), ZSTD(6);

        final int id;

        Codec(int id) {
            this.id = id;
        }

        /** The codec a {@code compression} setting names; Snappy by default. */
        static Codec of(String setting) throws ConnectorException {
            if (setting == null || setting.isBlank()) {
                return SNAPPY;
            }
            return switch (setting.strip().toLowerCase(Locale.ROOT)) {
                case "none", "uncompressed" -> NONE;
                case "snappy" -> SNAPPY;
                case "gzip" -> GZIP;
                case "zstd" -> ZSTD;
                default -> throw new ConnectorException("compression: snappy (the default), zstd, gzip or none, not '"
                    + setting + "'");
            };
        }

        byte[] compress(byte[] data, int length) throws IOException {
            return switch (this) {
                case NONE -> Arrays.copyOf(data, length);
                case SNAPPY -> {
                    ParquetRuntime.ensureNativeDirectory();
                    byte[] out = new byte[org.xerial.snappy.Snappy.maxCompressedLength(length)];
                    int n = org.xerial.snappy.Snappy.compress(data, 0, length, out, 0);
                    yield Arrays.copyOf(out, n);
                }
                case GZIP -> {
                    ByteArrayOutputStream b = new ByteArrayOutputStream(Math.max(64, length / 2));
                    try (GZIPOutputStream z = new GZIPOutputStream(b)) {
                        z.write(data, 0, length);
                    }
                    yield b.toByteArray();
                }
                case ZSTD -> {
                    byte[] out = new byte[(int) com.github.luben.zstd.Zstd.compressBound(length)];
                    long n = com.github.luben.zstd.Zstd.compressByteArray(out, 0, out.length, data, 0, length, 3);
                    if (com.github.luben.zstd.Zstd.isError(n)) {
                        throw new IOException("ZSTD compression failed: " + com.github.luben.zstd.Zstd.getErrorName(n));
                    }
                    yield Arrays.copyOf(out, (int) n);
                }
            };
        }
    }

    // Parquet's physical types
    private static final int BOOLEAN = 0;
    private static final int INT32 = 1;
    private static final int INT64 = 2;
    private static final int FLOAT = 4;
    private static final int DOUBLE = 5;
    private static final int BYTE_ARRAY = 6;
    private static final int FIXED_LEN_BYTE_ARRAY = 7;

    private final Counting out;
    private final List<WriteColumn> columns;
    private final ColumnBuffer[] buffers;
    private final Codec codec;
    private final long rowGroupBytes;
    private final List<RowGroup> rowGroups = new ArrayList<>();
    private long groupRows;
    private long totalRows;

    ParquetEncoder(List<WriteColumn> columns, OutputStream stream, Codec codec, long rowGroupBytes) throws IOException {
        this.columns = columns;
        this.codec = codec;
        this.rowGroupBytes = rowGroupBytes;
        this.out = new Counting(stream);
        this.buffers = new ColumnBuffer[columns.size()];
        for (int c = 0; c < columns.size(); c++) {
            buffers[c] = new ColumnBuffer(columns.get(c));
        }
        out.write("PAR1".getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public void write(VectorSchemaRoot batch) throws IOException {
        int n = batch.getRowCount();
        for (int c = 0; c < buffers.length; c++) {
            buffers[c].add(batch.getVector(c), n);
        }
        groupRows += n;
        totalRows += n;
        long buffered = 0;
        for (ColumnBuffer b : buffers) {
            buffered += b.buffered();
        }
        if (buffered >= rowGroupBytes) {
            flushRowGroup();
        }
    }

    private void flushRowGroup() throws IOException {
        if (groupRows == 0) {
            return;
        }
        List<Chunk> chunks = new ArrayList<>(buffers.length);
        for (ColumnBuffer b : buffers) {
            chunks.add(b.writeChunk(out));
        }
        rowGroups.add(new RowGroup(chunks, groupRows));
        groupRows = 0;
    }

    @Override
    public void finish() throws IOException {
        flushRowGroup();
        byte[] footer = footer();
        out.write(footer);
        out.write(new byte[] {(byte) footer.length, (byte) (footer.length >>> 8), (byte) (footer.length >>> 16),
            (byte) (footer.length >>> 24)});
        out.write("PAR1".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @Override
    public List<String> types() {
        List<String> out = new ArrayList<>();
        for (WriteColumn c : columns) {
            out.add(switch (c.kind()) {
                case BOOLEAN -> "BOOLEAN";
                case INT16 -> "INT32 INT(16)";
                case INT32 -> "INT32";
                case INT64 -> "INT64";
                case FLOAT32 -> "FLOAT";
                case FLOAT64 -> "DOUBLE";
                case DECIMAL -> (c.precision() <= 9 ? "INT32" : c.precision() <= 18 ? "INT64" : "FIXED_LEN_BYTE_ARRAY")
                    + " DECIMAL(" + c.precision() + "," + c.scale() + ")";
                case TEXT -> "BYTE_ARRAY STRING";
                case BINARY -> "BYTE_ARRAY";
                case DATE -> "INT32 DATE";
                case TIME -> "INT64 TIME(MICROS)";
                case TIMESTAMP -> "INT64 TIMESTAMP(MICROS)";
                case TIMESTAMP_TZ -> "INT64 TIMESTAMP(MICROS, UTC)";
            });
        }
        return out;
    }

    // ------------------------------------------------------------------ the footer

    private byte[] footer() {
        Compact f = new Compact();
        f.begin();
        f.i32(1, 1); // version
        f.list(2, Compact.STRUCT, columns.size() + 1);
        f.begin();
        f.binary(4, "schema");
        f.i32(5, columns.size());
        f.end();
        for (WriteColumn c : columns) {
            schemaElement(f, c);
        }
        f.i64(3, totalRows);
        f.list(4, Compact.STRUCT, rowGroups.size());
        for (RowGroup g : rowGroups) {
            f.begin();
            f.list(1, Compact.STRUCT, g.chunks().size());
            long uncompressed = 0;
            long compressed = 0;
            for (int c = 0; c < g.chunks().size(); c++) {
                Chunk k = g.chunks().get(c);
                uncompressed += k.uncompressed();
                compressed += k.compressed();
                f.begin();
                f.i64(2, k.offset()); // file_offset
                f.struct(3); // ColumnMetaData
                f.i32(1, physical(columns.get(c)));
                f.list(2, Compact.I32, 2);
                f.rawI32(0); // PLAIN
                f.rawI32(3); // RLE
                f.list(3, Compact.BINARY, 1);
                f.rawBinary(columns.get(c).name());
                f.i32(4, codec.id);
                f.i64(5, k.values());
                f.i64(6, k.uncompressed());
                f.i64(7, k.compressed());
                f.i64(9, k.offset()); // data_page_offset
                f.end();
                f.end();
            }
            f.i64(2, uncompressed);
            f.i64(3, g.rows());
            f.i64(5, g.chunks().isEmpty() ? 4 : g.chunks().get(0).offset());
            f.i64(6, compressed);
            f.end();
        }
        f.binary(6, "kilnmark");
        f.end();
        return f.bytes();
    }

    private static void schemaElement(Compact f, WriteColumn c) {
        f.begin();
        f.i32(1, physical(c));
        if (c.kind() == Kind.DECIMAL && c.precision() > 18) {
            f.i32(2, decimalBytes(c.precision()));
        }
        f.i32(3, 1); // OPTIONAL
        f.binary(4, c.name());
        switch (c.kind()) {
            case INT16 -> {
                f.i32(6, 16); // INT_16
                f.struct(10);
                f.struct(10); // INTEGER
                f.i8(1, 16);
                f.bool(2, true);
                f.end();
                f.end();
            }
            case DECIMAL -> {
                f.i32(6, 5);
                f.i32(7, c.scale());
                f.i32(8, c.precision());
                f.struct(10);
                f.struct(5);
                f.i32(1, c.scale());
                f.i32(2, c.precision());
                f.end();
                f.end();
            }
            case TEXT -> {
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
            case TIME -> {
                f.struct(10);
                f.struct(7);
                f.bool(1, false);
                f.struct(2);
                f.struct(2); // MICROS
                f.end();
                f.end();
                f.end();
                f.end();
            }
            case TIMESTAMP, TIMESTAMP_TZ -> {
                boolean utc = c.kind() == Kind.TIMESTAMP_TZ;
                if (utc) {
                    f.i32(6, 10); // TIMESTAMP_MICROS, which means adjusted to UTC
                }
                f.struct(10);
                f.struct(8);
                f.bool(1, utc);
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

    private static int physical(WriteColumn c) {
        return switch (c.kind()) {
            case BOOLEAN -> BOOLEAN;
            case INT16, INT32, DATE -> INT32;
            case INT64, TIME, TIMESTAMP, TIMESTAMP_TZ -> INT64;
            case FLOAT32 -> FLOAT;
            case FLOAT64 -> DOUBLE;
            case DECIMAL -> c.precision() <= 9 ? INT32 : c.precision() <= 18 ? INT64 : FIXED_LEN_BYTE_ARRAY;
            case TEXT, BINARY -> BYTE_ARRAY;
        };
    }

    /** The fewest bytes holding every unscaled value of {@code precision} digits, with its sign. */
    static int decimalBytes(int precision) {
        BigInteger max = BigInteger.TEN.pow(precision).subtract(BigInteger.ONE);
        return (max.bitLength() + 1 + 7) / 8;
    }

    private record Chunk(long offset, long values, long uncompressed, long compressed) {
    }

    private record RowGroup(List<Chunk> chunks, long rows) {
    }

    // ------------------------------------------------------------------ one column's pages

    /** One column's current page and the finished pages of its current column chunk. */
    private final class ColumnBuffer {

        private final WriteColumn column;
        private final int physical;
        private final int fixedLength;
        private final Bytes values = new Bytes(1 << 12);
        private final Bytes chunk = new Bytes(1 << 12);
        private boolean[] defined = new boolean[1024];
        private int pageValues;
        private int bits;
        private int bitCount;
        private long chunkValues;
        private long chunkUncompressed;

        ColumnBuffer(WriteColumn column) {
            this.column = column;
            this.physical = physical(column);
            this.fixedLength = physical == FIXED_LEN_BYTE_ARRAY ? decimalBytes(column.precision()) : 0;
        }

        long buffered() {
            return chunk.size() + values.size();
        }

        void add(org.apache.arrow.vector.FieldVector v, int n) throws IOException {
            for (int row = 0; row < n; row++) {
                Object value = column.value(v, row);
                if (pageValues == defined.length) {
                    defined = Arrays.copyOf(defined, defined.length * 2);
                }
                defined[pageValues++] = value != null;
                if (value != null) {
                    encode(value);
                }
                if (values.size() >= PAGE_BYTES) {
                    flushPage();
                }
            }
        }

        private void encode(Object value) {
            switch (column.kind()) {
                case BOOLEAN -> {
                    if ((Boolean) value) {
                        bits |= 1 << bitCount;
                    }
                    if (++bitCount == 8) {
                        values.write(bits);
                        bits = 0;
                        bitCount = 0;
                    }
                }
                case INT16, INT32 -> values.int32(((Long) value).intValue());
                case INT64 -> values.int64((Long) value);
                case FLOAT32 -> values.int32(Float.floatToRawIntBits((Float) value));
                case FLOAT64 -> values.int64(Double.doubleToRawLongBits((Double) value));
                case DECIMAL -> {
                    BigInteger unscaled = ((BigDecimal) value).setScale(column.scale(), RoundingMode.HALF_EVEN)
                        .unscaledValue();
                    if (physical == INT32) {
                        values.int32(unscaled.intValueExact());
                    } else if (physical == INT64) {
                        values.int64(unscaled.longValueExact());
                    } else {
                        byte[] b = unscaled.toByteArray();
                        if (b.length > fixedLength) {
                            throw new ArithmeticException("the value " + value + " has more digits than decimal("
                                + column.precision() + "," + column.scale() + ") holds");
                        }
                        byte pad = (byte) (unscaled.signum() < 0 ? 0xFF : 0);
                        for (int i = b.length; i < fixedLength; i++) {
                            values.write(pad);
                        }
                        values.write(b, 0, b.length);
                    }
                }
                case TEXT -> {
                    byte[] b = ((String) value).getBytes(StandardCharsets.UTF_8);
                    values.int32(b.length);
                    values.write(b, 0, b.length);
                }
                case BINARY -> {
                    byte[] b = (byte[]) value;
                    values.int32(b.length);
                    values.write(b, 0, b.length);
                }
                case DATE -> values.int32((int) ((LocalDate) value).toEpochDay());
                case TIME -> values.int64(((LocalTime) value).toNanoOfDay() / 1_000);
                case TIMESTAMP -> {
                    LocalDateTime t = (LocalDateTime) value;
                    values.int64(Math.addExact(Math.multiplyExact(t.toEpochSecond(ZoneOffset.UTC), 1_000_000L),
                        t.getNano() / 1_000));
                }
                case TIMESTAMP_TZ -> {
                    Instant t = (Instant) value;
                    values.int64(Math.addExact(Math.multiplyExact(t.getEpochSecond(), 1_000_000L), t.getNano() / 1_000));
                }
            }
        }

        /** Closes the current page: definition levels, then values, compressed, after its header. */
        void flushPage() throws IOException {
            if (pageValues == 0) {
                return;
            }
            if (bitCount > 0) {
                values.write(bits);
                bits = 0;
                bitCount = 0;
            }
            Bytes levels = new Bytes(16 + pageValues / 8);
            int present = 0;
            for (int i = 0; i < pageValues; i++) {
                present += defined[i] ? 1 : 0;
            }
            if (present == pageValues || present == 0) {
                levels.varint((long) pageValues << 1); // one RLE run
                levels.write(present == 0 ? 0 : 1);
            } else {
                int groups = (pageValues + 7) / 8; // bit-packed, eight levels a byte, the last group padded
                levels.varint((long) groups << 1 | 1);
                for (int g = 0; g < groups; g++) {
                    int b = 0;
                    for (int i = 0; i < 8; i++) {
                        int at = g * 8 + i;
                        if (at < pageValues && defined[at]) {
                            b |= 1 << i;
                        }
                    }
                    levels.write(b);
                }
            }
            Bytes body = new Bytes(4 + levels.size() + values.size());
            body.int32(levels.size());
            body.write(levels.array(), 0, levels.size());
            body.write(values.array(), 0, values.size());
            byte[] compressed = codec.compress(body.array(), body.size());

            Compact h = new Compact();
            h.begin();
            h.i32(1, 0); // DATA_PAGE
            h.i32(2, body.size());
            h.i32(3, compressed.length);
            h.struct(5);
            h.i32(1, pageValues);
            h.i32(2, 0); // PLAIN
            h.i32(3, 3); // RLE
            h.i32(4, 3); // RLE
            h.end();
            h.end();
            byte[] header = h.bytes();
            chunk.write(header, 0, header.length);
            chunk.write(compressed, 0, compressed.length);
            chunkUncompressed += header.length + body.size();
            chunkValues += pageValues;
            pageValues = 0;
            values.reset();
        }

        /** Writes the column chunk to {@code out} and starts the next one. */
        Chunk writeChunk(Counting out) throws IOException {
            flushPage();
            long offset = out.count();
            out.write(chunk.array(), 0, chunk.size());
            Chunk c = new Chunk(offset, chunkValues, chunkUncompressed, chunk.size());
            chunk.reset();
            chunkValues = 0;
            chunkUncompressed = 0;
            return c;
        }
    }

    // ------------------------------------------------------------------ bytes

    /** A growable little-endian byte buffer. */
    private static final class Bytes {

        private byte[] buf;
        private int size;

        Bytes(int capacity) {
            buf = new byte[Math.max(16, capacity)];
        }

        private void room(int n) {
            if (size + n > buf.length) {
                long want = Math.max((long) buf.length * 2, (long) size + n);
                if (want > Integer.MAX_VALUE - 16) {
                    throw new IllegalStateException("A Parquet page or column chunk grew past 2 GiB");
                }
                buf = Arrays.copyOf(buf, (int) want);
            }
        }

        void write(int b) {
            room(1);
            buf[size++] = (byte) b;
        }

        void write(byte[] b, int off, int len) {
            room(len);
            System.arraycopy(b, off, buf, size, len);
            size += len;
        }

        void int32(int v) {
            room(4);
            buf[size++] = (byte) v;
            buf[size++] = (byte) (v >>> 8);
            buf[size++] = (byte) (v >>> 16);
            buf[size++] = (byte) (v >>> 24);
        }

        void int64(long v) {
            room(8);
            for (int i = 0; i < 8; i++) {
                buf[size++] = (byte) (v >>> (8 * i));
            }
        }

        void varint(long v) {
            while ((v & ~0x7FL) != 0) {
                write((int) ((v & 0x7F) | 0x80));
                v >>>= 7;
            }
            write((int) v);
        }

        byte[] array() {
            return buf;
        }

        int size() {
            return size;
        }

        void reset() {
            size = 0;
            if (buf.length > 4 * PAGE_BYTES) {
                buf = new byte[1 << 12]; // a large row group's chunk is not kept for the next one
            }
        }
    }

    /** Counts the bytes written, for the offsets the footer records. */
    private static final class Counting {

        private final OutputStream out;
        private long count;

        Counting(OutputStream out) {
            this.out = out;
        }

        void write(byte[] b) throws IOException {
            write(b, 0, b.length);
        }

        void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }

        void flush() throws IOException {
            out.flush();
        }
    }

    /** Just enough of Thrift's compact protocol for Parquet page headers and the footer. */
    private static final class Compact {

        static final int I32 = 5;
        static final int BINARY = 8;
        static final int STRUCT = 12;

        private final Bytes out = new Bytes(256);
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
                out.varint(((id << 1) ^ (id >> 31)) & 0xFFFFFFFFL);
            }
            last = id;
        }

        void bool(int id, boolean v) {
            header(id, v ? 1 : 2);
        }

        void i8(int id, int v) {
            header(id, 3);
            out.write(v);
        }

        void i32(int id, int v) {
            header(id, I32);
            rawI32(v);
        }

        void rawI32(int v) {
            out.varint(((v << 1) ^ (v >> 31)) & 0xFFFFFFFFL);
        }

        void i64(int id, long v) {
            header(id, 6);
            out.varint((v << 1) ^ (v >> 63));
        }

        void binary(int id, String s) {
            header(id, BINARY);
            rawBinary(s);
        }

        void rawBinary(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            out.varint(b.length);
            out.write(b, 0, b.length);
        }

        /** A struct field; {@link #end} closes it. */
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
                out.varint(size);
            }
        }

        byte[] bytes() {
            return Arrays.copyOf(out.array(), out.size());
        }
    }
}
