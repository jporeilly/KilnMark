package io.kilnmark.kiln.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphNode;
import io.kilnmark.kiln.graph.KilnGraphRunner;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.graph.PortRef;

/**
 * Rough timings of sort and join on this machine, in memory and spilling, at 1 and 10 million rows: a first
 * data point for docs/design/benchmarks.md, not a benchmark. Runs only with {@code KILNMARK_TIMING=1}
 * ({@code KILNMARK_TIMING=1 ./gradlew :kiln-nodes:test --tests "*SortJoinTiming*" -i}).
 */
@EnabledIfEnvironmentVariable(named = "KILNMARK_TIMING", matches = "1")
class SortJoinTiming {

    static final Schema ROWS = Harness.schema(Harness.field("id", Harness.INT64), Harness.field("k", Harness.INT64),
        Harness.field("name", Harness.UTF8), Harness.field("amount", Harness.decimal(12, 2)));

    /** Makes {@code rows} rows: id in order, k random, a name of 10-14 characters, an amount. */
    static final class Generate implements Node {
        private final long rows;
        private final long keys;
        private final SplittableRandom random;
        private long sent;

        Generate(long rows, long keys, long seed) {
            this.rows = rows;
            this.keys = keys;
            this.random = new SplittableRandom(seed);
        }

        @Override
        public String type() {
            return "generate";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            int n = (int) Math.min(8192, rows - sent);
            if (n <= 0) {
                return false;
            }
            VectorSchemaRoot root = VectorSchemaRoot.create(ROWS, context.allocator());
            BigIntVector id = (BigIntVector) root.getVector(0);
            BigIntVector k = (BigIntVector) root.getVector(1);
            VarCharVector name = (VarCharVector) root.getVector(2);
            DecimalVector amount = (DecimalVector) root.getVector(3);
            root.allocateNew();
            for (int i = 0; i < n; i++) {
                id.setSafe(i, sent + i);
                k.setSafe(i, random.nextLong(keys));
                name.setSafe(i, ("customer-" + random.nextInt(100_000)).getBytes(StandardCharsets.UTF_8));
                amount.setSafe(i, BigDecimal.valueOf(random.nextLong(1_000_000), 2));
            }
            root.setRowCount(n);
            sent += n;
            try {
                context.output().put(root);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
            return true;
        }
    }

    /** Counts rows and checks they arrive in order of {@code k}. */
    static final class Count implements Node {
        long rows;
        long last = Long.MIN_VALUE;
        boolean ordered = true;

        @Override
        public String type() {
            return "count";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try (VectorSchemaRoot b = context.input().take(context.allocator())) {
                if (b == null) {
                    return false;
                }
                BigIntVector k = (BigIntVector) b.getVector("k");
                for (int i = 0; i < b.getRowCount(); i++) {
                    long v = k.get(i);
                    ordered &= v >= last;
                    last = v;
                }
                rows += b.getRowCount();
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
        }
    }

    static String time(String what, long rows, long limitMb) throws Exception {
        Count count = new Count();
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(new GraphNode("gen", new Generate(rows, Long.MAX_VALUE, 7), null));
        nodes.add(new GraphNode("sort", new SortFactory().create(Map.of("by", List.of("k"), "memory_limit_mb",
            limitMb)), null));
        nodes.add(new GraphNode("count", count, null));
        List<Edge> edges = List.of(new Edge(PortRef.parse("gen", "out"), PortRef.parse("sort", "in")),
            new Edge(PortRef.parse("sort", "out"), PortRef.parse("count", "in")));
        long started = System.nanoTime();
        RunResult r = new KilnGraphRunner((limitMb * 3) << 20, 8, Duration.ofMinutes(30))
            .run(new PipelineGraph("timing", nodes, edges), e -> { });
        double seconds = (System.nanoTime() - started) / 1e9;
        assertTrue(r.succeeded(), r.error());
        assertEquals(rows, count.rows);
        assertTrue(count.ordered);
        String line = String.format(java.util.Locale.UK, "%s: %,d rows, memory_limit_mb %d: %.1f s (%,.0f rows/s), "
            + "spilled %,.0f MB, peak Arrow memory %,.0f MB", what, rows, limitMb, seconds, rows / seconds,
            r.node("sort").spilledBytes() / 1048576.0, r.node("sort").peakMemoryBytes() / 1048576.0);
        System.out.println("TIMING " + line);
        return line;
    }

    /** Counts rows, and the rows whose right side matched. */
    static final class CountJoined implements Node {
        long rows;
        long matched;

        @Override
        public String type() {
            return "count";
        }

        @Override
        public void init(NodeContext context) {
        }

        @Override
        public boolean process(NodeContext context) throws NodeException {
            try (VectorSchemaRoot b = context.input().take(context.allocator())) {
                if (b == null) {
                    return false;
                }
                rows += b.getRowCount();
                BigIntVector rk = (BigIntVector) b.getVector("right_k");
                for (int i = 0; i < b.getRowCount(); i++) {
                    matched += rk.isNull(i) ? 0 : 1;
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("interrupted", e);
            }
        }
    }

    /** Joins {@code rows} left rows to as many right rows, keys drawn from as many values (about one match each). */
    static String timeJoin(String what, long rows, long limitMb) throws Exception {
        CountJoined count = new CountJoined();
        List<GraphNode> nodes = new ArrayList<>();
        nodes.add(new GraphNode("left", new Generate(rows, rows, 7), null));
        nodes.add(new GraphNode("right", new Generate(rows, rows, 11), null));
        nodes.add(new GraphNode("join", new JoinFactory().create(Map.of("on", Map.of("k", "k"), "how", "left",
            "memory_limit_mb", limitMb)), null));
        nodes.add(new GraphNode("count", count, null));
        List<Edge> edges = List.of(new Edge(PortRef.parse("left", "out"), PortRef.parse("join", "left")),
            new Edge(PortRef.parse("right", "out"), PortRef.parse("join", "right")),
            new Edge(PortRef.parse("join", "out"), PortRef.parse("count", "in")));
        long started = System.nanoTime();
        RunResult r = new KilnGraphRunner((limitMb * 3) << 20, 8, Duration.ofMinutes(30))
            .run(new PipelineGraph("timing", nodes, edges), e -> { });
        double seconds = (System.nanoTime() - started) / 1e9;
        assertTrue(r.succeeded(), r.error());
        assertTrue(count.rows >= rows);
        String line = String.format(java.util.Locale.UK, "%s: %,d + %,d rows (%,d out, %,d matched), memory_limit_mb %d: "
            + "%.1f s (%,.0f input rows/s), spilled %,.0f MB, peak Arrow memory %,.0f MB", what, rows, rows, count.rows,
            count.matched, limitMb, seconds, 2 * rows / seconds, r.node("join").spilledBytes() / 1048576.0,
            r.node("join").peakMemoryBytes() / 1048576.0);
        System.out.println("TIMING " + line);
        return line;
    }

    @Test
    void join() throws Exception {
        timeJoin("warm-up", 1_000_000, 4096);
        timeJoin("left join in memory", 1_000_000, 4096);
        timeJoin("left join spilling", 1_000_000, 32);
        timeJoin("left join in memory", 10_000_000, 8192);
        timeJoin("left join spilling", 10_000_000, 256);
    }

    @Test
    void sort() throws Exception {
        time("warm-up", 1_000_000, 4096);
        time("sort in memory", 1_000_000, 4096);
        time("sort spilling", 1_000_000, 16);
        time("sort in memory", 10_000_000, 8192);
        time("sort spilling", 10_000_000, 256);
    }
}
