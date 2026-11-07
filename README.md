# Kilnmark

A modern data integration engine, on-prem, that knows the quality of the data it moves: it profiles
each source once, predicts a pipeline's output quality before it runs, and gates or quarantines rows
in the same pass ([ADR 0021](docs/adr/0021-pipeline-first.md)). **Kiln** is its execution engine.

Status: version 0.0.9, early and moving fast; see the [changelog](CHANGELOG.md).

- **Sources:** PostgreSQL, SQL Server, MySQL, MariaDB, Oracle, any database with a JDBC driver an
  admin registers, local folders and S3, with CSV, JSON and Parquet files. Scan them, search
  tables, views and columns, and open each asset with its column profiles and quality score.
- **Pipelines:** graphs of nodes (read, filter, compute, select, lookup, join, aggregate, union,
  dedupe, route, limit, sort, quality gate, write), built on a canvas or in YAML, with parameters, server
  variables, previews at any node and a predicted quality before they run.
- **Flows:** run pipelines in an order, on success, failure or a condition over what they
  measured, with SQL, notice, wait and stop nodes, and one pipeline for each item of a list. Nodes
  with no connection between them run in parallel, with limits per flow and per source. Each flow
  is a diagram you can change, above its YAML, and each run shows where each node spent its time.
- **Quality:** rules from a library, drift and issues, sent out as OpenLineage and ODCS 3.1.
- **Samples:** small pipelines that show one idea each, and a starter pack to run them on.
- **Sign-in** with your organisation's identity provider (OpenID Connect, with MFA), the Keycloak
  that comes with Kilnmark for organisations without one, or API tokens, in four roles: viewer, steward (runs pipelines and looks after quality), designer (builds
  pipelines, flows and sources) and admin (Settings and tokens). Columns marked sensitive stay masked
  for everyone but designers, admins included ([ADR 0027](docs/adr/0027-unmasking-is-a-grant.md)).
- **For regulated organisations:** an append-only audit log that can go to your SIEM, HTTPS and
  encrypted connections to sources, access reviews, two people for unmasking, and backup and
  restore scripts ([Compliance](docs/product/compliance.md)).

## What it looks like

A pipeline's page: the canvas, each node with the rows it moved and the quality it measured, and
under it a panel with the latest run, its nodes, suggestions, every run and every version.

![A pipeline's page: a canvas reading customers, checking them with a quality gate and writing the clean rows and the set-aside rows to two tables, each node showing its rows, quality and predicted quality; under it the Run tab with the latest run's status, times, rows read and written, and quality](docs/images/pipeline-page.png)

A flow's page: its diagram, connections labelled with when they are taken, and the same panel, here
listing each node with what it runs after and how it went in the latest run.

![A flow's page, with Run now and Delete beside its name: a diagram running a pipeline, then either a second pipeline or a notice depending on the share of customers set aside; under it the Nodes tab, a table of each node, what it runs after, and its status, start, time and result in the latest run](docs/images/flow-page.png)

## Quick start

Requirements: any Java 17 or later to start the Gradle wrapper. The wrapper downloads Gradle, and Gradle downloads the JDK 25 the build needs.

```
./gradlew build          # compile and test everything (Windows: gradlew.bat build)
./gradlew :server:quarkusDev   # run the server in dev mode on http://localhost:8780
./gradlew release        # assemble dist/release/kilnmark-<version>, the product only
```

The server needs PostgreSQL with Apache AGE and pgvector; `podman compose up --build` starts both
(Docker works too). The root `compose.yaml` is the developer setup; customers install from the
release folder, which has its own `compose.yaml` and never the reference lab
([ADR 0024](docs/adr/0024-the-lab-is-not-the-product.md)).
To install on a clean machine, see [INSTALL.md](INSTALL.md), which starts with
[choosing a deployment](INSTALL.md#choosing-a-deployment): a production install, or a Starter Pack
install for training, demonstrations and development, each chosen once at install. For everything
else, the [development guide](docs/development.md).

## Layout

| Folder | Contents |
| --- | --- |
| `kiln-core/` | Kiln engine: node interface, Arrow row sets, metadata side-channel, runner |
| `kiln-plugins/` | Built-in nodes: connectors, profiling, discovery |
| `graph/` | Metadata store: URNs, schema migrations, the GraphStore interface |
| `server/` | The one server image (roles: api, scheduler, worker) |
| `ui/` | Web UI: React and TypeScript |
| `sidecar-python/` | AI sidecar (from v0.4) |
| `containers/` | PostgreSQL and server images (Containerfiles) |
| `lab/` | Reference lab with synthetic data, for development, tests and demos only: never shipped |
| `samples/` | Example pipelines and flows, one idea each, sorted by what they show. They ship as templates, naming their sources by placeholders (`crm-db`, `shop-db`, `files`): **Add samples** in the Library maps them to your sources and keeps them there, apart from your own pipelines (or **Add the starter pack** creates synthetic sample tables for them, from `samples/data`), `lab.sh samples` to the lab's |
| `pipelines/` | Built-in pipeline definitions |
| `tests/regression/` | End-to-end suite against the lab |
| `docs/` | All documentation |
| `dist/` | All build output (not committed) |
