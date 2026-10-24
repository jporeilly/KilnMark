package io.kilnmark.spike.arrow;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.arrow.adapter.jdbc.ArrowVectorIterator;
import org.apache.arrow.adapter.jdbc.JdbcFieldInfo;
import org.apache.arrow.adapter.jdbc.JdbcToArrow;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfig;
import org.apache.arrow.adapter.jdbc.JdbcToArrowConfigBuilder;
import org.apache.arrow.adapter.jdbc.JdbcToArrowUtils;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.util.TransferPair;

/**
 * One measurement: a source step hands batches through a bounded pipe to one or more profiling steps,
 * which profile in-stream and are merged at the end.
 *
 * <pre>
 * --mode arrow | rows | jdbc-arrow | jdbc-rows   (default arrow)
 * --rows N          synthetic rows (default 1,000,000)
 * --batch N         rows per batch (default 8192)
 * --queue N         batches the pipe holds before the source blocks (default 4)
 * --consumers N     profiling steps in parallel (default 1)
 * --slow-ms N       pause per batch in each profiling step, to show back-pressure (default 0)
 * --limit-mb N      Arrow memory limit for the whole run (default 512)
 * --url, --user, --password, --sql   JDBC source (defaults: the lab on localhost:5433, transactions_big)
 * --no-decimal-hint read NUMERIC without telling the adapter its precision
 * --count-only      the receiving step only counts rows: the cost of the hand-over itself
 * </pre>
 */
public final class Spike {

    record Options(String mode, long rows, int batch, int queue, int consumers, int slowMs, long limitMb,
                   String url, String user, String password, String sql, boolean decimalHint,
                   boolean countOnly) {

        static Options parse(String... args) {
            Map<String, String> m = new java.util.HashMap<>();
            for (int i = 0; i < args.length; i++) {
                String k = args[i].replaceFirst("^--", "");
                if (k.equals("no-decimal-hint") || k.equals("count-only")) {
                    m.put(k, "true");
                } else {
                    m.put(k, args[++i]);
                }
            }
            return new Options(m.getOrDefault("mode", "arrow"),
                Long.parseLong(m.getOrDefault("rows", "1000000").replace("_", "")),
                Integer.parseInt(m.getOrDefault("batch", "8192")),
                Integer.parseInt(m.getOrDefault("queue", "4")),
                Integer.parseInt(m.getOrDefault("consumers", "1")),
                Integer.parseInt(m.getOrDefault("slow-ms", "0")),
                Long.parseLong(m.getOrDefault("limit-mb", "512")),
                m.getOrDefault("url", "jdbc:postgresql://localhost:5433/lab"),
                m.getOrDefault("user", "lab"), m.getOrDefault("password", "lab"),
                m.getOrDefault("sql", "SELECT txn_id, account_id, amount, txn_at FROM billing.transactions_big"),
                !m.containsKey("no-decimal-hint"), m.containsKey("count-only"));
        }
    }

    record Result(Options options, long rows, double seconds, int deepestQueue, long arrowPeakBytes,
                  long arrowLeakedBytes, long heapPeakBytes, long gcCount, long gcMillis, Profiler profile) {

        String line() {
            return String.format("%-10s rows=%,d time=%.2fs rate=%,.0f rows/s queue<=%d arrowPeak=%.1fMB "
                    + "heapPeak=%.0fMB gc=%d (%d ms) leaked=%d",
                options.mode(), rows, seconds, rows / seconds, deepestQueue, arrowPeakBytes / 1e6,
                heapPeakBytes / 1e6, gcCount, gcMillis, arrowLeakedBytes);
        }
    }

    private Spike() {
    }

    public static void main(String[] args) throws Exception {
        Options o = Options.parse(args);
        System.out.println("java " + Runtime.version() + ", max heap " + Runtime.getRuntime().maxMemory() / 1_000_000
            + " MB, " + Runtime.getRuntime().availableProcessors() + " cpus, args " + Arrays.toString(args));
        Result r = run(o);
        System.out.println(r.line());
        for (ColumnProfile p : r.profile().columns()) {
            System.out.println("  " + p.summary());
        }
    }

