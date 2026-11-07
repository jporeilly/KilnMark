# Installing Kilnmark

Two ways to install, from a clean machine. Details, options and troubleshooting are in the
[development guide](docs/development.md).

- **A. A release**, as a customer runs Kilnmark: the product only, on Linux or a laptop.
- **B. From source**, to develop, to build a release, or to try Kilnmark with the reference lab
  and the [samples](samples/README.md).

## What to install first

| Tool | Version | For |
| --- | --- | --- |
| Podman (Podman Desktop with a WSL 2 machine on Windows), or Docker | Podman 5 or later; Docker with Compose v2 | PostgreSQL and the server, in containers: A and B |
| cosign | 3.0 or later | A: checking the download and the images (`winget install Sigstore.Cosign` on Windows) |
| Git | any recent | B |
| Java | 17 or later on the `PATH` | B: it starts the Gradle wrapper, which downloads the JDK 25 the build needs |
| Node.js | 22.12 or later | B: the web UI |
| Python 3 | any recent | B on macOS or Linux: `lab/lab.sh samples` and `pipelines` |

Kilnmark wants 4 GB of memory for its containers (5 GB with Keycloak) and 10 GB of disk for images,
data and backups. On Windows, give the Podman machine or Docker Desktop that much; Podman starts its
machine itself when the control script needs it.

## A. Install a release

A release is `kilnmark-<version>.tar.gz` (or `.zip`). It ships with
`kilnmark-<version>-images.tar.gz`, the images for a machine without internet access, and a
`.sigstore.json` signature beside each file. The images are also at `ghcr.io/jporeilly`.

1. **Check the download** before unpacking it (below, "Check a release").
2. **On Linux, prepare the machine once**, from the unpacked folder:
   `sudo ./kilnmark.sh prepare`. It:
   - makes the `kilnmark` account that runs Kilnmark (`--user` names another). The account has no
     password and you reach it with `sudo -iu kilnmark`;
   - gives the account the container engine: rootless Podman's id ranges and lingering, so its
     containers start at boot, or the `docker` group with `--engine docker` (that group can act as
     root on the machine: prefer rootless Podman where that matters);
   - makes `/opt/kilnmark` (`--root` for another) the account's own, and places the release in it.
     It makes a new folder only, and refuses one that exists; later versions arrive by `upgrade`.

   Everything after this runs as that account, never as root. On Windows, Kilnmark runs as your own
   account, and there is no prepare.
3. **Install**, with the control script's `install`:

   | | Linux (as the kilnmark account) | Windows |
   | --- | --- | --- |
   | Online | `sudo -iu kilnmark /opt/kilnmark/versions/<version>/kilnmark.sh install` | `kilnmark.cmd install`, from the unpacked folder |
   | Offline | the same, with `--offline /opt/kilnmark/downloads/kilnmark-<version>-images.tar.gz` (copy the file and its `.sigstore.json` there, owned by kilnmark) | `kilnmark.cmd install -Offline ..\kilnmark-<version>-images.tar.gz` |

   Install:
   - checks the prerequisites and reports every problem at once, with its fix;
   - makes the install root (`/opt/kilnmark` or `C:\Kilnmark`; another with `--root` or `-Root`,
     a folder without spaces);
   - copies the release into it;
   - writes `config/.env` with a new database password and admin token. Only your account can
     read it, and nothing ships with a default. An existing `.env` is kept as it is;
   - gets the images: it loads the offline file or pulls them, checking their signatures, or
     builds them with `--build`;
   - starts Kilnmark and waits until it answers.

   With `--auth` (`-Auth`), install also turns on Keycloak with new secrets (below). To use other ports, set them first, for example
   `KILNMARK_PORT=9780 ./kilnmark.sh install`.
4. **Open** http://localhost:8780 and sign in with the admin token: it is `KILNMARK_ADMIN_TOKEN` in
   `config/.env`. Under **API tokens**, make a permanent admin token for yourself and keep it safe.
