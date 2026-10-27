package io.kilnmark.kiln.plugins.profile;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.SplittableRandom;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.datasketches.hash.MurmurHash3;

/**
 * Chooses a sample of whole rows as batches stream past.
 *
 * <ul>
 * <li>{@link Mode#KEY} and {@link Mode#ROW}: a bottom-k sample. Each row's key (its key fields, or
 * the whole row) is hashed, and the {@code size} rows with the smallest hashes are kept. The hash
 * has a fixed seed, so the same rows are chosen on every scan. Two tables holding the same key
 * values choose the same keys; a table that only refers to another (orders and customers) does
 * not, because each keeps its own smallest k. A sample of matching rows across a join needs a
 * fixed hash threshold instead of a fixed size: later work (ADR 0020).</li>
 * <li>{@link Mode#RESERVOIR}: reservoir sampling with Algorithm L (Li, 1994), for data without a
 * key such as files. The random numbers come from {@code seed}, so the same input in the same
 * order gives the same sample.</li>
 * </ul>
 *
 * <p>Cells are cut at {@value #CELL} characters, and the rows kept are capped at {@code maxBytes}
 * (about two bytes per character): beyond it the sample keeps fewer rows rather than more memory.
 */
public final class RowSampler {

    /** Longest cell kept in a sample, in characters. */
    public static final int CELL = 200;
    /** Default cap on a sample's size in memory. */
    public static final long DEFAULT_MAX_BYTES = 8L << 20;