    static Result run(Options o) throws Exception {
        List<MemoryPoolMXBean> heap = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(p -> p.getType() == MemoryType.HEAP).toList();
        System.gc();
        heap.forEach(MemoryPoolMXBean::resetPeakUsage);
        long gc0 = gcCount();
        long gcMs0 = gcMillis();
        long t0 = System.nanoTime();

        boolean arrow = o.mode().endsWith("arrow");
        RootAllocator root = new RootAllocator(o.limitMb() * 1024 * 1024);
        BatchPipe<Object> pipe = new BatchPipe<>(o.queue(), o.consumers());
        BufferAllocator source = root.newChildAllocator("source", 0, Long.MAX_VALUE);

        Thread producer = Thread.ofVirtual().name("source").start(() -> {
            try {
                switch (o.mode()) {
                    case "arrow" -> {
                        SyntheticSource s = new SyntheticSource(o.rows(), o.batch());
                        while (s.hasNext()) {
                            pipe.put(s.nextBatch(source));
                        }
                    }
                    case "rows" -> {
                        SyntheticSource s = new SyntheticSource(o.rows(), o.batch());
                        while (s.hasNext()) {
                            pipe.put(s.nextRows());
                        }
                    }
                    case "jdbc-arrow" -> jdbcArrow(o, source, pipe);
                    case "jdbc-rows" -> jdbcRows(o, pipe);
                    default -> throw new IllegalArgumentException("mode " + o.mode());
                }
                pipe.finish();
            } catch (Throwable t) {
                try {
                    pipe.fail(t);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        List<Profiler> profilers = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();
        List<BufferAllocator> consumerAllocators = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicLong counted = new java.util.concurrent.atomic.AtomicLong();
        for (int k = 0; k < o.consumers(); k++) {
            Profiler profiler = arrow || !o.mode().equals("jdbc-rows")
                ? (arrow ? new Profiler() : Profiler.forSchema(SyntheticSource.SCHEMA))
                : new Profiler(List.of("txn_id", "account_id", "amount", "txn_at"), List.of(
                    ColumnProfile.Kind.NUMBER, ColumnProfile.Kind.NUMBER, ColumnProfile.Kind.NUMBER,
                    ColumnProfile.Kind.TIME));
            profilers.add(profiler);
            BufferAllocator mine = root.newChildAllocator("profile-" + k, 0, Long.MAX_VALUE);
            consumerAllocators.add(mine);
            consumers.add(Thread.ofVirtual().name("profile-" + k).start(() -> {
                try {
                    Object b;
                    while ((b = pipe.take()) != null) {
                        if (b instanceof VectorSchemaRoot batch) {
                            try (VectorSchemaRoot owned = transfer(batch, mine)) {
                                counted.addAndGet(owned.getRowCount());
                                if (!o.countOnly()) {
                                    profiler.accept(owned);
                                }
                            }
                        } else {
                            Object[][] rows = (Object[][]) b;
                            counted.addAndGet(rows.length);
                            if (!o.countOnly()) {
                                profiler.accept(rows);
                            }
                        }
                        if (o.slowMs() > 0) {
                            Thread.sleep(o.slowMs());
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        producer.join();
        for (Thread t : consumers) {
            t.join();
        }
        double seconds = (System.nanoTime() - t0) / 1e9;

        Profiler merged = new Profiler();
        for (Profiler p : profilers) {
            merged.merge(p);
        }
        long rows = counted.get();
        long heapPeak = heap.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        long leaked = root.getAllocatedMemory();
        long peak = root.getPeakMemoryAllocation();
        if (leaked == 0) {
            for (BufferAllocator a : consumerAllocators) {
                a.close();
            }
            source.close();
            root.close();
        }
        if (failure.get() != null) {
            throw new IllegalStateException("profiling failed", failure.get());
        }
        return new Result(o, rows, seconds, pipe.deepest(), peak, leaked, heapPeak,
            gcCount() - gc0, gcMillis() - gcMs0, merged);
    }

    /** Moves a batch's buffers into the receiving step's allocator, so each step's memory is accounted. */
    static VectorSchemaRoot transfer(VectorSchemaRoot from, BufferAllocator to) {
        List<FieldVector> moved = new ArrayList<>();
        for (FieldVector v : from.getFieldVectors()) {
            TransferPair tp = v.getTransferPair(to);
            tp.transfer();
            moved.add((FieldVector) tp.getTo());
        }
        VectorSchemaRoot out = new VectorSchemaRoot(from.getSchema(), moved, from.getRowCount());
        from.close();
        return out;
    }

    private static void jdbcArrow(Options o, BufferAllocator source, BatchPipe<Object> pipe) throws Exception {
        try (Connection c = DriverManager.getConnection(o.url(), o.user(), o.password())) {
            c.setAutoCommit(false); // PostgreSQL streams with a cursor only inside a transaction
            try (Statement st = c.createStatement()) {
                st.setFetchSize(o.batch());
                try (ResultSet rs = st.executeQuery(o.sql())) {
                    JdbcToArrowConfigBuilder b = new JdbcToArrowConfigBuilder(source, JdbcToArrowUtils.getUtcCalendar())
                        .setTargetBatchSize(o.batch())
                        .setReuseVectorSchemaRoot(false);
                    if (o.decimalHint()) {
                        b.setExplicitTypesByColumnName(Map.of("amount", new JdbcFieldInfo(Types.DECIMAL, 18, 2)))
                            .setBigDecimalRoundingMode(RoundingMode.UNNECESSARY);
                    }
                    JdbcToArrowConfig config = b.build();
                    try (ArrowVectorIterator it = JdbcToArrow.sqlToArrowVectorIterator(rs, config)) {
                        while (it.hasNext()) {
                            pipe.put(it.next());
                        }
                    }
                }
            }
        }
    }

    private static void jdbcRows(Options o, BatchPipe<Object> pipe) throws SQLException, InterruptedException {
        try (Connection c = DriverManager.getConnection(o.url(), o.user(), o.password())) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.setFetchSize(o.batch());
                try (ResultSet rs = st.executeQuery(o.sql())) {
                    List<Object[]> batch = new ArrayList<>(o.batch());
                    while (rs.next()) {
                        long id = rs.getLong(1);
                        long account = rs.getLong(2);
                        BigDecimal amount = rs.getBigDecimal(3);
                        Timestamp at = rs.getTimestamp(4);
                        batch.add(new Object[] {id, account, amount, at == null ? null : at.getTime()});
                        if (batch.size() == o.batch()) {
                            pipe.put(batch.toArray(new Object[0][]));
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        pipe.put(batch.toArray(new Object[0][]));
                    }
                }
            }
        }
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
    }
}
