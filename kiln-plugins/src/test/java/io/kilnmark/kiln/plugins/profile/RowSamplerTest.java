package io.kilnmark.kiln.plugins.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

/** Coordinated (bottom-k) samples choose the same rows on every scan; reservoirs are repeatable. */
class RowSamplerTest {

    private static final Schema SCHEMA = new Schema(List.of(
        new Field("id", FieldType.nullable(new ArrowType.Int(64, true)), null),
        new Field("name", FieldType.nullable(ArrowType.Utf8.INSTANCE), null)));

    /** Feeds rows (id, "name-" + version(id)) in the given order, in batches of 1000. */
    private static RowSampler sample(RowSampler s, List<Long> ids, IntUnaryOperator version) {
        try (BufferAllocator alloc = new RootAllocator()) {
            for (int start = 0; start < ids.size(); start += 1000) {
                int end = Math.min(ids.size(), start + 1000);
                try (VectorSchemaRoot b = VectorSchemaRoot.create(SCHEMA, alloc)) {
                    BigIntVector id = (BigIntVector) b.getVector(0);
                    VarCharVector name = (VarCharVector) b.getVector(1);
                    b.allocateNew();
                    for (int i = start; i < end; i++) {
                        long k = ids.get(i);
                        id.setSafe(i - start, k);
                        name.setSafe(i - start, ("name-" + k + "-v" + version.applyAsInt((int) k))
                            .getBytes(StandardCharsets.UTF_8));
                    }
                    b.setRowCount(end - start);
                    s.accept(b);
                }
            }
        }
        return s;
    }

    private static Set<String> keys(RowSampler s) {
        Set<String> out = new HashSet<>();
        s.rows().forEach(r -> out.add(r[0]));
        return out;
    }

    private static List<Long> range(long from, long to) {
        List<Long> out = new ArrayList<>();
        for (long i = from; i < to; i++) {
            out.add(i);
        }
        return out;
    }

    @Test
    void keyedSamplesChooseTheSameRowsInAnyOrderAndAfterValuesChange() {
        List<Long> ids = range(0, 20_000);
        RowSampler first = sample(new RowSampler(RowSampler.Mode.KEY, List.of("id"), 100, 0), ids, k -> 1);
        assertEquals(100, first.rows().size());
        assertEquals(20_000, first.seen());
        assertEquals(List.of("id"), first.keyFields());

        List<Long> shuffled = new ArrayList<>(ids);
        Collections.shuffle(shuffled, new java.util.Random(7));
        // Second scan: rows in another order, and every other row's name changed.
        RowSampler second = sample(new RowSampler(RowSampler.Mode.KEY, List.of("id"), 100, 0), shuffled,
            k -> k % 2 == 0 ? 2 : 1);
        assertEquals(keys(first), keys(second), "the same keys are chosen");
        assertEquals(first.rows().stream().map(r -> r[0]).toList(), second.rows().stream().map(r -> r[0]).toList(),
            "in the same order");

        // A third scan with new rows added keeps every earlier sampled row whose hash is still small enough.
        RowSampler third = sample(new RowSampler(RowSampler.Mode.KEY, List.of("id"), 100, 0), range(0, 21_000),
            k -> 1);
        Set<String> kept = new HashSet<>(keys(first));
        kept.retainAll(keys(third));
        assertTrue(kept.size() >= 90, "at most the newcomers displace earlier rows: " + kept.size());
    }

    @Test
    void relatedTablesSampledOnTheSameKeyChooseMatchingRows() {
        RowSampler customers = sample(new RowSampler(RowSampler.Mode.KEY, List.of("id"), 50, 0), range(0, 5000),
            k -> 1);
        RowSampler other = sample(new RowSampler(RowSampler.Mode.KEY, List.of("id"), 50, 0), range(0, 5000),
            k -> 9);
        assertEquals(keys(customers), keys(other));
    }

    @Test
    void withoutAKeyTheWholeRowIsHashed() {
        RowSampler s = sample(new RowSampler(RowSampler.Mode.KEY, List.of("missing"), 10, 0), range(0, 1000), k -> 1);
        assertEquals(List.of(), s.keyFields(), "falls back to the whole row");
        RowSampler again = sample(new RowSampler(RowSampler.Mode.ROW, List.of(), 10, 0), range(0, 1000), k -> 1);
        assertEquals(keys(s), keys(again));
    }

    @Test
    void reservoirSamplesAreRepeatableForTheSameSeedAndUniform() {
        List<Long> ids = range(0, 50_000);
        RowSampler a = sample(new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 200, 42), ids, k -> 1);
        RowSampler b = sample(new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 200, 42), ids, k -> 1);
        RowSampler c = sample(new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 200, 43), ids, k -> 1);
        assertEquals(200, a.rows().size());
        assertEquals(a.rowsJson(), b.rowsJson(), "same seed, same input: same sample");
        assertNotEquals(keys(a), keys(c), "another asset's seed gives another sample");
        // Roughly uniform: about half the sample from each half of the input.
        long low = a.rows().stream().filter(r -> Long.parseLong(r[0]) < 25_000).count();
        assertTrue(low > 70 && low < 130, "rows from the first half: " + low);
        // Fewer rows than the sample size: all of them, in order.
        RowSampler small = sample(new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 200, 42), range(0, 5), k -> 1);
        assertEquals(List.of("0", "1", "2", "3", "4"), small.rows().stream().map(r -> r[0]).toList());
    }

    @Test
    void samplesStayWithinTheirMemoryCapAndCutLongCells() {
        Schema wide = new Schema(List.of(new Field("id", FieldType.nullable(new ArrowType.Int(64, true)), null),
            new Field("text", FieldType.nullable(ArrowType.Utf8.INSTANCE), null)));
        RowSampler key = new RowSampler(RowSampler.Mode.KEY, List.of("id"), 1000, 0, 64 * 1024);
        RowSampler res = new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 1000, 1, 64 * 1024);
        try (BufferAllocator alloc = new RootAllocator(); VectorSchemaRoot b = VectorSchemaRoot.create(wide, alloc)) {
            b.allocateNew();
            String big = "x".repeat(5000);
            for (int i = 0; i < 2000; i++) {
                ((BigIntVector) b.getVector(0)).setSafe(i, i);
                ((VarCharVector) b.getVector(1)).setSafe(i, big.getBytes(StandardCharsets.UTF_8));
            }
            b.setRowCount(2000);
            key.accept(b);
            res.accept(b);
        }
        for (RowSampler s : List.of(key, res)) {
            List<String[]> rows = s.rows();
            assertTrue(rows.size() > 50 && rows.size() < 1000, "fewer rows, not more memory: " + rows.size());
            assertEquals(RowSampler.CELL + 1, rows.get(0)[1].length(), "cut at 200 characters plus an ellipsis");
        }
        // The cap keeps the smallest hashes: a capped keyed sample is a prefix of the uncapped one.
        RowSampler all = new RowSampler(RowSampler.Mode.KEY, List.of("id"), 1000, 0);
        sample(all, range(0, 2000), k -> 1);
        List<String> capped = key.rows().stream().map(r -> r[0]).toList();
        assertEquals(all.rows().stream().map(r -> r[0]).toList().subList(0, capped.size()), capped);
    }

    @Test
    void rowsAreJson() {
        RowSampler s = sample(new RowSampler(RowSampler.Mode.RESERVOIR, List.of(), 1, 1), range(0, 1), k -> 1);
        assertEquals("[[\"0\",\"name-0-v1\"]]", s.rowsJson());
        assertEquals(List.of("id", "name"), s.columns());
    }
}
