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
The owner asked on 6 November 2026 for more nodes, starting after release 0.1.1, and for calls to
REST APIs and other web endpoints: not every customer is a bank, and many integrations today are
web services.

## Decision

1. **Nodes stay coarse: one node per shape of work,** not per function and not per connector.
   A new connector is a connector (`read` and `write` gain it); a new operation on single values
   is an expression function.
2. **A new node is added only for work the existing nodes and expressions cannot express:** it
   changes how many rows there are (split, unpivot), needs order or state across rows (sort,
   window, sequence, change detection), combines inputs (join, compare), reaches something
   outside the pipeline (a web service), or means something for governance (mask).
3. **Every node ships complete:** its settings in the node catalogue, its entry in the components
   reference (whose settings tables are generated from the catalogue), its tests, an example,
   preview, and a stated memory behaviour: either it streams with bounded memory, or it says how
   much it holds and spills to disk past a limit.
4. **Web calls are added, with controls an admin sets.** Three pieces:
   - a **REST connector** (with the other connectors on the backlog): `read` pages through an API's
     results, `write` posts rows to it, with its credentials stored as any source's are;
   - an **`http` node**: calls an endpoint for each row or each batch of rows and adds what it
     answers as columns (enriching rows, validating addresses, scoring);
   - an **`http` flow node**: calls a web hook, for example to tell another system a load is
     done or to start a job there, and fails the node on an error answer.

   Every call goes through the controls the database connectors already have, so what a pipeline
   can reach is known before it runs: only hosts on the server's allowed-hosts list
   (`kilnmark.sources.allowed-hosts`), which an admin sets as tightly or loosely as the
   organisation needs (a bank to a few named hosts, another customer to any); TLS by default;
   credentials from a stored source, never written in the YAML; a time limit, a limit on the
   size of an answer, retries with back-off and a rate limit per host; nothing a call returns
   shown unmasked where its columns are marked sensitive; and each call counted in the run's
   record. A call that fails says which host and what status, never the address's query, the
   credentials or the answer's body.
5. **Not added: a node that runs code a person writes** (a script). It would make what a pipeline
   does unknowable from its YAML, which every customer's reviewers rely on. It needs a decision
   record of its own if a customer asks.
6. **The order**, starting after 0.1.1:
   1. **`sort`** and **`join`** (inner, left, right and full, of two inputs of any size, spilling
      to disk; `lookup` stays for a small side held in memory).
   2. **`window`**: row number, rank, running totals, and the previous and next row, by group
      and order.
   3. **More expression functions**: `if` and `case`, padding, splitting text, adding to and
      the difference between dates, and hashing. These close many small gaps at once.
   4. **Web calls**: the REST connector, the `http` node and the `http` flow node (decision 4).
   5. **Reading and building JSON and XML**, and splitting one row into many: what web calls
      return.
   6. **`pivot`** and **`unpivot`**.
   7. **Change detection** (compare two inputs into new, changed, deleted and unchanged rows by a
      key and a row hash) and **history in a dimension table** (types 1 and 2) as a write mode.
   8. **`sequence`**: surrogate keys.
   9. **`mask`**: safe copies of production data for testing, by masking, hashing or
      tokenising columns marked sensitive.
   10. **Generating rows**: calendars and date tables.
   11. **File actions in flows**: move, copy, archive and zip files and send them by SFTP, with
       the SFTP connector already on the backlog.

## Consequences

- The node count will grow to about twenty-four, and stay well below other products' counts by
  design; the components reference and the guide say why, so a count is never read as the whole
  story.
- Expression functions carry much of the breadth, so they need the same care as nodes: each in
  the reference, with tests, and checked before a pipeline runs.
- Nodes that hold state (sort, join, window, change detection) need spilling to disk and bounded
  memory, and are the first the benchmarks (docs/design/benchmarks.md) measure.
- Web calls make the allowed-hosts list matter more: the install guide and the admin pages say
  plainly what it allows, and what an empty list means is decided with the REST connector.
- A customer who needs a script is told what Kilnmark offers instead (expression functions, a web
  call to their own service, an external scheduler step), and the request is recorded.
