# Kilnmark

On-prem trusted-data engine: it finds, profiles and scores every data asset, then acts on what it
finds. **Kiln** is its execution engine.

Status: version 0.0.8. Register sources (PostgreSQL, SQL Server, MySQL, MariaDB, Oracle, folders,
S3 and any database with a JDBC driver), scan them, search tables, views and columns, and open an
asset page with every column's profile and quality score. Build pipelines that read, transform and
write data, with quality gates on the way. Sign in with an API token; a token has one of four roles:
viewer, steward (runs pipelines), designer (builds pipelines, adds sources, sees sensitive values)
or admin (Settings and tokens).

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
See the [development guide](docs/development.md).

## Layout

| Folder | Contents |
| --- | --- |
| `kiln-core/` | Kiln engine: step interface, Arrow row sets, metadata side-channel, runner |
| `kiln-plugins/` | Built-in steps: connectors, profiling, discovery |
| `graph/` | Metadata store: URNs, schema migrations, the GraphStore interface |
| `server/` | The one server image (roles: api, scheduler, worker) |
| `ui/` | Web UI: React and TypeScript |
| `sidecar-python/` | AI sidecar (from v0.4) |
| `containers/` | PostgreSQL and server images (Containerfiles) |
| `lab/` | Reference lab with synthetic data, for development, tests and demos only: never shipped |
| `pipelines/` | Built-in pipeline definitions |
| `tests/regression/` | End-to-end suite against the lab |
| `docs/` | All documentation |
| `dist/` | All build output (not committed) |
