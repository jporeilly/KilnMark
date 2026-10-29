# Spike 0001: Arrow row sets

Sprint 0, 24 October 2026. Code: [`spikes/arrow`](../../spikes/arrow) (built only with `-Pspikes`,
never shipped).

## Question

Should nodes hand each other Apache Arrow batches, or plain row objects? The worry was the
learning curve, memory handling, and whether back-pressure and in-stream profiling work.

## Answer: yes, use Arrow

Arrow batches between nodes, with the changes below. The fallback to row objects is not needed.

## What was built

A source node hands batches through a bounded pipe to one or more profiling nodes. Each
profiling node takes ownership of a batch by moving its buffers into its own memory allocator,
profiles it with DataSketches (distinct count, quantiles, frequent values, nulls, lengths), and
frees it. Partitions profiled apart are merged at the end. The same run with boxed row objects is
the baseline. Five tests check that the two agree and that memory is bounded and never leaks.

## Results

On a 2-CPU cloud container, Java 25, 10 million rows of six columns (`spikeRun`):

| Run | Heap limit | Rows/s | Heap peak | Arrow memory peak | GC time |
| --- | --- | --- | --- | --- | --- |
| Arrow, profiled | 256 MB | 2.14 million | 47 MB | 4.9 MB | 100 ms |
| Row objects, profiled | 256 MB | 2.13 million | 159 MB | none | 402 ms |
| Arrow, profiled | **64 MB** | 1.95 million | 26 MB | 4.9 MB | 139 ms |
| Row objects, profiled | **64 MB** | 1.34 million | 73 MB | none | 1,841 ms |
| Arrow, hand-over only | 256 MB | 6.28 million | 52 MB | 4.1 MB | 63 ms |
| Row objects, hand-over only | 256 MB | 10.08 million | 97 MB | none | 62 ms |
| PostgreSQL to Arrow (adapter) | 256 MB | 0.74 million | 59 MB | 2.0 MB | 442 ms |
| PostgreSQL to row objects | 256 MB | 0.86 million | 62 MB | none | 420 ms |

- **Memory is the win.** Arrow uses about a third of the heap and a quarter of the GC time. In a
  64 MB heap it profiles 10 million rows 45% faster than row objects, which spend nearly two
  seconds collecting garbage. The plan's target (10 million rows inside 4 GB) is met with a wide
  margin.
- **Back-pressure works.** A full pipe makes the source wait. 80 MB of batches passed through a
  24 MB memory limit; 10 million rows never needed more than 5 MB of Arrow memory. Breaking the
  limit fails at once with a clear error instead of hanging, and every run freed every byte.
- **Profiling is accurate and small.** Distinct counts landed within about 1% of the truth
  (7,500 accounts: 7,500; 2 million references: 1.98 million). A column's whole profile is about
  9 KB whatever the row count, and four partitions merged to the same answer as one.
- **Profiling costs more than the hand-over.** Both formats profile at the same speed when the
  heap is roomy: the sketches are the work. Arrow's hand-over alone is slower than row objects'
  (a fresh batch is allocated each time); pooling batches can close that later. It is not a
  bottleneck.
- **Databases are slower than either.** Reading PostgreSQL ran at under a million rows a second
  on the same small machine, so the source sets the pace.

## What changes

1. **Java 25.** DataSketches 9 needs it; see [ADR 0013](../adr/0013-java-25.md). The class
   names changed in 9 (`HllUnion`, `FrequentItemsSketch`).
2. **Use Arrow's Unsafe allocator, not the Netty one.** On Java 25 the Netty allocator fails to
   start unless the JVM is given `--sun-misc-unsafe-memory-access=allow -Dio.netty.noUnsafe=false`.
   The Unsafe allocator works, with a warning that `sun.misc.Unsafe` will be removed in a future
   Java. Arrow has to move to the foreign memory API before then: a watch item, not a blocker.
3. **Write our own JDBC-to-Arrow reader.** Arrow's JDBC adapter was slower than a plain reader,
   and failed on PostgreSQL's unconstrained `NUMERIC` (`scale must equal that in the Arrow
   vector: 2 != 0`) unless told each column's precision. It also turns `timestamptz` into
   milliseconds. The engine needs a type-mapping layer per database dialect anyway (from
   Apache-licensed dialects), so the reader belongs there, with the adapter as a reference only.
4. **Each node owns its memory.** Batches move between nodes by transferring buffers into the
   receiving node's allocator, so the engine can report and limit memory per node.

## Not tested

Pooling and reusing batches; Arrow's own IPC between processes (needed for remote workers in
v0.5); a source with nested types; more than 2 CPUs.