5. **Add a source** under **Sources**: nothing is scanned until you do.
6. **To try the samples**, small pipelines that each show one idea, open the **Library** and choose
   **Add samples**: they stay in the Library, apart from your own pipelines.
   - **Add the starter pack** creates Kilnmark's sample tables in a database and schema you
     pick, and adds every sample reading them. It needs a database source and a file source that
     allow writing (an admin turns that on under **Sources**).
   - Or pick your own sources for the placeholders the samples use (`crm-db`, `shop-db`,
     `files`).

   See [samples/README.md](samples/README.md). The samples are also in the release's `samples`
   folder.
7. **Schedule a daily backup**: `kilnmark.sh backup` (`kilnmark.cmd backup`), from cron or Task
   Scheduler (below, "Back up and restore").

The install root after that ([ADR 0044](docs/adr/0044-installing-with-one-control-script.md)):

```
kilnmark/                  /opt/kilnmark or C:\Kilnmark
  kilnmark.sh | kilnmark.cmd, kilnmark.ps1, start-/stop-kilnmark, create-shortcut
  versions/<version>/      the release, never edited; current (Linux) or current.txt names the one that runs
  config/.env              this site's settings and secrets (yours only)
  config/.env.example      every setting, with what it does
  certs/https/             server.crt and server.key, for HTTPS on Kilnmark itself
  downloads/               (Linux) where to put images files and upgrades for the kilnmark account
  backups/                 one folder per backup
  logs/                    what each install, start, upgrade and backup said
```

Kilnmark's data is in the compose project's volumes (`kilnmark_pgdata`, `kilnmark_keys`, and
`kilnmark_keycloak-pgdata` with Keycloak), not in the root. Every long-running container restarts
by itself after a reboot: on Linux with rootless Podman, prepare turns on lingering for the
kilnmark account and install turns on its `podman-restart` service. On Linux, run each command
below as that account: `sudo -iu kilnmark /opt/kilnmark/kilnmark.sh <command>`.

Below, `.env` means `config/.env` in the install root. After changing it, apply the change with
`kilnmark.sh up` (`kilnmark.cmd up`), which recreates what changed. A `compose` command below runs
from the install root as `podman compose -p kilnmark --env-file config/.env -f current/compose.yaml`
(or `docker compose`), which is what the control script runs.

### The control script

Run it from the install root (on Linux, as the kilnmark account). Run with no command, it starts
Kilnmark. On Windows, options are
written `-Offline`, `-Root`, `-SkipVerify` and so on.

| Command | What it does |
| --- | --- |
| `prepare` | Linux, once, with sudo: makes the kilnmark account and the install root (above) |
| `up` | Starts Kilnmark (and Keycloak when `.env` turns on the `auth` profile), waits until it answers, and opens it in the browser |
| `down` | Stops it. Every volume, so all data, is kept |
| `restart [service]` | Restarts everything, or one service |
| `status` | Version, containers, health, and the last backup |
| `logs [service] [-f]` | The last 200 lines of the logs, or follows them |
| `check` | Checks the prerequisites, and lists settings in the version's `.env.example` that your `.env` does not mention. Changes nothing |
| `verify FILE` | Checks a downloaded file against its signature |
| `load-images FILE` | Checks and loads an offline images file |
| `pull` | Pulls this version's images and checks their signatures |
| `build` | Builds the images from the release's `containers` folder instead |
| `backup [DIR]` | Backs up the database and the keys, into `backups` by default |
| `restore BACKUP --yes` | Puts Kilnmark back to a backup |
| `upgrade ARCHIVE [--offline IMAGES]` | Checks the new release, backs up, puts it beside this one, and starts it. If the new version does not answer, the old one is current again, and the script says how to restore |
| `uninstall [--purge]` | Stops Kilnmark. `--purge` also deletes its volumes and images, once you type `DELETE` |

Every command writes what it said to `logs`, and ends with a failure code when it fails, so it can
be scheduled. No command prints a secret. Without cosign, `install`, `upgrade`, `load-images` and
`pull` refuse to go on unless given `--skip-verify` (`-SkipVerify`), which the log records.

A second Kilnmark on the same machine, or one beside a source checkout that already uses the
project name `kilnmark`, needs its own project, root and ports:
`KILNMARK_PROJECT=kilnmark2 KILNMARK_PORT=9780 KILNMARK_HTTPS_PORT=9443 KILNMARK_DB_PORT=9442
./kilnmark.sh install --root /opt/kilnmark2`, with `KILNMARK_PROJECT` set again for every later
command.

