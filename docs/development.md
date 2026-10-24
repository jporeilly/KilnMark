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
other software on the machine (Tomcat, for example) expects it.

Gradle is not needed: use the wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Build and test

```
./gradlew build                 # compile, test, write output to dist/build
./gradlew :server:quarkusDev    # live-reload server on http://localhost:8780
./gradlew release               # assemble dist/release/kilnmark-<version>
```

Ports: Kilnmark listens on **8780** (8080 is left for Tomcat and the like) and its
PostgreSQL is published on **5442**. Change them with `KILNMARK_PORT` and `KILNMARK_DB_PORT` for
Compose, or `QUARKUS_HTTP_PORT` for the server on its own.

Useful endpoints in dev mode (all but the first and the `/q` ones need an API token; see
[Sign in](#sign-in)):

- `GET /api/v1/info`: product, version and roles
- `GET /api/v1/me`: who the token belongs to
- `POST /api/v1/sources`, `POST /api/v1/sources/import`, `POST /api/v1/sources/{name}/scans`
- `PUT /api/v1/sources/{name}`, `DELETE /api/v1/sources/{name}`: edit and delete (steward)
- `POST /api/v1/sources/{name}/test`, `GET /api/v1/sources/{name}/containers`: test the connection,
  list schemas or folders with counts (steward); `POST /api/v1/drafts/test` and
  `POST /api/v1/drafts/containers` do the same for settings not yet saved
- `GET /api/v1/sources/{name}/history`: runs with their jobs; `GET /api/v1/common-settings`
- `GET /api/v1/imports/{id}`: progress of an import's tests and scans (admin)
- `GET /api/v1/connectors/catalogue`: connectors by category, with planned ones;
  `GET /api/v1/connectors/{kind}/address?host=&port=&database=`: the URL those build
- `GET /api/v1/sources/{name}/assets?container=` and `GET /api/v1/sources/{name}/preview?asset=&rows=`:
  a container's tables or files, and the first rows of one (steward); `POST /api/v1/drafts/assets`
  and `POST /api/v1/drafts/preview` for settings not yet saved
- `GET /api/v1/search?q=...`, `GET /api/v1/assets/{urn}`, `GET /api/v1/runs/{id}`
- `POST /api/v1/tokens`, `GET /api/v1/tokens`, `DELETE /api/v1/tokens/{id}`: API tokens (admin)
- `GET /q/health/ready`, `GET /q/health/live`: readiness and liveness (see [Health](#health))
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
| `steward` | also edit, test and delete sources, list what they hold, and start scans |
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
to `POST /api/v1/sources/import` (`?dryRun=true` only checks). See [Sources](#sources) for the
other columns and options; `lab/sources.csv` registers the whole lab (see `lab/README.md`).

## Sources

**Adding a connection.** **Sources > Add a connection** shows every kind of source by category
(Databases, NoSQL, Files, Streaming, Applications, Lakehouse), with what each can do. Connectors
that are planned but not built are shown greyed out with their wave; set
`kilnmark.connectors.show-planned=false` (`KILNMARK_CONNECTORS_SHOW_PLANNED`) to hide them. Choosing
one opens its panel beside the gallery, in four steps you can move between: **Connect** (a
database's host, port and database build its URL; the full URL is under Advanced), **Test**,
**Choose what to scan** (schemas or folders with counts, patterns, and a preview of any table or
file), and **Save** (display name, description, AI rule; Save, or Save and scan). Edit on a
source's page opens the same panel. Escape closes it.

**Preview.** The first rows of a table or file (100 by default, at most 1,000), read live through
the connector with the same time limit and places as connection tests, and never stored, logged
or sent to a model. Values longer than 500 characters are cut, bytes are shown as hex with their
length, and a preview stops at about 2 MB. For files it also shows what was detected (format,
delimiter, header row, encoding); if that is wrong, set `delimiter`, `header` (false when the
first row is data) or `encoding` on the source: they apply to every file of it, in previews and
scans.

**Name and display name.** A source's name is its ID: it is part of every asset's reference
(`urn:kilnmark:<name>:<path>`), so it is set when the source is added and never changes. An edit
that tries to change it is refused; add a new source instead. People see the display name, which
can change at any time (by default it is the name).

**Settings every source has**, whatever its kind, stored beside the connector's own settings and
listed by `GET /api/v1/common-settings`:

| Setting | Meaning |
| --- | --- |
| `description` | Free text for people |
| `ai_egress` | Which AI models may see the source's metadata and data: `local_only` (the default) only models on servers marked local, `allowed` any configured model, `none` no model |
| `include` | Only tables or files matching these patterns (see below); empty for all |
| `exclude` | Leave out tables or files matching these patterns |

Patterns are globs, matched ignoring case: `*` (not across `/`), `**` (across `/`), `?`, `[abc]`,
`{a,b}`, `\` before a character to take it literally; up to 100 per setting, each up to 200
characters. Matching never backtracks, so no pattern can be slow. For a database, a pattern without
a `.` matches table names (`cust*` matches `crm.customers`) and one with a `.` matches
`schema.table` (`crm.*` every table in `crm`). For files, a pattern without a `/` matches file names
(`*.csv`) and one with a `/` matches paths (`raw/**`). Every kind of source applies them the same way.

**Choosing what to scan.** A database source scans the schemas in `schemas`; a folder or bucket
source the top-level folders in `containers` (`/` stands for files at the top). Empty means all.
The form's **Choose from the source** lists them with how many tables or files each holds, read
from database metadata or a listing, never by reading data. Before every scan the UI asks the
same question, and warns before a scan that would find nothing.

**Editing.** Leaving the password empty keeps the stored one, unless the edit changes where it
would go (the JDBC URL or user name, the S3 endpoint or access key): then enter it again, so a
stored password is never sent somewhere new. **Delete** is refused while a scan is waiting or
running; otherwise it removes the source with its assets, fields, profiles, run history and
graph nodes, in one transaction.

**Connection tests** and listings answer within `kilnmark.sources.check-timeout` (15 s), and at
most `kilnmark.sources.check-concurrency` (4) run at once (more get 429, "try again"). A check
keeps its place until it has really ended, so a source that never answers cannot get round the
limit: the drivers get connect, login and socket timeouts a second under the limit, S3 requests the
same time limit and listings a deadline, and a check still running at the limit is interrupted.
They answer in plain words and never repeat the password.

**Bulk import** is re-runnable, keyed by name: each row creates its source, updates it, or leaves
it unchanged, and the report says which. An update changes only the settings the file has (its
CSV columns, a JSON object's keys); an empty value removes that setting, and everything else, such
as a steward's later edits, stays. `ai_egress` is never loosened by an empty value, and a row that
sets a looser one says so in its notes. The stored password stays unless the row gives one or
moves it; a row with a password always counts as updated, since comparing it with the stored one
would let anyone running imports test guesses. Columns: `name`, `kind`,
`display_name`, `password` or `password_ref`, the connector's settings, the common ones above, and,
for databases, `host`, `port` and `database` instead of `url`. Options on
`POST /api/v1/sources/import`:

| Option | Effect |
| --- | --- |
| `dryRun=true` | Check only; nothing changes |
| `onError=skip` | Apply the rows that are right even if others are wrong (default `stop`: nothing changes) |
| `remapHosts=true` | Rewrite host names in URLs and the `host` column with `kilnmark.sources.host-map` |
| `test=true` | Afterwards, test each source's connection |
| `scan=true` | Afterwards, queue a scan of each source (only those that connected, with `test`) |

With `test` or `scan` the report has a `batchId`; `GET /api/v1/imports/{id}` shows each row's
progress (the UI polls it). The host map, `KILNMARK_SOURCES_HOST_MAP`, is a list of `from=to` pairs
set by an administrator, for example `localhost=host.docker.internal,127.0.0.1=host.docker.internal`:
sources written down from a desktop then work from inside a container. Only whole host names in
the host part of address settings (a JDBC `url`, an S3 `endpoint`, the `host` column) are
replaced, never other settings, and only when an import asks for it. The compose stack sets
that example by default.

## Security of sources

Stewards can edit, test and list sources, and test settings that are not saved. Each of those
makes the Kilnmark server open a connection to a host the steward chooses, from the server's
network: treat the steward role as able to probe what the server can reach (server-side request
forgery). To limit it:

- **Allowed hosts.** `kilnmark.sources.allowed-hosts` (`KILNMARK_SOURCES_ALLOWED_HOSTS`) lists the
  hosts sources may reach: names, addresses or `*.example.com`, comma-separated. Every host a
  source names is checked (each host of a PostgreSQL host list, SQL Server's `serverName` and
  `failoverPartner`, S3 endpoints, and AWS's S3 host when there is none) whenever a source is added,
  edited, imported, tested or listed. Empty, the default, allows any host.
- **The server's own identity and files.** JDBC URL properties that make the driver act as the
  server or touch its files are refused when a source is checked or scanned: SQL Server
  `integratedSecurity`, `authentication=ActiveDirectory...`, `authenticationScheme`, `trustStore`,
  `keyStoreLocation`, `serverCertificate`; PostgreSQL `sslkey`, `sslcert`, `sslrootcert`,
  `sslfactory`, `sslpasswordcallback`, `socketFactory`, `loggerFile`, Kerberos and GSS options; and
  any property whose name ends in File, Path, Factory, Class or ClassName.
- **Stored passwords stay put.** An edit, check or import that changes where a password goes (URL,
  user name, S3 endpoint or access key) needs the password again. The check is repeated on the
  locked row when the change is saved, so two edits at once cannot slip past it.

## Health

`GET /q/health/ready` (readiness) is up when the database answers, every migration is applied and,
in a process with the worker role, the worker is taking jobs. `GET /q/health/live` (liveness) is up
while the process runs. Both are public and say only UP or DOWN per check (`database`,
`migrations`, `worker`, `server`): no host names, users or errors, which go to the log instead.
`compose.yaml` uses readiness as the `kilnmark` service's health check; in Kubernetes, point the
readiness probe at `/q/health/ready` and the liveness probe at `/q/health/live`.

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
