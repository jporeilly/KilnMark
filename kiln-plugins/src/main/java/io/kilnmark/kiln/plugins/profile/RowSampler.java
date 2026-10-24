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
 * has a fixed seed, so the same rows are chosen on every scan, and tables sampled on the same key
 * values choose matching rows.</li>
 * <li>{@link Mode#RESERVOIR}: reservoir sampling with Algorithm L (Li, 1994), for data without a
 * key such as files. The random numbers come from {@code seed}, so the same input in the same
 * order gives the same sample.</li>
 * </ul>
 */
public final class RowSampler {

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
    private final int size;
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
        this.mode = mode;
        this.keyFields = List.copyOf(keyFields == null ? List.of() : keyFields);
        this.size = Math.max(0, size);
        this.random = new SplittableRandom(seed);
        this.bottom = new PriorityQueue<>(Math.max(1, this.size), LARGEST_FIRST);
        this.reservoir = new Sampled[this.size];
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
        } else if (LARGEST_FIRST.compare(s, bottom.peek()) > 0) {
            bottom.poll();
            bottom.add(s);
        }
    }

    private long hash(List<FieldVector> vectors, int row) {
        StringBuilder b = new StringBuilder();
        if (keyIndex != null) {
            for (int k : keyIndex) {
                b.append(CellText.of(vectors.get(k), row)).append('\u001f');
            }
        } else {
            for (FieldVector v : vectors) {
                b.append(CellText.of(v, row)).append('\u001f');
            }
        }
        return MurmurHash3.hash(b.toString().getBytes(StandardCharsets.UTF_8), SEED)[0] >>> 1; // non-negative
    }

    /** Algorithm L: fill the reservoir, then jump straight to the next row that replaces one. */
    private void reservoir(List<FieldVector> vectors, int row, long index) {
        if (index < size) {
            reservoir[(int) index] = new Sampled(0, index, cells(vectors, row));
            return;
        }
        if (index + 1 == next) {
            reservoir[random.nextInt(size)] = new Sampled(0, index, cells(vectors, row));
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
            out[c] = CellText.of(vectors.get(c), row);
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
