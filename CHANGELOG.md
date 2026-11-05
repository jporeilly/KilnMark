# Changelog

Each release starts with **What's new**, a short summary in plain words, followed by the
details.

## Unreleased

### What's new

- **Check a source before the work starts** (ADR 0041). A flow's new `check-source` node checks
  that a source can be reached and holds the tables or files the flow needs, keeping no data, and
  fails at once, naming what is missing, if not. Its `missing` value can go in a notice.
  ([Checking a source before the work starts](docs/guide/flows.md#checking-a-source-before-the-work-starts))

### Details

- **The `check-source` flow node** (ADR 0041, docs/design/flow-controls.md, C1). It takes `source`,
  `tables` and `files` (lists, at most 50 names together, no ${...}) and `timeout`. It connects with
  `Connector.test` within `kilnmark.sources.check-timeout`, after the allowed hosts, then looks each
  name up with `Connector.asset` (a table's columns from metadata; a file's from its start, as a
  scan reads it), keeping nothing; a file path as the catalogue writes it (`%23`) is tried on disk
  too. With no `timeout`, a hung check ends after 10 minutes; either way the node ends at once. It
  runs on a thread beside the flow's watcher, holding no worker slot. It succeeds when all are there and fails with the missing
  names, or with "Could not reach <source>"; it reports `missing`. Messages never quote the source's
  settings or its driver. Saving checks that the source exists and holds tables (databases) or files
  (file and object stores). The diagram, Settings tab and run timeline show it.

- **Release images signed on the local runner**: `release.yml` signs the digest the registry
  stores for each image, read back by tag after the push. On a Podman engine the digest recorded
  locally is not the registry's, and signing it failed, which stopped the 0.0.10 release.

## 0.0.10 (5 November 2026)

### What's new

- **Install, start, upgrade and back up Kilnmark with one script**: `kilnmark.sh` on Linux,
  `kilnmark.cmd` on Windows. On Linux, `sudo kilnmark.sh prepare` first makes the `kilnmark`
  service account that runs it, and `/opt/kilnmark` its own. `install` checks the machine, makes an install root (`/opt/kilnmark`
  or `C:\Kilnmark`) with new secrets readable by you only, gets the images and starts Kilnmark;
  `up`, `down`, `status`, `logs`, `backup`, `restore`, `upgrade` and the rest follow (INSTALL.md,
  "A. Install a release"; ADR 0044). Backup and restore now work on Windows too.
- **Signed images, and an install without internet access.** The images are built once, by the
  release, pinned to exact base images, signed with the release key and published on
  `ghcr.io/jporeilly`; each release also has them as a signed file to load offline.
- **Flow nodes look like pipeline nodes.** On a flow's diagram, each node is now a card as on a
  pipeline's canvas: its type with an icon, its id, what it does, and `in` and `out` connectors.
  Connections run in square steps from one node's `out` to the next one's `in`, never from a
  corner, and the diagram fits every connection, even one that runs round the others.
  ([The diagram](docs/guide/flows.md#the-diagram))
- **Run timelines, nested as the run went** (docs/guide/flows.md, docs/guide/pipelines.md). A flow
  run's page has a **Run timeline** in place of the run chart: the flow, then each node, each node
  opening into its pipeline run's nodes, and a `for-each` node into its items, each with its value,
  every bar on one time line with how long it took at its end. One sentence sums up the run, and
  choosing any row shows everything recorded about it. A pipeline run's page shows the same
  timeline again, with the chosen node's details.
- **Choose which runs start first** (ADR 0041). A `run-pipeline` or `for-each` node can say its
  `priority`: `high`, `normal` (the default) or `low`. When more runs wait than there are free
  workers, a worker takes the highest priority first, then the one queued first; a source's limit
  and `max_parallel` still hold. Set it on the node's Settings tab.
  ([Choosing which runs start first](docs/guide/flows.md#choosing-which-runs-start-first))
- **Every build checks its code and its parts** (ADR 0042). The Java code is read for known kinds of
  bug as it compiles, and a finding stops the build; the first run found and fixed two places where
  quality prediction could fail on a column with an unknown count, and a file write to a path ending
  in `/`. Every library and tool the build downloads is checked against its recorded checksum and,
  where it is signed, its signature, so a changed file cannot slip into a release.

- **A Library of samples and templates** (ADR 0043). **Library**, in the navigation, holds what
  ships with Kilnmark, apart from your own work. **Samples**: Add samples and the starter pack moved
  here from the Pipelines page, and added samples stay here, so the Pipelines and Flows pages show
  only your team's own development and production work. The sample flows are now added too, after
  the pipelines they run. **Templates**: five starting points (copy a table, load a file, a quality
  gate, an incremental load by key, and a flow running pipelines in order); **Use this template**
  opens one in the editor as a new pipeline or flow of your own. **From a template** on the
  Pipelines and Flows pages opens them. ([The Library](docs/guide/library.md))
- **Stop a flow node that takes too long** (ADR 0041). A `run-pipeline`, `for-each` or `sql` node
  can say how long its work may take (`timeout: 2h`, at most 7 days). Past it, the pipeline run is
  cancelled or the statement rolled back, and the node fails, saying so, so a `failure` connection
  can send a notice; for a `for-each` node, each item has the time to itself. Time waiting for a
  worker or a source does not count. The run page marks the node, or item, **Timed out**.
  ([Stopping a node that takes too long](docs/guide/flows.md#stopping-a-node-that-takes-too-long))

- **Start and stop Kilnmark with one click**, on Windows 11 and Ubuntu. The release folder has
  `start-kilnmark` and `stop-kilnmark` scripts for each, and `create-shortcut` puts **Kilnmark**
  and **Stop Kilnmark** on the desktop and in the Start or applications menu, with Kilnmark's
  icon. Starting waits until Kilnmark answers, then opens it in the browser; with
  `COMPOSE_PROFILES=auth` in `.env`, Keycloak starts and stops with it. Stopping keeps every bit of
  data. (INSTALL.md, "Start and stop with one click")

- **Keycloak for organisations with no identity provider** (ADR 0014). An optional compose profile,
  `auth`, starts Keycloak beside Kilnmark and sets it up: a `kilnmark` realm with a group for each
  role and for seeing sensitive values, the client Kilnmark signs in with, a sign-in of a password
  and a one-time code (so Kilnmark's MFA check passes), and a sign-in page in Kilnmark's colours
  and fonts. For demonstrations, five example people at `kilnmark.com`, one per role. Every secret
  comes from `.env`, none with a default. (INSTALL.md, "Keycloak, if you have no identity provider";
  [Signing in](docs/guide/signing-in.md#keycloak-if-you-have-no-identity-provider))

- **Run one pipeline for each item, several at once** (ADR 0040). A flow's new `for-each` node runs
  a pipeline once for each item of a list (regions, files, years), written in the flow or given as
  a list parameter when it runs, with up to `max_parallel` of them at once. Every item runs; the
  node fails if any did, and conditions can read how many succeeded and failed. The run page lists
  each item with a link to its pipeline run. A flow's own `max_parallel` caps how many of its nodes
  run at once. ([Running one pipeline for each item](docs/guide/flows.md#running-one-pipeline-for-each-item))

- **Flow pages read like pipeline pages.** A flow run's page now opens with a **Summary** as a
  pipeline run's does: status, version, who started it, its times, its nodes by how they stand, the
  pipeline runs it asked for, the rows they wrote and the lowest quality among them. Then
  **Where each node spent its time**: wall-clock time, most nodes at once, the longest node and the
  time its pipeline runs were queued for a worker, and a bar per node split into working, queued
  for a worker and waiting, all on the run's scale. A flow's page puts Diagram and YAML on tabs, with
  the pipeline page's panel under them: **Nodes** lists every node, what it runs after and how it
  went in the latest run; **Settings** changes the chosen node's settings (its pipeline, version,
  notice, wait and so on) or connection without touching the YAML by hand; then **Run**,
  **History** and **Versions**. Its history gives how long each run took and the rows it wrote. On
  pipeline and flow pages alike, the tab once called Runs is now **History**, so it is not mistaken
  for **Run**. The flow run API adds these totals and each pipeline run's times.
  ([Reading a run](docs/guide/flows.md#reading-a-run))

- **A limit on runs per source, and more workers** (ADR 0040). A source can set **Most runs at
  once** (1 to 100), a cap its administrators can agree to: pipeline runs and flow `sql` nodes
  beyond it wait their turn, oldest first, saying which source they wait for, and neither fail nor
  use up an attempt. It counts runs, not database sessions: a run may open a few connections, and
  previews, scans and quality checks are not counted. Each change is in the audit
  log. Workers watch `KILNMARK_WORKER_FLOWS` flows at once (8), and INSTALL.md says how to size the
  slots against the database's connections and how to add a worker-only process.
  ([Sources that take a few runs at a time](docs/guide/pipelines.md#sources-that-take-a-few-runs-at-a-time))

- **Pictures in the guides, and a guide to signing in.** The guides now have diagrams of how things
  work (a pipeline run, a quality gate, choosing a write mode, a rule's cycle, how notifications
  reach you, where audit events go, what the Ask panel sends, a restore's steps) and screenshots of
  the pages they describe, in the app's documentation and on GitHub. A new guide,
  [Signing in, roles and tokens](docs/guide/signing-in.md), explains API tokens, signing in with
  your organisation, the four roles and the unmask grant, token lifetime, the access review and two
  people for sensitive values. Page descriptions and the guides' text now use the page's full
  width. The notifications guide now lists flow
  events too, and **Settings › Notifications** shows each event's description on its own line again.

- **See a flow, and watch it run.** A flow's page draws it on a tab beside the YAML: a box per node and an
  arrow per connection, labelled with when it is taken, following the text as you type. A run's
  page draws the same diagram with each node's status as the run goes, and a run chart: a bar per
  node on one time line, a skipped node hatched, each bar opening the pipeline run it started.
  ([Building and running a flow](docs/guide/flows.md#the-diagram))

- **Change a flow on its diagram.** Designers add nodes, connect them (by dragging from one node to
  another, or with the form in the panel's Nodes tab), change when a connection is taken, remove nodes and
  connections, and move nodes, with Undo. Each change is written into the YAML at once, touching
  only the lines it must; a connection that would make a loop or repeat one is refused, saying why.
  Moved nodes stay where you put them, in a `layout:` block; **Lay out again** goes back to the
  diagram's own layout. ([Changing the flow on the diagram](docs/guide/flows.md#changing-the-flow-on-the-diagram))

- **How Kilnmark is built, for buyers who ask** ([SSDF self-assessment](docs/product/ssdf-self-assessment.md)):
  each task of NIST's Secure Software Development Framework, with where the evidence is and what is
  still missing, for a federal attestation or a bank's due diligence. The build tool's download is
  now checked against its published checksum, and every CI action is pinned to an exact commit.

- **Backup and restore** (ADR 0039). `./backup.sh` in the release folder saves the database and the
  keys that open stored source passwords, as two files to keep apart; `./restore.sh` puts them back,
  on the same machine or a new one. It restores into a database of its own before touching the
  running one, keeps what it replaces to go back to, and the audit log (and your SIEM) shows the
  restore. The runbook,
  [Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md), covers scheduling, what to
  check after a restore (tokens revoked since the backup work again), and a quarterly restore
  test. A database restored without its keys is now named in the log at start, and testing a
  source says what to do instead of failing with a server error.

- **Two people for sensitive values** (ADR 0038). With sign-in through your identity provider and
  `KILNMARK_UNMASK_APPROVAL=true`, a designer token asked to see values of columns marked sensitive
  is made without, and a second person approves or refuses it under **API tokens**; the asker, under
  any name, and tokens cannot approve. Every step is in the audit log, and the access review shows
  who approved.

- **Access review, and a longest token lifetime** (ADR 0037). **Settings**, **Access review** lists
  who can do what: every API token with its role, whether it sees sensitive values, its expiry and
  last use, and everyone who signs in with your identity provider, with the role of their last
  sign-in. Download it as CSV for your auditors; each download is in the audit log. Set
  `KILNMARK_TOKEN_MAX_LIFETIME` (for example `90d`) and every new token must expire within it, while
  older tokens stop working: check the review first.

- **Sign in with your organisation's identity provider** (ADR 0036). Entra ID, Okta, Ping, ADFS,
  Keycloak or any OpenID Connect provider: the sign-in page offers **Sign in with** it, roles come
  from the provider's groups as the settings map them, and only designers in an unmask group see
  sensitive values. A second factor is required, a sign-in lasts at most 8 hours, and every sign-in
  is in the audit log. API tokens keep working for scripts. INSTALL.md, "Sign in with your
  identity provider".

- **Designers, and who sees sensitive values.** A designer role sits between steward and admin:
  designers build pipelines, flows and sources, and stewards run them. Only designers see values of
  columns marked sensitive; admins see them masked, as everyone else does, and a designer token can
  be made without that sight (ADR 0027).
- **Allow SQL.** A PostgreSQL source can let SQL a person writes run on it, with the new **Allow SQL**
  setting (`allow_sql`), off by default. Custom SQL quality rules run only on such sources, written
  by designers whose token sees unmasked values (ADR 0028).
- **Read with a query.** A read node can run one `SELECT` in place of a table, on a PostgreSQL
  source with Allow SQL on (ADR 0028). The new sample `sample-read-query` shows one.
- **SQL in flows.** A flow's new `sql` node runs statements on a PostgreSQL source before or after
  its pipelines: empty a staging table, rebuild an index, call a procedure. Its statements run in one
  transaction and it reports the rows they changed. The source must allow both SQL and writing, and
  only designers whose token sees unmasked values write one (ADR 0028). The new sample
  `sample-sql-before-and-after` shows one.

- **Flows in the app.** A new **Flows** page, after Pipelines, lists every flow. Designers write a
  flow's YAML on its page, checked as they type, and save it with every version; stewards run it,
  giving values for its parameters, and cancel runs. A run's page shows each node's status, time and
  message, with a link to the pipeline run it started. The flow canvas comes later.

- **The audit log.** Every change made through the API, every request refused, and the changes
  that decide who sees sensitive values and what reaches a source are recorded, can never be changed
  or deleted, and can be sent to a SIEM. Admins read it under Settings, Audit log (ADR 0029).

### Details

- **Releases on the local runner**: on a tag, `release.yml` runs where `KILNMARK_RUNNER` says, as CI
  does; on a self-hosted runner it builds, signs, pushes the images and drafts the release, and
  leaves the install rehearsal to GitHub's runners. The runner image adds `gh`.

- **CI on a local runner**: the `build`, `heavy-databases` and `scan` jobs run where the repository
  variable `KILNMARK_RUNNER` says, GitHub's runners when it is empty. `ci/local-runner` builds a Linux
  runner for Podman, with its image pinned by digest and the runner download checked
  (docs/development.md, "CI on a local runner").

- **The control script** (ADR 0044). One install root per site holds `versions/<version>/`,
  `config/.env` (written by `install` with a new database password and admin token, `600`, never
  overwritten), `certs/https`, `backups` and `logs`. Every command logs what it said and exits
  non-zero on failure; none prints a secret. Downloads, the offline images file and pulled images
  are checked with cosign against the `cosign.pub` the release now carries (`--skip-verify` to go
  on without, recorded in the log). `upgrade` backs up first, starts the new version beside the old
  one, and points back to the old one if it does not answer. `start-kilnmark` and `stop-kilnmark`
  now run the control script. The Windows script is Windows PowerShell 5.1, through
  `kilnmark.cmd`.
- **The compose project is always `kilnmark`** (`name:` in compose.yaml, and `-p`), so volumes
  keep their names from one version's folder to the next; `KILNMARK_PROJECT` names another for a
  second install on one machine. **A 0.0.9 install moves over by backup and restore**: its project
  was named after its folder (INSTALL.md, "From 0.0.9").
- **Every long-running service restarts by itself** (`restart: always`; `down` removes them); with rootless
  Podman, install turns on the `podman-restart` user service.
- **Images from the release, not built on site.** compose.yaml names
  `ghcr.io/jporeilly/kilnmark-server` and `-postgres` at the release's version; the base images
  are pinned by digest. The release workflow builds them, writes `kilnmark-<version>-images.tar.gz`
  and `-keycloak-images.tar.gz`, signs them with the other files, and on a tag pushes the images
  and signs them by digest. Its rehearsal on pull requests installs the release with `kilnmark.sh`
  from the offline file and backs up, restores, stops, starts and purges it.
- **HTTPS certificates** go in `certs/https` (or the folder `KILNMARK_CERTS_DIR` names), which
  compose.yaml now always mounts read-only; a release folder run by hand has an empty `tls` folder.
- **Flow diagram cards** (docs/design/flows.md, F4a). `FlowDiagram` draws a node with the pipeline
  canvas's `.cv-node` styles (the icon square in the node type's tint) and `in`/`out` ring
  connectors; a node's run status is in its foot. A laid-out connection is drawn in rounded square
  steps from connector to connector through dagre's route (`route.ts`: `steps` turns only in the
  gaps between columns, and brackets a second connection between the same two nodes round its label;
  `roundedPath`), its bends in those gaps and its label moved to the connectors' height; connections
  between nodes placed by hand still bow apart. Two unseen 1-pixel points at the corners of the
  routes let fitting the view take in a route round the outside. The hint under the Nodes tab says
  to drag from a node's out connector.
- **A flow node's `priority`** (ADR 0041, amended; docs/design/flow-controls.md, C5). `run-pipeline`
  and `for-each` nodes take `priority: high | normal | low` (any case), checked when saved; `sql`
  and the other types refuse it, since a sql node never waits in the job queue. Migration V33 adds
  `job.priority` (smallint, -1 to 1, default 0) and rebuilds `job_ready_idx` as `(priority DESC, id)`;
  `JobQueue.claim` takes the due job of highest priority, then the first queued. The pipeline runs a
  node queues, and each of a for-each node's items, carry its priority; everything else is normal.
  The diagram's Settings tab has a **Priority** choice, and a node's summary says a high or low one.
  Upgrading: building the new index briefly holds up the job queue while it reads `job`, which
  keeps every job ever queued; the column's check is validated without holding it up.
- **Static analysis and dependency verification** (ADR 0042; SSDF PW.7.2, RV.1.2, PW.4.4). Error
  Prone 2.51 runs in every Java compile, main and test, and `-Werror` makes its warnings fail the
  build; nine checks are off for the whole build, each with its reason in the ADR, and suppressions
  are `@SuppressWarnings` on the narrowest method with a comment. Its first run found 5 errors and
  327 warnings. Fixed: two conditional expressions in `QualityPredictor` that unboxed a null (a text
  column profiled without blanks; a group-by of an unknown row count); `LocalFileStore.create`
  taking `a/` as a file `a` (now refused before any folder is made); a confirmation mail sent with
  `submit`, whose errors were lost (now `execute`, with every error caught and logged); `WriteSql`
  making its own subclasses in static fields, which can deadlock two threads (the instances moved
  into the subclasses); `toLowerCase()`, `toUpperCase()` and `getBytes()` without a locale or
  character set; identity comparisons of values; an unused longest table name read from every
  generic JDBC driver; `Thread.interrupted()` calls now say why they clear the status.
  `FlowDefinition`, its test, `FlowService` and `JobQueue` are left out until the flow-controls
  change merges. `gradle/verification-metadata.xml` holds the sha256 of each of the 1,622 files the
  build downloads, and Gradle checks signatures against 130 trusted keys in
  `gradle/verification-keyring.keys`, never a key server. `./gradlew resolveAllDependencies`
  resolves every configuration, for writing the metadata (docs/development.md, "Dependency
  verification"). CI validates the Gradle wrapper's jar before every job that runs Gradle.

- **The Library** (ADR 0043). Migration V32 adds `library` to `pipeline` and `flow`: `sample` for
  one added from the samples, set by the server, never by the YAML; samples added before are marked
  by their fixed names, never by a pattern. `GET /api/v1/pipelines`, `/flows` and each one's detail
  give `library`. `POST /api/v1/samples` adds the sample flows after their pipelines, skipping one
  whose pipelines are not all there. `GET /api/v1/templates` lists the templates in
  `samples/templates`, with their YAML, for any signed-in person, even with the samples turned off.
  `#/pipelines/new?template=<file>` and `#/flows/new?template=<file>` open one in the editor.
- **The release key** is in place: `config/release/cosign.pub` is the public half of the key that
  signs every file of a release from now on. Check a release with it as INSTALL.md, "Check a
  release", says.
- **People are greeted by name.** Someone signed in through the identity provider now shows as their
  name there ("Alex Morgan"), with their initials, in the top bar and on Home, rather than as
  `sso:<name> (<ID>)`. The audit log, usage and "created by" still record the `sso:` name, which the
  user menu also shows. `GET /api/v1/me` adds `displayName` (ADR 0036).
- **New pipeline and New flow** are the same height as the Add samples button beside them.

- **A flow node's `timeout`** (ADR 0041, docs/design/flow-controls.md, C3). `run-pipeline`,
  `for-each` and `sql` nodes take `timeout` (`90s`, `30m`, `2h`, `1d`; more than nothing, at most
  7 days); other node types refuse it when saved. It counts from the pipeline run's `started_at`, or
  a `sql` node's start after its place on its source. Past it the flow cancels the pipeline run (as a
  person's cancel) or stops the `sql` node's statement and rolls back, waits for the run to end, and
  fails the node: "Took longer than 2h and was stopped". A timed-out item of a `for-each` node counts
  as failed, not cancelled, so the other items go on. The node's row records `timed_out: true`, or
  `timed_out_items` for a for-each node, which `GET /api/v1/flow-runs/{id}` returns in `reported`
  and which conditions do not read; a timed-out item's status there is `failed`. When a timed-out
  run-pipeline or sql node fails the flow, the flow's error is "Node load took longer than 2h and was
  stopped"; a for-each node's message names the first item that timed out. The Settings tab on the
  diagram has a **Timeout** field for the three types. The sample `sample-for-each-region` gives
  each item 30 minutes. No migration.

- **Parallel runs in flows** (ADR 0040, docs/design/flows.md section 8). A flow's top-level
  `max_parallel` (1 to 50) holds ready nodes back, in the order written, while that many run; `stop`
  nodes never wait. The `for-each` node takes `pipeline`, `version`, `items` (1 to 500 texts or
  numbers, or a text list or number list parameter of up to 200), `as`, `with` and `max_parallel` (4
  by default); saving checks that the pipeline takes `as` as one value of the items' kind, and, when
  items run at once, that each writes its own asset (`${<as>}` in every write's target), since a run
  waiting for another writing its asset fails after 30 seconds. Each item is an ordinary pipeline
  run with `flow_item` set (migration V31, which moves the unique index to the flow run, node and
  item); the items of one node are the one exception to a pipeline having at most one run queued or
  running, so Run now on that pipeline is refused while they run. An item that cannot be queued
  counts as failed. Cancelling one item's run cancels the flow. It reports `items`, `succeeded` and
  `failed`. `GET /api/v1/flow-runs/{id}` lists a for-each
  node's `items`, each with its value (withheld from viewers when it came from the run's values),
  status and pipeline run. The diagram and the add-node form know the new type; the sample
  `sample-for-each-region` shows it.

- **Parallel runs, P1, P2 and P5** (ADR 0040, docs/design/parallel-runs.md). `kilnmark.worker.flows`
  (`KILNMARK_WORKER_FLOWS`, 8) replaces the fixed eight flows a worker watched;
  `kilnmark.worker.concurrency` is documented as jobs (scans, quality checks, pipeline runs), not
  scans. A source's `max_runs` (a common setting, in `settings` in the API, 1 to 100 or empty) caps
  the pipeline runs and flow `sql` nodes using it. A run takes one place on each limited source its
  version reads or writes before it starts: advisory locks on its job's lease connection, sources
  in id order, all or nothing. A run on a full source stays queued with the note "Waiting for a
  free place on source <name>", and its job goes back in the queue for 5 seconds without using its
  attempt; a `sql` node waits inside its flow. Places are freed when the run ends, or by PostgreSQL
  when a worker dies. Scans and quality checks do not count. A change of the limit is the audit
  event `source.max_runs`, with what it was and is. A test runs two workers in one process against
  one queue: every run runs once, both workers' slots are used, and a source limit holds across
  both. INSTALL.md gains "Sizing the workers" (`concurrency × 3 + flows + 6` connections per
  process) and "A second worker".

- **The flows API** (docs/design/flows.md, F3): `GET/POST /api/v1/flows`, `POST
  /api/v1/flows/validate`, `GET/PUT /api/v1/flows/{name}` (the ETag is the latest version; a `PUT`
  with an older `If-Match` is refused with 409), `GET .../versions` and `.../versions/{n}`, `POST
  .../{name}/validate`, `POST` and `GET .../{name}/runs` (with `parameters`), `GET
  /api/v1/flow-runs/{id}` (the run with every node of its version, each with its status, times,
  message, what it reported and the pipeline run it started) and `POST
  /api/v1/flow-runs/{id}/cancel`. Viewers read, designers save, stewards run and cancel. Viewers do
  not see the values a run started with, nor node messages quoting them, since they can hold server
  variables. Saving
  passes the caller's unmask grant, so a flow with a `sql` node is saved only by a designer whose
  token sees unmasked values (ADR 0028). There is no delete yet. User guide: "Building and running a
  flow".

- **The allowed-hosts list sees the S3 hosts requests really reach** (ADR 0035). The AWS SDK works
  the host out, as for a read, and nothing is sent: usually with the bucket in it
  (`mybucket.storage.example.com`, `mybucket.s3.eu-west-2.amazonaws.com`) unless `path_style` is
  on, and AWS's FIPS, dual-stack, partition and access-point hosts where they apply. **A list that
  named only `storage.example.com` or `s3.<region>.amazonaws.com` now refuses those sources:** add
  the host the refusal names, or a narrow wildcard such as `*.storage.example.com` or
  `*.s3.eu-west-2.amazonaws.com`. Never `*.amazonaws.com`, which admits hosts anyone can set up on AWS.

- **Encrypted connections to sources, when required** (ADR 0034). With
  `kilnmark.sources.require-tls=true` (off by default), a source whose connections could go
  unencrypted is refused when it is saved, and nothing connects to one saved earlier: PostgreSQL
  without `sslmode=require` or stronger, SQL Server with `encrypt=false` or `optional`, MySQL and
  MariaDB with `tls_mode` `preferred` or `disabled`, Oracle with `encryption` `accepted`, S3 with an
  `http://` endpoint, and every Generic JDBC source. The message names the setting to change.

- **SBOMs, scanning and signed releases** (ADR 0033, docs/design/supply-chain.md). Every release
  carries CycloneDX SBOMs of the server's libraries and the web UI's packages, in its `sbom` folder.
  CI scans them with Trivy on every change and every Monday, and fails on a high or critical
  vulnerability that has a fix; the base images are reported, not failed. A version tag builds the
  release, signs every file with the release key and leaves a draft GitHub release; INSTALL.md,
  "Check a release", says how to verify one. Dependabot proposes updates monthly, one pull request
  per ecosystem. `SECURITY.md` says how to report a vulnerability.
- **Security:** lz4-java 1.11.4 and snappy-java 1.1.10.12, over the Quarkus platform's versions, for
  three high vulnerabilities the new scan found (lz4-java's temporary files; snappy-java's memory use
  and buffer overflow on malformed input).
- **Kilnmark's own database over TLS only** (ADR 0032). The bundled PostgreSQL makes a certificate
  on its first start (existing volumes too, with their data as it was) and speaks TLS 1.2 or later;
  Kilnmark connects with `sslmode=require`. **The server no longer starts unless its database
  connection demands TLS** (`sslmode` `require`, `verify-ca` or `verify-full`) **and the session is
  encrypted**, as read from the connection pool's own settings and the driver's own parser: an
  installation with its own PostgreSQL turns TLS on there and sets the sslmode (`verify-full` with
  its CA is best); INSTALL.md, "Kilnmark's own database".
  `kilnmark.database.require-tls=false` turns the check off, for a developer's own database.
  The database migrations now run after the check, through the checked pool: `quarkus.flyway.*-at-start`
  settings, or a Flyway URL or login of its own, stop the server while the check is on.
- **HTTPS and security headers** (ADR 0031). Every response carries a Content-Security-Policy that
  lets only Kilnmark's own files run or load (no inline script or style, no framing by other
  sites), with `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, a Permissions-Policy and same-origin opener and resource
  policies; the API's own page (`/api/docs`) alone may run its inline start. Over HTTPS, responses
  carry HSTS for a year (`KILNMARK_HTTP_HSTS=always` behind a proxy that ends TLS). Kilnmark serves
  HTTPS itself on 8443 when given a certificate and key as PEM files (`KILNMARK_TLS_CERT_FILE`,
  `KILNMARK_TLS_KEY_FILE`), TLS 1.3 and 1.2 with forward-secret AEAD suites only, with plain HTTP
  from other machines enabled, redirected or disabled (`KILNMARK_HTTP_PLAIN`; an unknown value stops
  the server); INSTALL.md, "HTTPS". The page's theme script moved to a file of its own.
- **Relationships: confident joins accepted automatically, and Browse holds them** (ADR 0019,
  amended). Inferred joins found with 95% confidence or more are accepted without a person, shown
  as "Automatically, at 95% or more" with their confidence; a steward can still reject one, and a
  person's decisions are never overridden by a rescan. Joins across sources that only repeat a join
  within one source (the same table through two sources on one database) are no longer proposed.
  Relationships is a view of Browse now (Catalogue and Relationships tabs), not a sidebar entry of
  its own; its address still works. On Browse, the filter and tree sit beside what the schema holds,
  and the diagram, with its relationships, takes the whole width under them.
- **The panel under the canvas, reviewed.** Nodes is the first tab, as choosing a node there opens its
  settings. The Nodes table shows each node's metrics from the last run: rows in and out, time, rows
  a second, how its time went (working, waiting for rows, held up by the next node, as a bar), peak
  memory, and the node that held the run back most; its columns fold into a count. A node's settings
  show the same metrics under Last run, and are laid out in three areas (the node, its settings in
  as many columns as fit, what leaves it and its last run) by the panel's own width, so a long
  setting no longer splits across columns and a rule's checkbox keeps its label beside it.
- **Sidebar in the order the work goes**: Browse, Relationships, Quality rules, then Pipelines.
- **The connector gallery's "Install the driver" link** sits inside its card.
- **A new release shows at once**: the web app's page is sent with `Cache-Control: no-cache` (it was
  cached for a day, so browsers kept the old release's screens after an upgrade).
- **The flow sql node** (V29, ADR 0028, docs/design/flows.md section 7). `type: sql` with a `source`
  and 1 to 20 `statements`, each starting `TRUNCATE`, `INSERT`, `UPDATE`, `DELETE`, `MERGE`, `CREATE`,
  `DROP`, `ALTER`, `CALL`, `ANALYZE`, `REFRESH`, `COMMENT`, `REINDEX`, `CLUSTER` or `LOCK`; never a
  query, transaction control, `SET`, `COPY`, `DO`, `GRANT` or `REVOKE`, `RETURNING`, `${...}`, nor
  anything that creates or changes logins, rights, functions, triggers or ways out of the database.
  It runs on a thread of its own beside the flow, under the source's login, which must not be a
  superuser or reach files, programs or other databases (the check custom rules use), with
  `kilnmark.pipelines.sql-statement-timeout` (default 30 minutes) per statement. A statement that
  fails rolls them all back; the node says which failed and its SQLSTATE, never the database's
  message or the SQL. `<node>.rows_affected` can be read in conditions and notices. Saving a flow with
  a sql node takes a designer whose token sees unmasked values (`FlowService.save`), and versions saved
  before this (`flow_version.sql_checked` false) are refused when queued and when they start. Each
  sql node that starts is an audit event `flow.sql`, shown on the audit log page as **Flow ran SQL**.
  The flows API is still to come (F3), so flows are saved through the service for now, and Add
  samples lists the new sample without adding it.
- **SQL checks read as PostgreSQL does** (two reviews of the sql node; custom SQL rules and query
  reads share the check). A carriage return ends a `--` comment, dollar-quote tags and names take any
  non-ASCII character, and the PostgreSQL driver's own parser must find one statement, so no second
  statement hides in what the check read as a comment or string. Refused now in custom SQL rules and
  query reads too: `E'...'` strings (write the value in plain quotes), `U&"..."` names, control
  characters other than whitespace, and `ts_stat` and `ts_rewrite`. **A PostgreSQL source whose URL
  sets `preferQueryMode` is refused**: take the setting out. **A custom rule written with an `E'...'`
  string stops running** until it is written with plain quotes.
- **SQL a person wrote runs only with `standard_conforming_strings` on** (third review of the sql
  node). With it off, a backslash ends a string where the check did not expect, and a source whose
  URL set `options=-c standard_conforming_strings=off` (or a login or database with it off) could
  make a query read leave its read-only transaction and write. Query reads, custom SQL rules and sql
  nodes now turn it on for their transaction and check before each statement that it is still on.
  Refused too: `pg_settings` everywhere (its rules change settings), text that is not valid
  Unicode (a lone surrogate reaches the database as `?`), and in sql nodes, views, `ALTER ROUTINE`
  (code that runs later) and `GRANT` or `REVOKE` anywhere, `CREATE SCHEMA` included.
- **Reads stay read-only.** A PostgreSQL source whose URL sets `readOnlyMode` is refused (pgjdbc
  would ignore read-only, so a query read calling a function that writes could write), and query
  reads also set `SET TRANSACTION READ ONLY` themselves. Sql nodes refuse `SCHEMA` (a schema named as
  the login changes what every unqualified name means). Stored defaults and checks that call an
  existing function are accepted for now (ADR 0028).
- **SQL never runs under a login acting as another role.** A superuser login whose URL's `options`
  or settings set `role` passed the login check (which looked at the role it acts as), with its
  superuser settings in force. The check now refuses a session whose `session_user` is not its
  `current_user`, for sql nodes, query reads and custom rules alike. Sql nodes and query reads also
  check the source's URL rules again when they run, so a source saved before a rule existed is
  refused until saved again, and a query read's plan runs read-only.
- **The login check refuses more**: a login that may create roles, and one whose
  `session_replication_role` is not `origin` (PostgreSQL 15 lets a login be granted that setting,
  which skips foreign keys and triggers). Custom rules check the source's URL rules again when they
  run, as sql nodes and query reads do.
- **Query reads run.** A read with a query failed when its pipeline ran ("needs the setting
  'asset-urn'"): it has no single asset, and now records no row count for one.
- **The query check lives in `PipelineService`.** `create` and `save` check a query read's author
  themselves, so no caller can skip it; without a person asking, a query read is refused. Tests now
  show a pipeline version saved before V27 refused when queued and when it starts.
- **Generic JDBC: time limits, SQL Server instances, and the vetted list in full** (ADR 0030). A
  time limit is now a name ending in `timeout`, with or without `ms`, `_ms` or `seconds`, so a
  setting that only holds the word is no longer taken for one. SQL Server's `instanceName` is
  vetted, so a named instance without a port can be used
  (`jdbc:sqlserver://host;instanceName=SALES`). The registration error, the drivers guide and
  ADR 0030 now list every kind of vetted setting, compression, `readonly`, `mode` and `create`
  included, and the ADR says that the MySQL and MariaDB local-file settings are forced only on
  those drivers' URLs. **A registration listing a name that holds `timeout` without ending in it
  is no longer used**; register it again without that name.
- **The audit log** (V28, ADR 0029). `kilnmark.audit_event` is append-only: a trigger refuses
  `UPDATE`, `DELETE` and `TRUNCATE`. Events: `token.created` (with role, unmask and expiry),
  `token.revoked`, `mark.set`, `mark.cleared`, `source.deleted`, `source.allow_writing` and
  `source.allow_sql` (from the form or an import), `api.request` for every `POST`, `PUT`, `PATCH`
  or `DELETE` that succeeded, and `api.refused` for every 403, and every 401 on a change. Only the
  method, path, status and caller's address of a request are kept, never its body or query string.
  Start-up records the admin tokens it makes or sets aside. Rows are stored in the background, in
  order; refusals are kept at most 30 a minute for each caller, and a flood of requests never keeps
  a token, mark or source event out. `GET /api/v1/audit` (admins)
  pages through them; each is also one JSON line on the `kilnmark.audit` log category, sent to a
  syslog collector with the `siem` profile and `KILNMARK_AUDIT_SYSLOG_ENDPOINT`.

- **Unmasking is a grant of its own** (V26, ADR 0027). Tokens have `unmask`, true only on designer
  tokens (the default for them; create one with `"unmask": false` to leave it out); the API and a
  database check refuse it on any other role. Every view of data values asks for the grant, not a
  role: admins, stewards, viewers and sign-in-off callers see sensitive values masked, in previews,
  run errors, document text and search too, and a model never sees them. The Tokens page shows which
  tokens hold it and who made them, and the server logs each such token and each mark cleared.
  Custom SQL rules need the grant to be written, changed or run. Deleting a source with marks is for
  admins. A second folder or bucket source over the same files is refused.
- **Marks follow the data** (V25). A column a pipeline writes from a column marked sensitive is
  marked on what it writes: passed on, renamed, looked up or worked out from one (a computed column,
  an aggregate's value or group), followed through the pipeline as previews already follow it, for
  every run whoever asked for it and however it ended. These marks are recorded for every write as
  the run ends, before the written asset is catalogued (and on its catalogued columns when they
  could not be told before), and stay when it is removed and found again (`carried_mark`; the
  stewards' own marks are now `column_mark`, and `sensitive_column` is a view of both). Masks match
  column names ignoring case; a write Kilnmark cannot follow is marked on every column (`*`), even
  when its run failed. Only admins clear a mark, as before. Write paths must be written as the
  source lists them (`/` between folders, no empty or `.` folders), so marks land where a scan finds
  the file. Before this, copying a marked column elsewhere and previewing the copy showed its values
  to stewards and viewers.
- **A designer role, between steward and admin** (V24). Designers build: they add, change,
  preview and delete pipelines and flows, add, import, change and delete sources, turn on writing
  and change where a writable source points, write custom SQL rules, set server variables and add
  the samples. They see values of columns marked sensitive, since they cannot transform data they
  cannot see; admins, stewards and viewers see them masked (ADR 0027). **Stewards keep running pipelines and flows,
  cancelling runs, testing, scanning and previewing sources and looking after quality, but no
  longer build: existing steward tokens lose editing pipelines, flows, sources and server
  variables.** Give a designer token to whoever builds. Admins keep the Settings pages, tokens,
  admin-only connectors (generic JDBC), clearing a mark and deleting a source with marks; designers
  may now run source imports. Tokens can be made for the new role under API tokens.
- **Allow SQL** (`allow_sql`, ADR 0028), a setting every source has, for PostgreSQL sources only:
  designers and admins turn it on or off, and the server logs it. Custom SQL quality rules are
  accepted and run only where it is on. **`kilnmark.quality.custom-sql` is retired**: it does
  nothing, and a server that sets it to true logs a warning. Turn on Allow SQL for each source whose
  custom rules should keep running, with a login that can read only what the SQL needs.
- **Generic JDBC reaches database servers only** (ADR 0027). A generic JDBC URL must name a network
  host, as `//host[:port]` (or `jdbc:sybase:Tds:host:port`); a URL for a database the driver would
  open inside the Kilnmark server is refused for admins too: H2 files and memory (and H2's `INIT`,
  now as a driver property too), SQLite, DuckDB, HSQLDB files and memory, embedded Derby,
  UCanAccess, CSV files, Calcite, embedded, local or native Firebird (and Firebird's `type`, in the URL
  or as a property), Oracle URLs other than `jdbc:oracle:thin:`, Redshift `iam:` URLs (they sign in as
  the server's AWS identity), Drill with `zk=local`, Ignite from `cfg:`, Hive with no host, and any
  other URL with no host Kilnmark can read. No driver's allow-list may hold a property that loads
  code, reads the server's files or signs in as the server: interceptors, verifiers, callbacks,
  providers, strategies, transforms, plugins, checkers, handlers, loggers, locations, client
  certificates (`*cert`, `clientCertificate`), MySQL's `autoDeserialize` and local-file loading,
  `integratedSecurity`, and any authentication, Kerberos, JAAS, GSS or security-mechanism setting.
  **A registered driver whose allow-list holds one of these is no longer used** (the server log says
  which): register it again without them. **Existing
  generic JDBC sources with such URLs stop connecting**, and their tests, scans and runs say why.
  Run the database as a server (an H2 or HSQLDB server, Derby's network server and the like) and
  change the source's URL to its network address.
- **Generic JDBC takes only vetted settings** (ADR 0030). A registered driver may be allowed only
  settings Kilnmark has vetted: time limits, TLS, characters, database, schema, catalog, warehouse
  and role, the application's name, fetch and batch sizes, time zones, keep-alive and failover
  servers, and a few databases' own (Teradata `TMODE`, Informix `INFORMIXSERVER`). **Registrations
  whose lists hold anything else are no longer used**; register them again without it. Snowflake,
  Vertica and Exasol are refused through Generic JDBC, as their drivers move files for ordinary SQL,
  and MySQL and MariaDB connections never load local files. A URL is checked for what it opens
  before its settings. Running drivers in a process of their own is next.
- **S3 access points and overlapping sources.** An S3 source whose bucket is an access point (an
  `-s3alias`, Object Lambda `--ol-s3`, Outposts `--op-s3`, directory bucket `--<zone>--xa-s3` or `.mrap`
  alias, or an ARN), or whose
  endpoint host names a bucket or access point (`<bucket>.s3.<region>.amazonaws.com`,
  `s3-accesspoint` hosts and the like), counts as reading the same files as any other source at
  that endpoint, since Kilnmark cannot see the bucket behind it. Two access point ARNs in different
  regions or partitions are told apart; accounts are not compared, as an access point may front
  another account's bucket. Endpoint hosts are compared without a trailing dot, and amazonaws.com.cn
  and amazonaws.eu hosts count as AWS's own service.
- **A read's `query`** (ADR 0028), in place of `asset`, never with `columns` or `where`. Checked as
  custom SQL rules are, when the pipeline is checked and again as it runs, on a read-only connection
  and a login without superuser-like rights; `row_limit` wraps it so the database stops early.
  `${...}` is refused for now. Only designers whose token sees unmasked values save or preview a
  pipeline with one (403 otherwise, admins included); anyone who runs pipelines runs it. Every
  column a query returns is marked (`*`), and a run with one keeps its engine messages for
  designers with the grant; the writes it feeds are marked before any row moves. A version saved
  before this (V27, `queries_checked`) that has a query read does not run until saved again. Each run records the tables it reads, from the database's plan
  (`EXPLAIN`), as reads and as the inputs of what it writes. The canvas edits
  the query in a box over several lines. Adding the samples skips `sample-read-query` unless
  `crm-db` is a PostgreSQL source with Allow SQL on, or for a caller who may not see unmasked values.

## 0.0.9 (31 October 2026)

### What's new

- **Samples, and a starter pack to run them on.** Kilnmark comes with small pipelines that show one
  idea each, such as filtering, routing, lookups, totals, quality gates and parameters, and with
  made-up data for them. On the Pipelines page, **Add samples**, then **Add the starter pack**: it
  creates the sample tables in a database of yours and adds every sample, ready to run. Or point the
  samples at your own sources. One sample runs four million rows, so you can watch where a run's time
  goes. Admins can turn the samples off under Settings.
- **Read only what you need.** A read node can name the columns it wants and a `where` condition,
  which can use the pipeline's parameters. For PostgreSQL, MySQL, MariaDB, SQL Server and Oracle
  tables the database does as much of the filtering as is safe, with every value bound, so fewer rows
  travel.
- **Parameters and server variables.** A pipeline can declare parameters (text, numbers, true or
  false, and lists) with defaults, and take other values for a run through the API. Server variables
  are values of the server, such as a schema or a region, that every pipeline can use.
- **A calmer canvas.** The node list sits beside the canvas, with **Nodes** in the toolbar to hide it.
  A click selects a node so you can move it, and a double-click opens its settings. Connections no
  longer cross where a node's outputs feed several nodes, and a node's preview scrolls within the
  screen with the column names kept in view.
- **Steps are now called nodes**, and the lines between them connections, everywhere in Kilnmark.
  Pipelines written with `steps:` still read.

### Details

- **Samples can be turned off.** Admins have **Offer the samples** under Settings, Administration
  (on by default; stored in the new `server_setting` table, V23, with `GET`/`PUT
  /api/v1/samples/offered`). Off, the Pipelines page shows no **Add samples** and the samples API
  answers 403 for listing, adding and the starter pack; pipelines already added stay.
- **The run page's rows a second follows the run you are on.** Going from one run's page to
  another's without a reload no longer shows the first run's nodes.
- **The canvas: the node list only, and connections that do not cross.** The rail of folder
  buttons beside the node list is gone; **Nodes** in the canvas toolbar shows and hides the list,
  which is open by default. Auto-layout now stacks each column in the order of the outputs feeding
  it, so a route's routes (or a filter's kept and rejected rows) reach their nodes without crossing.
  The pipelines guide says what solid and dashed connections are, and that one output feeding
  several nodes gives each of them every row (route, filter and gates are how rows are split).
  A click on a node now only selects it, so it can be moved without its settings opening; a
  double-click or Enter opens them (and a node just added opens them at once). A node's preview
  scrolls inside a box that fits the screen, both ways, with the column names kept in view and
  smaller text.
- **A volume sample.** The sample data gains one large table, `volume/sales.csv`: four million
  made-up till sales, written by the `sample-data` source as they are read (from a fixed seed, every
  row 54 bytes, so the file's size is known) rather than stored. `sample-volume` reads it with the
  sample products, values each line, adds its category with a lookup, routes the large lines to a
  file of their own and totals the rest per category and channel: a run of several seconds, so the
  run's page has something to show. It reads the sample data directly, so it needs only a place to
  write; adding it adds the `sample-data` source if it is not there.
- **The starter pack: sample data, and every sample at once.** Kilnmark now ships synthetic sample
  data for the samples (`samples/data`, written by `generate.py`: ten small tables of made-up
  customers, accounts, transactions, invoices, orders, order lines, products and a supplier feed,
  shaped so each sample has something to show), read through a new built-in, read-only source kind,
  **Kilnmark sample data** (`sample-data`; the CSV files are in the kiln-plugins jar under
  `kilnmark/sample-data`). `POST /api/v1/samples/tables` (stewards and admins) copies them into one
  schema of a database that allows writing, as `crm_customers`, `shop_orders` and so on, with a
  pipeline called `sample-tables`, and queues its run; it adds the `sample-data` source and sets the
  server variable `sample_region` if they are not there. `POST /api/v1/samples` takes `schema` to
  point the samples at those tables, and `GET /api/v1/samples` says where they were created. In
  **Add samples**, **Add the starter pack** does both in one step; choosing your own sources stays
  below it. `SampleTablesTest` checks every sample against the sample data's columns, and
  `SampleTablesApiTest` creates the tables in PostgreSQL, adds the samples and runs them (the stop
  gate stops). `checkProductOnly` also scans the sample data for the lab.
- **The samples ship, and Add samples maps them to your sources.** The samples in `samples/` no
  longer name the lab's sources: they name three placeholders, `crm-db` and `shop-db` (databases)
  and `files` (file storage they write to), and their comments no longer mention the lab. The
  server bundles them (`kilnmark/samples` in its jar, listed in `samples.list`) and the release has
  them in its `samples` folder. `GET /api/v1/samples` lists the placeholders and samples (viewers);
  `POST /api/v1/samples` adds every sample pipeline whose placeholders are all given a saved source
  (stewards and admins), leaving one already there as it is and skipping flows until the flows API
  comes. **Add samples** on the Pipelines page asks which of your sources each placeholder means,
  the sources of the right kind first, and lists what happened to each sample. `lab.sh samples`
  and `lab.ps1 samples` map the placeholders to the lab's sources from `lab/sample-sources.csv`,
  and `LabPipelinesTest` checks every sample through the same mapping, and that the samples name
  only placeholders. `checkProductOnly` now also fails the release if a shipped sample mentions the
  lab. ADR 0024 records the change.
- **The read node's `columns` and `where`** (docs/guide/pipelines.md, "Reading"). A read node keeps
  only the columns chosen, in that order, and the rows meeting `where`, an expression that may use
  the pipeline's parameters (`status in ${statuses}`); `row_limit` counts the rows kept. For
  PostgreSQL, MySQL, MariaDB, SQL Server and Oracle tables, the database applies as much of `where`
  as is safe (text by `=`, `in` and `like`; numbers, dates, true/false; `and`, `or`, `not`) with
  every value bound, never written into the SQL; Kilnmark always applies the whole condition as
  well, so the rows are the same whichever does the work. The node's note says which. On the
  canvas, the read node lists the table's columns with their types to tick, and the Where box
  offers columns and `${parameters}` as buttons; each node's notes now show under its settings.
  Samples: `sample-read-columns-where`, and `sample-orders-by-status` now filters in its read node.
- **Samples and an install guide.** `samples/` holds 18 small pipelines and 2 flows, one idea each,
  sorted by what they show (read and write, filter and route, transform, combine, aggregate,
  quality, parameters, flows), on the reference lab's data; `lab/lab.sh samples` (or `lab.ps1`)
  adds them to a running Kilnmark. (They now ship: see the entry above.) `LabPipelinesTest` checks
  every sample against the lab's columns and every flow against the sample pipelines.
  `INSTALL.md` says how to install on a clean machine: a release, or from source with the lab.
- **Parameters and server variables** (V22; docs/guide/pipelines.md, "Parameters and server
  variables"). A pipeline declares `parameters:` (text, number, boolean, text list, number list, each
  with a default) and uses them as `${name}`; a server variable is a value of the server, set by
  stewards and admins through `/api/v1/variables` (every change kept, names that look like
  credentials refused, `KILNMARK_VAR_<NAME>` seeds one at start-up) and written `${vars.name}`. A
  value only ever fills a value: in an expression as a literal (`status in ${statuses}` becomes
  `status in ('delivered', 'shipped')`; a number as a decimal; never inside quotes or joined to a
  name), in where a write goes only as part of the last name beside text the pipeline writes
  (`orders/${label}.csv`, letters, digits, `_` and `-`), in a number or true/false setting as the
  whole value, and never in a source, asset, column, choice or another node. `POST /api/v1/pipelines/{name}/runs` takes `parameters`; each pipeline and flow
  run keeps its parameters and the server's variables as they were when it was queued. Flows pass
  values on: `with:` on `run-pipeline`, a flow parameter inherited by a pipeline parameter of the
  same name, and an earlier node's values (`${clean.rows_written}`). New flow nodes: `notify` (a
  `flow_notice` notification, in the app and by email) and `wait` (up to 24h); a flow run that fails
  posts `flow_failed`. Lab: `demo-orders-by-status` and the flow `demo-nightly-customers`, checked
  against the lab.
- **Steps are now called nodes** (V21). The boxes on a pipeline canvas are **nodes**, and in a flow
  the line joining two flow nodes is a **connection**. Pipeline and flow YAML list `nodes:`, and
  flows list `connections:`; files with the old `steps:` and `paths:` keys are still read (saved
  versions keep the YAML they were written with; the pipeline canvas writes `nodes:` the first time
  it changes one), and so are problems saved with "Step" in their words. The node catalogue is `GET
  /api/v1/node-types` (was `/api/v1/steps`), and JSON fields named `step` or `stepId` are now `node`
  and `nodeId`. Migration V21 renames the run tables and columns (`pipeline_node_run`,
  `pipeline_node_quality`, `flow_node_run`, `node_id`, `pipeline_run.flow_node`, `series_nodes`) and
  the run log's `step_started` and `step_ended` events to `node_started` and `node_ended`, and
  renames the keys Kilnmark wrote in stored JSON (problems, run results and progress, quality), so
  earlier runs still show. In the code, `kiln-steps` is now `kiln-nodes`, `Step` names become `Node`
  names (`StepFactory` is `NodeFactory`), and the canvas's `StepNode` is `NodeCard`. The setting
  `kilnmark.pipelines.preview-max-steps` is now `kilnmark.pipelines.preview-max-nodes`
  (`KILNMARK_PIPELINES_PREVIEW_MAX_NODES`): set the new name if you had set the old one.
- **The node list sits beside the canvas.** On the pipeline canvas, the open node list now takes a
  column of its own and the canvas narrows beside it, instead of opening over your nodes. A folder's
  icon on the rail opens the list at that folder, its nodes shown under it; the separate pop-out
  beside the rail is gone. On a phone the list still opens over the canvas.
- **Flows, the engine** (development; V20; ADR 0026; docs/design/flows.md). A flow runs saved
  pipelines in an order, joined by connections: on success, on failure, always, or on a condition
  over what a node measured, such as `clean.quarantined_share < 0.02 and clean.score >= good`. Not yet
  reachable from the API or the pages (flows are queued and cancelled through `FlowService` only);
  the API, the run page with its Gantt chart and the canvas follow, and the user guide with them.
  `FlowDefinition` (`flow/v1`: parameters, `run-pipeline` and `stop` nodes, connections,
  `queue_timeout`, checked with line and column and suggestions, loops refused), `FlowCondition` (a
  small language of comparisons with SQL's three-valued logic and the quality words `good`, `fair`,
  `poor`; a condition may read only nodes that must have ended) and `FlowEngine` (the state machine
  that decides what runs next; no threads, no database) are in kiln-core. `FlowStore` and `FlowService` (server) queue a
  flow run as a job of kind `flow`; the worker only watches, holds no worker slot, and each
  `run-pipeline` node queues an ordinary pipeline run as the person who started the flow, with their
  role, linked by `pipeline_run.flow_run_id` and `flow_node`. A node whose pipeline is already running
  waits, up to `queue_timeout` (30 minutes by default). A flow is tried once, like a pipeline; one
  run of a flow at a time, and one worker watches at most 8 flows at a time. Flow nodes never copy
  why a pipeline failed or could not run (it can quote data or name fields). Saving, running and
  cancelling take the steward role. Tests:
  `FlowRunTest` runs flows on PostgreSQL for sequences, quality conditions, failure connections,
  waiting, cancelling and stop nodes.

## 0.0.8 (28 October 2026)

### What's new

- **See inside a run.** The run page shows where each node spent its time (working, waiting for
  rows, or held up by the next node) and names what limited the run; rows per second for each node
  through the run, with a band showing when it was held up; and a log of what happened, gate
  verdicts included, to filter and download. While a run goes, the canvas shows each node's rows
  and rows per second and marks the slowest node.
- **Preview at a node.** Right-click a node and choose **Preview rows**, or open its **Preview**
  tab, to see the rows as they would leave it, before running, with nothing written. Sensitive
  columns stay masked, and for anyone but an admin a preview is refused when a node looks inside
  one.
- **Models that think before answering** (such as gemma4) now pass **Test** under AI models.
- **Clearer nodes after a run.** On the canvas, a node's rows, its measured quality and its
  predicted quality now each look different, and nodes no longer overlap or hide the connections'
  row counts once a run's results are shown.

### Details

- **See inside a run** (V19; `RunListener`, `RunProgress`, `RunLog`; `RunTimeSpent`, `RunRates`, `RunLogPanel`).
  The engine times every node (`NodeResult.startedAt`, `firstRowAt`, `lastRowAt`, `finishedAt`) and
  adds up where its time went: waiting for rows on its inputs and held up by the next node
  (`RowSet` put and take waits, counted only when it has to wait; `pipeline_node_run` keeps them,
  and `duration_ms` is now filled). Through an optional `RunListener` on a thread of its own (a
  listener that throws or hangs never fails or holds up a run; no allocation per batch) it reports
  each node starting, its first row in and out, its last row out and how it ended, the engine's
  notes, and a snapshot every two seconds: rows and rows a second per node, waits so far, each
  queue's fill, and the **Slowest** node. The worker keeps the latest snapshot on `pipeline_run` and
  rows a second in `pipeline_run_point` (appended, at most 900 points, spikes kept), and the log in `pipeline_run_event` (at most 10,000
  events; the engine's words and rule messages are the held-back detail; each write gives up after
  5 seconds). Every time is on the worker's clock. `GET /api/v1/pipeline-runs/{id}/events` (by
  node and level, paged), `.../series` and a streamed `.../events/download` (text or NDJSON). The run page shows
  the run at a glance in tiles, **where each node spent its time** (working, waiting for rows, held
  up by the next node, with "What limited this run: check, working 90% of the time"), **rows per
  second through the run** (a line per node with a Held up band for its output queue, and warnings,
  errors and gates quarantining marked on the time axis), each node's time and share of the run,
  live rows and rates while it runs, and a **Log** to filter and download, with gate verdicts in
  it. While a run goes the canvas shows each node's rows so far and rows a second ("19,814 out ·
  8,351 rows/s") and marks the node holding it back Slowest, in words and with an orange edge.
- **Preview at a node** (`POST /api/v1/pipelines/preview`, `POST /api/v1/pipelines/{name}/versions/{n}/preview`,
  `PipelinePreviews`, `NodePreview`; docs/design/pipelines.md, "Preview at a node"; the guide's "Preview a
  node"). The first rows (100 by default, at most 1,000) leaving one output of one node of the YAML as it
  stands, with their columns and kinds, how long it took and, in words, any node before it that reads all
  its rows first. Only the node and the nodes upstream of it run, on the API process while the caller
  waits; a collector takes the rows and finishes, so the engine ends the nodes before it early. Only reads
  and the in-memory row nodes may run: anything else, writes included, is refused (400). Stewards and
  admins; reads resolve their sources inside a run context naming the caller. For all but admins,
  columns marked sensitive may pass through unchanged and are masked inside the collector; a preview
  in which any node looks inside one (an expression, cast, group, dedupe or lookup key, or a gate's
  rule, inline or the asset's, whatever its `on_fail`) is refused (403), since even its success or
  failure could tell a value; a failure's message is held back as a run's is. Limits, checked before
  any work: one per person at a time, `previews-per-minute` (6 per person), `preview-concurrency` (2),
  `preview-max-nodes` (50), `preview-max-reads` (10); stateful nodes' `memory_limit_mb` lowered to a
  share of `preview-memory-limit-mb` (256); `preview-timeout` (30s) is hard: the nodes run on their
  own thread and the request is answered after the limit and 2 s of grace whatever they do (a
  preview still stopping is logged, counted and keeps its slot until it ends); and a preview stops
  when its request is closed. Responses are `Cache-Control: no-store`. On the canvas: a node's context menu (right-click,
  Shift+F10 or the menu key; `NodeMenu`) with Preview rows, Settings, Connect to…, Duplicate and Remove,
  and a Preview tab in the node's drawer (`NodePreviewPanel`, reusing `PreviewTable`). A right-click no
  longer selects the node first, so the drawer does not open under the pointer.
- **Engine: a node whose readers have all finished ends early even when it writes only to unconnected
  ports** (`KilnGraphRunner`). Before, such a node (a filter whose only connected output is `rejected`,
  say) learnt its readers had gone only when it next wrote to them, and could read its whole input for
  nothing. `KilnGraphRunner` also takes a `stopWait`: how long a stopped run waits for its nodes'
  threads (unbounded unless set; previews wait 5 s). `Preview.text` and `Preview.utf8Length` are now
  public, for the node preview's collector.
- **Canvas chips after a run** (`NodeCard` `nodeChips`, `components/ui/StatusChip`). Each kind of
  chip has its own look and words, so none depends on colour: rows and outcome in a neutral chip
  with a rows icon ("5,000 rows out · Finished"; Ended early, Stopped and Failed in a chip of their
  own, toned `warn` or `bad`); measured quality in a solid chip with a gauge icon, toned by its word
  (`--km-tint-green/amber/red-*`), "Quality 99% (Good)"; predicted quality in a dashed outline chip
  in the AI violet (`--km-tint-violet-bg`/`-fg`, the new `StatusChip` `outline`, `.km-chip--outline`)
  with a sparkles icon, "Predicted ≈ 100%", "(estimate)" for screen readers and in its title, and
  "1 unknown" for what it could not tell; a gate's counts as plain text, "4,771 passed · 229
  quarantined". One row per kind, each 20px high, so a node's height is known before it is drawn.
- **Canvas spacing for run results** (`layout.ts`). `nodeHeight` counts the rows of chips a node
  shows (problems and run, measured, predicted, gate counts), and the canvas uses the larger of that
  and the height React Flow measured. Auto-layout (dagre) gets each node's real height and spaces the
  columns for the widest connection label. A layout saved before a run is moved apart in the view
  only (`makeRoom`: nodes move right where a connection's label does not fit between columns, then
  down where they would overlap or come closer than 36px in a column); the YAML's `layout:` is
  unchanged until a node is moved or Auto-layout is chosen. Connection labels have a padded
  background.
- **A write's header says file or table**: "Sink · Write to a file" with `path` or a file-like
  `target` (a folder or a file extension), "Sink · Write to a table" with `table` or another
  `target`, instead of the type's "Write to a table or file" cut to "Write to a table…".

- **Quieter Windows builds** (development): `build.ps1` and `lab.ps1` no longer show podman's
  "Executing external compose provider" notice, or the harmless message that the shared network is
  still in use, as errors. `lab.ps1 start` and `lab.sh start` check each demo source's port first and
  name the container or program already holding it, instead of failing inside compose with
  "address already in use" (lab/README.md, "Port already in use").
- **The lab's S3 moves to port 9020** (development), from 9000, which other S3 servers such as MinIO
  commonly take. `lab/sources.csv`, the lab scripts and the guides say 9020; import `lab/sources.csv`
  again to update `lab-bucket` and `lab-warehouse`.
- **Models that think before answering pass Test.** Local models such as gemma4, qwen3 and
  deepseek-r1 spend part of their reply on reasoning. **Test** allowed only 20 tokens, so they ran out
  before answering and Test said "The model answered with nothing". Test now allows 1,024 tokens, and
  Ask's search planning 1,500 (from 300).

## 0.0.7 (28 October 2026)

### What's new

- **Any database with a JDBC driver.** For databases without a connector of their own, such as
  Db2, Teradata, Snowflake, Sybase or Informix, an admin registers the database's JDBC driver under
  **Settings**, **JDBC drivers** (upload the jar or pick it in the drivers folder), then adds a
  **Generic JDBC** source with its URL. Kilnmark discovers and profiles its schemas and tables,
  previews and reads them in pipelines with their types (decimals never rounded; unknown types as
  text, with a note), and, when the source is not read-only, appends to tables or creates them.
  Kilnmark records the driver's checksum, who registered it and when, and stops using it if the
  file changes. The admin also lists the driver settings sources may use; any other in a URL is
  refused. Replace and upsert are not offered for these sources, as no database-neutral way
  makes them safe.
- **Predicted quality before a pipeline runs.** While you edit a pipeline, each write and quality
  gate on the canvas shows the score Kilnmark predicts for it, such as "Predicted 91% (estimate)",
  worked out from what the catalogue already knows about the tables and files it reads, without
  reading any data. What it cannot tell, it says is unknown. Each run keeps its prediction, and the
  run's page shows the measured score beside the predicted one, per dimension, with the gap: a wide
  gap shows where the catalogue's information, or the pipeline, needs attention.
- **Suggestions to improve a pipeline.** **Suggestions** on the canvas lists nodes that would raise
  a write's predicted score, such as trimming spaces, standardising two date formats, keeping one
  row per key, dropping rows with no match in a related table, or setting aside rows outside the
  accepted patterns, each with its predicted effect. **Add** puts the node in place, and one Undo
  takes it out. With an AI model set for **Pipeline suggestions**, it ranks and explains them and
  adds its own, seeing only names, types, shares and value shapes of sources whose AI rule allows
  it, never values; what it suggests is checked before you see it.

### Details

- **Canvas icons.** Read nodes and the Read folder show a database, write nodes and the Write folder
  a save icon; the two database icons they had differed only by a small arrow.
- **Generic JDBC connector** (kind `jdbc`; docs/design/connectors.md, "Generic JDBC"; docs/guide/drivers.md,
  "Databases without a connector of their own"; docs/guide/writing-data.md, "Generic JDBC"). Settings
  `driver` (CHOICE of the registered drivers), `url` (TEXT; must start as the driver's URL prefix and
  pass the driver's `acceptsURL`; every `key=value` in it, after `; ? & : / ,` or inside `( )`, must be
  on the driver's allow-list, and a URL with an `=` Kilnmark cannot place, quotes, braces,
  back-slashes, spaces or percent-encoding is refused, `JdbcUrlSettings`), `username`, password as the
  secret, `properties` (`name=value; ...`, on the allow-list too), `schemas`, `read_only` (default
  true). Names that hold secrets (`pass`, `pwd`, `secret`, `token`, `key`, `credential`) or the user
  name are refused in the URL and the properties whatever the allow-list, and the URL is never quoted
  in messages. `hosts()` also reads host-naming settings (failover partners, `*servername`,
  `alternate*`, values that are URLs), so `kilnmark.sources.allowed-hosts` applies to them.
  `Connector.adminOnly()` (new): only admins add such sources, change their connector settings or
  check unsaved ones (`SourceService.adminOnlyKind`, 403). Catalogue from `DatabaseMetaData` with
  fallbacks (schemas, else catalogues, else one `default` container; nulls), types by
  `GenericTypes` (decimal(p,s) up to 38 digits, wider or undeclared as exact text, timestamps with
  and without zone, unknown vendor types as text with a note in the column's description). Reads
  use `SELECT *` quoted as the driver says and `Statement.setMaxRows` for limits
  (`JdbcTableInput.MAX_ROWS`, new, with the fetch size kept at or below it); quality rules are
  checked on the rows read (`JdbcConnector.runsRulesInDatabase()`, new). Writes: `append` and
  `create` only, through `JdbcTableOutput` with `GenericWriteSql` (table, columns, sizes and types
  from `DatabaseMetaData`); new `WriteSql` hooks `existsByMetadata`, `requiredByMetadata`,
  `targetColumnsByMetadata`, `createProblem`, `checksCreatedTable` (a created table is described
  again and every value checked against it) and `dropCreatedOnFailure` (where DDL commits). The
  other writers are unchanged. `replace` and `upsert` are refused with a message. Text is checked
  against `CHAR_OCTET_LENGTH` in UTF-8 bytes where the driver reports it. Every database write now
  refuses a driver that stays in auto-commit or offers no transactions. A table a failed create made
  is dropped only where table definitions commit by themselves and only while it is empty. The
  generic reader goes on when a driver refuses `setReadOnly` or `setAutoCommit(false)`. A target
  schema must be one the driver lists; without an identifier quote only plain names are read. Times
  kept to more than six digits of a second say in the column's description that the rest is cut.
- **Registered JDBC drivers** (docs/design/drivers.md, "Registered drivers"; ADR 0025). Settings ›
  JDBC drivers and `GET/POST /api/v1/drivers/registered`, `DELETE /api/v1/drivers/registered/{name}`,
  `GET /api/v1/drivers/registered/history`, `GET /api/v1/drivers/files`,
  `POST /api/v1/drivers/files/{fileName}` (octet-stream; never replaces a file), all admin-only.
  Registering takes the driver's allowed properties (never secrets, files, folders, classes, key
  stores), loads the class from a private copy without running its initialisers until it is known to
  implement `java.sql.Driver`, refuses a jar with a pinned driver's checksum under any name, and records
  the jar's SHA-256 (`driver_registration`, migration V18); `DriverFolder.driver(RegisteredDriver)` loads only while
  the checksum matches, in its own class loader, never through `DriverManager`. Removal is refused
  while sources use the driver (checked and removed in one transaction, sources locked);
  `driver_registration_log` keeps every registration and removal, append-only (a trigger refuses
  UPDATE, DELETE and TRUNCATE). Uploads are limited by the server's request limit
  (`quarkus.http.limits.max-body-size`, 10 MB by default), which the page shows; larger jars are copied
  into the folder. Jars are read without following links and never beyond 200 MB.
  Nothing is downloaded for registered drivers. The pinned path is unchanged.
- **H2 for tests only** (`com.h2database:h2` 2.5.252, MPL 2.0 or EPL 1.0): `testRuntimeOnly` in
  `kiln-plugins`, registered through the drivers folder by the generic JDBC tests.
  `checkNoCustomerDrivers` now also fails a release holding H2's jar (`h2-*.jar`) or classes
  (`org/h2/`).

- **Predicted quality** (docs/guide/pipelines.md, "Predicted quality"; docs/design/data-quality.md
  section 8). `QualityPredictor` carries each read asset's latest profile (nulls, blanks, distinct,
  value shapes, formats, most common values, quantiles, outliers), accepted shapes, relationships
  and rule results through the nodes, one rule per node type (filter conditions from null shares,
  most common values, quantiles and value shapes; compute's trim, case, coalesce, parse_date and
  format_date; lookup orphan rates from a relationship's containment or the columns' HyperLogLog
  sketches; aggregate and dedupe from distinct counts; union weighted by rows; a quarantining gate
  removing the predicted failing share from `out`). Writes are scored with the same `Measures` and
  `Scores` as a run's measured output, keeping only the dimensions the estimate can tell; gates
  from their rules' predicted failing shares, scored as the gate scores itself. Each prediction
  lists what it could not tell and what it assumed. `kiln-nodes` gains `Expression.view()` and
  `columns()`, a read-only view of a parsed expression (`ExprView`).
- **API.** `POST /api/v1/pipelines/predict` (`{yaml}`; viewer) returns `{estimate, valid, nodes:
  [{node, type, rows}], outputs: [{node, role, asset, overall, dimensions, reasons, unknown,
  assumptions, rows, gate}]}`; `GET /api/v1/pipelines/{name}/versions/{n}/prediction` the same for a
  saved version; `POST /api/v1/pipelines/suggest` (`{yaml, ai}`; steward) returns `{estimate,
  suggestions: [{id, kind, title, why, source, nodes: [{id, type, settings, inputs}], placement:
  {before, port, from, through}, effect: {output, before, after, dimensionsBefore, dimensionsAfter,
  rowsBefore, rowsAfter, note}}], ai: {state, message, model}}`. A run's quality rows gain
  `prediction` (`{unknown, assumptions, rows, gate}`) beside `predicted`.
- **At run start** each write's and gate's prediction is stored (migration V17 adds
  `pipeline_node_quality.predicted_detail`); a failed run keeps its predictions, with a note that the
  write was not measured.
- **Suggestions** (`PipelineAdvisor`): rule-based candidates with no model, each tried out by
  predicting the pipeline with it and kept only when it raises the write's score. New AI feature
  `pipeline_suggestions` (chat): ranks, explains and adds, through `AiGateway` with the interactive
  budget. Only columns whose source's `ai_egress` lets that model see them are sent (names, kinds,
  shares, value shapes, accepted shapes; never values, never computed columns), with the strictest
  of those sources passed to the gateway for its own check; no call is made when no source allows
  it. The model's additions are checked (node types the server has, settings they take, columns it
  was shown that reach that point, the node's own check and output schema) and dropped otherwise.
  An absent, refused, slow or failing model leaves the rule-based suggestions, and says why. A
  suggestion reading another asset is shown to the model only when that asset's source allows it.
  The model is asked when the panel opens and on Ask again (at most 6 times a minute per person);
  edits re-run only the rule-based suggestions. A suggestion that removes rows says how many.
- **Values stay with those who may see them.** Prediction for a request uses a column's most common
  values and spread only when the caller may see data, and for a column marked sensitive only for an
  admin (through `Sights`, checked by `SightsUseTest`); otherwise those estimates are unknown, so a
  viewer cannot probe values through predicted row counts.
- **Canvas.** Predicted scores on each write and gate (debounced, labelled "(estimate)", in each
  node's accessible name and settings drawer); a lazily loaded **Suggestions** panel whose **Add**
  inserts the nodes with `insertNodes` (text edits only, one undo step). The run page shows
  measured beside predicted per dimension with the gap.

- **A demo from start to finish** (development): `lab/README.md` walks through a whole demo, from
  starting Kilnmark and the demo sources to discovery and pipelines with a quality gate that sets rows
  aside and then stops a run. Three demo pipelines in `lab/pipelines`: customers cleaned through a
  gate (PostgreSQL), spend per customer with aggregate, lookup and route, and a MariaDB supplier feed
  whose repeated SKUs are quarantined. `lab.sh pipelines` and `lab.ps1 pipelines` add them to a
  running Kilnmark through its API, and `LabPipelinesTest` checks them against the lab's columns so
  a change to the nodes cannot break them unnoticed. Nothing of this ships.

## 0.0.6 (28 October 2026)

### What's new

- **Quality gates in pipelines.** Add a **Quality gate** node (in the Quality folder) to check rows
  against quality rules as they pass, with no second read of the data: the rules of the table or
  file the pipeline read, and any you write on the node. When rows fail, the gate can stop the run
  so that the writes after it do not commit, send the failing rows to a separate **quarantine**
  output while the rest carry on, or just warn. A limit on the share of failing rows, or on the score, stops the run
  when too much is wrong.
- **Every run measures the quality of what it writes.** After a run, each write and each gate shows
  its quality score on the canvas, beside its rows, and the run's page lists the score per
  dimension and each gate's rules. Give a pipeline a quality threshold (`quality: min_score`) and
  its followers are told when a run writes data that scores below it.
- **Build pipelines on a canvas.** A pipeline's page has a **Canvas** tab beside the YAML: drag
  nodes from a list of folders (Read, Write, Filter and route, Transform, Combine and more, with
  your recently used nodes first), connect their ports, and change each node's settings in a panel that offers
  your sources, tables and columns, with problems shown on the node and setting they are about.
  The canvas and the YAML are the same pipeline, so a change in one shows in the other. After a
  run, each node shows its rows and how it ended. Everything works from the keyboard too.
- **MySQL and MariaDB.** Add MySQL (8.0.19 and later) or MariaDB (10.6 and later) as a source by
  host, port and database: Kilnmark discovers and profiles their databases, reads their tables in
  pipelines and writes to them in every mode. MySQL's own types are understood: `UNSIGNED`
  numbers, `ENUM`, `SET`, `JSON`, `YEAR`, and `TINYINT(1)` read as true or false. The driver is
  included, so there is nothing to install. Writes never change data silently: an upsert that could
  overwrite another row through a second unique index, a time with more fractions of a second than
  its column keeps, or a replace that a hidden foreign key would break stops with a plain message.
- **Oracle.** Add Oracle Database (19c and later) as a source by host, port and service name:
  Kilnmark discovers and profiles its schemas, reads its tables in pipelines and writes to them in
  every mode, without ever rounding a number or turning empty text into null behind your back.
  Oracle's driver cannot ship with Kilnmark, so an admin installs it once, under **Settings**,
  **Drivers**.
- **Settings, Drivers.** Where admins install the drivers some databases need that Kilnmark may not
  ship: accept the licence and Kilnmark downloads the exact version it was tested with and checks
  it, or, on a server without internet access, shows which file to copy where. Until then those
  connectors say **Needs its driver**.
- **Every connection setting has the right control.** On and off settings, such as trusting a
  test server's certificate, are switches; settings with a fixed set of values, such as SQL
  Server's read isolation, are lists; and saving says plainly when a value is the wrong kind,
  for example "true or false, not 'yes'".

### Details

- **Quality gate node** (`type: gate`, docs/guide/pipelines.md, "Quality gates";
  docs/design/data-quality.md sections 7 and 8). Ports `in`, `out` and `quarantine`. Settings:
  `on_fail` (`stop`, the default; `quarantine`; `warn`), `asset_rules` (on by default: the enabled
  rules of the asset read upstream), `rules_from` (the read node to take them from when rows come
  from several), `rules` (inline rules, each a mapping such as `{type: not_null, field: email}`,
  checked as rules made in Quality are; `references` and `custom_sql` are refused, as they run in
  the database), and the run-level thresholds `min_score` and `max_failing_share` (0 to 1). Rows are
  checked in the stream with `RowChecks`, all rules in one pass; `RowChecks.Check.test` says whether
  a row fails. `stop` fails the run as soon as a rule can no longer pass (a count limit passed, or
  a failing row where none is allowed), otherwise at the end of the rows, so the writes after it do
  not commit, naming the rule and its counts. Validation refuses what would get round that: a write
  after a stop gate that commits as it goes (`commit_every` with create, append or upsert) and a
  `limit` after it; it notes a write that gets the gate's rows by another path, and quarantine wiring
  that drops rows or gets none. `quarantine` stops only when a threshold is crossed or a check of
  the whole data (row count, freshness) fails. In a gate, `unique` fails only the repeats of a key;
  it keeps keys as 128-bit hashes, up to 96 MB (`RowChecks` now hashes keys for every streamed
  uniqueness check), and says how many repeats it had set aside if it reaches that. A gate cut short
  by the run is still judged on what it saw, marked incomplete. A new setting kind `RULES`
  (`SettingSpec.Kind`, `rules` in `GET /api/v1/node-types`) takes a list of mappings; the canvas edits
  it as a list of rules and writes one rule a line. A gate whose rows come from several reads must
  name one with `rules_from`, and a note says when a gate has nothing to check.
- **Measured quality per run** (migration V15, `pipeline_node_quality`): each write's score per
  dimension and overall, from the profile its writer made while writing (Measures and Scores, as an
  asset is scored, without history), and each gate's score from its rules' results with its
  decision, row counts and each rule's result. `GET /api/v1/pipeline-runs/{id}` adds `quality`
  (`[{node, role, asset, measured, predicted, note, gate}]`; `predicted` is null until design-time
  prediction comes), and run summaries add `qualityScore` (the lowest output's) and
  `qualityThreshold`. An `append` or `upsert` is not profiled, so it has no score, and says so.
- **Pipeline quality threshold.** A new top-level `quality:` block with `min_score` (0 to 1). When a
  run succeeds and an output scores below it, `pipeline_low_quality` is published once for the run,
  naming the output (`NotificationPublisher.pipelineRunFinished` gains an overload with the node).
- **The pipeline canvas** (docs/guide/pipelines.md, "Building on the canvas"): a palette of node
  types by kind with search, nodes with named ports and problem badges, connections refused with a
  reason, move, duplicate, delete, undo and redo, zoom, fit and a left-to-right auto-layout, and a
  settings panel generated from each node type's settings schema. Where nodes are drawn is kept in
  a new top-level `layout` block that the compiler accepts and ignores. `GET /api/v1/node-types` gives
  the write node `writerSettings`, the writer settings each kind of source takes. New pipelines
  can start from the template or an empty canvas; **Run now** asks first when there are unsaved
  changes. Keyboard: arrow keys between connected nodes, Shift+arrows to move, `C` for Connect to…,
  `?` for every shortcut. The UI adds `yaml` 2.9.1 (ISC), pins `@xyflow/react` to 12.12.0, and
  runs its unit tests (vitest) in `npm run build`; the canvas loads lazily, outside the main
  bundle. A canvas change rewrites only the YAML it changes: other lines stay byte for byte. The
  canvas takes the page's full width with an icon rail of node folders (`NodeFactory.category()`,
  returned as `category` by `GET /api/v1/node-types`), a settings drawer, an overview map, **Fit** and
  **Readable size**. Undo also covers typing in the YAML tab and loading the latest version after a
  save conflict, which now keeps your version one undo away; moving nodes alone no longer makes
  **Run now** ask about unsaved changes.
- **MySQL and MariaDB connector** (kinds `mysql` and `mariadb`), through MariaDB Connector/J
  3.5.10 (LGPL 2.1, allowed for that module only: ADR 0007; the licence's text now ships in the
  release's `licenses/` folder, named in NOTICE). Settings `host`, `port`, `database`,
  `username`, `tls_mode` (`preferred` by default), `server_ca` and `tinyint1_as_boolean`, with
  kinds declared; no driver property can be set by people. Zero dates read as null and are
  counted; `TIME` reads as text. Writes: `create`, `append`, `upsert` (`ON DUPLICATE KEY UPDATE`)
  and `replace` (an atomic `RENAME TABLE`), with `LOAD DATA LOCAL INFILE` for tables the node
  creates when the server allows it. The connection form shows their host, port and database once
  (`addressFields` is false for connectors whose own settings hold the host). Tests against
  MySQL 8.4.10 and MariaDB 11.4.12 run in CI's heavy-databases job.
- **MySQL and MariaDB writes, after review.** Sessions use a fixed `sql_mode`
  (`STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION`),
  never the server's. Upsert refuses a table with another unique index that lacks a key column,
  naming it, unless `allow_other_unique_keys: true` (a new writer setting). Replace refuses
  engines other than InnoDB, Aria and MyISAM, and tables whose triggers the login cannot be shown
  to see (with a role active too); it reads foreign keys from `INNODB_FOREIGN` /
  `INNODB_SYS_FOREIGN` when the login has `PROCESS`, and when a key it could not see refuses the
  old table's drop, puts both names back and fails. Any other leftover `kiln_old_…` table is a
  note on the run (`write_warning` on the writer's `ROW_COUNT`). Per value: `DATETIME(p)`,
  `TIMESTAMP(p)` and `TIME(p)` refuse extra digits of a second (PostgreSQL `timestamp(p)` and
  `time(p)` too); `TIME` takes only times and text; `YEAR` takes 1901 to 2155. `LOAD DATA` checks
  `@@warning_count`. On servers with `lower_case_table_names` set, targets are written and recorded
  in lower case.
- **Oracle connector** (kind `oracle`): settings `host`, `port`, `service_name`, `username`,
  `encryption` (`accepted`, `required` or `tls`) and `schemas`, with kinds declared; the address is
  always built, never given. Catalogue from the `ALL_*` views, keys across schemas, `NUM_ROWS` as
  the estimate, `SAMPLE` for sampling. `NUMBER` without precision and `FLOAT(b)` read as text
  holding their exact digits; `DATE` as a timestamp; `TIMESTAMP WITH LOCAL TIME ZONE` through a
  cast to `TIMESTAMP WITH TIME ZONE`, whatever the JVM's time zone. Writes: `create`, `append`,
  `upsert` (`MERGE`) and `replace` (one PL/SQL block that puts the names back on failure),
  refusing empty text and bytes (unless `empty_text_as_null`; in an upsert key always), decimals
  past 38 digits, a `FLOAT(b)` column's digits or a NUMBER's range, timestamps with more digits of a
  second than the column keeps, characters the column's character set lacks (on databases that are
  not Unicode; national columns bound with `setNString`), and text over a column's size in bytes.
  The driver's `TNS_ADMIN` is an empty private folder. Tests against Oracle Database Free in CI's
  heavy-databases job, in their own Gradle run (`./gradlew :kiln-plugins:oracleTest`, in
  `America/New_York` time); `encryption` `required` and `tls` are not tested against a listener.
- **Drivers the customer installs** (ADR 0007, docs/design/drivers.md): connectors declare their
  driver (coordinates, pinned version, SHA-256, licence, driver class); the drivers folder
  (`kilnmark.drivers.dir`, `/var/lib/kilnmark/drivers` in the image) loads only verified jars, from
  a private copy in `drivers.loaded` beside it (emptied at start), each in a class loader that sees
  only the JDK, and uses a loaded driver again without re-reading its file while the file's size and
  modification time are unchanged. `GET /api/v1/drivers` and
  `POST /api/v1/drivers/{id}/accept` (admins) record who accepted the licence and download from
  `kilnmark.drivers.maven-url`; `kilnmark.drivers.download=false` for servers without internet
  access. `GET /api/v1/connectors` adds each connector's `driver` state. Oracle's driver is a
  test-only dependency, and `./gradlew release` fails if one reaches the release folder
  (`checkNoCustomerDrivers`, which also looks inside jars nested in jars). Database migration V14
  adds `driver_licence`. **Upgrades:** when a release pins a newer driver version, the installed
  file is no longer used; the driver shows as missing, an admin accepts the licence again, and the
  server logs a warning at start naming the old file. Release notes will say when this happens.
- **Lab: Oracle** (development). `lab-oracle` (Oracle Database Free 23ai, profiles `oracle` and
  `all`) on host port 1522, service `FREEPDB1`, with a synthetic parcel carrier in schemas `FLEET`
  and `FREIGHT` and planted quality problems (null emails, out-of-range and backwards dates,
  negative weights, duplicate tracking numbers in a table without a key, three postcode formats, a
  `NUMBER` without precision), an empty `WAREHOUSE` schema for pipeline writes, a `lab-oracle` row
  in `lab/sources.csv`, `oracle` in `lab.ps1` and `lab.sh`, and `lab.sh driver oracle`, which
  downloads Oracle's driver after showing its licence. CI loads the seed and checks the answer key.
- **Lab: MariaDB** (development). `lab-mariadb` (MariaDB 11.4.12, profiles `mariadb` and `all`) on
  host port 3307, with synthetic shop orders and inventory in databases `shop` and `stock` and
  planted quality problems (null emails, zero dates, out-of-range dates, a negative stock level,
  duplicate SKUs in a table without a key, inconsistent JSON), an empty `warehouse` database for
  pipeline writes, a `lab-mariadb` row in `lab/sources.csv` and `mariadb` in `lab.ps1` and
  `lab.sh`.
- **Setting kinds.** `Connector.Setting` has a kind (`TEXT`, `NUMBER`, `BOOLEAN`, `CHOICE` or
  the new `URL`, from `SettingSpec.Kind`), its choices and its default; the old constructor gives
  `TEXT`. Every built-in connector declares them, and the settings every source has carry a kind
  too, now including `keep_samples`, `sample_rows` and `table_sample_above_rows`.
  `GET /api/v1/connectors` adds `kind`, `choices` and `defaultValue` to each setting and
  `GET /api/v1/common-settings` adds `kind`; nothing was removed. Saves and bulk imports refuse a
  value of the wrong kind; stored values stay text, so nothing is migrated and values saved before
  still load. The connection panel follows the kinds and keeps its old guesses only for a setting
  without one.

## 0.0.5 (27 October 2026)

### What's new

- **Email alerts and notifications.** A bell in the top bar and a Notifications card on the home
  page tell you when a quality rule starts failing or recovers, an issue is opened, a scan or
  connection test fails, or drift is found. Turn on email under **Settings**, **Notifications**:
  each alert as it happens or a daily digest, for every source or only yours. Admins set the mail
  server, team addresses and who may get email under **Settings**, **Email**, and can see every
  email sent. Emails never contain values from your data.
- **A new look.** A dark sidebar with coloured icons, a light page with white cards, new fonts and
  clearer status chips, in Light, Dark and High contrast. Orange is still the default accent, and
  you can choose another under **Settings**, **Appearance**.
- **A home page that shows what matters.** A greeting with what needs attention, your sources at a
  glance with their health and quality score, the issues that need a look, and a checklist that
  shows how far set-up has got, which shrinks to one line once everything is done.
- **Add recommended rules together.** On an asset's new **Quality** tab, tick the suggested rules
  you want (the ones that fit every row are ticked already) and add them in one go. Each rule
  shows a bar for each of its last ten results.
- **Asset pages have tabs.** Overview, Columns and Quality, so a long page is easier to move
  around.
- **Sources as cards, and real switches.** Sources show as cards with their health. On and off
  settings, such as including sub-folders or keeping document text, are switches instead of
  typing true or false.
- **Ask Kilnmark, notifications and your account in the top bar.** The theme switch has moved into
  the menu under your name. **What's new** is now the first entry under Documentation in the
  sidebar, marked **New** after an upgrade.
- **Better on small screens.** The sidebar becomes a row of icons, then a menu, and pages no
  longer scroll sideways on a phone.

### Details

- **The release holds the product only** (ADR 0024): the reference lab is for development, tests
  and demos, and never ships. The release has its own `compose.yaml`, made from
  `containers/compose.release.yaml`: no lab defaults, a commented-out example for the folder to
  scan, images tagged with the version, and the server image built from the release folder (the
  server Containerfile's new `APP_DIR` argument). It has no default password or token: it requires
  `KILNMARK_DB_PASSWORD` and `KILNMARK_ADMIN_TOKEN` and stops, naming the missing one, without
  them; `.env.example` beside it lists every setting; the database is published on the machine
  itself only. The root `compose.yaml` is the developer setup, with its defaults.
  **New pipeline** starts from the first source with scanned tables or files and the first that
  allows writing, or from placeholders such as `<your source>`, instead of lab sources. Guide and
  message examples use neutral names (`sales-db`, `warehouse`, `file-share`, `landing-bucket`).
  `release` now runs `checkProductOnly`, which fails on any lab folder, source name, path, lab-only
  port or mention in the release, in Kilnmark's own jars (the UI bundle included) and in the
  bundled guides, and on a default or literal value for any password, secret or token in the
  shipped `compose.yaml` or `.env.example`; justified exceptions go in
  `config/product-only-allow.txt`.
- **Reference lab: `lab-warehouse`** (lab/README.md): a second bucket on the lab's VersityGW server,
  an anonymous volume that starts empty (no new service). `lab/sources.csv` registers it with
  `allow_writing=true`; the column's empty cells keep `lab-db`, `lab-bucket` and `lab-share`
  read-only, also on a later import. Reset it by emptying the bucket.
- **A lab bucket for pipeline output (development).** The reference lab has an empty bucket, `lab-warehouse`,
  that pipelines may write to, so the lab's synthetic source data stays read-only.

- **A sample SQL Server database in the reference lab (development).** `.\lab\lab.ps1 start sqlserver` (or
  `./lab/lab.sh start sqlserver`) starts SQL Server 2022 Developer with `labdb`, a synthetic
  field-service and sales database with problems planted for profiling and rules to find. Start,
  stop and reset each demo source on its own, and every new connector now comes with a lab dataset.
- **Reference lab: SQL Server and per-source scripts** (lab/README.md): service `lab-sqlserver`
  (`mcr.microsoft.com/mssql/server:2022-CU27-GDR1-ubuntu-22.04`, the tag `SqlServerIngestTest`
  pins; port 14330; named volume; about 2 GB of memory; starting it accepts Microsoft's Developer
  licence, `ACCEPT_EULA=Y`) and a one-shot `lab-sqlserver-init` that loads `labdb` once with the
  image's `sqlcmd`: schemas `sales`, `hr`, `ops` (9 tables, a view, 6,901 rows, synthetic personal
  data marked in descriptions and sensitivity classifications) and an empty `warehouse` for
  pipeline writes. Logins `kiln_reader` (read-only) and `kiln_writer` (tables in `warehouse` only).
  Planted: null emails, duplicate keys in a staging table with no primary key, out-of-range dates
  and five phone formats. `lab/sources.csv` adds `lab-sqlserver`, with
  `trust_server_certificate=true` for the server's self-signed certificate. Every lab service
  is now in a compose profile named after its demo source plus `all`, so a bare `up` starts
  nothing; `lab/lab.ps1` and `lab/lab.sh` (`list`, `start`, `stop`, `status`, `reset`, `logs`,
  `sources`) start just what is needed, and `build.ps1 -Lab` takes names
  (`-Lab postgres,sqlserver`). docs/design/connectors.md gains a definition of done: a connector
  ships with a lab dataset, or for SaaS and cloud-only connectors a mock or emulator and a test
  account recipe; lab/README.md has a lab coverage table.
- **SQL Server: Trust the server certificate** (docs/design/connectors.md): new setting
  `trust_server_certificate` (`true` or `false`, off by default) for test servers with a
  self-signed certificate. It passes `trustServerCertificate=true` to the driver as a connection
  property on every connection, scans, checks, rules and reads (`JdbcConnector.driverSettings`,
  `driver.` settings of the reader node), and never edits the URL; a URL that also sets
  `trustServerCertificate` is refused with the setting, and changing it needs the password again.
  The connection-failure hint names it instead of the URL property.
- **Bulk import keeps semicolons** (docs/development.md): only list settings (`schemas`,
  `containers`) turn `;` into `,`. A SQL Server URL in a `url` column, a `;` delimiter and a
  description were corrupted before; passwords never were.

- **Notifications and email alerts** (docs/guide/notifications.md, docs/design/notifications.md):
  rules that start to warn, fail or not run and that recover, new issues, failed scans, drift
  against the previous scan and failed connection tests become notifications. One internal event
  type (`NotificationEvent`, fired through `NotificationPublisher`, recorded off the caller's
  thread), told once per failing streak and once on recovery (`notify_state`). **In the app** for
  every person (a token that opens the app; not scripts or AI assistants' tokens), per kind, with
  `GET /api/v1/notifications` (each item's channels: in-app, and the caller's email with its status;
  team emails by name for admins), `GET /api/v1/notifications/unread-count` (99 with `more: true`
  beyond) and `POST /api/v1/notifications/read`; kept 90 days. **By email** for those who opt in
  and confirm their address (admins get high-severity events at once): kinds, least severity, at
  once or a daily digest at an hour in the server's time zone, every source or some
  (`/api/v1/me/notifications`, **Settings › Notifications**). Admins set the mail server and its
  rules under **Settings › Email** (`/api/v1/mail/settings`): STARTTLS, TLS or none; the password
  sealed with the server's key, write-only, never logged, and only ever sent where it was entered
  for (changing the server, port, security or user name asks for it again); sign-in without
  encryption refused unless allowed; allowed email domains (others paused); confirmation of
  personal addresses; tries, the hourly limit per address, and what happens to emails unsent
  after a while. `kilnmark.mail.*` gives the defaults, and `kilnmark.mail.locked` fixes settings
  the UI then shows read-only. A test email reports the server's answer in plain words, scrubbed
  of the sign-in. Team addresses get what a viewer sees. Emails are plain text and simple HTML,
  without images or tracking, never with values from the data: what a database said (a failed scan
  or connection test) only for stewards and admins, for admins alone on sources with sensitive
  columns. Workers send them with Jakarta Mail (Eclipse Angus), one claim at a time, retrying after
  1, 3, 9 and 27 minutes then hourly, at least once, and log every attempt (**Settings › Sent
  emails**, `GET /api/v1/mail/log`, kept 30 days). Pipeline runs (failed, or below their quality
  threshold) have their kinds and a one-line hook, `NotificationPublisher.pipelineRunFinished`, for
  the pipelines change to call. Migration V13 (after V12). The reference lab gains Mailpit, demo
  source `mail` (compose profile `mail`; SMTP on 1025, its web page on 8025).

- Design tokens reworked (`ui/src/tokens.css`): page, card and sidebar colours, tints for chips and
  tiles, chart and bar fills, radii, shadows and type sizes, for light, dark (cards `#1b2030` on
  `#11141c`, the sidebar kept dark) and high contrast. Each accent preset has an indicator, button
  fill, link, tint and on-dark shade; the orange buttons use `#c2410c` so white text keeps 4.5:1.
  The contrast check resolves `var()` and now covers 692 pairs (sidebar, hero, chips, tiles, fills
  against their track, the New badge, every accent).
- Inter (400, 500, 600) and Manrope (700, 800) are bundled from `@fontsource/inter` and
  `@fontsource/manrope` 5.2.8, Latin subset only, and listed in the UI's third-party notices.
- Shared components in `ui/src/components/ui`: Card, PageHeader, StatTile, IconTile, StatusChip,
  SeverityChip, DataTable, Switch, EmptyState, Tabs, Button, ButtonLink, Meter, ScoreRing,
  FilterChips. Every page uses them; plain elements and older class names are styled to match.
- The shell: the sidebar's entries live in `ui/src/nav.ts`; an icon rail under 1200 px and a modal
  drawer under 768 px. The search's submit button sits inside the field, which removes the 15 px
  sideways scroll at 375 px. The user menu holds the theme switch, Settings and Sign out.
- Home is built from existing endpoints (sources, source quality scores, open issues, rules, AI
  status). The 14-day quality trend shows an empty state, because the server keeps only the latest
  scores. **Recent pipeline runs** lists the latest run of each pipeline, newest first, from
  `GET /api/v1/pipelines`. The **Notifications** card and the bell (`GET /api/v1/notifications`,
  `…/unread-count`, `POST …/read`) stay hidden while those endpoints answer 404, and appear once
  they exist; other failures keep the bell, which tries again at the next poll.
  Notifications show severity in words, unread marks, email delivery (Emailed, Email queued,
  Email failed, and for admins the team addresses), Mark all read, and links to notification
  settings and, for admins, sent emails.
- Asset tabs are kept in the address (`#/asset/<urn>?tab=quality`). Recommended rules are added
  with the existing create-rule call, one per rule, with one summary of successes and failures that
  takes focus. A rule's history comes from `GET /api/v1/quality/rules/{id}/results`.
- Connector settings get a control by kind (switch, list, number, secret), from
  `ui/src/components/settingKinds.ts`; values are still sent as `true` and `false`. Rules have an
  **On** switch instead of Switch on and Switch off buttons; other on/off options (blank text
  counts as missing, open a task, local model server, remove the stored key, local AI assistant,
  import options) are switches too.
- Each home card keeps loading, data and errors apart: a failed call says **Could not load** with
  **Try again**, never a green "nothing open". Open issues are counted up to 500 and shown as
  **500+** beyond that. Scores are fetched for the six source cards shown only, and the score tile
  says it is their average. Sources shows 24 at a time with **Show more**, and a find box past
  eight. Heavy pages (pipelines, rules, AI, tokens, relationships, documentation) load on demand.
- Layout: home pairs runs and sources with the issue and notification lists (which scroll inside
  fixed heights) so the columns end level; Getting set up runs across the page and shrinks to a
  dismissible **All set up** line once done; the connection panel is a modal side sheet (scrim,
  focus kept inside, page scroll locked); fieldsets inside cards are titled sections, not boxes.
- The rule form links to the quality rules guide (**How rules work**). The documentation's page list
  puts What's new first. `#/sources?add=1` opens Add a connection.

## 0.0.4 (25 October 2026)

### What's new

- **Pipelines you can run.** Build a pipeline that reads from your sources, changes the rows and
  writes the result to a table or file. Kilnmark checks it before it runs (every mistake with its
  line, and a suggestion when a name is misspelt), runs its nodes side by side within one memory
  limit, and records what each node did and how it ended. A run that fails names the node where it
  went wrong, and a run can be cancelled.
- **Nodes for changing rows.** Filter rows, compute new columns, pick and rename columns, look up
  values in another input, total and count by group, combine inputs, remove duplicates, send rows
  down different paths and keep the first rows. Conditions and new columns are written in one
  expression language, checked against the columns before the pipeline runs, with the position of
  any mistake and a suggestion.
- **Writing to databases and files.** A pipeline can write to PostgreSQL or SQL Server tables
  (create, add to, replace or update by key) and to CSV or Parquet files in folders and S3
  storage. Sources stay read-only until an admin turns on **Allow writing** for one. What a
  pipeline writes is profiled as it is written and joins the catalogue with its columns and where
  it came from, so the next pipeline starts from what is known.
- **The Pipelines page.** Under **Pipelines**, a YAML editor that checks as you type, a graph of
  the nodes with the columns leaving each, every saved version with who saved it and why, and each
  run with what every node did and what was written.

### Details

- **Pipelines run as graphs (engine, ADR 0022):** `KilnGraphRunner` runs a pipeline graph with
  every node on its own virtual thread and a bounded row set per edge, inside one memory limit.
  An output read by several nodes gives each its own copy; a port such as union's `in*` takes many
  inputs (`NodeContext.inputs`, `RowSet.takeAny`); rows on an unconnected output are dropped
  (`NodeContext.emit`). A failure stops the whole run and names the node where it started; a run
  in which every node waits on another fails after 120 seconds with a message naming them; run
  results count the rows each node was given, per edge and per port, and say how each node ended
  (finished, ended early, stopped by the engine, or failed). Nodes can read the columns of an
  input that sends no rows (`NodeContext.inputSchema`). Scanning's linear runner now runs as
  a graph too, and a node that stops before its input ends no longer leaves the node before it
  waiting.
- **Pipeline YAML is read and checked** (`kilnmark: pipeline/v1`): every problem with its line and
  column (unknown keys, node types and settings with suggestions, missing or doubled inputs,
  loops), and each node's columns worked out before anything runs, without opening a connection.
  Settings that are text keep exactly what was written (`asset: 2024.10` stays `2024.10`);
  numbers with more than 1,000 digits, or a decimal point more than 1,000 places away, are refused.
  Read with SnakeYAML Engine 3.2 (Apache-2.0); anchors, aliases, tags, duplicate keys and files
  over 1,000,000 characters are refused.
- **Pipeline nodes and expressions** (new module `kiln-nodes`): `filter`, `compute`, `select`,
  `lookup`, `aggregate`, `union`, `dedupe`, `route` and `limit`, each with its settings schema,
  checks and output columns for validation, and one expression language for conditions and
  computed columns: SQL-like operators, `case`, `cast`, SQL null rules, 22 functions and the
  aggregates `count`, `count_distinct`, `sum`, `min`, `max` and `avg`. Expressions are parsed by
  hand and type-checked against the input columns (`boolean`, `int64`, `float64`, `decimal`,
  `utf8`, `date`, `timestamp` in UTC; numbers widen, anything else needs `cast`), with errors
  giving the position and a suggestion; they are worked out a batch at a time into Arrow vectors.
  Limits: 10,000 characters, nesting 64 deep, comparisons that do not chain, text values up to
  1,000,000 characters, dates in years -9999 to 9999. Regular expressions refuse patterns that
  repeat a repeating group (unless each repetition starts with its own separator), back-references
  and comment mode, cap values at 100,000 characters (2,000 for patterns that repeat a group),
  catch stack overflows and budget the work per value and per batch; `like` is capped too.
  `lookup`, `aggregate`, `dedupe` and `union` stop at `memory_limit_mb` (256 by default) with a
  message saying what to change. `lookup` takes waiting main rows only when its lookup side is
  quiet, `union` reads whichever input has rows, and `limit` stops reading once it has its rows.
  Inputs that send no rows use the columns the pipeline check worked out, so an aggregate without
  `group_by` writes one row and union and lookup keep their declared columns.
  Guide: **Pipeline nodes and expressions**.
- **Writing from pipelines** (docs/guide/writing-data.md): PostgreSQL, SQL Server, folder and S3
  sources can now be written to by a pipeline's `write` node (`Connector.writer`, capability
  `WRITE`). Tables: modes `create`, `append` (every input column checked against the table
  first, all mismatches named), `replace` (filled under a temporary name and swapped in one
  transaction, waiting at most `lock_timeout`; grants and the owner kept, indexes, constraints and
  triggers not; refused for views, partitions, inheritance, anything else depending on the table,
  and row-level security unless `allow_dropping_row_security` is set) and `upsert` by `key`
  (PostgreSQL `ON CONFLICT`, which needs a unique index on the key, INCLUDE columns allowed; SQL
  Server `MERGE … WITH (HOLDLOCK)`). PostgreSQL loads with `COPY`;
  SQL Server with batched inserts, bulk-copied into tables the node creates. One transaction per
  run, or `commit_every` rows (a failure then keeps the committed rows, and says so). The login's
  rights are checked first and a refusal names the grant to ask for. Files: CSV (RFC 4180, UTF-8,
  `delimiter`) and Parquet (Snappy, ZSTD, GZIP or none) to a path inside the source, written to a
  temporary name and renamed (folders) or uploaded and completed at the end (S3); existing files
  need `overwrite: true`, and paths scans would not find need `allow_unscanned: true`. A created
  or replaced table, or a file, is profiled in the same pass and reported for the written asset
  (`SCHEMA`, `ROW_COUNT`, `PROFILE`, `SAMPLE`) once the write has succeeded; an append or upsert
  reports only the rows it wrote (`ROW_COUNT`, `complete=false`, `appended=true`), so the table's
  own profile is kept. Each writer declares its settings (`Connector.writeOptions()`, as
  `SettingSpec`s; `profile_output: false` skips the profile), `Connector.writtenAssetPath()` gives
  the catalogue path of what is written (below an S3 source's prefix), and `WriteOptions.profiling`
  carries the source's own profiling settings, which the writer's profile follows. A profiling
  failure never fails a write: `ROW_COUNT` then says `profiled=false` with `profile_error`. Replace
  also refuses tables a publication names (and, on SQL Server, replicated or change-tracked tables)
  and tables whose row-type array or owned sequences others use.
- **Parquet is written by Kilnmark's own encoder** (`ParquetEncoder`, from the Apache Parquet
  specification): Hardwood only reads, and parquet-java needs Hadoop (ADR 0015, amended). No new
  dependency: snappy-java and zstd-jni, already shipped for reading, are now compiled against,
  and the PostgreSQL driver the server ships is compiled against for `COPY`.
- **Profiling no longer fails on an empty piece of text** (a quoted `""` in a CSV file), on a
  column whose values are all NaN, or on 8-bit and unsigned integer, 256-bit decimal and large
  text columns, and reads dates, times and timestamps in any unit.
- **Pipelines can be saved, checked and run** (`/api/v1/pipelines`, `/api/v1/pipeline-runs`,
  `/api/v1/node-types`, `/api/v1/lineage`): every version is kept with who saved it and why, saves use
  `If-Match` on the latest version number (409 when someone saved since), and YAML is checked as
  it is written, with problems by line and each node's columns. Runs are queued as jobs of kind
  `pipeline`, run once by a worker, recorded node by node (how each node ended, rows in and out per
  port, peak memory, error) and can be cancelled. The node catalogue includes kiln-nodes'
  transforms. Viewers read; stewards edit and run.
- **Reading and writing sources in pipelines:** the `read` node reads a saved source's table or
  file; the `write` node writes to a source only when an admin has set its new **Allow writing**
  setting (`allow_writing`, off by default; stewards cannot change it). Allowed hosts and the
  role of whoever asked are checked again when a run starts, and no message carries a source's
  secret. A write node takes exactly the settings its source's writer declares, written beside its
  other settings and checked when the pipeline is, and nothing that says where or as whom to
  connect; the source's profiling settings (with key-like columns' sketches for relationship
  discovery) go with it, and a writer that could not profile leaves a note on the run. What a write
  wrote is registered in the catalogue from what the writer reports, with the container, name and
  key its connector gives, under the URN a scan would give it, with its lineage from the assets read
  (under the URNs the catalogue records them by), under a run of kind `pipeline` that source run lists do not show
  (migration V12; `kilnmark.pipelines.max-yaml-kb`, `memory-limit-mb`, `row-set-capacity` and
  `wait-for-target`). While a source allows writing, only admins change where it points, and a
  write's settings cannot redirect it. A pipeline runs once at a time, and one run at a time writes
  each table or file. Viewers see that a node failed but not its message, which may quote the data;
  for runs touching columns marked sensitive, only admins see it.
- **Pipelines page:** a list with Run now, and for each pipeline a YAML editor that checks as you
  type (choosing a problem moves to its line), Save with a message, a graph of the nodes with the
  columns leaving each, versions and runs with a per-node breakdown. The guide "Building and
  running a pipeline" (docs/guide/pipelines.md) walks through it.

## 0.0.3 (25 October 2026)

### What's new

- **Ask Kilnmark.** An **Ask** panel on every page (Alt+A) answers questions from the
  documentation, in a few sentences, with links to the exact sections it used. It works with a
  local model, and without one it lists the matching sections.
- **Documentation inside the app.** The user guides and this changelog open in the app, under
  **Documentation**, and the running version links to **What's new**.
- **Quality rules without the jargon.** Pick the table and column from lists, fill in plain fields
  instead of JSON, preview on the sample, and edit rules in place. Two people editing the same rule
  can no longer overwrite each other.
- **Find local models.** The AI models page finds Ollama, LM Studio and the bundled model servers
  on this machine or its container, lists their models, and shows whether each runs on the GPU.
  When something cannot be reached, it says why and what to change.
- **Looks and branding.** Light, Dark, High contrast or System themes, accent colours and density,
  chosen by each user. Consistent icons, letter tiles for each product, a compact connection
  gallery, and a folder where you can drop your own logos.
- **Sensitive data stays hidden.** Values of columns marked sensitive are hidden from everyone but
  admins wherever they could appear: previews, rule results, drift, documents, search and the MCP
  server. Search stays fast on large catalogues, however common the words.
- **Sign-in and roles.** Every user signs in with an API token, as a viewer, steward or admin.
  Admins manage tokens under **Settings**.
- **Adding connections is guided.** Pick a connector from a gallery grouped by category, test the
  connection, choose which schemas or folders to scan, and preview the data before scanning.
  Sources can be edited and deleted.
- **More kinds of source.** SQL Server, folders of files, and S3-compatible object storage. Excel
  sheets are read as tables.
- **Documents are read.** PDF, Word, Excel, PowerPoint and email files: their text is searchable
  and readable page by page on the asset page.
- **Browse and relationships.** A tree of sources, schemas, folders and tables, and schema
  diagrams showing how tables relate, from declared foreign keys and from the data itself.
- **Data quality.** Every scan scores each table in eight quality dimensions with no set-up. Value
  patterns, rules with previews, drift between scans, sample rows with sensitive columns masked,
  and an issue list for stewards. Contracts export in open formats (ODCS, OpenLineage).
- **AI, local first.** Add local or hosted models; each source decides what may leave the
  network. Search by meaning, ask questions in plain English, and connect outside AI assistants
  through the MCP server. Every model call is recorded.
- **For administrators.** A job queue, health checks, and interactive API documentation at
  `/api/docs`.

### Details

- **The assistant, step 1** (docs/design/assistant.md): an **Ask** panel in the header (Alt+A) on
  every page answers questions from the documentation that ships with the server, in a few
  sentences, citing sections as [n] links that open the in-app viewer at that section; the
  conversation stays while moving around and is cleared on sign-out. New AI feature `assistant`
  (chat), routed under AI models; without it, or when the model fails or the server allows only
  local models, the panel lists the best matching sections. `POST /api/v1/assistant/ask`, `GET
  /api/v1/assistant/sections`, `POST /api/v1/assistant/feedback` (**Yes, it helped** or **No**;
  kept for `kilnmark.ai.usage-retention`) and `GET /api/v1/assistant/feedback` (admins). At
  start-up the guides and changelog, now bundled in the server jar too, are split at `##` and
  `###` headings into sections with the viewer's anchors and stored in `knowledge_section`
  (migration V11, with `assistant_feedback` and `ai_usage.domains`); only changed sections are
  written again, and sections are embedded in the background when an embedding model is set. The
  changelog's Unreleased part is shown in the viewer but not answered from. Retrieval is
  PostgreSQL full-text search weighted by word rarity, plus nearest embeddings, with a boost for
  the page you are on. The prompt is sized to the model profile's context (4,096 tokens when it
  does not say), the conversation shortened newest first, with one smaller retry if the model
  still refuses it for length; the assistant never marks a shared chat model as down. The model
  gets the sections as data with no tools, and every citation is checked against the sections
  sent. Questions are limited to 2,000 characters (after Unicode normalisation) and 20 a minute
  per token. The MCP server gains `search_docs`. Guide: **Asking Kilnmark**. Evaluation set
  `tests/ai-eval/docs.json` (22 questions) runs in `:server:aiEval`. The UI build needs Node.js
  22.12 or later (or 20.19), as Vite does.
- **Find local models** on the AI models page (`POST /api/v1/ai/discover`, admins only): looks for
  Ollama and LM Studio on the machine running Kilnmark or its container
  (`host.containers.internal`, `host.docker.internal`, `localhost`) and for the bundled Compose
  model services, or checks one typed address, and lists each server's models with size and GPU
  use as the server reports it (Ollama: on GPU, split with the share on GPU, CPU only, not loaded
  yet). **Use this model** fills in the add-model form, and a Base URL that matches a found server
  offers its models to choose from. Failures are explained in plain words with what to do, such as
  setting `OLLAMA_HOST=0.0.0.0:11434` and keeping the port closed in the firewall. Only
  model-server paths are called, after the address rules, with short timeouts, no redirects and no
  response bodies returned. Kilnmark itself never looks for a GPU.
- **`host.containers.internal` and `host.docker.internal` are on the default
  `kilnmark.ai.local-hosts` when Kilnmark runs in a container** (ADR 0016 amendment), counting as
  local only while they resolve to loopback, private addresses or 169.254.1.2. The image sets
  `KILNMARK_IN_CONTAINER=true`.
- **Rootless Podman 5.3+ (pasta):** in a container, 169.254.1.2 is accepted as a model address
  only when reached through those two names; every other link-local and metadata address stays
  refused, including IPv4-mapped IPv6. Model addresses with an IPv4 address in an unusual form
  (leading zeros, hexadecimal, a single number, fewer than four parts) or a host name ending in a
  dot are refused with a 400.
- **Themes and appearance:** every signed-in user chooses, for themselves under **Settings**,
  **Appearance** (now open to every role), a theme (System, Light, Dark, High contrast), an
  accent colour (six presets, each with a shade per theme) and a density (Comfortable or Compact);
  kept in their browser. The top bar has a quick theme switch. Every colour is now a token in
  `ui/src/tokens.css`; `npm run build` checks every theme and accent against WCAG AA
  (`npm run check:contrast`). Schema diagrams, score and heat bars and badges follow the theme
  (diagram arrowheads now take the edge's colour); Windows contrast themes and reduced motion are
  respected.
- **Icons:** Tabler Icons (MIT, pinned 3.48.0, only the icons used are bundled) for categories,
  connector families, file formats (CSV, JSON, XML, Parquet, Excel, PDF, Word, email and more) and
  the browse tree, mapped by name in one module.
- **Connector tiles:** products show letters on a tint (`Pg`, `SQL`, `S3`), generic connectors an
  icon; in the gallery, the sources list, a source's page and the browse tree. Connector SPI:
  `monogram()` (default none); `GET /api/v1/connectors` gains `monogram`, and planned connectors
  in the catalogue gain `id` and `monogram`. No vendor logos are shipped.
- **Branding folder** (docs/guide/branding.md): `kilnmark.branding.dir` (default
  `/etc/kilnmark/branding`) holds a `manifest.json` with the organisation's product name and logo
  and logos, brand colours and labels per connector, for logos the customer is licensed to use.
  Served without sign-in at `GET /api/v1/branding` and `/api/v1/branding/files/{name}`: only
  listed SVG, PNG and WebP files up to `kilnmark.branding.max-file-kb` (256), content checked, no
  symbolic links or sub-folders, with `nosniff` and a Content-Security-Policy that stops scripts;
  the UI shows them only with `<img>`. Invalid entries are logged and fall back to the tile.
  `compose.yaml` has a commented read-only mount.
- **Third-party notices for the UI:** the build writes the licences of the UI's packages to
  `dist/ui/third-party-notices.txt` (docs/legal/third-party-ui.md).
- **Data quality** (docs/design/data-quality.md, build step 1). Profiling now keeps, over every
  row, each text column's value shapes (`SW1A 1AA` is `AA9A 9AA`; the top 20 with counts and the
  share outside them), detected formats (email, phone, UK postcode, NI number, IBAN, card numbers
  by Luhn, ISO dates and timestamps, numbers and booleans as text, JSON, padded values), blank
  values, and for numbers and dates quantiles every 5%, negatives, values beyond the quartile
  fences and dates after today. A few example values per shape are kept apart from the profile.
- **Samples:** every scan keeps a sample of whole rows (1,000 by default; source setting
  `sample_rows`), chosen by the hash of the primary key (or of the whole row), so the same rows
  are chosen every time; files use reservoir sampling seeded per asset. Samples are replaced on
  each scan and deleted with the asset; cells are cut at 200 characters and a sample holds at most
  `kilnmark.quality.sample-max-mb` (8 MiB). Source setting `keep_samples=false` keeps no sample
  rows, example values, most common values or failing rows (numeric statistics such as min, max
  and quantiles are still kept), and removes those already stored as soon as it is seen. Database
  tables larger than the source setting `table_sample_above_rows` are profiled from a
  `TABLESAMPLE`; the profile and any rule read through it say they are estimates, and the
  catalogue keeps the database's row estimate. Viewing samples, examples, failing rows and the
  values in measures, drift and exports needs the steward role; viewers get words, counts and
  shares. Columns a steward marks sensitive are masked (in samples, examples, failing rows, the
  asset's profile and exports) unless an admin unmasks them; only an admin can unmark one.
- **Automatic pre-analysis** after each scan, with no extra read: measures for completeness,
  uniqueness, validity, consistency, accuracy (estimated), timeliness and volume, each with its
  evidence; suggested regular expressions for a column's dominant shapes; stewards accept shapes
  with one click, which makes a validity rule.
- **Rules:** not null, unique, allowed values, matches pattern, in range, freshness, row count and
  its change, references and custom SQL, each tagged with a dimension, with warn and fail
  thresholds as a share or a count. Actions: status, a steward task (the new **Issues** list),
  and webhooks to URLs on the administrator's allow-list (`kilnmark.quality.webhook-allow-list`).
  They run after every scan and on demand through the job queue (jobs of kind `quality`): as SQL
  in PostgreSQL and SQL Server, each check in its own transaction (read-only on PostgreSQL) with a
  time limit (`kilnmark.quality.query-timeout`), and while streaming files (regular expressions
  bounded in time and length). Regular expressions are limited to a subset Java and PostgreSQL read
  alike. Results keep the query that lists the failing rows and a few failing rows; database error
  text is shown to stewards only. Rules preview against the sample before saving, and are
  recommended from the profile without AI.
- **Custom SQL rules are off by default** (`kilnmark.quality.custom-sql=true` turns them on) and
  only admins write, change or run them. They run only on PostgreSQL, never under a login that is
  a superuser or may read server files, run programs or signal other sessions, as one checked
  SELECT (no functions that touch files, locks, settings, sequences or other sessions) in a
  read-only transaction with a statement timeout. They can still read anything the source's login
  can see, beyond the source's schemas: give sources a read-only login.
- **Profile versions and drift:** a version per asset per scan; each is compared with the one
  before and with a chosen baseline for schema, volume, distribution (quantile shift, nulls,
  distinct values, most common values, shape shares) and freshness. Drift events have a severity,
  lower the scores and open steward tasks.
- **Scores** per column and dimension, rolled up to the asset and the source, with reasons.
- **Open formats** (ADR 0020): `GET /api/v1/quality/export/openlineage` and `/export/odcs` export
  an asset as an OpenLineage run event (schema, data quality metrics and assertions facets) and as
  an ODCS 3.1 data contract, validated against the published schemas in the tests.
- **Web UI:** a Quality section on asset pages (scores by dimension, value patterns with share
  bars and examples, accept a pattern, rules and results, drift, sample rows, recommended rules),
  a **Quality rules** page and an **Issues** page.
- **Rule form for stewards:** the form on the Quality rules page finds the asset by name (or takes
  a pasted URN) and offers the columns that suit the check, with their types. Each check has plain
  fields instead of JSON: allowed values one per line, a pattern typed as it is (with the profile's
  suggested expression one click away), minimum and maximum as numbers or dates, hours, rows, a
  percentage, and the referred-to asset found by name in the same source. The JSON parameters stay
  under "Advanced: edit as JSON", which can take over from the fields. Rules are edited in place
  (**Edit**, saved with `PUT /api/v1/quality/rules/{id}`), keeping their results and any dimension
  set through the API. A `PUT` with `If-Match` set to the rule's `updatedAt` is refused (409) when
  someone else changed the rule since, and the form sends it, so an edit never undoes another
  unseen. `GET /api/v1/search` takes `source` to keep to one source's assets. Values held back
  for a sensitive column (`{"hidden": true}`) are shown as hidden and sent back unchanged unless
  replaced; admins load rules unmasked, and recommendations without their values have no
  one-click Add.
- **Documentation in the app:** the guides in `docs/guide` and this changelog (as What's new) are
  bundled into the web UI at build time and shown under **Documentation**, so they work offline.
  Each section has its own address, `#/docs/<page>/<section>`, from its heading as GitHub makes
  anchors. The navigation shows the running version with a link to What's new. Markdown is
  rendered with marked 18.1.0 (MIT); HTML inside it is shown as text, never run.
- **Database:** migration V9 adds profile versions, samples, rules, accepted patterns, quality
  runs, results, measures, scores, drift events and issues, a `sensitive` flag on fields, and
  lets the job queue hold jobs of other kinds.
- **Sensitive columns, after review:** every value drawn from a column marked sensitive is masked
  for anyone who may not unmask it, stewards included, through one check per asset: measure
  evidence (range, fences, usual values, latest timestamp), drift detail, the allowed values and
  range bounds of its rules and recommendations, failing-rows queries and database error text, the
  ODCS export's rule values, the profile's histogram bounds, live previews of the catalogued table
  or file, and the MCP server's `get_asset`, which always masks. Shapes, counts and shares stay. Drift,
  rules, results and recommendations take `unmask=true` from an admin. Embedded text and prompts were
  checked: names, types and descriptions only, never values.
  - A rule on a sensitive column cannot be previewed by anyone who may not unmask it, and its results
    give counts only (no failing rows or query): failing rows would show which rows hold which values.
    Custom SQL's text, like its failing query, is hidden while the asset has any sensitive column. A
    freshness result on a sensitive timestamp says only whether the latest value is within the limit.
  - Saving a rule as shown keeps what was masked: parameters `{"hidden": true}` keep the stored ones
    (same column and type only, otherwise 400) and webhook `"(set)"` keeps the stored webhook; both
    markers are refused on new rules and previews.
  - Live previews find marks by the saved source and the connector's own path, and files and objects
    are read only under the path the listing gives them (no `a//b`, `./` or `../` spellings). For a
    source with marks, only admins may change how it is read (folders, patterns, header rows,
    formats) or preview such changes; stewards still preview new connections and edit descriptions.
  - A workbook's text holds its sheets' cells: when a sheet has a sensitive column, the workbook's
    text, find, chunks and search snippets are for admins only, and others' searches do not match
    its text at all; the same up the chain (a mail whose attached workbook's sheet is marked).
  - Marks are kept apart from the column rows a scan replaces (migration V10, `sensitive_column`), so a
    dropped or renamed column keeps its mark and the values stored under its name stay masked; a
    detected rename carries the mark to the new name.
  - One request-scoped `Sights` bean decides masking for every endpoint and MCP tool, and a test over
    the source tree fails if one shows values without it.
- **Freshness rules** count the values they could not read as a date or time, and say so.
- **Webhooks, after review:** a webhook path may not hold a semicolon, raw or encoded (`%3b`), so a
  segment such as `..;x`, which some servers read as `..` once they drop its parameters, cannot
  climb out of an allowed prefix. Percent-escapes in the path are allowed only for letters, digits,
  `-`, `_` and `~` (so no `%25`, `%00` or overlong UTF-8 such as `%c0%ae`), the URL must be printable
  ASCII, and a written-out default port (`:443`, `:80`) matches an allow-list entry without one.
  Checked when a rule is saved and again before each call; a call refused then is noted on the
  rule's result.
- **Custom SQL, after review:** refused under a login that may reach other databases (one that may
  execute dblink's functions or use a foreign server) and on any database with a user mapping or a
  foreign table, which a view owned by another role can reach, since a read-only transaction does
  not hold on the other side. The refusal names the fix (for dblink, a revoke of its own functions by
  name, never of a whole schema). dblink and postgres_fdw function names are refused in the SQL as
  well.
- **Allowed hosts, after review:** a saved source's hosts are checked against
  `kilnmark.sources.allowed-hosts` again just before a scan, quality run, preview, listing, test or
  on-demand read connects, not only when the source is saved, so a narrowed list applies at once;
  the job fails saying which host is no longer allowed.
- **Search, after review:** keyword search stays quick however common the words ("id", "the", a word
  in most documents). It gathers a fixed number of candidates through indexes (exact names, the
  nearest names in order of distance, matching words, alike field names, a sample of matching
  document chunks) and ranks only those, instead of ranking every match before the limit; snippets
  are chosen from a document's first matching chunks. Where more match alike than are taken, a
  bounded pool (four times as many) is taken through the index, then the first of it by URN, so
  matches that sit at the end of the table cannot make a branch walk it; matches are found through
  the indexes only. A search kept to sources is kept to them in every branch, before any limit.
  Exact names now always come first, then the closest whole names. Migration V10 adds GiST trigram
  indexes on asset names and expression indexes for exact names, has the asset words index take
  entries at once (no pending list), and adds `sensitive_column`. Developer databases that applied
  an earlier draft of V10 (`V10__bounded_search`) need `flyway repair` or a fresh database.

- **Sign-in with API tokens:** every API call needs a token (`Authorization: Bearer km_...`)
  with a role: viewer (read), steward (also start scans) or admin (also add sources and manage
  tokens). Public without a token: `GET /api/v1/info`, `/q/health` and the API description
  `/q/openapi`. Tokens are shown once and stored only as SHA-256 hashes; they can expire and be
  revoked at once. The first start writes a 24-hour admin token to `admin-token` next to the
  secret key (use it to create a permanent one), or takes one from `KILNMARK_ADMIN_TOKEN`.
  `KILNMARK_AUTH_MODE=off` turns sign-in off for a lab, and only on a loopback address.
- **Web UI:** a sign-in page, sign-out, and an **API tokens** page for admins (create with a
  role and expiry, copy the new token once, revoke). Viewers no longer see controls they cannot
  use. Pages no longer scroll sideways on phones.
- **Job queue:** scans are queued in PostgreSQL and run by any process with the worker role, so
  api and worker can run apart. Workers wake on `LISTEN/NOTIFY` and also poll, run
  `kilnmark.worker.concurrency` scans at a time (default 2), never scan one source twice at
  once (asking again returns the scan in progress), recover scans left by a worker that
  stopped, and finish running scans before shutting down (Compose now allows 60 s for this).
- **Database:** migration V4 adds the `api_token` and `job` tables, and marks scans left waiting
  or running by an earlier version as failed, so they can be started again.
- **Sources:** SQL Server (kind `sqlserver`): tables and views in user schemas,
  with `MS_Description` descriptions and primary keys; every SQL Server type read, with
  `datetimeoffset` as a UTC timestamp, `money` as an exact decimal, and geography, geometry and
  hierarchyid as text. Reads take no shared locks by default (setting `isolation`:
  `read-uncommitted`, `snapshot` or `read-committed`). Uses Microsoft's JDBC driver (MIT).
- **Sources:** a JDBC URL holding a password is refused; enter it as the source's password, which
  is encrypted.
- **Fixed:** a schema whose name holds `_` (such as `a_b`) no longer picks up the columns of a
  same-named table in a schema it matches as a pattern (`axb`); PostgreSQL schemas named `sys` or
  `guest` are scanned.
- **Fixed:** a read stopped early by a failing node now ends at once instead of reading the rest
  of the table.
- **Files and object storage:** two new kinds of source. `files` scans a folder the server can
  read; `s3` scans a bucket on AWS or any S3-compatible store (VersityGW, MinIO, Ceph). CSV, TSV,
  JSON, JSON lines and Parquet files are catalogued with their fields and profiled like tables;
  every other file is recorded as a document (size and time) without being read.
- **Administrators decide what sources may reach:** folder sources only inside the folders listed
  in `kilnmark.files.roots` (none by default, so folder sources are off until set); S3 sources need
  their own access key unless `kilnmark.s3.allow-default-credentials` lets sources on AWS use the
  server's identity.
- CSV: delimiter (including Excel's `sep=` line), quote and encoding found automatically (UTF-8,
  or windows-1252 when a file is not valid UTF-8; a source's `encoding` setting overrides); column
  types (integer, decimal, boolean, date, time, timestamp) inferred from the first 1,000 rows. A
  value that does not parse later in the file is read as null and counted (`unparsed`), never a
  failed scan.
- Objects are streamed: a catalogue reads only the first megabyte of text files and the footer of
  Parquet files, and a sampled read stops the transfer when it has enough rows.
- A corrupt, too deeply nested or unsupported file (a Parquet codec such as Brotli) is recorded as
  not readable with the reason, and the scan goes on.
- Parquet is read without Hadoop, through Hardwood, with one shared, bounded decoding pool
  (`kilnmark.files.parquet-threads`, `kilnmark.files.parquet-chunk-mb`); nested values become JSON
  text. S3 through the AWS SDK's S3 module only (ADR 0015). 19.3 MB of jars added.
- **Fixed:** profile statistics above about 9.2 trillion are no longer capped.
- **AI models (ADR 0016):** admins add models under **AI models**: local ones (llama.cpp's
  llama-server, vLLM, Ollama or any OpenAI-compatible server, marked local) and hosted ones with an
  API key (OpenAI, Azure OpenAI, Anthropic, Google Gemini). No restart needed. API keys are
  encrypted and never shown again (only their last four characters), and are asked for again when
  a model's address moves to another host. Link-local and cloud metadata addresses are refused. **Test** says in plain words
  whether a model works and what to fix. Each feature (search by meaning, asking in plain English,
  describe, classify) uses the model an admin chooses, or is off and works without AI.
- **What may leave the network:** every model call passes one check. A source's AI rule
  (`ai_egress`: `local_only` by default, `allowed` or `none`) decides which models may see it, and
  hosted models can be forbidden for the whole server by an admin, or by whoever runs it
  (`KILNMARK_AI_HOSTED_MODELS=forbidden`); then a model counts as local only if its address is on
  a local network (`KILNMARK_AI_LOCAL_HOSTS`). A refused call never reaches the model and says why.
- **AI usage:** every model call is recorded (feature, model, source, tokens, time, outcome, who
  asked), refused ones too, without prompt or answer text; admins see totals by day and model.
  Records are kept 90 days (`kilnmark.ai.usage-retention`).
- **Search by meaning:** after each scan, assets (names, descriptions, fields and types, never
  values) are embedded with the chosen embedding model into pgvector, skipping unchanged ones; the
  scan's run says how many. Search combines words and meaning (reciprocal rank fusion), shows how
  each result matched, and falls back to words, saying why (without naming the model). A search
  waits about 10 seconds at most for the model, and skips one that failed in the last 30 seconds.
- **Ask a question:** the search page has an "Ask a question" mode. A chat model turns the
  question into a search (words, kinds, sources, field names), which is shown with the results,
  including anything it could not use. Without a chat model the question is searched as typed.
- **MCP server:** outside AI assistants can search and read the catalogue at `/mcp` (Streamable
  HTTP) with a viewer API token: `search_assets`, `get_asset`, `list_sources`. Assistants see
  sources whose AI rule is `allowed`; tokens an admin marks as used by a local assistant also see
  `local_only` ones; `none` sources are never shown.
- **Compose:** profiles `ai` (CPU) and `ai-cuda` (NVIDIA GPU) run llama-server with a small chat
  model and an embedding model; `ai-download` fetches them into the `models` volume from fixed
  revisions, checked by SHA-256. Images pinned by tag and digest.
- **AI evaluation:** `./gradlew :server:aiEval` scores a running server's search (recall, MRR)
  and question answering against a synthetic golden set over the lab, and fails if a score falls
  below the baseline; not part of the build.
- **Database:** migration V6 adds `model_profile`, `ai_route`, `ai_setting`, `ai_usage` and
  `asset_embedding`, and `api_token.local_assistant`. 2.7 MB of jars added (LangChain4j provider modules, Quarkus MCP server), all
  Apache-2.0.

- **Managing sources:** stewards can edit a source (display name, settings, password) and delete
  it. A source's name is its fixed ID, part of every asset reference, so it cannot change; the new
  display name can. A blank password keeps the stored one, unless the URL, endpoint or user name
  changes. Delete is refused while a scan waits or runs, and otherwise removes the source's assets,
  fields, profiles, runs and graph nodes in one transaction.
- **Settings every source has:** `description`, `ai_egress` (`local_only` by default, `allowed` or
  `none`), and `include` / `exclude` glob patterns (ignoring case) that choose tables or files for
  every kind of source. The folder connector's own `include` setting is now this common one, and
  now ignores case.
- **Test connection** for a saved source or a form not yet saved, within 15 seconds, in plain words
  and never repeating the password.
- **Choose what to scan from a list:** schemas (databases) or top-level folders (folders and
  buckets) with how many tables or files each holds, from metadata or a listing. Before a scan the
  UI warns if it would find nothing.
- **Bulk import, version 2:** run the same file again to create, update or leave sources unchanged
  (reported per row); `onError=skip` applies the right rows; `host`, `port` and `database` columns
  build the URL; `display_name` and the common settings as columns; `remapHosts=true` rewrites
  host names with the administrator's `kilnmark.sources.host-map` (for example localhost to
  host.docker.internal); `test=true` and `scan=true` test each source and queue scans, with
  progress the UI shows. Files in the earlier format still import. `lab/sources.csv` registers
  the lab.
- **Source page** in the web UI: settings (never the password), what it scans, run history with
  durations, attempts and errors, and edit, test, scan and delete.
- **Readiness and liveness:** `/q/health/ready` (database, migrations, worker) and
  `/q/health/live`, public and saying only UP or DOWN. The Compose service has a health check.
- **Security:** stewards' checks connect from the server, so an administrator can limit the hosts
  sources may reach with `kilnmark.sources.allowed-hosts`. JDBC URL properties that make a driver
  use the server's own identity or files (`integratedSecurity`, `authentication=ActiveDirectory...`,
  `sslkey`, `sslfactory`, `socketFactory`, `loggerFile`, any `...File`, `...Path`, `...Factory`,
  `...Class`) are refused. Checks give their place back only when they have really ended.
- **Security:** host names come from the database driver's own URL parser; URL properties naming
  another host (`PGHOST`, `server`, `address`...), loosening time limits (`socketTimeout`,
  `loginTimeout`...), using `pg_service.conf` (`service`), or any SQL Server `authentication` but
  `SqlPassword` are refused, whatever braces, quotes or encoding they are written with.
- **Changed:** bulk import's row statuses are now `created`, `updated`, `unchanged` and `failed`
  (they were `ok`, `created` and `error`), and a row naming an existing source updates it instead
  of failing. An update changes only the columns in the file.
- **Changed:** `include` and `exclude` patterns without a `.` match database table names only (with
  a `.`, `schema.table`); without a `/`, file names only.
- **Add a connection:** a gallery of connectors by category, with tabs, search and what each can
  do (discover, read; write and stream to come), including planned connectors and their wave
  (`kilnmark.connectors.show-planned`). Choosing one opens a four-step panel beside it: connect
  (host, port and database build a database URL), test, choose what to scan, save or save and
  scan. Edit uses the same panel. Connectors declare their category, family, capabilities,
  formats, icon and docs section, and a driver descriptor (all bundled today).
- **Preview** any table or file before scanning: the first 100 rows (up to 1,000), read live and
  never stored. Files show what was detected; new file settings `delimiter` and `header` correct
  it for previews and scans.
- **Sources list:** filter and group by category; file sources show the formats found, with counts.
- **Database:** migration V5 adds `data_source.display_name` and the import batch tables.
- **Fixed:** an S3 source with its own access key can be added through the API, the form and bulk
  import; the check that the secret goes with the key no longer misses the secret.

- **Browse:** a new **Browse** page shows the catalogue as a tree: source, then schema, folder or
  bucket prefix, then tables, views and files, with counts, kind labels and icons. Children load
  as a node opens, a hundred at a time; a filter box narrows the tree by name; the tree follows
  the ARIA tree pattern for the keyboard. Choosing a schema lists what it holds and draws it.
- **Schema diagrams:** tables as boxes with their columns (keys marked PK and FK) and lines for
  relationships: declared keys solid, proposals dashed with their confidence, rejected ones hidden.
  Pan, zoom, fit, automatic layout; at most 60 tables, with a filter or one table and its
  neighbours (one or two steps, across schemas and sources) for larger schemas. The same
  relationships are listed in a table beside it. Uses @xyflow/react and @dagrejs/dagre (MIT,
  ADR 0019), loaded only when a diagram is shown.
- **Relationships:** scans of PostgreSQL and SQL Server record declared foreign keys, including
  multi-column keys in key order and keys between schemas. After every scan Kilnmark proposes
  joins nobody declared, within and across sources, from column names, types, unique targets and
  value containment estimated from stored HyperLogLog sketches; on the lab,
  `billing.transactions.customer_id` to `crm.customers`. Stewards accept or reject proposals on
  the new **Relationships** page, the asset page or below a diagram; decisions survive rescans.
- **Asset page:** a summary with source and schema links, the asset's relationships in and out,
  and per-column statistics: open a column for nulls, distinct values, quartiles, mean, a small
  histogram, lengths and most common values.
- **API:** `GET /api/v1/browse/sources`, `.../browse/sources/{name}/containers`,
  `.../browse/sources/{name}/assets?container=`, `GET /api/v1/diagram` (a container, an asset and
  its neighbours, or chosen assets), `GET /api/v1/relationships`, `GET /api/v1/relationships/{id}`,
  and `POST /api/v1/relationships/{id}/accept` or `/reject` (steward).
- **Profiles:** numbers, dates and times gain a ten-bin histogram; key-like columns (identifier
  names and single-column primary keys) keep their distinct-value sketch (about 8 KiB) beside the
  profile for discovery.
- **Database:** migration V8 adds the `relationship` table and `profile.hll`.
- **Connectors:** `Connector.catalogWithRelationships` (a default method) returns the catalogue
  with the relationships a source declares, and warnings; `AssetInfo` is unchanged. Foreign keys
  that cannot be read leave the scan going, with a warning in its detail.

- Settings: API tokens, AI models and AI usage move under one Settings section, admins only.
- Interactive API documentation at `/api/docs`, served by the server itself (works offline), from
  the OpenAPI document at `/q/openapi`. Calls made from it need an API token (Authorize button).

- **Documents are read, not just listed:** the `files` and `s3` sources read the text of PDF,
  Word, Excel, PowerPoint (current and 97-2003 formats), OpenDocument, EPUB, RTF, HTML, XML, mail
  (`.eml`, `.msg`), plain text and Markdown files, with Apache Tika. Each document gets a profile:
  content type, pages, words, characters, language, title, author, created and modified dates, and
  for S3 the object's owner and tags. A mail message shows from, to, cc, date and subject, and each
  attachment is an asset of its own. No OCR yet (ADR 0017).
- **Excel sheets as tables:** each sheet with a header row is a table inside its workbook, read with
  POI's streaming reader and profiled like any table, with typed columns. Other sheets (notes) are
  recorded with the reason.
- **Search finds words in documents,** ranked below name matches, with a highlighted snippet
  (`snippet` in search results, text with highlight positions, never markup).
- **Reading documents:** the asset page shows a document's profile and its text page by page (slide
  by slide, sheet by sheet), with find; `GET /api/v1/documents/text`, `/find` and `/chunks`.
- **Documents are untrusted input:** limits on size, characters, time (a document still being read
  after 60 seconds is abandoned), nesting depth and zip bombs; one bad document is marked not
  readable with the reason and the scan goes on. Settings `kilnmark.documents.*`.
- **Sources:** setting `keep_text` (default `true`); `false` keeps only documents' properties, and
  their text is read from the source again when someone opens it.
- **Database:** migration V7 adds `document` (profiles), `document_chunk` (text in chunks, searched
  through a `tsvector`) and `asset.parent_urn` (sheets and attachments inside their file), all
  deleted with their asset. The text of documents no longer in their source is deleted on the scan
  that notices.
- **Lab:** synthetic documents (PDF, Word, Excel, mail, Markdown) in the share and the bucket, made
  by `lab/make-documents.py`. 27.4 MB of jars added (ADR 0017).
- **Documents, after review:** Office files are checked for parts that really expand past
  `kilnmark.documents.max-part-mb` (64 MB) before any parser opens them, and sheet rows stop at
  16,384 cells. Healthy documents wait for a reader instead of failing; a read that fails for a
  passing reason (busy, out of time, stopped, source not reachable) keeps what the last scan stored.
  Sheet and attachment names lose control characters. `%` and `#` in file names are escaped in URNs,
  so a file cannot pass for a sheet or attachment. `keep_text=false` now hides text too, unless
  `read_text_on_demand=true` lets stewards read it from the source. Find copes with letters whose case
  changes their length (Turkish İ). Persian, Urdu, Catalan, Marathi and Nepali are no longer
  mistaken for their neighbours.

## 0.0.2 (24 October 2026)

The first working slice: point Kilnmark at PostgreSQL databases, scan them, and search what it
found.

- **Sources:** register PostgreSQL databases one at a time, or many at once from a CSV or JSON
  file (checked row by row, all or nothing, with a dry run). Passwords are encrypted with
  AES-256-GCM before they reach the database; the key lives outside it. Bulk files can refer to
  passwords as `env:VARIABLE` or `secret:NAME` instead of holding them.
- **Scans:** every table and view, with columns, types, comments and primary keys; each one read
  through the Kiln engine and profiled as it streams (rows, nulls, distinct values, ranges,
  lengths, most common values). Two tables at a time; one failing does not stop the scan. Assets
  that disappear from a source are marked removed.
- **Search:** tables, views and columns by name or description, tolerant of partial and misspelt
  words.
- **Web UI:** search from anywhere (Ctrl+K), sources with scan status, asset pages with every
  column's profile. WCAG 2.2 AA automated checks pass in light and dark themes.
- **Kiln engine:** nodes on virtual threads joined by bounded Arrow row sets, per-node memory
  accounting, back-pressure, clean failure and leak reporting.
- **Platform:** Java 25 (ADR 0013), Business Source License 1.1 (ADR 0008), sign-in plan
  (ADR 0014). No sign-in yet: lab use only.

## 0.0.1 (24 October 2026)

Scaffolding: build, CI, containers, reference lab and decision records.
