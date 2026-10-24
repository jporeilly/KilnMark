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

Useful endpoints in dev mode (all but the first and the `/q` ones need an API token; see
[Sign in](#sign-in)):

- `GET /api/v1/info`: product, version and roles
- `GET /api/v1/me`: who the token belongs to
- `POST /api/v1/sources`, `POST /api/v1/sources/import`, `POST /api/v1/sources/{name}/scans`
- `GET /api/v1/search?q=...`, `GET /api/v1/assets/{urn}`, `GET /api/v1/runs/{id}`
- `POST /api/v1/tokens`, `GET /api/v1/tokens`, `DELETE /api/v1/tokens/{id}`: API tokens (admin)
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

## Sign in

Every API call needs an API token except three public ones: `GET /api/v1/info`, the health
checks under `/q/health`, and the API description at `/q/openapi` (it lists endpoints, never
data). The token goes in a header:
`Authorization: Bearer km_...`. The web UI asks for one on its sign-in page and keeps it only
for that browser tab. A token has one role, and each role includes the ones before it:

| Role | Can |
| --- | --- |
| `viewer` | search, and read assets, sources, runs and connectors |
| `steward` | also start scans, manage quality rules, patterns and issues, and see sample values |
| `admin` | also add and import sources, and create, list and revoke tokens |

**The first admin token.** On first start, when no admin token is usable, the server creates
one and writes it to a file called `admin-token` in the same folder as the secret key file
(`kilnmark.secret-key-file`): `~/.kilnmark/admin-token` in dev mode, and
`/var/lib/kilnmark/admin-token` in the container (the `keys` volume). The file is readable only by
the server's user; the log says where it is, never what it holds. Read it with, for example,
`podman compose exec kilnmark cat /var/lib/kilnmark/admin-token`.

This token **works for 24 hours only**. Sign in with it, open **API tokens**, and create a
permanent admin token for yourself (role admin, expires never), and keep that somewhere safe.
The file can then be deleted. If every admin token is later revoked or expired, the next start
writes a new 24-hour one in the same place, so restarting the server is how to get back in.

**Or set it yourself.** `KILNMARK_ADMIN_TOKEN` (or `kilnmark.admin-token`) gives the server an
admin token, stored hashed on start; no file is written. It must be `km_` followed by at least 43
letters, digits, `-` or `_`:

```
export KILNMARK_ADMIN_TOKEN=km_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
```

Changing or removing the variable sets the previous value aside on the next start; setting it
back brings that token back. A token an admin revoked under **API tokens** stays revoked even if
the variable still holds it (choose a new value). If that leaves no usable admin token, the
server writes a 24-hour `admin-token` file as above. Give every api instance the same value.

Token names may not hold line breaks or other control characters, and are written to the log
only with such characters replaced, so a name cannot forge a log line.

**Tokens for scripts.** Create them under **API tokens** in the UI, or:

```
curl -X POST http://localhost:8780/api/v1/tokens -H "Authorization: Bearer $KILNMARK_ADMIN_TOKEN" \
     -H "Content-Type: application/json" \
     -d '{"name": "nightly-ci", "role": "steward", "expiresAt": "2027-01-31T00:00:00Z"}'
```

The response holds the token once; only its SHA-256 is stored. `DELETE /api/v1/tokens/{id}`
revokes one at once.

**Lab use only:** `KILNMARK_AUTH_MODE=off` turns sign-in off: anyone who can reach the server
is an admin, and no tokens can be created. The server only starts this way when it listens on a
loopback address (`QUARKUS_HTTP_HOST=127.0.0.1`), and logs a warning on every start. The setting
takes `tokens` (the default) or `off`; any other value, a typo for example, stops the server
rather than guessing.

## Workers and the job queue

Scans run on the worker role. `POST /api/v1/sources/{name}/scans` (api role) only queues the
scan, as a run plus a job in PostgreSQL; any process with the worker role picks it up, so api and
worker can run in separate containers. A process without the worker role never runs a scan; if no
worker is running, scans stay `queued`.

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.worker.concurrency` (`KILNMARK_WORKER_CONCURRENCY`) | `2` | Scans one worker runs at the same time |
| `kilnmark.worker.poll-interval` | `5s` | How often to look for jobs when no notification arrives |
| `kilnmark.worker.heartbeat-interval` | `10s` | How often a running job reports that it is alive |
| `kilnmark.worker.stale-after` | `2m` | A running job silent this long, whose worker is gone, is recovered |
| `kilnmark.worker.max-attempts` | `3` | Tries before a job and its run are marked failed |
| `kilnmark.worker.shutdown-timeout` | `30s` | On shutdown, how long running scans may take before they are requeued |

Workers wake at once on a PostgreSQL notification and poll as a fallback. A source has at most
one scan waiting or running: asking for another returns the one in progress. While a worker scans
a source it holds a lock on that source, so two scans of one source never overlap. If a worker
dies, the next worker to check (on start, then every 30 seconds) puts its jobs back in the queue,
or marks them failed once they have been tried `max-attempts` times.

**Stopping a worker.** On shutdown a worker stops taking jobs and lets running scans finish for
up to `shutdown-timeout` (30 s). Scans still running are then asked to stop, which takes up to
15 s more, and go back in the queue without using up an attempt; another worker starts them
again. Give the container time for this, or it is killed first and its scans wait for stale-job
recovery (`stale-after`, 2 minutes) instead:

- Compose: `compose.yaml` sets `stop_grace_period: 60s` on the `kilnmark` service (the default is
  10 s).
- Kubernetes: set `terminationGracePeriodSeconds: 60` in the worker's pod spec (the default is
  30 s).

With a shorter grace period, lower `KILNMARK_WORKER_SHUTDOWN_TIMEOUT` to leave 15 s or more. Each scan also profiles
`kilnmark.scan.parallel-tables` tables at a time, so one worker reads up to
`concurrency × parallel-tables` tables at once.

## Data quality

Each scan also samples rows, keeps value shapes and formats, and then queues a job of kind
`quality` that works out measures, drift and scores and runs the asset's rules. Endpoints (asset
endpoints take `?urn=`, since file URNs hold slashes):

- `GET /api/v1/quality/asset?urn=...`: scores, measures, value patterns, accepted patterns
- `GET /api/v1/quality/asset/drift`, `/asset/versions`, `PUT /asset/baseline` (steward)
- `GET /api/v1/quality/asset/samples` (steward): sample rows, sensitive columns masked unless an
  admin passes `unmask=true`; `PUT /asset/sensitive` (steward)
- `POST /api/v1/quality/asset/patterns` (steward): accept a shape; `GET /asset/recommendations`
- `GET|POST /api/v1/quality/rules`, `GET|PUT|DELETE /rules/{id}`, `POST /rules/preview` and
  `POST /rules/{id}/run` (steward), `GET /rules/{id}/results`, `GET /results/{id}`
- `POST /api/v1/quality/asset/runs` (steward), `GET /runs/{id}`
- `GET /api/v1/quality/issues`, `PUT /issues/{id}` (steward)
- `GET /api/v1/quality/sources/{name}`: a source's scores
- `GET /api/v1/quality/export/openlineage` and `/export/odcs` (ADR 0020)

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.quality.webhook-allow-list` | none | URL prefixes rule webhooks may call; none allows no webhooks |
| `kilnmark.quality.query-timeout` | `60s` | How long one rule's query may run in a source database |
| `kilnmark.quality.sample-rows` | `1000` | Rows sampled per asset |
| `kilnmark.quality.examples` | `5` | Example values kept per value shape |
| `kilnmark.quality.failing-rows` | `20` | Failing rows kept with each result |
| `kilnmark.quality.keep-versions` | `30` | Profile versions kept per asset, besides baselines |
| `kilnmark.quality.default-cadence` | `1d` | How often new data is expected until an asset's history says |

Source settings, for any kind of source: `keep_samples` (`true` by default; `false` keeps no
sample rows, example values, most common values or failing rows, though numeric statistics are
kept), `sample_rows` (up to 10,000) and, for databases, `table_sample_above_rows`. Seeing values
(samples, examples, failing rows, previews, and values in measures, drift and exports) needs the
steward role; only admins unmask sensitive columns or unmark them.

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.quality.custom-sql` | `false` | Whether admins may write and run custom SQL rules |
| `kilnmark.quality.sample-max-mb` | `8` | The most memory one asset's sample may hold |

Webhooks are called once per evaluation that warns, fails or recovers. There are no retries: a
failed call is logged, and the next evaluation calls again while the rule still warns or fails.

### Security: give every source a read-only login

Kilnmark only reads sources, and runs rules in each check's own read-only transaction (on
PostgreSQL) with a time limit. Still, **register sources with a read-only login that is not a
superuser** and has no rights to server files, programs or other sessions:

- Custom SQL rules (off by default; admins only) run as that login and **can read anything it can
  see**, not only the schemas the source lists. They refuse to run when the login is a superuser
  or a member of `pg_read_server_files`, `pg_write_server_files`, `pg_execute_server_program` or
  `pg_signal_backend`, and on SQL Server, which has no read-only transactions.
- PostgreSQL's read-only transactions do not stop functions that act outside the database
  (reading files, advisory locks, signalling sessions), which is why such functions are refused
  in custom SQL and why the login must not hold those rights.

For PostgreSQL, for example:

```
CREATE ROLE kilnmark_reader LOGIN PASSWORD '...';
GRANT USAGE ON SCHEMA sales TO kilnmark_reader;
GRANT SELECT ON ALL TABLES IN SCHEMA sales TO kilnmark_reader;
```

## Try it on the reference lab

Start Kilnmark first, then the lab, which joins Kilnmark's network (`kilnmark`):

```
podman compose up -d                          # Kilnmark on http://localhost:8780
podman compose -f lab/compose.yaml up -d      # the lab, reachable from Kilnmark by name
```

Open http://localhost:8780, sign in, go to **Sources**, and add the lab. Kilnmark reaches it by service
name, the same under Podman and Docker:

| Field | Value |
| --- | --- |
| Kind | PostgreSQL |
| Name | `lab` |
| JDBC URL | `jdbc:postgresql://lab-postgres:5432/lab` |
| User name / password | `lab` / `lab` |

(From the machine itself the lab is on `localhost:5433`, and its S3 on `localhost:9000`.)

Press **Scan now**. A scan of the lab (six tables, 10 million rows once `lab.make_big_table` has
run) takes about 15 seconds. Then search for `customer` and open `crm.customers`.

To register many sources at once, import a CSV (header row `name, url, username, password,
password_ref, schemas`) or a JSON array under **Sources > Import sources from a file**, or post it
to `POST /api/v1/sources/import` (`?dryRun=true` only checks).

Sign in first with the admin token from `admin-token` (see [Sign in](#sign-in)). API tokens are
for machines and for the lab; people will sign in through OpenID Connect from v0.2
([ADR 0014](adr/0014-authentication.md)).

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
