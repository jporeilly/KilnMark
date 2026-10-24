# Development guide

## Platforms

Kilnmark servers run on **Linux** (see [ADR 0002](adr/0002-linux-on-prem-one-image.md)).
You can develop on Windows, macOS or Linux: the server is plain Java, and the database runs in
a container.

| Tool | Version | Needed for |
| --- | --- | --- |
| Java | 17 or later on the PATH | Starting the Gradle wrapper; it fetches JDK 25 itself |
| Git | any recent | Source control |
| Node.js | 22 or later | The web UI |
| Podman (Podman Desktop with a WSL 2 machine on Windows), or Docker | Podman 5 or later | PostgreSQL and the full stack |

**Java 25 is downloaded for you.** The build runs on JDK 25 (`gradle/gradle-daemon-jvm.properties`)
and compiles with it; if the machine has none, Gradle downloads one into its own cache the first
time. Any Java 17 or later on the `PATH` is enough to start the wrapper, so leave `JAVA_HOME` as
Pentaho and Tomcat expect it.

Gradle is not needed: use the wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Build and test

```
./gradlew build                 # compile, test, write output to dist/build
./gradlew :server:quarkusDev    # live-reload server on http://localhost:8780
./gradlew release               # assemble dist/release/kilnmark-<version>
```

Ports: Kilnmark listens on **8780** (8080 is left for Pentaho, Tomcat and the like) and its
PostgreSQL is published on **5442**. Change them with `KILNMARK_PORT` and `KILNMARK_DB_PORT` for
Compose, or `QUARKUS_HTTP_PORT` for the server on its own.

Useful endpoints in dev mode:

- `GET /api/v1/info`: product, version and roles
- `POST /api/v1/sources`, `POST /api/v1/sources/import`, `POST /api/v1/sources/{name}/scans`
- `GET /api/v1/search?q=...`, `GET /api/v1/assets/{urn}`, `GET /api/v1/runs/{id}`
- `GET /q/health`: health checks
- `GET /q/openapi`: the API specification

## Run the stack

```
podman machine start            # Windows and macOS only, once per boot
./gradlew :server:quarkusBuild  # build the server first
podman compose up --build       # PostgreSQL (AGE, pgvector) and the Kilnmark server
```

If `podman compose` reports `failed to connect to the docker API at npipe:////./pipe/podman-machine-default`,
the compose tool cannot find Podman: start the machine (`podman machine start`), or point it at
the machine's pipe (`podman machine inspect --format "{{.ConnectionInfo.PodmanPipe.Path}}"`, then
`$env:DOCKER_HOST = "npipe://" + that path with forward slashes`), or turn on Docker compatibility
in Podman Desktop's settings.

`podman compose` uses whichever compose provider is installed (Podman Desktop can install
`podman-compose` or `docker-compose`). Docker users run `docker compose up --build` instead; the
same `compose.yaml` and Containerfiles work for both
([ADR 0011](adr/0011-container-runtime.md)).

Integration tests use Testcontainers, against the same PostgreSQL image Kilnmark ships. Build it
once (and after changing it) before `./gradlew build`:

```
podman build -t kilnmark-postgres:dev -f containers/postgres/Containerfile containers/postgres
```

With Podman, point Testcontainers at the Podman socket:
set `DOCKER_HOST` to the address `podman machine inspect` reports, and
`TESTCONTAINERS_RYUK_DISABLED=true` if Ryuk fails to start under rootless Podman.

The server applies database migrations on start. To run separate roles, set `KILNMARK_ROLES`,
for example `KILNMARK_ROLES=worker`.

## Try it on the reference lab

With the stack and the lab both running:

```
podman compose -f lab/compose.yaml up -d      # the lab's PostgreSQL on :5433
podman compose up --build                     # Kilnmark on http://localhost:8780
```

Open http://localhost:8780, go to **Sources**, and add the lab. From inside the Kilnmark container
the host is `host.containers.internal` under Podman (`host.docker.internal` under Docker):

| Field | Value |
| --- | --- |
| Name | `lab` |
| JDBC URL | `jdbc:postgresql://host.containers.internal:5433/lab` |
| User name / password | `lab` / `lab` |

Press **Scan now**. A scan of the lab (six tables, 10 million rows once `lab.make_big_table` has
run) takes about 15 seconds. Then search for `customer` and open `crm.customers`.

To register many sources at once, import a CSV (header row `name, url, username, password,
password_ref, schemas`) or a JSON array under **Sources > Import sources from a file**, or post it
to `POST /api/v1/sources/import` (`?dryRun=true` only checks).

There is no sign-in yet ([ADR 0014](adr/0014-authentication.md)): run it only on a trusted machine.

## Web UI

```
cd ui
npm install
npm run dev        # http://localhost:5173, calls the server on :8780
npm run build      # writes dist/ui
```

## Rules

- Versions go up by exactly 0.0.1 per release.
- Run the full regression suite before every package.
- Anything hard to reverse gets a [decision record](adr/README.md).
- Code adapted from Kettle 10.2 is logged in [provenance.md](legal/provenance.md).
