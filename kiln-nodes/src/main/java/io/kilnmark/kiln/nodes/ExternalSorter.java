package io.kilnmark.kiln.nodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.dictionary.DictionaryProvider;
import org.apache.arrow.vector.ipc.ArrowStreamReader;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.nodes.KeyCodec.KeyBlock;

/**
 * Sorts rows by their encoded keys ({@link KeyCodec}) within a memory limit: the sort node's
 * engine, and each side of a join's. Rows are added a batch at a time and held, with their keys,
 * in an allocator of the sorter's own; when its owner finds they take too much memory it calls
 * {@link #spill}, which sorts what is held and writes it to disk as one sorted run (an Arrow stream
 * with the key as an extra column, encrypted, in a {@link SpillFolder}) and lets it go. At the end
 * {@link #finish} gives the rows back in order: straight from memory when nothing was spilled, or
 * by merging the runs, at most {@value #FAN_IN} at a time (merging runs into fewer, bigger runs
 * first when there are more).
 *
 * <p>The sort is stable: rows with equal keys come out in the order they were added. In memory, a
 * merge sort keeps them in order; runs hold rows added earlier than the runs after them, and the
 * merge takes the earlier run's row on a tie.
 *
 * <p>Closing the sorter frees its memory and deletes its files, whatever state it is in.
 */
final class ExternalSorter implements AutoCloseable {

    /** Rows in each batch given back. */
    static final int OUT_ROWS = 8_192;
    /** Runs merged at once. */
    static final int FAN_IN = 64;
    private static final int INSERTION = 32;
    private static final String KEY_COLUMN = "_kilnmark_sort_key";

    private final String label;
    private final KeyCodec codec;
    private final BufferAllocator memory;
    private final long limitBytes;
    private final java.util.function.LongConsumer spilledSink;
    private final int fanIn;
    private Schema schema;
    private Schema runSchema;
    private final List<VectorSchemaRoot> batches = new ArrayList<>();
    private final List<KeyBlock> keys = new ArrayList<>();
    private long rows;
    private long keyBytes;
    private long totalRows;
    private final List<Path> runs = new ArrayList<>();
    private SpillFolder spill;
    private long spilledBytes;
    private int runsWritten;
    private int runRows = OUT_ROWS;
    private Sorted output;
    private final List<AutoCloseable> open = new ArrayList<>();

    /**
     * @param label       what the rows are, for messages ("the left input")
     * @param parent      the node's allocator; the sorter takes a child of it
     * @param limitBytes  the memory its owner lets it hold, for sizing what it reads back at once
     * @param spilledSink told how many bytes each run written takes on disk
     */
    ExternalSorter(String label, KeyCodec codec, BufferAllocator parent, long limitBytes,
                   java.util.function.LongConsumer spilledSink) {
        this(label, codec, parent, limitBytes, spilledSink, FAN_IN);
    }

    /** As above, merging at most {@code fanIn} runs at once (tests use few, to merge in several passes). */
    ExternalSorter(String label, KeyCodec codec, BufferAllocator parent, long limitBytes,
                   java.util.function.LongConsumer spilledSink, int fanIn) {
        if (fanIn < 2) {
            throw new IllegalArgumentException("fanIn must be at least 2");
        }
        this.fanIn = fanIn;
        this.label = label;
        this.codec = codec;
        this.memory = parent.newChildAllocator("sort-" + label.replace(' ', '-'), 0, Long.MAX_VALUE);
        this.limitBytes = limitBytes;
        this.spilledSink = spilledSink;
    }

    /** The columns of the rows, or null before the first row. */
    Schema schema() {
        return schema;
    }

    /** Rows added in all. */
    long rows() {
        return totalRows;
    }

    /** Roughly the memory the rows held take now: their Arrow buffers, their keys and the sort's arrays. */
    long held() {
        return memory.getAllocatedMemory() + keyBytes + 24L * rows;
    }

    long spilledBytes() {
        return spilledBytes;
    }

    int runsWritten() {
        return runsWritten;
    }

    /** The folder its files go in; null until it first spills. */
    Path folder() {
        return spill == null ? null : spill.path();
    }

