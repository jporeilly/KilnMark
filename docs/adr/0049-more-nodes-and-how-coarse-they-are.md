# 0049 More nodes, and how coarse a node is

Status: Accepted (6 November 2026)

## Context

Kilnmark has twelve pipeline nodes: `read`, `write` and `gate`, and nine that change rows
(`filter`, `compute`, `select`, `lookup`, `aggregate`, `union`, `dedupe`, `route`, `limit`). Mature
integration products list hundreds of components, and customers compare the numbers.

The numbers are not like for like:

- **One `read` and one `write` serve every connector.** Other products often have an input and an
  output component for each database, file type and application, and those make up much of their
  count.
- **Operations on one value are expression functions.** Trimming, replacing, changing case,
  regular expressions, parsing and formatting dates and rounding are functions inside `compute`
  and `filter` (docs/guide/pipeline-nodes.md), where other products often give each its own
  component.

Even so, some common shapes of work cannot be written with Kilnmark's nodes today: sorting, a
true join of two large inputs, numbering and running totals, pivoting, reading JSON or XML,
finding what changed between two loads, keeping history in a dimension table, and making keys.
The owner asked on 6 November 2026 for more nodes, starting after release 0.1.1.

## Decision

1. **Nodes stay coarse: one node per shape of work,** not per function and not per connector.
   A new connector is a connector (`read` and `write` gain it); a new operation on single values
   is an expression function.
2. **A new node is added only for work the existing nodes and expressions cannot express:** it
   changes how many rows there are (split, unpivot), needs order or state across rows (sort,
   window, sequence, change detection), combines inputs (join, compare), or means something for
   governance (mask).
3. **Every node ships complete:** its settings in the node catalogue, its entry in the components
   reference (whose settings tables are generated from the catalogue), its tests, an example,
   preview, and a stated memory behaviour: either it streams with bounded memory, or it says how
   much it holds and spills to disk past a limit.
4. **Not added:** a node that runs code a person writes (a script), and calls to web services from
   inside a pipeline without network controls. Either would weaken what Kilnmark promises a
   bank: that what a pipeline can do is known from its YAML. Each would need a decision record of
   its own if a customer asks.
5. **The order**, starting after 0.1.1:
   1. **`sort`** and **`join`** (inner, left, right and full, of two inputs of any size, spilling
      to disk; `lookup` stays for a small side held in memory).
   2. **`window`**: row number, rank, running totals, and the previous and next row, by group
      and order.
   3. **More expression functions**: `if` and `case`, padding, splitting text, adding to and
      the difference between dates, and hashing. These close many small gaps at once.
   4. **`pivot`** and **`unpivot`**.
   5. **Reading and building JSON and XML**, and splitting one row into many.
   6. **Change detection** (compare two inputs into new, changed, deleted and unchanged rows by a
      key and a row hash) and **history in a dimension table** (types 1 and 2) as a write mode.
   7. **`sequence`**: surrogate keys.
   8. **`mask`**: safe copies of production data for testing, by masking, hashing or
      tokenising columns marked sensitive.
   9. **Generating rows**: calendars and date tables.
   10. **File actions in flows**: move, copy, archive and zip files and send them by SFTP, with
       the SFTP and REST connectors already on the backlog.

## Consequences

- The node count will grow to about twenty-two, and stay well below other products' counts by
  design; the components reference and the guide say why, so a count is never read as the whole
  story.
- Expression functions carry much of the breadth, so they need the same care as nodes: each in
  the reference, with tests, and checked before a pipeline runs.
- Nodes that hold state (sort, join, window, change detection) need spilling to disk and bounded
  memory, and are the first the benchmarks (docs/design/benchmarks.md) measure.
- A customer who needs a script or an in-pipeline web call is told what Kilnmark offers instead
  (an external scheduler step, a connector, or a flow node), and the request is recorded.
