# Kilnmark

A data integration engine for your own servers that knows the quality of the data it moves: it
profiles each source once, predicts a pipeline's output quality before it runs, and gates or
quarantines rows in the same pass ([ADR 0021](docs/adr/0021-pipeline-first.md)). **Kiln** is its
execution engine. It is built for banks and other regulated organisations.

Status: version 0.1.2, early and moving fast; see the [changelog](CHANGELOG.md).

## What it does

- **Two ways to deploy, chosen once at install.** **Production** is for real work. A **Starter
  Pack** install is for training, demonstrations and development: example data in a database of
  its own, every sample ready to run, an example schedule, and five example people, one per role.
  An install never changes mode ([Choosing a deployment](INSTALL.md#choosing-a-deployment)). A
  guided install puts the program where the operating system keeps programs (`C:\Program
  Files\Kilnmark`, `/opt/kilnmark`) and the data where it keeps data (`C:\ProgramData\Kilnmark`,
  `/var/opt/kilnmark`), each locked to who should change it, with `check` and `configure` after
  ([INSTALL.md](INSTALL.md)).
- **People.** Sign in with your organisation's identity provider (OpenID Connect, with a second
  factor), the Keycloak that comes with Kilnmark, or an API token. With the bundled Keycloak, the
  control script adds people: `kilnmark.sh people add` for one, `people import FILE` for a class or
  a team, `people activate` and `people list`; `kilnmark.sh starter people` adds the example
  people
  ([Adding people](docs/guide/install/production-bundled-keycloak.md#adding-people-to-the-bundled-keycloak)).
  Four roles: viewer,
  steward (runs pipelines and looks after quality), designer (builds pipelines, flows and sources)
  and admin (Settings and tokens). Columns marked sensitive stay masked for everyone but designers
  given the unmask grant, admins included ([ADR 0027](docs/adr/0027-unmasking-is-a-grant.md)).
- **Sources:** PostgreSQL, SQL Server, MySQL, MariaDB, Oracle, any database with a JDBC driver an
  admin registers, local folders and S3, with CSV, JSON and Parquet files. Scan them, search
  tables, views and columns, open each with its column profiles and quality score, and explore a
  database's tables, keys and indexes or generate their SQL
  ([Database tools](docs/guide/database-tools.md)). Drivers Kilnmark may not ship, such as
  Oracle's, an admin installs under Settings ([Database drivers](docs/guide/drivers.md)).
- **Pipelines:** graphs of nodes, built on a canvas or in YAML: read, write, filter, route, limit,
  dedupe, compute, select, sort, window, lookup, join, union, aggregate and quality gate. Each run
  shows its rows and quality at every node, and a prediction before it starts
  ([Building and running a pipeline](docs/guide/pipelines.md)).
  - **Which row wins** is stated: `dedupe`, `lookup` and an upsert keep the first, last, latest or
    earliest row of a key, or stop the run ([Which row wins](docs/guide/reference-pipeline-nodes.md#which-row-wins)).
  - **Rows that fail** go to an `errors` port instead of stopping the run, up to a limit you set
    ([Rows that fail](docs/guide/rows-that-fail.md)).
  - **Expressions** with functions for text, numbers, dates, `if`, window values and a `sha256`
    that keeps a sensitive column marked ([Expressions](docs/guide/expressions.md)).
  - Parameters, shown and edited on the canvas and asked for when a run starts, server variables,
    previews at any node, and a version for every save
    ([Parameters in the canvas](docs/guide/pipelines.md#parameters-in-the-canvas)).
  - **Lineage as a graph** on each run and each asset's page: what every table or file written
    came from ([Reading a run](docs/guide/pipelines.md#reading-a-run)).
  - **Download** a file a run wrote from the run's page; a file with sensitive columns only by
    someone allowed to see them, and every download in the audit log
    ([Downloading the files a run wrote](docs/guide/pipelines.md#downloading-the-files-a-run-wrote)).
- **Runs that recover.** A `run:` block tries a failed run again, only when it is safe, with a
  timeout for each try and a warning to the owners when a run is slow
  ([Trying a run again](docs/guide/pipelines.md#trying-a-run-again)). Owners are told when a run
  fails after its last try and when it recovers.
- **Flows** run pipelines in an order: on success, on failure or on a condition over what they
  measured, with for-each, SQL, notice, wait and stop nodes, and nodes that check a source or wait
  for a file before the work. Nodes take a timeout, retries and a priority, and run in parallel
  within limits. A failed flow run can be **run again from the failure**: what succeeded is kept
  ([Building and running a flow](docs/guide/flows.md)).
- **Schedules** in a time zone, and a run script for Control-M, AutoSys, cron and other schedulers
  ([Schedules](docs/guide/schedules.md), [External schedulers](docs/guide/external-schedulers.md)).
- **Quality:** rules from a library, quality gates that stop, quarantine or warn, drift and
  issues, notifications in the app and by email, sent out as OpenLineage and ODCS 3.1
  ([Quality rules](docs/guide/quality-rules.md), [Quality gates](docs/guide/quality-gates.md)).
- **Samples that explain themselves:** small pipelines and flows, one idea each, in the Library,
  each saying what it shows, why it matters, what to look for and what it needs, on the Library
  and on the sample's own page; and templates to start your own work from
  ([The Library](docs/guide/library.md)).
- **Business scenarios:** customer onboarding, a supplier's file arriving and the month-end close,
  each several pipelines and a flow, with the steward's part in each: the rows set aside, the
  sensitive columns, the exceptions to work through
  ([Business scenarios](docs/guide/scenarios.md)).
- **Ask Kilnmark:** a chat panel that answers from the guides, with a local or hosted model;
  Ollama or LM Studio on the same computer is found by itself
  ([Asking Kilnmark](docs/guide/assistant.md),
  [A model server on this computer](docs/guide/install/models-on-this-computer.md)).
- **For regulated organisations:** an append-only audit log that can go to your SIEM, an access
  review of everyone, what each role can do and the rules in force, with a CSV for auditors, two people for unmasking, HTTPS and encrypted connections to
  sources, and backup and restore scripts that keep Keycloak's people too
  ([Audit log](docs/guide/audit-log.md), [People and access](docs/guide/people-and-access.md),
  [Backup and restore](docs/guide/backup-and-restore.md),
  [Compliance](docs/product/compliance.md)).

## What it looks like

A pipeline's page: its owners, the canvas with each node's rows and the quality it measured, and
under it a panel with the latest run, its nodes, suggestions, every run, its schedules and every
version.

![The page of the sample pipeline sample-gate-quarantine: its owners, then a canvas reading customers, checking them with a quality gate and writing the clean rows and the set-aside rows to two files, each node showing its rows, quality and predicted quality; under it the Run tab with the latest run's status, who asked for it, times, rows read and written, and quality](docs/images/pipeline-page.png)

A flow's page: its diagram, connections labelled with when they are taken, and the same panel, here
listing each node with what it runs after and how it went in the latest run.

![The page of the sample flow sample-quality-decides, with Run now and Delete beside its name: a diagram running a pipeline, then either a second pipeline or a notice and a stop, depending on the share of customers set aside, with a red dashed connection on failure; under it the Nodes tab, a table of each node, what it runs after, and its status, start, time and result in the latest run](docs/images/flow-page.png)

## Quick start

To install a release on a clean machine, see [INSTALL.md](INSTALL.md). It starts with
[choosing a deployment](INSTALL.md#choosing-a-deployment): production, or a Starter Pack install
to try Kilnmark with its samples and example people.

To build from source you need any Java 17 or later to start the Gradle wrapper. The wrapper
downloads Gradle, and Gradle downloads the JDK 25 the build needs.

```
./gradlew build          # compile and test everything (Windows: gradlew.bat build)
./gradlew :server:quarkusDev   # run the server in dev mode on http://localhost:8780
./gradlew release        # assemble dist/release/kilnmark-<version>, the product only
```

The server needs PostgreSQL with Apache AGE and pgvector; `podman compose up --build` starts both
(Docker works too). The root `compose.yaml` is the developer setup; customers install from the
release folder, which has its own `compose.yaml` and never the reference lab
([ADR 0024](docs/adr/0024-the-lab-is-not-the-product.md)). For everything else, see the
[development guide](docs/development.md) and the [documentation](docs/README.md).

## Layout

| Folder | Contents |
| --- | --- |
| `kiln-core/` | Kiln engine: node interface, Arrow row sets, metadata side-channel, runner |
| `kiln-nodes/` | Built-in pipeline nodes (filter, compute, sort, join, window and the rest) and the expression language |
| `kiln-plugins/` | Connectors (databases, files, documents), profiling, discovery |
| `graph/` | Metadata store: URNs, schema migrations, the GraphStore interface |
| `server/` | The one server image (roles: api, scheduler, worker) |
| `ui/` | Web UI: React and TypeScript |
| `sidecar-python/` | Reserved for an optional Python AI service; empty for now, as the server calls models itself |
| `containers/` | PostgreSQL and server images (Containerfiles) |
| `lab/` | Reference lab with synthetic data, for development, tests and demos only: never shipped |
| `samples/` | Example pipelines and flows, one idea each, and the templates. They name their sources by placeholders (`crm-db`, `shop-db`, `files`). On a Starter Pack install the Starter Pack adds them all, on its own sample tables; **Add samples** in the Library maps them to sources you choose; `lab.sh samples` adds them to the lab's |
| `pipelines/` | Built-in pipeline definitions |
| `tests/regression/` | End-to-end suite against the lab |
| `docs/` | All documentation |
| `dist/` | All build output (not committed) |