    /** Takes a batch; the sorter owns it from now on, whatever happens. */
    void add(VectorSchemaRoot batch) throws NodeException {
        try {
            int n = batch.getRowCount();
            if (n == 0) {
                return;
            }
            if (schema == null) {
                schema = batch.getSchema();
            } else if (!schema.equals(batch.getSchema())) {
                throw new NodeException(label + ": the columns changed part-way through");
            }
            KeyBlock k = codec.encode(batch);
            List<FieldVector> moved = new ArrayList<>();
            try {
                for (FieldVector v : batch.getFieldVectors()) {
                    moved.add(BaseNode.move(v, v.getField(), memory));
                }
            } catch (RuntimeException e) {
                moved.forEach(FieldVector::close);
                throw e;
            }
            batches.add(new VectorSchemaRoot(schema.getFields(), moved, n));
            keys.add(k);
            rows += n;
            totalRows += n;
            keyBytes += k.bytes();
        } finally {
            batch.close();
        }
    }

    /** Sorts the rows held, writes them to disk as one run and lets them go. */
    void spill() throws IOException, InterruptedException {
        if (rows == 0) {
            return;
        }
        if (spill == null) {
            spill = SpillFolder.create();
            long rowBytes = Math.max(1, held() / rows);
            runRows = Math.clamp(limitBytes / (4L * fanIn) / rowBytes, 16, OUT_ROWS);
        }
        long[] order = sortHeld();
        try (Sorted held = new InMemory(order, memory, runRows, true)) {
            runs.add(write(held));
        }
        release();
    }

    /**
     * The rows in order, each batch with its keys when {@code withKeys}; batches are allocated from
     * {@code out}. Spills what is held first if anything was spilled before. Call once.
     */
    Sorted finish(BufferAllocator out, boolean withKeys) throws IOException, InterruptedException {
        if (runs.isEmpty()) {
            output = new InMemory(sortHeld(), out, OUT_ROWS, withKeys);
            return output;
        }
        spill();
        while (runs.size() > fanIn) {
            List<Path> group = new ArrayList<>(runs.subList(0, fanIn));
            Path merged;
            try (Merge m = new Merge(group, memory, runRows, true)) {
                merged = write(m);
            }
            runs.subList(0, fanIn).clear();
            runs.addFirst(merged); // still before the later runs, so ties keep their order
        }
        output = new Merge(new ArrayList<>(runs), out, OUT_ROWS, withKeys);
        runs.clear();
        return output;
    }

    // ---- sorting in memory ----

    private long[] sortHeld() throws InterruptedException {
        int n = Math.toIntExact(rows);
        long[] refs = new long[n];
        long[] prefixes = new long[n];
        int at = 0;
        for (int b = 0; b < batches.size(); b++) {
            KeyBlock k = keys.get(b);
            for (int r = 0; r < k.rows(); r++) {
                refs[at] = ((long) b << 32) | r;
                prefixes[at++] = k.prefix(r);
            }
        }
        mergeSort(refs, prefixes);
        return refs;
    }

    private int compare(long ra, long pa, long rb, long pb) {
        if (pa != pb) {
            return Long.compareUnsigned(pa, pb);
        }
        return KeyBlock.compare(keys.get((int) (ra >>> 32)), (int) ra, keys.get((int) (rb >>> 32)), (int) rb);
    }