    /** How rows are chosen. */
    public enum Mode {
        KEY, ROW, RESERVOIR;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Mode parse(String s) {
            return Mode.valueOf(s.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** The key hash's seed: fixed, so samples line up across scans and across tables. */
    static final long SEED = 0x4B494C4EL;

    private record Sampled(long hash, long index, String[] cells) {
    }

    private static final Comparator<Sampled> LARGEST_FIRST = Comparator.comparingLong(Sampled::hash)
        .thenComparing(s -> String.join("\u001f", Arrays.stream(s.cells()).map(String::valueOf).toList()))
        .reversed();

    private final Mode mode;
    private final List<String> keyFields;
    private int size;
    private final long maxBytes;
    private long bytes;
    private final SplittableRandom random;
    private List<String> columns;
    private int[] keyIndex;
    private final PriorityQueue<Sampled> bottom;
    private final Sampled[] reservoir;
    private long seen;
    // Algorithm L state
    private double w;
    private long next;

    public RowSampler(Mode mode, List<String> keyFields, int size, long seed) {
        this(mode, keyFields, size, seed, DEFAULT_MAX_BYTES);
    }

    public RowSampler(Mode mode, List<String> keyFields, int size, long seed, long maxBytes) {
        this.maxBytes = Math.max(1024, maxBytes);
        this.mode = mode;
        this.keyFields = List.copyOf(keyFields == null ? List.of() : keyFields);
        this.size = Math.max(0, size);
        this.random = new SplittableRandom(seed);
        this.bottom = new PriorityQueue<>(Math.max(1, this.size), LARGEST_FIRST);
        this.reservoir = new Sampled[this.size];
        // (size may shrink below the array's length when the memory cap is reached)
    }

    public Mode mode() {
        return mode;
    }

    /** The key fields actually used: empty unless {@link Mode#KEY} and every key field was present. */
    public List<String> keyFields() {
        return keyIndex == null || mode != Mode.KEY ? List.of() : keyFields;
    }

    /** Rows offered so far. */
    public long seen() {
        return seen;
    }

    public void accept(VectorSchemaRoot batch) {
        if (size == 0) {
            seen += batch.getRowCount();
            return;
        }
        if (columns == null) {
            start(batch);
        }
        List<FieldVector> vectors = batch.getFieldVectors();
        int n = batch.getRowCount();
        for (int i = 0; i < n; i++) {
            long index = seen++;
            if (mode == Mode.RESERVOIR) {
                reservoir(vectors, i, index);
            } else {
                bottomK(vectors, i, index);
            }
        }
    }

    private void start(VectorSchemaRoot batch) {
        columns = batch.getSchema().getFields().stream().map(Field::getName).toList();
        if (mode == Mode.KEY && !keyFields.isEmpty() && columns.containsAll(keyFields)) {
            keyIndex = keyFields.stream().mapToInt(columns::indexOf).toArray();
        } else {
            keyIndex = null; // no usable key: the whole row is the key
        }
        w = Math.exp(Math.log(uniform()) / size);
        next = size + skip();
    }

    private void bottomK(List<FieldVector> vectors, int row, long index) {
        long hash = hash(vectors, row);
        if (bottom.size() == size && hash > bottom.peek().hash()) {
            return; // most rows stop here, before any value is copied
        }
        Sampled s = new Sampled(hash, index, cells(vectors, row));
        if (bottom.size() < size) {
            bottom.add(s);
            bytes += weight(s);
        } else if (LARGEST_FIRST.compare(s, bottom.peek()) > 0) {
            bytes -= weight(bottom.poll());
            bottom.add(s);
            bytes += weight(s);
        } else {
            return;
        }
        // Over the memory cap: drop the largest hashes and keep that many rows from now on.
        while (bytes > maxBytes && bottom.size() > 1) {
            bytes -= weight(bottom.poll());
            size = bottom.size();
        }
    }

    /** Rough bytes a row holds: two per character, plus overhead per cell. */
    private static long weight(Sampled s) {
        long w = 32;
        for (String c : s.cells()) {
            w += 16 + (c == null ? 0 : 2L * c.length());
        }
        return w;
    }

    /** The row's key hash, cell by cell: each cell's own hash is mixed into the running one. */
    private long hash(List<FieldVector> vectors, int row) {
        long h = SEED;
        if (keyIndex != null) {
            for (int k : keyIndex) {
                h = mix(h, cellHash(vectors.get(k), row));
            }
        } else {
            for (FieldVector v : vectors) {
                h = mix(h, cellHash(v, row));
            }
        }
        return h >>> 1; // non-negative
    }

    private static long cellHash(FieldVector v, int row) {
        String text = CellText.of(v, row);
        return text == null ? 0x6E756C6CL : text.isEmpty() ? 0x656D707479L
            : MurmurHash3.hash(text.getBytes(StandardCharsets.UTF_8), SEED)[0];
    }

    private static long mix(long h, long cell) {
        long x = (h ^ cell) * 0x9E3779B97F4A7C15L;
        x ^= x >>> 31;
        x *= 0xBF58476D1CE4E5B9L;
        return x ^ (x >>> 29);
    }

    /** Algorithm L: fill the reservoir, then jump straight to the next row that replaces one. */
    private void reservoir(List<FieldVector> vectors, int row, long index) {
        if (index < size) {
            Sampled s = new Sampled(0, index, cells(vectors, row));
            if (bytes + weight(s) > maxBytes && index > 0) {
                size = (int) index; // the cap is reached: the reservoir holds what it has
                w = Math.exp(Math.log(uniform()) / size);
                next = index + skip();
                return;
            }
            reservoir[(int) index] = s;
            bytes += weight(s);
            return;
        }
        if (index + 1 == next) {
            Sampled s = new Sampled(0, index, cells(vectors, row));
            int slot = random.nextInt(size);
            long after = bytes - weight(reservoir[slot]) + weight(s);
            if (after <= maxBytes) { // a row too big for the cap is skipped, the same way every time
                reservoir[slot] = s;
                bytes = after;
            }
            w *= Math.exp(Math.log(uniform()) / size);
            next += skip();
        }
    }

    private long skip() {
        return (long) Math.floor(Math.log(uniform()) / Math.log(1 - w)) + 1;
    }

    /** A uniform random number in (0, 1): never 0, so its logarithm is finite. */
    private double uniform() {
        double u;
        do {
            u = random.nextDouble();
        } while (u == 0.0);
        return u;
    }

    private static String[] cells(List<FieldVector> vectors, int row) {
        String[] out = new String[vectors.size()];
        for (int c = 0; c < out.length; c++) {
            out[c] = CellText.cut(CellText.of(vectors.get(c), row), CELL);
        }
        return out;
    }

    public List<String> columns() {
        return columns == null ? List.of() : columns;
    }

    /** The sample: by hash for {@code KEY} and {@code ROW} (a stable order), by arrival for {@code RESERVOIR}. */
    public List<String[]> rows() {
        List<Sampled> out = new ArrayList<>();
        if (mode == Mode.RESERVOIR) {
            for (Sampled s : reservoir) {
                if (s != null) {
                    out.add(s);
                }
            }
            out.sort(Comparator.comparingLong(Sampled::index));
        } else {
            out.addAll(bottom);
            out.sort(LARGEST_FIRST.reversed());
        }
        return out.stream().map(Sampled::cells).toList();
    }

    /** The sample as JSON: an array of rows, each an array of cell texts or nulls. */
    public String rowsJson() {
        StringBuilder b = new StringBuilder("[");
        int r = 0;
        for (String[] row : rows()) {
            b.append(r++ == 0 ? "[" : ",[");
            for (int c = 0; c < row.length; c++) {
                b.append(c == 0 ? "" : ",").append(row[c] == null ? "null" : Json.string(row[c]));
            }
            b.append(']');
        }
        return b.append(']').toString();
    }
}