**From 0.0.9:** a 0.0.9 folder's compose project was named after its folder. Back it up with that
folder's `./backup.sh`, install this version, then `kilnmark.sh restore <that backup> --yes`.

### Start and stop with one click

After install, Kilnmark can start and stop from a shortcut, on Windows 11 and on Ubuntu (or any
desktop that reads `.desktop` files). The shortcuts run the control script's `up` and `down`.

| | Windows 11 | Ubuntu |
| --- | --- | --- |
| Make the shortcuts, once, in the install root | `powershell -ExecutionPolicy Bypass -File .\create-shortcut.ps1` | `./create-shortcut.sh` |
| They go | on the desktop and in the Start menu | in the applications menu and on the desktop |

On Ubuntu with Docker, your user must be in the `docker` group, so the shortcut needs no `sudo`.
If something goes wrong, the window stays open and says what, and which logs to look at. To have
Kilnmark start when you sign in to Windows, put the Kilnmark shortcut in `shell:startup`.

### Check a release

Check each file you downloaded before unpacking or loading it. Use
[cosign](https://docs.sigstore.dev/cosign/system_config/installation/) 3.0 or later (earlier
versions cannot read the signatures) and Kilnmark's public key: `cosign.pub`, from the release's
owner or `config/release/cosign.pub` in the repository.

```
cosign verify-blob --key cosign.pub --insecure-ignore-tlog=true \
  --bundle kilnmark-<version>.tar.gz.sigstore.json kilnmark-<version>.tar.gz
```

`Verified OK` means the file is the one Kilnmark signed. The signatures are deliberately kept off
Sigstore's public log, which is what `--insecure-ignore-tlog` acknowledges; the key is the proof.

- `sha256sum -c SHA256SUMS --ignore-missing` checks the files against the signed list of checksums.
- The SBOMs, `kilnmark-<version>-server.cdx.json` and `-ui.cdx.json` (also in the release's `sbom`
  folder), list every library and package it contains, for your own scanners.
- Once Kilnmark is installed, the control script checks the offline images file and later
  upgrades itself, with the `cosign.pub` the release carries.
- Pulled images are signed by digest:
  `cosign verify --key cosign.pub --insecure-ignore-tlog=true ghcr.io/jporeilly/kilnmark-server@sha256:...`

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
for its lease and uses one or two more while it works; the flows a worker watches share one for their
locks, and each uses one now and then while it looks; and each worker keeps one open to hear of new
jobs. So each Kilnmark process needs a pool
(`QUARKUS_DATASOURCE_JDBC_MAX_SIZE`, 30 by default) of at least

```
concurrency × 3 + flows + 6
```

where the 6 is one for the worker's listening and five for the API and the scheduler. With the
defaults that is 2 × 3 + 8 + 6 = 20, so the default pool leaves room to raise the slots to 5. With
8 slots and 8 flows it is 38: set `QUARKUS_DATASOURCE_JDBC_MAX_SIZE=40`. A process with the worker
role only needs five fewer (`concurrency × 3 + flows + 1`), and one without it needs five.

Kilnmark checks this at start and refuses to start with a smaller pool, saying how many
connections it needs and which settings to raise or lower: a pool too small works while little
runs, then fails requests and runs under load. Raise the pool, or lower
`KILNMARK_WORKER_CONCURRENCY` or `KILNMARK_WORKER_FLOWS`, and start it again. PostgreSQL's
`max_connections` (100 in the bundled database) must be at least the sum of the pools of every
Kilnmark process, plus what backups and your own tools use.

The sources themselves see one connection, or a few, per run that reads or writes them; set their
**Most runs at once** where their administrators ask for a cap. It counts runs, not sessions, and
previews, scans and quality checks are outside it.

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
  files, as `server.crt` and `server.key` in `certs/https` in the install root (the folder
  `KILNMARK_CERTS_DIR` names; a release folder run by hand uses its `tls` folder), readable by the
  container; and set in `.env`:

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
   the ID token's `groups` claim, require MFA for the application, and let it issue refresh tokens.
