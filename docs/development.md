# Development guide

## Platforms

Kilnmark servers run on **Linux** (see [ADR 0002](adr/0002-linux-on-prem-one-image.md)).
You can develop on Windows, macOS or Linux: the server is plain Java, and the database runs in
a container.

| Tool | Version | Needed for |
| --- | --- | --- |
| Java | 17 or later on the PATH | Starting the Gradle wrapper; it fetches JDK 25 itself |
| Git | any recent | Source control |
| Node.js | 22.12 or later (or 20.19 or later), as Vite needs | The web UI |
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
- `GET /api/v1/browse/sources`, `GET /api/v1/browse/sources/{name}/containers`,
  `GET /api/v1/browse/sources/{name}/assets?container=...`: the catalogue as a tree, paged
  (`offset`, `limit`) and filtered by name (`q`)
- `GET /api/v1/diagram?source=...&container=...`, `?asset={urn}&depth=1`, or `?urn=...&urn=...`:
  tables, columns and relationships for a diagram (at most 60 tables unless `limit` says more, up to 150)
- `GET /api/v1/relationships?asset={urn}` or `?source=...&status=proposed`, and
  `POST /api/v1/relationships/{id}/accept` or `/reject` (steward)
- `GET /api/v1/documents/text?urn=...&page=N`, `GET /api/v1/documents/find?urn=...&q=...`,
  `GET /api/v1/documents/chunks?urn=...&offset=0&limit=5`: a document's text (see
  [Documents](#documents))
- `POST /api/v1/tokens`, `GET /api/v1/tokens`, `DELETE /api/v1/tokens/{id}`: API tokens (admin)
- `/api/docs`: interactive API documentation; `/q/openapi`: the OpenAPI document it reads
- `GET /q/health/ready`, `GET /q/health/live`: readiness and liveness (see [Health](#health))
- `POST /api/v1/search/ask`: ask in plain English; `/api/v1/ai/...`: AI models (see [AI models](#ai-models))
- `/mcp`: the read-only MCP server for outside AI assistants
- `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `POST /api/v1/notifications/read`,
  `GET|PUT /api/v1/me/notifications`; `/api/v1/mail/...` for admins (see [Notifications](#notifications))
- `GET /q/openapi`: the API specification

## Run the stack

```
podman machine start            # Windows and macOS only, once per boot
./gradlew :server:quarkusBuild  # build the server first
podman compose up --build       # PostgreSQL (AGE, pgvector) and the Kilnmark server
```

On Windows, `.\build.ps1` in PowerShell does the whole thing: it checks Node.js, runs
`gradlew.bat release`, builds both images directly (podman-compose on Windows cannot build an
image whose Containerfile is in a sub-folder), restarts the stack and waits until the server
answers. `-NoRestart` builds only; `-Lab` also starts the reference lab.

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
| `steward` | also edit, test and delete sources, list what they hold, start scans, accept or reject proposed relationships, manage quality rules, patterns and issues, and see sample values |
| `admin` | also add and import sources, create, list and revoke tokens, manage AI models, and write custom SQL quality rules |

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
steward role; only admins unmask sensitive columns or unmark them. A column marked sensitive has
every value drawn from it masked for everyone else, wherever it appears: samples, examples, the
profile's most common values, range, quantiles and histogram, measure evidence, drift detail,
allowed values and range bounds of its rules and recommendations, failing rows, failing-rows
queries and database error text, exports, live previews and the MCP server (which always masks).
Shapes, counts and shares stay. Quality endpoints take `unmask=true` from an admin. A rule on a
sensitive column is not previewed for anyone else and its results give counts only, since failing
rows would show which rows hold which values. A workbook whose sheets have a sensitive column keeps
its text, find and snippets for admins. Marks outlive the column (a dropped or renamed column's
stored values stay masked). Embeddings and prompts carry names, types and descriptions only, never
values.

**Known limit:** marks protect the catalogued source and its stored data. A steward who can reach
the same folder, bucket or database another way, for example by drafting a new connection to the
same folder path (folders need no password), can preview it unmasked under the new connection. The
control for that is where sources may point: limit folder sources to the folders an administrator
lists (`KILNMARK_FILES_ROOTS`) and give database and object storage sources their own credentials,
which a steward drafting a new connection would also need. Objects in a bucket whose keys start with
`/` or contain empty, `.` or `..` parts are still scanned from the listing, but cannot be previewed
or read on demand.

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
  `pg_signal_backend`, when it may reach other databases (it may execute any of dblink's functions,
  which are granted to everyone when the extension is created, or use a foreign server), on a
  database with any user mapping or foreign table (a view owned by another role can reach one), and
  on SQL Server, which has no read-only transactions. The refusal names the fix, such as
  `REVOKE EXECUTE ON FUNCTION dblink_connect(text,text), ... FROM PUBLIC` with dblink's own functions. dblink and postgres_fdw functions
  are also refused in the SQL itself.
- PostgreSQL's read-only transactions do not stop functions that act outside the database
  (reading files, advisory locks, signalling sessions), which is why such functions are refused
  in custom SQL and why the login must not hold those rights.

**Known limit: counts.** A steward who may not unmask a sensitive column cannot preview rules on
it or see their failing rows, but can still save such a rule, run it and read how many rows fail.
Repeated with different bounds or values, counts tell how the column's values are spread (never
which row holds which value). Marking a column sensitive limits what is shown, not which rules may be
run; keep the steward role for people trusted with that.

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

Open **Browse** to walk the lab: choose `billing` to see its tables drawn with their
relationships. The declared key from `crm.accounts` is a solid line; the join from
`billing.transactions.customer_id`, which no key declares, is a dashed proposal. As a steward or
admin, accept or reject it there, on the asset page, or under **Relationships**. Proposals are
refreshed after every scan (within and across sources); decisions are kept.

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
scans. A preview reads the table or file named even when the source's include or exclude patterns
leave it out of scans. Columns marked sensitive on the catalogued table or file are masked for
anyone but an admin. For a source with marks, only admins may change how it is read (folders,
patterns, header rows, formats) or preview such changes, as other settings could read the marked
columns under other names; new connections, which have no marks, preview as before. Previews are sent with `Cache-Control: no-store`.

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
characters, and their total size per setting is capped. Matching never backtracks: its cost grows
with the patterns' size times the name's length, never faster. For a database, a pattern without
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
  edited, imported, tested or listed, and again just before every scan, quality run, preview, test
  or on-demand read connects: narrow the list (and restart) and saved sources naming other hosts stop
  at once, their jobs failing with a message that names the host. Names are compared as written;
  what a name resolves to is not checked. Hosts are read with the database driver's own parser, and URL
  properties that would name another host, port or database (PostgreSQL `PGHOST`, `PGPORT`,
  `PGDBNAME`; SQL Server `server`, `address`, `addr`, `network address`) are refused, so the
  address is always the URL's. Empty, the default, allows any host.
- **The server's own identity and files.** JDBC URL properties that make the driver act as the
  server or touch its files are refused when a source is checked or scanned, as the driver reads
  them (braces, quotes and percent-encoding do not hide them): SQL Server `integratedSecurity`,
  any `authentication` but `SqlPassword` or `NotSpecified`, `authenticationScheme`, `trustStore`,
  `keyStoreLocation`, `serverCertificate`; PostgreSQL `sslkey`, `sslcert`, `sslrootcert`,
  `sslfactory`, `sslpasswordcallback`, `socketFactory`, `loggerFile`, `service` (the server's
  `pg_service.conf`), Kerberos and GSS options; and any property whose name ends in File, Path,
  Factory, Class or ClassName. Note that PostgreSQL's driver reads the server's own `~/.pgpass`
  when a source gives no password: keep no `.pgpass` file for the user Kilnmark runs as.
- **Time limits.** URL properties that set the driver's timeouts (`loginTimeout`,
  `connectTimeout`, `socketTimeout`, `cancelSignalTimeout`) are refused, since PostgreSQL's driver
  lets them override the limits Kilnmark sets for checks.
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

## AI models

AI is optional at every step: each feature works without a model and says so
([ADR 0012](adr/0012-ai-runtime.md), [ADR 0016](adr/0016-ai-runtime-choices.md)). Admins add
models under **AI models**, press **Test**, and choose which model each feature uses.

**Run the bundled models.** llama.cpp's `llama-server` in Compose, on CPU or an NVIDIA GPU:

```
podman compose --profile ai-download run --rm ai-download   # once: about 1.2 GB, checked by SHA-256
podman compose --profile ai up -d                           # CPU: services ai and ai-embed
podman compose --profile ai-cuda up -d                      # or GPU: services ai-cuda and ai-embed
```

The defaults are Qwen2.5-1.5B-Instruct (Q4_K_M, Apache 2.0) for chat and bge-small-en-v1.5 (Q8_0,
MIT, 384 dimensions) for embeddings. For an offline install, copy the `.gguf` files into the
`models` volume instead of downloading, and set `KILNMARK_AI_CHAT_MODEL` or
`KILNMARK_AI_EMBED_MODEL` to use other files. Each file comes from a fixed revision and is kept
only if its SHA-256 matches (sources, revisions and checksums are in ADR 0016). GPU: Docker needs the NVIDIA Container Toolkit;
Podman needs a CDI specification (`sudo nvidia-ctk cdi generate --output=/etc/cdi/nvidia.yaml`),
and if your compose tool ignores the GPU reservation, run the CUDA image with
`--device nvidia.com/gpu=all`.

**Add them in Kilnmark** under **AI models > Add a model**, then **Test** each:

| Field | Chat | Embeddings |
| --- | --- | --- |
| Provider | OpenAI-compatible server | OpenAI-compatible server |
| Base URL | `http://ai:8080/v1` (GPU: `http://ai-cuda:8080/v1`) | `http://ai-embed:8080/v1` |
| Model name | `qwen2.5-1.5b-instruct` | `bge-small-en-v1.5` |
| Purpose | Chat | Embedding, dimensions `384`, context size `512` |
| Local | ticked (on your network) | ticked (on your network) |

A server outside Compose (`./gradlew :server:quarkusDev`) reaches them at
`http://localhost:8781/v1` and `http://localhost:8782/v1`. Then, under **Features**, choose the
embedding model for **Search by meaning** (every source is embedded in the background, and again
after each scan) and the chat model for **Ask in plain English**.

**Find local models.** **AI models > Find local models** looks for Ollama and LM Studio on
`localhost` and, when Kilnmark runs in a container, on the machine running it and for the bundled
Compose services, and lists their models with size and GPU use; **Use this model** fills in the
add-model form (with context size and embedding size where the server says). **Check this address**, next to **Base URL**, asks one address (with or without `/v1`) and
then offers its models to choose from. When something is not found, it says why in plain words
(connection refused, timed out, unknown host name, an HTTP status, not a model server) and what to
do. Behind it is `POST /api/v1/ai/discover` (admins only, optional body `{"baseUrl": "..."}`): it
asks only the model servers' own paths (`/api/version`, `/api/tags`, `/api/ps`, `/v1/models`,
`/health`, `/props`, `/api/v0/models`, and `POST /api/show` for Ollama's embedding models) with a
1-second connect and 3-second read timeout and a 12-second limit overall, follows no redirects,
uses no proxy and returns a summary, never the servers' answers. Addresses pass the same rules as
model profiles first: while hosted models are forbidden, hosts not in `kilnmark.ai.local-hosts`
are listed as not checked, without being looked up or called; names are looked up within 2
seconds; metadata and link-local addresses are refused. One server reached under two names is
listed once.

**Ollama or LM Studio on the same PC.** Inside a container, `localhost` is the container itself,
not your PC, so `http://localhost:11434/v1` does not reach an Ollama running on the PC. Use the
name the container engine gives the PC:

| Kilnmark runs in | Ollama | LM Studio |
| --- | --- | --- |
| Podman (including Podman Desktop on Windows) | `http://host.containers.internal:11434/v1` | `http://host.containers.internal:1234/v1` |
| Docker, or Kilnmark's `compose.yaml` with either engine | `http://host.docker.internal:11434/v1` | `http://host.docker.internal:1234/v1` |
| No container (`quarkusDev`, `java -jar`) | `http://localhost:11434/v1` | `http://localhost:1234/v1` |

Ollama and LM Studio listen only on their own machine by default, which a container counts as
another machine. On Windows, set `OLLAMA_HOST=0.0.0.0:11434` as a user environment variable and
restart Ollama (quit it from the tray first). On Linux, run `sudo systemctl edit ollama`, add

```
[Service]
Environment="OLLAMA_HOST=0.0.0.0:11434"
```

then `sudo systemctl daemon-reload && sudo systemctl restart ollama`. In LM Studio, start the
server and turn on **Serve on Local Network**.

Either way the model server now accepts connections from other computers too, so close the port
to everything else. Ollama has no API key: anyone who can reach port 11434 can use your GPU, and
also pull new models onto your disk and delete the ones you have. On Windows, the WSL virtual
adapter (`vEthernet (WSL)`, which Podman machine uses) is usually on the **Public** profile, so a
rule for "private networks only" does not cover it and a rule for public networks opens the port
everywhere. Instead add an inbound rule for TCP 11434 (or 1234) limited by remote address to the
WSL subnet, for example in an administrator PowerShell
`New-NetFirewallRule -DisplayName "Ollama from WSL" -Direction Inbound -Protocol TCP -LocalPort 11434 -RemoteAddress 172.16.0.0/12 -Action Allow`
(check the subnet with `ipconfig`; WSL usually uses part of 172.16.0.0/12), and make sure no
broader rule allows the port.

**Rootless Podman 5.3 and later** (pasta networking) gives `host.containers.internal` the address
`169.254.1.2`. That is a link-local address, which model profiles may not use, so Kilnmark makes
one exception: while Kilnmark runs in a container, `169.254.1.2` is accepted when it is reached
through `host.containers.internal` or `host.docker.internal`, and refused typed as an address or
behind any other name. Other link-local and metadata addresses (169.254.169.254 and the like)
stay refused whatever the name. IPv4 addresses written in unusual forms (leading zeros,
hexadecimal, a single number, fewer than four parts) and host names ending in a dot are refused
with a plain error when a profile is saved or an address is checked, rather than read one way by
Kilnmark and another by the name service.

When Kilnmark runs in a container (the image sets `KILNMARK_IN_CONTAINER=true`; otherwise
`/.dockerenv` or `/run/.containerenv`), both names are on the default `kilnmark.ai.local-hosts`,
so models there count as local under the operator lock too, but only while the name leads to this
machine or a private network (loopback, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7, or
169.254.1.2), checked before every call (the answer is kept for 5 seconds): the name service
decides where these names go. Outside a container they are not on the default list.
`KILNMARK_IN_CONTAINER` takes `1`, `yes`, `true` or `on` (and `0`, `no`, `false`, `off`).

**GPU use comes from the model server.** Kilnmark does not run `nvidia-smi` or look for a GPU: in
a container it usually cannot see one, and it does not need to, because the model server is what
uses it. Ollama reports, for each loaded model, how much is in GPU memory, which the page shows as
"On GPU", "Split · 60% on GPU" or "CPU only" (a model that has not run yet shows "Not loaded
yet"). This works the same for NVIDIA, AMD and Apple GPUs and for a GPU server elsewhere on your
network. llama-server, vLLM and LM Studio do not report GPU use, so the page says so; their
models are still listed.

**Other models.** Ollama: `http://HOST:11434/v1`. vLLM: `http://HOST:8000/v1`. Hosted providers
need an API key: OpenAI (no base URL needed), Azure OpenAI
(`https://RESOURCE.openai.azure.com/openai/v1`, model = the deployment name), Anthropic and Google
Gemini (chat only for Anthropic). Only an OpenAI-compatible server you run can be ticked local, and
the box starts unticked. With a hosted embedding model, the text of every search is sent to that
provider; with a hosted chat model, every question asked in plain English is.

Changing a profile's address to another host clears **local** and asks for the API key again: a
stored key is only sent where it was entered for. Addresses on link-local networks
(169.254.0.0/16, fe80::/10) and cloud metadata names are refused, unless whoever runs the server
sets `kilnmark.ai.allow-metadata-addresses=true`.

**What may leave the network.** Each source has an AI rule, `ai_egress` in its settings:
`local_only` (the default) lets only local models see its metadata, `allowed` any model, `none`
no model. The admin's rule under **AI models** can refuse every model that is not local. Whoever
runs the server can go further with `KILNMARK_AI_HOSTED_MODELS=forbidden`: admins cannot undo it,
and a model then counts as local only if its address is an IP address on one of the networks in
`KILNMARK_AI_LOCAL_HOSTS`, or a host name listed there (names are not resolved for this), checked
when it is saved and before every call; ticking **local** is not enough. With the Compose AI
services, list their names: `KILNMARK_AI_LOCAL_HOSTS=10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,localhost,host.containers.internal,host.docker.internal,ai,ai-cuda,ai-embed`.
Kilnmark cannot tell when a local server forwards requests to a hosted one. A refused call never
reaches the model; usage shows it.

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.ai.hosted-models` | `allowed` | `forbidden` locks out every model that is not local; admins cannot undo it. Other values stop the server |
| `kilnmark.ai.local-hosts` | loopback and private networks and `localhost`; in a container also `host.containers.internal` and `host.docker.internal` | While hosted models are forbidden, where a local model must be: networks (CIDR), IP addresses and host names, comma-separated. Names are matched as written, not resolved, except that the two container host names count only while they resolve to loopback, private addresses or 169.254.1.2. Setting it replaces the whole default; empty means the default. An entry that does not parse stops the server |
| `kilnmark.ai.allow-metadata-addresses` | `false` | Allow link-local and cloud metadata addresses as model addresses |
| `kilnmark.ai.usage-retention` | `90d` | Usage records older than this are deleted; at least `1d` |
| `kilnmark.ai.max-retries` | `2` | Retries after timeouts, rate limits and server errors, with backoff (scans; a search waits about 10 seconds at most) |
| `kilnmark.ai.embed-batch-size` | `16` | Assets per embedding request |
| `kilnmark.ai.min-similarity` | `0.3` | Search by meaning ignores less similar assets (cosine, 0 to 1) |
| `kilnmark.ai.keep-usage-text` | `false` | Keep prompts and answers in usage records |

**Usage.** **AI models > See usage** shows calls, tokens and time by day and model, and the
latest calls with their outcome and who asked (`GET /api/v1/ai/usage?days=30`).

A model's **Calls at the same time** limit applies per Kilnmark process: with separate api and
worker processes, the model server can get that many from each.

**MCP server.** Outside AI assistants (MCP clients) can search and read the catalogue at
`http://HOST:8780/mcp` (Streamable HTTP only), sending a viewer token as
`Authorization: Bearer km_...`. Tools: `search_assets`, `get_asset`, `list_sources`. An assistant
sees only sources whose AI rule is `allowed`. If its model runs on your own network, an admin can
tick **Local AI assistant** for its token on the **API tokens** page; it then also sees sources
whose rule is `local_only`. Sources whose rule is `none` are never shown. Create a dedicated
viewer token for each assistant, so it can be revoked on its own. With sign-in off, `/mcp`
answers only requests addressed to `localhost`.

## Documents

The `files` and `s3` connectors read the text of PDF, Office, OpenDocument, EPUB, RTF, HTML, XML,
mail, plain text and Markdown files ([ADR 0017](adr/0017-documents.md)). Each document's asset page
shows what was found (pages, words, language, title, author, dates, mail headers), its text page by
page with find, a workbook's sheets (read as tables where they have a header row) and a message's
attachments. Search finds words in documents' text and shows a snippet.

Documents are untrusted input, so reading them is limited:

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.documents.max-file-mb` | `100` | Larger documents are recorded, not read |
| `kilnmark.documents.max-legacy-file-mb` | `30` | The same for .doc, .xls, .ppt and .msg, which are read whole into memory |
| `kilnmark.documents.max-part-mb` | `64` | Office files with a part (other than worksheets, slides, a document's body or media) that really expands past this are not read |
| `kilnmark.documents.max-characters` | `1000000` | Text kept from one document, attachments included |
| `kilnmark.documents.timeout-seconds` | `60` | A document still being read after this is abandoned (a sheet: 1,024 rows within this); documents waiting for a reader are not timed |
| `kilnmark.documents.max-depth` | `3` | How deep files inside files (attachments, embedded objects) are read |
| `kilnmark.documents.threads` | `2` | Documents read at the same time across the server |
| `kilnmark.documents.chunk-characters` | `1500` | Size of the chunks text is stored and searched in |
| `kilnmark.documents.chunk-overlap` | `150` | Characters each chunk repeats from the one before |

A source's setting `keep_text=false` keeps only documents' properties: no text is stored (text
stored before is deleted at the next scan) and none is shown. With `read_text_on_demand=true` as
well, stewards can still read a document's text on its asset page: it is read from the source when
they open it, on a reader of its own, at most six documents a minute each, and never stored.
Copies of S3 objects are made in a folder under `java.io.tmpdir` that only the server's user can
read, and deleted after (a workbook's copy is kept until the scan ends, so a scan fetches it once).

## Notifications

Events (failing and recovering rules, new issues, failed scans and connection tests, drift, and
pipeline runs) become in-app notifications for everyone and, for those who ask, emails
([design](design/notifications.md), [guide](guide/notifications.md)). Admins set the mail server
under **Settings**, **Email** (`/api/v1/mail/settings`; the password is write-only), send a test
email (`POST /api/v1/mail/test`), add team addresses (`/api/v1/mail/teams`) and read the send log
(`GET /api/v1/mail/log`). Workers send the emails, with retries; with no worker running, emails
wait. The reference lab's Mailpit catches them (see `lab/README.md`). The admin's settings in the UI
win over these defaults, except locked ones. Digests go at an hour of the server's clock: set `TZ`
(for example `TZ=Europe/London`) on the container, which otherwise runs on UTC.

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.mail.host`, `.port`, `.security`, `.username`, `.from`, `.from-name`, `.base-url` | none; `starttls`; `Kilnmark` | The mail server until an admin saves one |
| `kilnmark.mail.password` (`KILNMARK_MAIL_PASSWORD`) | none | Used only while the server and user name in force are the configured ones, never unencrypted, never logged or shown |
| `kilnmark.mail.allow-plain-auth` | `false` | Whether a password entered under **Settings**, **Email** may go unencrypted (security none) |
| `kilnmark.mail.allowed-domains` | none (any) | The domains email may go to, subdomains included |
| `kilnmark.mail.require-confirmation` | `true` | Whether people confirm their address before it gets email |
| `kilnmark.mail.locked` | none | Settings admins cannot change in the UI, by these names: `host`, `port`, `security`, `username`, `from`, `from-name`, `base-url`, `allow-plain-auth`, `allowed-domains`, `require-confirmation`, `max-attempts`, `rate-limit-per-hour`, `undelivered-after`, `undelivered-action`. Another name stops the server |
| `kilnmark.notifications.rate-limit-per-hour` | `20` | Emails of their own one address may get in an hour (1 to 1,000); the rest wait for its daily digest |
| `kilnmark.notifications.max-attempts` | `5` | Tries before an email is given up (1 to 10; after 1, 3, 9, 27 minutes, then hourly) |
| `kilnmark.notifications.undelivered-after`, `.undelivered-action` | `24h`, `digest` | An email unsent this long (email paused, server down) goes to the recipient's digest, or is dropped (`drop`) |
| `kilnmark.notifications.poll-interval` | `10s` | How often workers look for emails to send |
| `kilnmark.notifications.send-timeout` | `20s` | How long one SMTP connection, sign-in or write may take |
| `kilnmark.notifications.log-retention` | `30d` | The send log, and events with nothing left to send or show |
| `kilnmark.notifications.in-app-retention` | `90d` | In-app notifications, read or not |

## Branding and appearance

Each signed-in user chooses their own theme (System, Light, Dark, High contrast), accent colour and
density under **Settings**, **Appearance**; it is kept in their browser. The operator can set the
product name and logo, and logos or colours for connectors, with a branding folder. The manifest
format, the image rules and an example are in the [branding guide](guide/branding.md).

| Setting | Default | Meaning |
| --- | --- | --- |
| `kilnmark.branding.dir` | `/etc/kilnmark/branding` | Folder with `manifest.json` and the images it names; missing means Kilnmark's own look. Mount it read-only (see the commented line in `compose.yaml`) |
| `kilnmark.branding.max-file-kb` | `256` | Larger images are ignored |

`GET /api/v1/branding` and `GET /api/v1/branding/files/{name}` need no token: the sign-in page
shows the logo, and `<img>` cannot send one. Only files the manifest lists are served (SVG, PNG,
WebP, content checked against the extension, no symbolic links or sub-folders), with `nosniff` and
a Content-Security-Policy that stops SVGs running scripts; the UI shows them only with `<img>`.
Refusing SVGs with scripts or event attributes is defence in depth on top of that. Problems are logged as warnings
starting `Branding folder`.

## Web UI

```
cd ui
npm install
npm run dev              # http://localhost:5173, calls the server on :8780
npm run build            # type check, contrast check, then writes dist/ui
npm run check:contrast   # the colour tokens against WCAG AA, for every theme and accent
```

Every colour is a token in `ui/src/tokens.css`, defined once per theme; style sheets use only the
tokens. Icons are Tabler Icons, named in one place (`ui/src/components/icons.tsx`); products get
letter tiles (`ConnectorTile.tsx`), never vendor logos. The build also writes the UI packages'
licences to `dist/ui/third-party-notices.txt` ([third-party UI software](legal/third-party-ui.md)).

## Rules

- Versions go up by exactly 0.0.1 per release.
- Run the full regression suite before every package.
- Anything hard to reverse gets a [decision record](adr/README.md).
- Code adapted from Kettle 10.2 is logged in [provenance.md](legal/provenance.md).