    /** A stable merge sort of {@code refs} (with their prefixes alongside), by key. */
    private void mergeSort(long[] refs, long[] prefixes) throws InterruptedException {
        int n = refs.length;
        for (int lo = 0; lo < n; lo += INSERTION) {
            int hi = Math.min(n, lo + INSERTION);
            for (int i = lo + 1; i < hi; i++) {
                long r = refs[i];
                long p = prefixes[i];
                int j = i - 1;
                while (j >= lo && compare(refs[j], prefixes[j], r, p) > 0) {
                    refs[j + 1] = refs[j];
                    prefixes[j + 1] = prefixes[j];
                    j--;
                }
                refs[j + 1] = r;
                prefixes[j + 1] = p;
            }
            if ((lo & 0xFFFF) == 0) {
                checkInterrupt();
            }
        }
        long[] srcR = refs;
        long[] srcP = prefixes;
        long[] dstR = new long[n];
        long[] dstP = new long[n];
        boolean inRefs = true;
        for (int width = INSERTION; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(n, lo + width);
                int hi = Math.min(n, lo + 2 * width);
                int i = lo;
                int j = mid;
                int k = lo;
                while (i < mid && j < hi) {
                    if (compare(srcR[j], srcP[j], srcR[i], srcP[i]) < 0) {
                        dstR[k] = srcR[j];
                        dstP[k++] = srcP[j++];
                    } else {
                        dstR[k] = srcR[i];
                        dstP[k++] = srcP[i++];
                    }
                }
                System.arraycopy(srcR, i, dstR, k, mid - i);
                System.arraycopy(srcP, i, dstP, k, mid - i);
                k += mid - i;
                System.arraycopy(srcR, j, dstR, k, hi - j);
                System.arraycopy(srcP, j, dstP, k, hi - j);
                if (((lo / (2 * width)) & 0xFF) == 0) {
                    checkInterrupt();
                }
            }
            long[] t = srcR;
            srcR = dstR;
            dstR = t;
            t = srcP;
            srcP = dstP;
            dstP = t;
            inRefs = !inRefs;
        }
        if (!inRefs) {
            System.arraycopy(srcR, 0, refs, 0, n);
        }
    }

    static void checkInterrupt() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    private void release() {
        batches.forEach(VectorSchemaRoot::close);
        batches.clear();
        keys.clear();
        rows = 0;
        keyBytes = 0;
    }

    // ---- runs on disk ----

    private Schema runSchema() {
        if (runSchema == null) {
            List<Field> fields = new ArrayList<>(schema.getFields());
            fields.add(new Field(KEY_COLUMN, FieldType.notNullable(ArrowType.Binary.INSTANCE), null));
            runSchema = new Schema(fields);
        }
        return runSchema;
    }

    /** Writes every batch of {@code from}, with its keys, as one run; returns its file. */
    private Path write(Sorted from) throws IOException, InterruptedException {
        Path file = spill.newFile("run");
        int columns = schema.getFields().size();
        try (OutputStream os = spill.write(file);
             WritableByteChannel channel = Channels.newChannel(os);
             VectorSchemaRoot root = VectorSchemaRoot.create(runSchema(), memory);
             ArrowStreamWriter writer = new ArrowStreamWriter(root, new DictionaryProvider.MapDictionaryProvider(),
                 channel)) {
            writer.start();
            for (Chunk next = from.next(); next != null; next = from.next()) {
                try (Chunk c = next) {
                    int n = c.rows().getRowCount();
                    for (int i = 0; i < columns; i++) {
                        c.rows().getVector(i).makeTransferPair(root.getVector(i)).transfer();
                    }
                    VarBinaryVector kv = (VarBinaryVector) root.getVector(columns);
                    KeyBlock k = c.keys();
                    kv.allocateNew(Math.max(1, k.offsets()[n] - k.offsets()[0]), n);
                    for (int r = 0; r < n; r++) {
                        kv.setSafe(r, k.data(), k.offsets()[r], k.offsets()[r + 1] - k.offsets()[r]);
                    }
                    kv.setValueCount(n);
                    root.setRowCount(n);
                    writer.writeBatch();
                }
                checkInterrupt();
            }
            writer.end();
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
        long size = Files.size(file);
        spilledBytes += size;
        runsWritten++;
        spilledSink.accept(size);
        return file;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            file.toFile().deleteOnExit();
        }
    }

    // ---- giving rows back ----

    /** A batch of rows in order, and their keys (null when not asked for). Closing it closes the rows. */
    record Chunk(VectorSchemaRoot rows, KeyBlock keys) implements AutoCloseable {
        @Override
        public void close() {
            rows.close();
        }
    }

    /** Rows in order, a batch at a time; the caller owns each batch it is given. */
    interface Sorted extends AutoCloseable {
        /** The next batch, or null at the end. */
        Chunk next() throws IOException, InterruptedException;

        @Override
        void close();
    }

    /** Builds a batch of {@code count} rows of {@code schema} in {@code out}, filling each column with {@code fill}. */
    private static VectorSchemaRoot build(Schema schema, BufferAllocator out, int count, ColumnFill fill) {
        List<FieldVector> vectors = new ArrayList<>();
        try {
            for (int c = 0; c < schema.getFields().size(); c++) {
                FieldVector v = schema.getFields().get(c).createVector(out);
                vectors.add(v);
                v.setInitialCapacity(count);
                v.allocateNew();
                fill.fill(c, v);
                v.setValueCount(count);
            }
            return new VectorSchemaRoot(schema.getFields(), vectors, count);
        } catch (RuntimeException e) {
            vectors.forEach(FieldVector::close);
            throw e;
        }
    }

    private interface ColumnFill {
        void fill(int column, FieldVector into);
    }

    /** The rows held, in the order sorted. */
    private final class InMemory implements Sorted {
        private final long[] order;
        private final BufferAllocator out;
        private final int chunkRows;
        private final boolean withKeys;
        private int at;

        InMemory(long[] order, BufferAllocator out, int chunkRows, boolean withKeys) {
            this.order = order;
            this.out = out;
            this.chunkRows = chunkRows;
            this.withKeys = withKeys;
        }

        @Override
        public Chunk next() throws InterruptedException {
            checkInterrupt();
            if (at >= order.length) {
                return null;
            }
            int from = at;
            int count = Math.min(chunkRows, order.length - at);
            at += count;
            VectorSchemaRoot rows = build(schema, out, count, (c, v) -> {
                for (int j = 0; j < count; j++) {
                    long ref = order[from + j];
                    v.copyFromSafe((int) ref, j, batches.get((int) (ref >>> 32)).getVector(c));
                }
            });
            KeyBlock k = null;
            if (withKeys) {
                KeyCodec.Sink s = new KeyCodec.Sink(count * 16);
                int[] offsets = new int[count + 1];
                for (int j = 0; j < count; j++) {
                    long ref = order[from + j];
                    KeyBlock b = keys.get((int) (ref >>> 32));
                    int r = (int) ref;
                    s.put(b.data(), b.offsets()[r], b.offsets()[r + 1]);
                    offsets[j + 1] = s.length;
                }
                k = new KeyBlock(s.trimmed(), offsets);
            }
            return new Chunk(rows, k);
        }

        @Override
        public void close() {
            at = order.length;
        }
    }

    /** One run on disk, read a batch at a time. */
    private final class RunReader implements AutoCloseable {
        final int order;
        final Path file;
        private final InputStream in;
        private final ArrowStreamReader reader;
        VectorSchemaRoot root;
        KeyBlock keys;
        int pos;
        int count;

        RunReader(Path file, int order, BufferAllocator allocator) throws IOException {
            this.order = order;
            this.file = file;
            this.in = spill.read(file);
            ArrowStreamReader r = null;
            try {
                r = new ArrowStreamReader(in, allocator);
                root = r.getVectorSchemaRoot();
            } catch (IOException | RuntimeException e) {
                if (r != null) {
                    r.close();
                }
                in.close();
                throw e;
            }
            this.reader = r;
        }

        /** Loads the next batch with rows; false at the end of the run. */
        boolean load() throws IOException {
            while (reader.loadNextBatch()) {
                count = root.getRowCount();
                if (count > 0) {
                    VarBinaryVector kv = (VarBinaryVector) root.getVector(root.getFieldVectors().size() - 1);
                    int[] offsets = new int[count + 1];
                    org.apache.arrow.memory.ArrowBuf ob = kv.getOffsetBuffer();
                    int base = ob.getInt(0);
                    for (int i = 0; i <= count; i++) {
                        offsets[i] = ob.getInt((long) i * VarBinaryVector.OFFSET_WIDTH) - base;
                    }
                    byte[] data = new byte[offsets[count]];
                    kv.getDataBuffer().getBytes(base, data, 0, data.length);
                    keys = new KeyBlock(data, offsets);
                    pos = 0;
                    return true;
                }
            }
            return false;
        }

        @Override
        public void close() {
            try {
                reader.close();
            } catch (IOException | RuntimeException e) {
                // the file is deleted next; nothing more to free
            }
            deleteQuietly(file);
        }
    }

    /** Runs merged into one order. */
    private final class Merge implements Sorted {
        private final List<Path> files;
        private final BufferAllocator out;
        private final int chunkRows;
        private final boolean withKeys;
        private final List<RunReader> readers = new ArrayList<>();
        private final PriorityQueue<RunReader> heads = new PriorityQueue<>((a, b) -> {
            int c = KeyBlock.compare(a.keys, a.pos, b.keys, b.pos);
            return c != 0 ? c : Integer.compare(a.order, b.order);
        });
        private boolean started;
        /** A run whose batch the last chunk used up. */
        private RunReader used;

        Merge(List<Path> files, BufferAllocator out, int chunkRows, boolean withKeys) {
            this.files = files;
            this.out = out;
            this.chunkRows = chunkRows;
            this.withKeys = withKeys;
            open.add(this);
        }

        private void start() throws IOException {
            started = true;
            for (int i = 0; i < files.size(); i++) {
                RunReader r = new RunReader(files.get(i), i, memory);
                readers.add(r);
                if (r.load()) {
                    heads.add(r);
                } else {
                    r.close();
                }
            }
        }

        private boolean before(RunReader r, RunReader other) {
            int c = KeyBlock.compare(r.keys, r.pos, other.keys, other.pos);
            return c < 0 || (c == 0 && r.order < other.order);
        }

        @Override
        public Chunk next() throws IOException, InterruptedException {
            checkInterrupt();
            if (!started) {
                start();
            }
            if (used != null) {
                // its batch was used up by the last chunk, which has been copied out: load its next
                if (used.load()) {
                    heads.add(used);
                } else {
                    readers.remove(used);
                    used.close();
                }
                used = null;
            }
            if (heads.isEmpty()) {
                return null;
            }
            // Which run each row comes from, in order; the chunk ends early when a run's batch is used
            // up, so every row is copied before that run's next batch replaces it.
            RunReader[] src = new RunReader[chunkRows];
            int[] row = new int[chunkRows];
            KeyCodec.Sink s = withKeys ? new KeyCodec.Sink(chunkRows * 16) : null;
            int[] offsets = withKeys ? new int[chunkRows + 1] : null;
            int count = 0;
            while (count < chunkRows && !heads.isEmpty() && used == null) {
                RunReader r = heads.poll();
                RunReader next = heads.peek();
                do {
                    src[count] = r;
                    row[count] = r.pos;
                    if (s != null) {
                        s.put(r.keys.data(), r.keys.offsets()[r.pos], r.keys.offsets()[r.pos + 1]);
                        offsets[count + 1] = s.length;
                    }
                    count++;
                    r.pos++;
                } while (count < chunkRows && r.pos < r.count && (next == null || before(r, next)));
                if (r.pos < r.count) {
                    heads.add(r);
                } else {
                    used = r;
                }
            }
            int n = count;
            VectorSchemaRoot rows = build(schema, out, n, (c, v) -> {
                for (int j = 0; j < n; j++) {
                    v.copyFromSafe(row[j], j, src[j].root.getVector(c));
                }
            });
            return new Chunk(rows, s == null ? null : new KeyBlock(s.trimmed(), java.util.Arrays.copyOf(offsets, n + 1)));
        }

        @Override
        public void close() {
            heads.clear();
            readers.forEach(RunReader::close);
            readers.clear();
            if (!started) {
                files.forEach(ExternalSorter::deleteQuietly);
            }
            open.remove(this);
        }
    }

    /** Frees every batch and deletes every file, whatever state the sorter is in. */
    @Override
    public void close() {
        if (output != null) {
            output.close();
        }
        for (AutoCloseable c : new ArrayList<>(open)) {
            try {
                c.close();
            } catch (Exception e) {
                // closing the rest matters more
            }
        }
        release();
        runs.forEach(ExternalSorter::deleteQuietly);
        runs.clear();
        if (spill != null) {
            spill.close();
        }
        memory.close();
    }
}
