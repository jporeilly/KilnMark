# Installing Kilnmark

Two ways to install, from a clean machine. Details, options and troubleshooting are in the
[development guide](docs/development.md).

- **A. A release**, as a customer runs Kilnmark: the product only, on Linux or a laptop.
- **B. From source**, to develop, to build a release, or to try Kilnmark with the reference lab
  and the [samples](samples/README.md).

## What to install first

| Tool | Version | For |
| --- | --- | --- |
| Podman (Podman Desktop with a WSL 2 machine on Windows), or Docker | Podman 5 or later | PostgreSQL and the server, in containers: A and B |
| Git | any recent | B |
| Java | 17 or later on the `PATH` | B: it starts the Gradle wrapper, which downloads the JDK 25 the build needs |
| Node.js | 22.12 or later | B: the web UI |
| Python 3 | any recent | B on macOS or Linux: `lab/lab.sh samples` and `pipelines` |

On Windows and macOS, start the Podman machine once per boot: `podman machine start`.

## A. Install a release

1. Get the release folder, `kilnmark-<version>`: built with `./gradlew release` (B, step 5), it
   lands in `dist/release/kilnmark-<version>`. Copy that folder to the machine.
2. In the folder, make the settings file from its example: `cp .env.example .env` (and
   `chmod 600 .env` on Linux). Fill in the two secrets; nothing ships with a default, and
   Kilnmark refuses to start without them:
   - `KILNMARK_DB_PASSWORD`: a long random value, for example `openssl rand -base64 32`.
   - `KILNMARK_ADMIN_TOKEN`: `km_` and at least 43 letters, digits, `-` or `_`, for example
     `km_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')`.
3. Start it: `podman compose up -d --build` (or `docker compose up -d --build`). This builds the
   database and server images and starts them.
4. Open http://localhost:8780 and sign in with the admin token from `.env`. Under **API tokens**,
   make a permanent admin token for yourself and keep it safe.
5. Add a source under **Sources**: nothing is scanned until you do.
6. To try the samples, small pipelines that show one idea each, choose **Add samples** on the
   **Pipelines** page. **Add the starter pack** creates Kilnmark's sample tables in a database and
   schema you pick, and adds every sample reading them; it needs a database and a file source that
   allow writing (an admin turns that on under **Sources**). Or pick your own sources for the
   placeholders the samples use (`crm-db`, `shop-db`, `files`). See
   [samples/README.md](samples/README.md). The samples are also in the release's `samples` folder.

### Check a release

