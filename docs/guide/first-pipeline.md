# Your first pipeline

A short walk from signing in to a finished run: a pipeline that reads a table and writes it to a
file. Each step links to the guide that says more.

## Before you start

- **Sign in** with your organisation's login or an API token
  ([Signing in, roles and tokens](signing-in.md)). To build a pipeline you need the **designer**
  role; to run one, **steward** or above.
- **A source to read and one that allows writing.** A pipeline names saved sources and never holds
  a password. On a Starter Pack install, turning the pack on adds both: `starter-samples-db`, a
  database with the sample tables, and `starter-samples-files`, a files source that allows writing
  ([The Library](library.md#samples-ready-to-run-the-starter-pack)). Otherwise, ask a designer or
  an admin to add your sources under **Sources**, scan the one you read from, and turn on **Allow
  writing** for the one you write to.

To see finished pipelines first, open the **Library**: each sample shows one idea, with what to
look for when it runs ([The Library: samples and templates](library.md)).

## Start a pipeline

1. Choose **Pipelines** in the navigation, then **New pipeline**.
2. The canvas opens with a template that reads the first 100 rows of a table or file from one of
   your sources and writes them to a source that allows writing. Where you have no such source, it
   shows a placeholder in angle brackets, such as `<your source>`: replace it with a source's name.
3. Give the pipeline a **Name**, such as `my-first-copy`: small letters, digits and dashes. It
   cannot change once saved.

The **Canvas** and **YAML** tabs show the same pipeline. Select a node to see its settings
([Building on the canvas](pipelines.md#building-on-the-canvas)).

## Change it

- Select the read node and choose another table or file under **Table or file**.
- Add a node: with the read node selected, choose **Filter rows** in the node list. It is added
  after the read node and reads from it. Give it a condition such as `status = 'active'`; the
  language is in [Expressions](expressions.md).
- Make the write take the filtered rows: in the write node's settings, choose **Disconnect** beside
  its input, then drag from the filter's `out` port to the write's `in` port.
- Watch the problems list under the canvas: Kilnmark checks the pipeline as you change it, and
  choosing a problem takes you to the setting it is about.

To see the rows a node passes on without running anything, right-click it and choose **Preview
rows** ([Preview a node](pipelines.md#preview-a-node)).

## Save and run it

1. Say what you changed under **What changed**, and choose **Save new version**.
2. Choose **Run now**. The run is queued for a worker, and the **Run** tab under the canvas follows
   it.
3. When it has finished, each node shows the rows it passed on, and the write shows the quality of
   what it wrote. **Open run** shows the run's own page: where each node spent its time, its log,
   and what it wrote ([Reading a run](pipelines.md#reading-a-run)).

What the run wrote is now in the catalogue, with where it came from.

## Next steps

- Check rows on the way with a [quality gate](quality-gates.md), and set rules on what you read
  ([Setting data quality rules](quality-rules.md)).
- Run pipelines in an order, with conditions on how each went, in a
  [flow](flows.md).
- Run it every night on a [schedule](schedules.md).
- Every node, with every setting, is in [Reference: pipeline nodes](reference-pipeline-nodes.md).
