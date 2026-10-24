# Kilnmark

On-prem trusted-data engine: it finds, profiles and scores every data asset, then acts on what it
finds. **Kiln** is its execution engine.

Status: version 0.0.2, the first working slice. Register PostgreSQL sources (one at a time or
imported from a file), scan them, search tables, views and columns, and open an asset page with
every column's profile. No sign-in yet: lab use only.

## Quick start

Requirements: Java 25 (run Gradle on it too: set JAVA_HOME to a JDK 25). The Gradle wrapper downloads Gradle itself.

```
./gradlew build          # compile and test everything (Windows: gradlew.bat build)
./gradlew :server:quarkusDev   # run the server in dev mode on http://localhost:8080
./gradlew release        # assemble dist/release/kilnmark-<version>
```

The server needs PostgreSQL with Apache AGE and pgvector; `podman compose up --build` starts both
(Docker works too).
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
| `lab/` | Reference lab with synthetic data |
| `pipelines/` | Built-in pipeline definitions |
| `tests/regression/` | End-to-end suite against the lab |
| `docs/` | All documentation |
| `dist/` | All build output (not committed) |