A release from GitHub comes as `kilnmark-<version>.tar.gz` or `.zip`, two SBOMs, `SHA256SUMS`, and
a `.sigstore.json` signature beside each. Check the one you downloaded before unpacking it, with
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/) 3.0 or later (earlier versions
cannot read the signatures) and Kilnmark's public key
(`cosign.pub`, from the release's owner or `config/release/cosign.pub` in the repository):

```
cosign verify-blob --key cosign.pub --insecure-ignore-tlog=true \
  --bundle kilnmark-<version>.tar.gz.sigstore.json kilnmark-<version>.tar.gz
```

`Verified OK` means the file is the one Kilnmark signed. (The signatures are deliberately kept off
Sigstore's public log, which is what `--insecure-ignore-tlog` acknowledges; the key is the proof.)
`sha256sum -c SHA256SUMS --ignore-missing` checks the files against the signed list of checksums.
The SBOMs, `kilnmark-<version>-server.cdx.json` and `-ui.cdx.json` (also in the release's `sbom`
folder), list every library and package it contains, for your own scanners.

### Kilnmark's own database

Kilnmark reaches its database over TLS only, and does not start otherwise
([ADR 0032](docs/adr/0032-tls-to-kilnmarks-own-database.md)).

- **The bundled PostgreSQL** makes its own certificate on its first start and speaks TLS;
  `compose.yaml` connects with `sslmode=require`. Nothing to do. To have the certificate verified,
  put one from your authority as `server.crt` and `server.key` in `/var/lib/postgresql/tls` in its
  volume (owned by uid 999, the key readable by its owner only, `chmod 600`), and use `verify-full`
  as below.
- **Your own PostgreSQL** (a managed or shared server): turn TLS on in it, and set
  `QUARKUS_DATASOURCE_JDBC_URL` to its address with `?sslmode=verify-full&sslrootcert=/path/to/ca.pem`
  (mount the CA certificate into the container). `sslmode=require` also works, but encrypts without
  checking who answers.

If the server stops with "does not demand TLS" or "not encrypted", its log says which and what to
change.

### Sizing the workers

Scans, quality checks and pipeline runs are jobs that wait in a queue in Kilnmark's database. A
process with the worker role takes them a few at a time. Each job it runs holds one of its
**slots**: `KILNMARK_WORKER_CONCURRENCY` (2 by default). Flows hold no slot, since they only decide
which pipeline runs next; a worker watches up to `KILNMARK_WORKER_FLOWS` of them at once (8 by
default). A job or flow beyond these waits in the queue until there is room
([ADR 0040](docs/adr/0040-parallel-runs-and-limits.md)).

To run more at once, raise the slots, or add a second worker (below). Two limits keep that from
overwhelming what Kilnmark reads and writes: a source's **Most runs at once**, set on the source,
which caps the runs using it whichever worker runs them
([Sources that take a few runs at a time](docs/guide/pipelines.md#sources-that-take-a-few-runs-at-a-time)),
and the database connections below.

More running at once needs more connections to Kilnmark's own database. Each running job holds one
for its lease and uses one or two more while it works; each watched flow holds one. So each
Kilnmark process needs a pool (`QUARKUS_DATASOURCE_JDBC_MAX_SIZE`, 20 by default) of at least

```
concurrency × 3 + flows + 5
```

where the 5 is for the API and the scheduler. With the defaults that is 2 × 3 + 8 + 5 = 19. With 8
slots and 8 flows it is 37: set `QUARKUS_DATASOURCE_JDBC_MAX_SIZE=40`. PostgreSQL's
`max_connections` (100 in the bundled database) must be at least the sum of the pools of every
Kilnmark process, plus what backups and your own tools use.

The sources themselves see one connection, or a few, per run that reads or writes them; set their
**Most runs at once** where their administrators ask for a cap.

### A second worker

Any number of Kilnmark processes can share one database: the queue hands each job to one worker
only, and a worker that stops has its jobs taken up by another. To add capacity, run a second
Kilnmark with the worker role only, for example as another service in `compose.yaml`, copied from
the `kilnmark` service with:

- `KILNMARK_ROLES: worker`, so it takes jobs but serves no one (keep `api` and `scheduler` on the
  first);
- the same database settings (`QUARKUS_DATASOURCE_*`);
- the same key for stored source passwords: mount the same `keys` volume on the same machine, or on
  another machine set `KILNMARK_SECRET_KEY` on both to the key from the first's
  `/var/lib/kilnmark/secret.key`. A worker with another key cannot open the sources' passwords, and
  says so in its log at start;
- the same files mounted at `KILNMARK_FILES_ROOTS`, and the same drivers (`drivers/` in the keys
  volume), so it can reach every source the first can;
- no published port, and the same `stop_grace_period`.

Size its pool as above, and count its pool in `max_connections`.

### HTTPS

Kilnmark should be reached over HTTPS only. Either:

- **A proxy in front ends TLS** (your load balancer or reverse proxy). Leave Kilnmark on plain HTTP
  behind it, reachable only from the proxy, and set `KILNMARK_HTTP_HSTS=always` in `.env` so
  browsers are told to keep to HTTPS.
- **Kilnmark ends TLS itself.** Put the certificate (with its chain) and its private key, as PEM
  files, in a `tls` folder beside `compose.yaml`; uncomment the `./tls` volume in `compose.yaml`;
  and set in `.env`:

  ```
  KILNMARK_TLS_CERT_FILE=/etc/kilnmark/tls/server.crt
  KILNMARK_TLS_KEY_FILE=/etc/kilnmark/tls/server.key
  KILNMARK_HTTP_PLAIN=redirect
  ```

  Kilnmark then answers HTTPS on port 8443 (`KILNMARK_HTTPS_PORT` changes the published port; use
  443 in production, as browsers that have seen HTTPS keep to it on every port), with TLS 1.3 or
  1.2 only, and sends plain HTTP on 8780 to HTTPS (`disabled` refuses it instead). Only the
  container's own health check still uses plain HTTP: a browser or proxy on the host counts as
  another machine. Open `https://<host>:8443` (or your HTTPS port) from then on.

Either way, every response carries the security headers in
[ADR 0031](docs/adr/0031-https-and-security-headers.md): a Content-Security-Policy allowing only
Kilnmark's own files, no framing by other sites, no referrer, and HSTS over HTTPS.

### Sign in with your identity provider

People can sign in with your organisation's identity provider over OpenID Connect (Microsoft Entra
ID, Okta, Ping, ADFS, Keycloak and others), with its second factor
([ADR 0036](docs/adr/0036-sign-in-with-the-identity-provider.md)). API tokens keep working for
scripts.

1. In the provider, register Kilnmark as a web application. Its redirect URI is
   `https://<Kilnmark's address>/api/v1/auth/login`. Have the provider put the person's groups in
   the ID token's `groups` claim, and require MFA for the application.
2. In `.env`, set `KILNMARK_OIDC_ENABLED=true`, `KILNMARK_OIDC_ISSUER` (the provider's issuer URL),
   `KILNMARK_OIDC_CLIENT_ID`, `KILNMARK_OIDC_CLIENT_SECRET` and, for the button,
   `KILNMARK_OIDC_PROVIDER_NAME`.
3. Say which groups give which role, comma-separated, exactly as the provider names them:
   `KILNMARK_OIDC_VIEWER_GROUPS`, `..._STEWARD_GROUPS`, `..._DESIGNER_GROUPS`, `..._ADMIN_GROUPS`.
   Designers in `KILNMARK_OIDC_UNMASK_GROUPS` see values of columns marked sensitive. A person in
   none of them signs in with no role, and is told so.
4. Restart: `podman compose up -d`. The sign-in page now shows **Sign in with** your provider.

Kilnmark requires a second factor: the provider must say so in the ID token's `amr` (`mfa`, or two
methods of two kinds, such as a password and a one-time code; one method alone, or two of one kind,
is not enough), or in its `acr` with a value you
list in `KILNMARK_OIDC_MFA_ACR`. A sign-in that shows none gets no role. A sign-in lasts at most 8
hours from the person's last sign-in at the provider (`KILNMARK_OIDC_MAX_SESSION`); the ID token
must say when that was (`auth_time`), which every provider can send. Every sign-in, and every one
given no role, is in **Settings**, **Audit log**, under `sso:` and the person's name.

Kilnmark must be reached over HTTPS for this (see HTTPS above): its session cookies are sent over
HTTPS only. Behind a proxy that ends TLS, also set `KILNMARK_OIDC_BEHIND_TLS_PROXY=true`.

### Review who has access

**Settings**, **Access review** lists every API token (role, whether it sees sensitive values,
expiry, last use) and everyone who signs in with your identity provider, with the role of their last
sign-in. **Download CSV** keeps it as evidence for your auditors; each download is in the audit log.

To limit how long tokens last, set `KILNMARK_TOKEN_MAX_LIFETIME` in `.env`, for example `90d`, and
restart. New tokens must then expire within it, and **tokens already older than it stop working**:
check the review first, and replace any a script still needs. The admin token from
`KILNMARK_ADMIN_TOKEN` is not limited ([ADR 0037](docs/adr/0037-token-lifetime-and-access-review.md)).

### Two people for sensitive values

Designer tokens can see values of columns marked sensitive. To make that take two people, set up
sign-in with your identity provider (above), then set `KILNMARK_UNMASK_APPROVAL=true` in `.env` and
restart. A designer token asked to see them can then be made only by someone signed in with the
provider, and is made without; another person, also signed in with it, approves it under **API
tokens**. Tokens cannot ask or approve, because a token names no one, and the provider's fixed id
tells the two people apart even if one renames themselves. Each step is in the audit log, and the
access review shows who approved each grant. For people's own sign-ins, the second person is whoever
approves membership of the unmask group at the provider
([ADR 0038](docs/adr/0038-a-second-admin-approves-the-unmask-grant.md)).

- Use one issuer for your own organisation: never a multi-tenant endpoint or
  `QUARKUS_OIDC_TOKEN_ISSUER=any`, which would let one person arrive as two.
- Set it on every Kilnmark api process alike.
- Designer tokens given sensitive values before you turned it on keep them: find them in the access
  review (no "approved by") and revoke any that should not.

### Back up and restore

From the release folder, while Kilnmark runs, `./backup.sh /path/to/backups` writes a new folder
holding the database and the keys that open stored source passwords. Keep the keys file apart from
the database dumps. `./restore.sh /path/to/backups/kilnmark-<time>` puts both back on this or a new
machine. Schedule a daily backup, back up before every upgrade, and test a restore every quarter:
the runbook, with what to check after a restore, is
[Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md)
([ADR 0039](docs/adr/0039-backup-and-restore.md)).

## B. From source, with the reference lab and the samples

1. `git clone https://github.com/jporeilly/KilnMark.git` and `cd KilnMark`.
2. Build and start Kilnmark:
   - **Windows:** in PowerShell, `.\build.ps1 -Lab`. It checks Node.js, builds, starts PostgreSQL
     and the server, starts every demo source of the reference lab, and waits until Kilnmark answers.
   - **macOS or Linux:** `./gradlew :server:quarkusBuild`, then `podman compose up --build`, then
     `./lab/lab.sh start all`.
   - For live reload while developing, run PostgreSQL in its container and the server with
     `./gradlew :server:quarkusDev` instead.
3. Sign in at http://localhost:8780. The first admin token lasts 24 hours; read it with
   `podman compose exec kilnmark cat /var/lib/kilnmark/admin-token` (with `quarkusDev`, it is in
   `~/.kilnmark/admin-token`). Make a permanent one under **API tokens**, and keep it in
   `KILNMARK_ADMIN_TOKEN` (PowerShell: `$env:KILNMARK_ADMIN_TOKEN = "km_..."`).
4. Register the lab: **Sources > Import sources from a file**, choose `lab/sources.csv`, tick
   **Rewrite host names for this server**, **Test each connection** and **Queue a scan**, and press
   **Import**. Wait until the scans of `lab-db`, `lab-bucket` and `lab-mariadb` have finished.
5. Add the examples: `./lab/lab.sh samples` (Windows: `.\lab\lab.ps1 samples`) adds the
   [samples](samples/README.md), sorted by what they show, with their placeholders mapped to the lab's
   sources, and `./lab/lab.sh pipelines` the lab's demo pipelines. Open **Pipelines**, pick one, and
   choose **Run now**.
6. To make a release for A: `./gradlew release` (Windows: `gradlew.bat release`). The release holds
   the product only, never the lab; the samples ship, naming their sources by placeholders.

## Check it works

- `GET http://localhost:8780/q/health/ready` answers 200 once the server and its database are up.
- `./gradlew build` compiles and runs every test; the server's tests need Podman or Docker running.