2. In `.env`, set `KILNMARK_OIDC_ENABLED=true`, `KILNMARK_OIDC_ISSUER` (the provider's issuer URL),
   `KILNMARK_OIDC_CLIENT_ID`, `KILNMARK_OIDC_CLIENT_SECRET` and, for the button,
   `KILNMARK_OIDC_PROVIDER_NAME`.
3. Say which groups give which role, comma-separated, exactly as the provider names them:
   `KILNMARK_OIDC_VIEWER_GROUPS`, `..._STEWARD_GROUPS`, `..._DESIGNER_GROUPS`, `..._ADMIN_GROUPS`.
   Designers in `KILNMARK_OIDC_UNMASK_GROUPS` see values of columns marked sensitive. A person in
   none of them signs in with no role, and is told so.
4. Apply it: `kilnmark.sh up`. The sign-in page now shows **Sign in with** your provider.

Kilnmark requires a second factor: the provider must say so in the ID token's `amr` (`mfa`, or two
methods of two kinds, such as a password and a one-time code; one method alone, or two of one kind,
is not enough), or in its `acr` with a value you
list in `KILNMARK_OIDC_MFA_ACR`. A sign-in that shows none gets no role. A sign-in lasts at most 8
hours from the person's last sign-in at the provider (`KILNMARK_OIDC_MAX_SESSION`); the ID token
must say when that was (`auth_time`), which every provider can send. Every sign-in, and every one
given no role, is in **Settings**, **Audit log**, under `sso:` and the person's name.

A sign-in renews itself while the provider's session lasts: when the provider's ID token expires,
Kilnmark gets a fresh one with the refresh token and checks it again, MFA and groups included,
without asking the person anything. For that the provider must issue a refresh token. Keycloak does;
Microsoft Entra ID and Okta do only when asked for `offline_access`, so for them set
`KILNMARK_OIDC_SCOPES=offline_access` (never for Keycloak, where it asks for a token that outlives
the sign-in). Without a refresh token, a sign-in ends when its first ID token does, often within an
hour. Signing out asks the provider to revoke the refresh token, where it offers that (Keycloak and
Okta do; Entra ID does not).

A page's requests can renew at the same moment with the same refresh token. If the provider rotates
refresh tokens, let it accept a token used twice in quick succession (Okta's grace period; in
Keycloak, leave **Revoke Refresh Token** off, as it is unless you change it), or the session ends.

Kilnmark must be reached over HTTPS for this (see HTTPS above): its session cookies are sent over
HTTPS only. Behind a proxy that ends TLS, also set `KILNMARK_OIDC_BEHIND_TLS_PROXY=true`.

### Keycloak, if you have no identity provider

Kilnmark comes with **Keycloak**, an open-source identity provider, for an organisation that has none
of its own, or only Active Directory or LDAP (which Keycloak can read). It is a compose profile,
`auth`, started only when asked for ([ADR 0014](docs/adr/0014-authentication.md)). It needs about
1 GB of memory more.

1. In `.env`, set Keycloak's secrets, each a long random value (`openssl rand -base64 32`):
   `KILNMARK_KEYCLOAK_DB_PASSWORD` and `KILNMARK_KEYCLOAK_ADMIN_PASSWORD` (the password of
   Keycloak's admin, `KILNMARK_KEYCLOAK_ADMIN`, `admin` unless you set it); and where browsers reach
   Keycloak and Kilnmark: `KILNMARK_KEYCLOAK_URL` (default `http://localhost:8180`, following
   `KILNMARK_KEYCLOAK_PORT`) and `KILNMARK_URL` (default `http://localhost:8780`).
2. Turn on sign-in with it, as `.env.example` shows: `KILNMARK_OIDC_ENABLED=true`,
   `KILNMARK_OIDC_ISSUER=http://keycloak:8080/realms/kilnmark`, `KILNMARK_OIDC_CLIENT_ID=kilnmark`,
   a new `KILNMARK_OIDC_CLIENT_SECRET`, `KILNMARK_OIDC_PROVIDER_NAME=Keycloak`, and the five group
   settings (`kilnmark-viewers`, `kilnmark-stewards`, `kilnmark-designers`, `kilnmark-admins`,
   `kilnmark-sensitive-data`).
3. Start it: set `COMPOSE_PROFILES=auth` in `.env` and run `kilnmark.sh up`, which then starts and
   stops Keycloak with Kilnmark. (`kilnmark.sh install --auth` does steps 1 to 3 for you, with new
   secrets.) By hand, `podman compose --profile auth up -d`.
   Docker Compose and recent podman-compose read it; an older podman-compose (as in Ubuntu 24.04)
   may not, so there add `--profile auth` yourself. The one-click scripts (below) always do. The `keycloak-setup` service waits for Keycloak,
   then makes the `kilnmark` realm: those groups, the client Kilnmark signs in with, a sign-in of a
   password and a one-time code, and Kilnmark's sign-in page. `podman compose logs keycloak-setup`
   says what it did. Run it again after changing `KILNMARK_URL` or the client secret:
   `podman compose --profile auth run --rm keycloak-setup`. It finishes anything an earlier run
   left half done, and keeps what is there.
4. Add people in Keycloak's console (`KILNMARK_KEYCLOAK_URL/admin`, signing in as
   `KILNMARK_KEYCLOAK_ADMIN` with `KILNMARK_KEYCLOAK_ADMIN_PASSWORD`, realm **kilnmark**,
   **Users**), give each a password and put them in a group. Or connect Active Directory or LDAP
   under **User federation**.

