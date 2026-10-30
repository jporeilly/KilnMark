# Kilnmark

On-prem trusted-data engine: it finds, profiles and scores every data asset, then acts on what it
finds. **Kiln** is its execution engine.

Status: version 0.0.2, the first working slice. Register PostgreSQL sources (one at a time or
imported from a file), scan them, search tables, views and columns, and open an asset page with
every column's profile. No sign-in yet: lab use only.

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
To install on a clean machine, see [INSTALL.md](INSTALL.md); for everything else, the
[development guide](docs/development.md).

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
| `samples/` | Example pipelines and flows, one idea each, sorted by what they show. They ship as templates, naming their sources by placeholders (`crm-db`, `shop-db`, `files`): **Add samples** on the Pipelines page maps them to your sources, `lab.sh samples` to the lab's |
| `pipelines/` | Built-in pipeline definitions |
| `tests/regression/` | End-to-end suite against the lab |
| `docs/` | All documentation |
| `dist/` | All build output (not committed) |