Keycloak makes its admin on its first start only: changing `KILNMARK_KEYCLOAK_ADMIN_PASSWORD` later
changes nothing, and `keycloak-setup` then cannot sign in. Change the password in Keycloak's console
first, then in `.env`.

Each person sets up a one-time code (any authenticator app) on their first sign-in. Keycloak does
not count setting it up as using it, so Kilnmark then asks them to sign in once more, with the code.

Keycloak's tokens last 5 minutes (its default, kept so that disabling someone, or taking them out of
a group, shows within 5 minutes); Kilnmark renews them while the person works. Keycloak's session
for the realm ends after 2 hours unused or 8 hours at most, and the person then signs in again.
Signing out of Kilnmark ends the Keycloak session too.

For a demonstration, also set `KILNMARK_KEYCLOAK_DEMO_PASSWORD`: `keycloak-setup` then adds five
example people, one per role, at `kilnmark.com` addresses (Documentation, "Signing in, roles and
tokens"). Never set it where real data is.

On one machine, `http://localhost` works as it is: browsers treat localhost as secure, so
Kilnmark's session cookie (sent over HTTPS only) still works there. Keycloak is published on this
machine only. For other machines, put a proxy on this machine that ends TLS in front of both, set
`KILNMARK_KEYCLOAK_URL` and `KILNMARK_URL` to their `https://` addresses, and run `keycloak-setup`
again (above).

`kilnmark.sh backup` backs up Kilnmark, not Keycloak. Back Keycloak's database up beside it:
`podman compose --profile auth exec -T keycloak-db pg_dump -U keycloak -Fc keycloak > keycloak.dump`.
Keycloak keeps compressed copies of its pages: after changing the theme in
`containers/keycloak/themes`, recreate it
(`podman compose --profile auth up -d --force-recreate keycloak`).

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

While Kilnmark runs, `kilnmark.sh backup` (`kilnmark.cmd backup`) writes a new folder in `backups`
(or the folder you name) holding the database and the keys that open stored source passwords. Keep
the keys file apart from the database dumps. `kilnmark.sh restore backups/kilnmark-<time> --yes`
puts both back, on this machine or a new one, and the same backup restores on Linux or Windows.
Schedule a daily backup, back up before every upgrade (`upgrade` does), and test a restore every
quarter: the runbook, with what to check after a restore, is
[Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md)
([ADR 0039](docs/adr/0039-backup-and-restore.md)). The release folder's `backup.sh` and `restore.sh`
are what the control script runs on Linux.

### Run pipelines and flows from your scheduler

The release folder holds `kilnmark-run.sh` (Linux and Unix) and `kilnmark-run.cmd` with
`kilnmark-run.ps1` (Windows). Copy them to the machines where Control-M, AutoSys, cron or Windows
Task Scheduler run their jobs: each starts a pipeline or flow, waits for it to end and exits with
what happened. Give the scheduler a steward token of its own, in a file only its jobs can read. See
[Running from an external scheduler](docs/guide/external-schedulers.md)
([ADR 0047](docs/adr/0047-external-schedulers.md)).

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
