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

## Choosing a deployment

Kilnmark is installed in one of two modes, chosen at install and never changed
([ADR 0051](docs/adr/0051-starter-pack.md)):

| | Starter Pack | Production |
| --- | --- | --- |
| For | Training, demonstrations and development | Real work |
| Chosen | At install (`install --starter`); recorded at the first start | At install (the default); recorded at the first start |
| Sign-in | Kilnmark's own Keycloak, always | Your organisation's own identity provider (recommended), Kilnmark's own Keycloak, or API tokens only |
| Example people | Five, one per role, made by `starter people` | Never; refused at sign-in |
| Sample data | A separate samples database; an admin turns it on and off under **Settings** | None; it cannot be turned on |
| Templates | Yes | Yes |
| First admin | `KILNMARK_ADMIN_TOKEN` from `.env` (with none, a first admin token that works for 24 hours); and, offered at install, a first admin person in Keycloak | The same; the first admin person with Kilnmark's own Keycloak |
| Adding people | `people add`; `people import` with one temporary password for a class; Keycloak's console | With Kilnmark's own Keycloak: `people add`; `people import`, each person then given a password of their own with `people activate`; Keycloak's console; or Active Directory or LDAP in it. With your own identity provider: in that service |
| Backup | Kilnmark's database and Keycloak's | Kilnmark's database, and Keycloak's when it is used |
| Changing mode | It cannot become Production: install afresh | It cannot become Starter Pack: install afresh |

Ways to add people:

| Way | Mode | Who runs it | Good for |
| --- | --- | --- | --- |
| `people add` | Either, with Kilnmark's own Keycloak | The operator | One person, admins included |
| `people import` | Either, with Kilnmark's own Keycloak | The operator | A class (Starter Pack: one shared temporary password) or a team (Production: each waits for `people activate`); never admins, nor on Production anyone who sees sensitive values |
| `people activate` | Either, with Kilnmark's own Keycloak | The operator | Giving one waiting person a temporary password of their own |
| Keycloak's console | Either, with Kilnmark's own Keycloak | The operator | Changes, password resets, removals |
| Active Directory or LDAP in Keycloak | Production | The operator | People already in the directory |
| Your organisation's identity provider | Production | That service's admins | Banks and other regulated organisations |

Kilnmark itself cannot add people: it holds no credential for Keycloak or for your identity
provider, so no Kilnmark admin can make an account, or learn a password, to get round its
two-person rules ([ADR 0051](docs/adr/0051-starter-pack.md), decision 5).

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
   - writes `config/.env` with a new database password and admin token, the deployment mode
     (`KILNMARK_MODE`, below) and, on a Starter Pack install, a password for the Starter Pack's
     database login. Only your account can read it, and nothing ships with a default. An existing
     `.env` is kept as it is;
   - gets the images: it loads the offline file or pulls them, checking their signatures, or
     builds them with `--build`;
   - starts Kilnmark and waits until it answers.

   **Production or Starter Pack.** Install makes a **production** install unless you give
   `--starter` (`-Starter`), which makes a **Starter Pack** install, for training and development
   only, and turns the Starter Pack on (below, "Try Kilnmark with the Starter Pack";
   [ADR 0051](docs/adr/0051-starter-pack.md)). The mode is `KILNMARK_MODE` in `config/.env`, and
   Kilnmark records it in its database at its first start: editing `.env` never changes it, and
   Kilnmark does not start while the two differ. A Starter Pack install never becomes a production
   one: for production, install Kilnmark afresh.

   **How people sign in.** A Starter Pack install always has the bundled Keycloak, where its
   example people are. A production install asks, at a terminal, how people will sign in:
   - your organisation's own identity provider, which install recommends for banks and other
     regulated organisations (below, "Sign in with your identity provider");
   - the bundled Keycloak, which install then turns on with new secrets, offering to make
     Kilnmark's first admin (below, "Keycloak, if you have no identity provider");
   - API tokens only, for now.

   `--auth` (`-Auth`) chooses the bundled Keycloak without asking; with no terminal and no
   `--auth`, it is API tokens only. To use other ports, set them first, for example
   `KILNMARK_PORT=9780 ./kilnmark.sh install`.
4. **Open** http://localhost:8780 and sign in with the admin token: it is `KILNMARK_ADMIN_TOKEN` in
   `config/.env`. Under **API tokens**, make a permanent admin token for yourself and keep it safe.
5. **Add a source** under **Sources**: nothing is scanned until you do.
6. **To try the samples**, small pipelines that each show one idea, use a Starter Pack install:
   an admin turns the **Starter Pack** on under **Settings** (below, "Try Kilnmark with the Starter
   Pack"), and every sample is then in the **Library**, ready to run, apart from your own pipelines.
   There, in the **Library**, **Add samples** also lets a designer pick sources for the
   placeholders the samples use (`crm-db`, `shop-db`, `files`). A production install has no
   samples, by any route; its **Library** holds the templates. See
   [samples/README.md](samples/README.md). The samples are also in the release's `samples` folder.
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

Kilnmark's data is in the compose project's volumes (`kilnmark_pgdata`, `kilnmark_keys`,
`kilnmark_files`, and `kilnmark_keycloak-pgdata` with Keycloak), not in the root. Every long-running container restarts
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
| `up` | Starts Kilnmark (and Keycloak when `.env` turns on the `auth` profile), waits until it answers, and opens it in the browser. First it adds to `.env` any secret this version needs that it lacks (such as `KILNMARK_SAMPLES_DB_PASSWORD`) |
| `down` | Stops it. Every volume, so all data, is kept |
| `restart [service]` | Restarts everything (a stop, then `up`, so `keycloak-setup` and `samples-db` run again), or one service |
| `status` | Version, containers, health, the last backup, and, with the bundled Keycloak, the hourly expiry of unused temporary passwords |
| `logs [service] [-f]` | The last 200 lines of the logs, or follows them |
| `check` | Checks the prerequisites, lists settings in the version's `.env.example` that your `.env` does not mention, and, with the bundled Keycloak, warns when the hourly expiry of unused temporary passwords is not set up. Changes nothing |
| `verify FILE` | Checks a downloaded file against its signature |
| `load-images FILE` | Checks and loads an offline images file |
| `pull` | Pulls this version's images and checks their signatures |
| `build` | Builds the images from the release's `containers` folder instead |
| `backup [DIR]` | Backs up Kilnmark's own database and the keys, and the bundled Keycloak's database when it is on, into `backups` by default (never `kilnmark_samples`, nor the files folder) |
| `restore BACKUP --yes` | Puts Kilnmark back to a backup |
| `upgrade ARCHIVE [--offline IMAGES]` | Checks the new release, backs up, puts it beside this one, and starts it. If the new version does not answer, the old one is current again, and the script says how to restore |
| `people add` | With the bundled Keycloak, in either mode, adds one person of your own, in any of Kilnmark's groups, admins included: it asks for their email, names, groups and a temporary password, which you type (it is not shown) or pipe in (below, "Adding people to the bundled Keycloak") |
| `people import FILE` | Adds the people in a CSV file; never an admin. On a Starter Pack install, with one temporary password for the class; on production, each waits, disabled and with no password, for `people activate` |
| `people activate EMAIL` | Gives one person waiting to be activated a temporary password of their own, typed (not shown) or piped in, and enables them |
| `people list` | Lists the realm's people, their Kilnmark groups, whether they are active or waiting to be activated, and who has not yet chosen their own password |
| `people expire` | Disables anyone whose temporary password went unused for longer than `KILNMARK_PEOPLE_ACTIVATION_HOURS`. `up` sets it to run every hour (a systemd timer on Linux, a scheduled task on Windows); you need not run it yourself |
| `starter people [--new-password \| --remove]` | On a Starter Pack install, the five example people in the bundled Keycloak, with `KILNMARK_STARTER=allowed`: adds them, gives them all a new password, or removes them. You type the password (it is not shown) or pipe it in, and it is kept nowhere (below, "Try Kilnmark with the Starter Pack") |
| `uninstall [--purge]` | Stops Kilnmark. `--purge` also deletes its volumes and images, once you type `DELETE`; an image another install on the machine still uses is kept, and named |

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
   stops Keycloak with Kilnmark. (`kilnmark.sh install --auth`, and every Starter Pack install,
   does steps 1 to 3 for you, with new secrets.) By hand, `podman compose --profile auth up -d`.
   Docker Compose and recent podman-compose read it; an older podman-compose (as in Ubuntu 24.04)
   may not, so there add `--profile auth` yourself. The one-click scripts (below) always do. The `keycloak-setup` service waits for Keycloak,
   then makes the `kilnmark` realm: those groups, the client Kilnmark signs in with, a sign-in of a
   password and a one-time code, and Kilnmark's sign-in page. `podman compose logs keycloak-setup`
   says what it did. Run it again after changing `KILNMARK_URL` or the client secret with
   `kilnmark.sh restart` (`kilnmark.cmd restart`). It finishes anything an earlier run left half
   done, and keeps what is there. It reads its two secrets from a file the control script writes
   for that run alone and removes afterwards (never `.env` whole), so start it through the control
   script rather than by hand.
4. Make **Kilnmark's first admin**, so nobody has to make the first person by hand: install asks
   for one when it turns Keycloak on (press Enter at the email to skip it). It is `people add` with
   the group `kilnmark-admins`: below, "Adding people to the bundled Keycloak".
5. Add everyone else with `people add` or `people import` (below), or in Keycloak's console
   (`KILNMARK_KEYCLOAK_URL/admin`, signing in as `KILNMARK_KEYCLOAK_ADMIN` with
   `KILNMARK_KEYCLOAK_ADMIN_PASSWORD`, realm **kilnmark**, **Users**). Or connect Active
   Directory or LDAP under **User federation**.

**Two admins, in two places.** Keycloak's own admin, `KILNMARK_KEYCLOAK_ADMIN` (`admin` unless you
set it), signs in to **Keycloak's console**, in Keycloak's realm **master**, to manage people: it
is not a Kilnmark account and cannot sign in to Kilnmark. **Kilnmark's first admin** is a person in
the realm **kilnmark**, in `kilnmark-admins`, who signs in to **Kilnmark**.

Keycloak makes its admin on its first start only: changing `KILNMARK_KEYCLOAK_ADMIN_PASSWORD` later
changes nothing, and `keycloak-setup` then cannot sign in. Change the password in Keycloak's console
first, then in `.env`.

Each person sets up a one-time code (any authenticator app) on their first sign-in. Keycloak does
not count setting it up as using it, so Kilnmark then asks them to sign in once more, with the code.

Keycloak's tokens last 5 minutes (its default, kept so that disabling someone, or taking them out of
a group, shows within 5 minutes); Kilnmark renews them while the person works. Keycloak's session
for the realm ends after 2 hours unused or 8 hours at most, and the person then signs in again.
Signing out of Kilnmark ends the Keycloak session too.

On a Starter Pack install, `kilnmark.sh starter people` adds five example people (below, "Try
Kilnmark with the Starter Pack"). A production install never has them.

### Adding people to the bundled Keycloak

With the bundled Keycloak, in either mode, the control script adds people of your own. It runs
`keycloak-setup` once, with Keycloak's own admin from `.env`: Kilnmark itself holds no Keycloak
credential, so it cannot add people
([ADR 0051](docs/adr/0051-starter-pack.md), decision 5). Run these as the account that runs
Kilnmark:

| | Linux | Windows |
| --- | --- | --- |
| Add one person | `kilnmark.sh people add` | `kilnmark.cmd people add` |
| Add the people in a file | `kilnmark.sh people import people.csv` | `kilnmark.cmd people import people.csv` |
| Activate one waiting person | `kilnmark.sh people activate ada@bank.example` | `kilnmark.cmd people activate ada@bank.example` |
| List who is there | `kilnmark.sh people list` | `kilnmark.cmd people list` |
| Expire unused temporary passwords now | `kilnmark.sh people expire` | `kilnmark.cmd people expire` |

- **`people add`** asks for the person's email, which is their user name, their first and last
  name, their groups (separated by `;`), and a temporary password, typed twice and not shown. To
  pipe them in instead, give five lines: email, first name, last name, groups, password. This is
  the only way the script puts someone in `kilnmark-admins`, one person at a time, as Kilnmark's
  two-person rules need.
- **`people import FILE`** adds everyone in a CSV file whose first line is
  `email,first_name,last_name,groups`. `groups` is a `;`-separated list of Kilnmark's groups,
  never `kilnmark-admins`; lines starting with `#`, and blank lines, are skipped; a name may not
  hold a comma, nor any of the characters Keycloak refuses in names (`( ) / \ ; & < > " $ % ! # ?
  * ~ | ^ = [ ] { }`). It checks the whole file before changing anything: a well-formed address, a
  known group and no address twice. If any line is wrong it names each one by its number, never
  its values, and makes nobody. At the end it says how many were made, already there and refused;
  should Keycloak refuse someone part way, it says how many it made before stopping.
  - On a **Starter Pack install**, for a class, it asks once for a temporary password the whole
    file shares (or takes one line on standard input), and everyone may sign in with it at once.
    Training data is made up, so a file may give `kilnmark-sensitive-data`.
  - On a **production install** nobody is given a password: each person is made **disabled**,
    waiting to be activated, and the operator gives each a temporary password of their own with
    `people activate`, one by one. A file may not give `kilnmark-sensitive-data` either: like
    admins, people who see sensitive values are added one at a time with `people add`.

  The release has an example, `containers/keycloak/people.example.csv`, at example.com addresses,
  which are reserved and receive no mail (on production, its last line is refused: Priya sees
  sensitive values).
- **`people activate EMAIL`** gives one person waiting to be activated (imported on production, or
  disabled because their temporary password went unused) a temporary password of their own, typed
  twice and not shown, or piped in, and enables them. It takes only someone waiting for a first
  password: no password at all, or only the temporary one these commands gave them, unused. It
  refuses someone already active, anyone with a password of their own or a one-time code (they
  have signed in, and were disabled on purpose: enable them in Keycloak's console), and anyone from
  a directory or another identity provider.
- **`people list`** shows each person in the realm with their Kilnmark groups, whether they are
  `active`, `waiting-to-be-activated` or `disabled`, and `must-choose-password=yes` while they have
  not yet signed in with their temporary password: for a trainer, who has not yet signed in. It
  shows no password.

Each temporary password works once: at their first sign-in Keycloak has the person choose their
own, and set up a one-time code. **Unused temporary passwords expire**, at most an hour late: anyone
still holding one more than `KILNMARK_PEOPLE_ACTIVATION_HOURS` (72 unless you set it, 1 to 720) after
it was set is disabled, and Kilnmark's audit log says so (`auth.person_disabled`). That includes
people made by `people add` and Kilnmark's first admin; `people activate` gives them a new one, and
`KILNMARK_ADMIN_TOKEN` stays the way in meanwhile. Only the temporary passwords these commands set
expire (each is marked `kilnmark-temporary` in Keycloak, and the mark goes when its person chooses
their own): someone you asked in Keycloak's console to change their password is never disabled for
it. The check runs at every start, at every `people` command, and every hour as `people expire`,
which `up` sets up with the bundled Keycloak and `uninstall` removes:
- on **Linux**, a systemd timer of the account that runs Kilnmark,
  `kilnmark-<project>-people-expire.timer` (a system one when the script runs as root). Rootless
  Podman keeps running while nobody is signed in once lingering is on, as `prepare` sets it;
  without systemd, `up` says so: run `kilnmark.sh people expire` every hour from cron instead;
- on **Windows**, a scheduled task of yours, `Kilnmark <project> people expire`, which runs while
  you are signed in to Windows, as the Podman machine or Docker Desktop does.

The task or timer runs the control script for you every hour, so `up` sets it up only where nobody
else may change that script: on Windows it first makes the install root yours alone, and says so
and sets nothing up while anyone else may still write `kilnmark.ps1` or its folder; on Linux, run
as root, only when root owns the install root and `kilnmark.sh` and nobody else may write them (run
Kilnmark as its own account instead, with `prepare`). `KILNMARK_PROJECT`, which names the task, is
lower-case letters, digits, `-` and `_`. A temporary password with a one-time code set up on it,
but no new password, still expires, and the code is taken away again.

`status` and `check` say whether it is set up. While Keycloak is stopped nobody can sign in, so
`people expire` does nothing then, and the next start expires them first. The password goes to `keycloak-setup` on its input only:
never on the screen, in `.env`, a command line or `logs`. Someone already there with that email is
left exactly as they are, their password and groups too, and reported as already there; running a
command again changes nothing. Addresses at kilnmark.com, the Starter Pack's example people's, are
refused, so a real person is never taken for an example one; people added this way are in exactly
the Kilnmark groups they were given, and never in the pack's group, so removing the example people
never removes them: should someone made land in another group of Kilnmark's (the realm has it as a
default group, say), they are deleted again and the command stops. People cannot change their own
email, names or user name in the bundled Keycloak: an admin does, in its console. The
commands act only where the mode Kilnmark recorded in its database is the one `.env` says, and stop
when the database does not answer.
Kilnmark's audit log records each person made (`auth.person_added`, with their groups), each import
(`auth.people_imported`) and each activation (`auth.person_activated`), never a password; Keycloak
records every change to people as an admin event too. The commands check that Kilnmark answers
first, so nobody is made without that record. With your organisation's own identity provider, the
commands refuse: add people there.

On one machine, `http://localhost` works as it is: browsers treat localhost as secure, so
Kilnmark's session cookie (sent over HTTPS only) still works there. Keycloak is published on this
machine only. For other machines, put a proxy on this machine that ends TLS in front of both, set
`KILNMARK_KEYCLOAK_URL` and `KILNMARK_URL` to their `https://` addresses, and run
`kilnmark.sh restart`, which runs `keycloak-setup` again (above).

While Keycloak is on, `kilnmark.sh backup` backs its database up too (its people, groups and
one-time code setups), and `restore` puts it back (below, "Back up and restore").
Keycloak keeps compressed copies of its pages: after changing the theme in
`containers/keycloak/themes`, recreate it
(`podman compose --profile auth up -d --force-recreate keycloak`).

### The files folder

File sources read, and with **Allow writing** on write, the folder `/srv/files` inside Kilnmark's
container (`KILNMARK_FILES_ROOTS`). Every install has one: the `files` volume (`kilnmark_files`),
writable by Kilnmark; the samples and the Starter Pack write only there. To let file sources read a
folder of your own, such as a share, set its full path in `.env` and apply it with
`kilnmark.sh up`: Kilnmark sees it read-only at `/srv/share`, and never writes in it.

```
KILNMARK_FILES_DIR=/srv/kilnmark-files
```

Kilnmark's user in the container (uid 1001) must be able to read it. On a host with SELinux,
Podman labels the folder for Kilnmark's container (`:Z`); if other services use the same folder,
give Kilnmark a copy. Never edit `versions/<version>/compose.yaml`: upgrades replace it. In
Kilnmark, add a source of kind **files** with path `/srv/share` (your folder) or `/srv/files`
(Kilnmark's own), or a folder inside either.

`kilnmark.sh backup` does not back the files folder up: back up your own folder, or the volume,
as you back up other files.

### Try Kilnmark with the Starter Pack

The **Starter Pack** is for trials, demonstrations and training: example data, every sample ready
to run and five example people to sign in as ([ADR 0051](docs/adr/0051-starter-pack.md)). It works
only on a **Starter Pack install**, which is never for real data:

| | Linux | Windows |
| --- | --- | --- |
| Install | `kilnmark.sh install --starter` | `kilnmark.cmd install -Starter` |

![The two deployment modes: install sets KILNMARK_MODE once and Kilnmark records it at its first start; a production install never has the Starter Pack, its samples database or its example people, and refuses their sign-ins; a Starter Pack install has the bundled Keycloak, the pack and its marking; a start whose setting differs from the record is refused, and a Starter Pack install never becomes a production one](docs/guide/images/deployment-modes.svg)

A Starter Pack install always has the bundled Keycloak, signs in with it and never with an outside
identity provider (Kilnmark does not start otherwise), and shows everyone the marking "Starter
Pack: training and development only". The templates are there on every install, production too.

![What the Starter Pack adds: on Kilnmark's PostgreSQL server, Kilnmark's own database and the separate kilnmark_samples database, whose own login reads and makes nothing in Kilnmark's; the pack's folder in the files folder; and, in Keycloak, five example people made by keycloak-setup with Keycloak's own admin, and no Keycloak credential in Kilnmark](docs/guide/images/starter-pack.svg)

**Turn it on.** Install turns it on. Afterwards, an admin opens **Settings**, **Starter Pack** and
chooses **Turn on**. Kilnmark then, by itself:

- adds a database source on `kilnmark_samples` and a files source on `/srv/files/starter-pack`,
  both allowing writing;
- creates the sample tables there (the `sample-tables` pipeline) and adds every sample to the
  **Library**, reading them;
- adds to the marking that the example data and accounts are on.

The page shows each step and how it went. A source called `starter-samples-db` or
`starter-samples-files` that the pack did not make, your own, is left as it is: the page says so.

**Its database.** A Starter Pack install has an empty database, `kilnmark_samples`, on Kilnmark's
own PostgreSQL server, with a login of its own, `kilnmark_samples`, whose password is
`KILNMARK_SAMPLES_DB_PASSWORD` in `.env` (the control script makes one). The database is closed to
everyone but that login when it is made, and PostgreSQL itself (`pg_hba.conf`) lets that login
reach its own database only, over TLS, and no other. The `samples-db` service makes or checks both
at every start, once Kilnmark's own record says this is a Starter Pack install, then stops
(`kilnmark.sh logs samples-db` says what it did); it changes no other database's grants. A
production install has neither. Kilnmark's backups do not hold `kilnmark_samples`: after a
restore, turn the pack off and on again to recreate the samples.

**The example people.** Install sets `KILNMARK_STARTER=allowed` in `.env`. Then, as the account
that runs Kilnmark:

| | Linux | Windows |
| --- | --- | --- |
| Add them | `kilnmark.sh starter people` | `kilnmark.cmd starter people` |
| Give them all a new password | `kilnmark.sh starter people --new-password` | `kilnmark.cmd starter people -NewPassword` |
| Remove them | `kilnmark.sh starter people --remove` | `kilnmark.cmd starter people -Remove` |

`keycloak-setup`, with Keycloak's own admin, adds `vera.lane` (viewer), `sam.okafor` (steward),
`dana.reyes` (designer), `priya.shah` (designer, sees sensitive values) and `alex.morgan` (admin),
all `@kilnmark.com`, which receives no mail from here, in the Keycloak group
`kilnmark-starter-pack`, which carries a mark only `keycloak-setup` sets. They share one password,
which you type twice, not shown, or pipe in on one line (12 characters or more). It goes to
`keycloak-setup` on its input only: never on the screen, in `.env`, a container's settings, a
command line, `logs` or Kilnmark. Adding them again keeps the password of anyone already there.
Someone already in Keycloak with one of those addresses that the pack did not make is left as they
are, and a group of that name that `keycloak-setup` did not make (from a directory, say) stops it,
changing nothing. Each sets up a one-time code on first sign-in. Kilnmark's audit log records each
change (`starter.people`), and Keycloak records each as an admin event (its console, realm
**kilnmark**, **Events**, **Admin events**). Kilnmark holds no Keycloak credential, in any setting.
Anyone who has the password can sign in as an example admin or as a designer who sees sensitive
values: that is why they are only for trials and training.

To take them out, run `starter people --remove`, or remove `KILNMARK_STARTER=allowed` from `.env`
and run `kilnmark.sh up`: `keycloak-setup` then deletes the members of the pack's group whose user
name is one of the five example addresses (user names cannot change), and the group. Anyone else
someone put in the group is only taken out of it, and named; nobody outside it is touched.

**Turn it off.** Under **Settings**, **Starter Pack**, choose **Turn off** and type `DELETE`.
Kilnmark removes every sample in the Library, the pack's sources, the tables in `kilnmark_samples`
(the database stays, empty) and the files in `/srv/files/starter-pack`. Nothing else changes, and
the install stays a Starter Pack install. The example people stay until you remove them. Both
turning on and off are in the audit log, and the access review marks kilnmark.com accounts as
example accounts.

**A production install** never has the Starter Pack: its page under **Settings** says so and offers
nothing, and the API refuses it (each refusal is in the audit log). It has no samples database and
no example people: Kilnmark refuses the sign-in of anyone in the group `kilnmark-starter-pack`, and
of the five example addresses, recording each refusal, and `keycloak-setup` refuses a realm that has
any of the five. For a trial beside a production install, install a separate Kilnmark in Starter
Pack mode, with its own `KILNMARK_PROJECT`, root and ports (above, "The control script").

### Review who has access

**Settings**, **Access review** lists every API token (role, whether it sees sensitive values,
expiry, last use) and everyone who signs in with your identity provider, with the role of their last
sign-in. **Download CSV** keeps it as evidence for your auditors; each download is in the audit log.
Every kilnmark.com account that has signed in is marked **Example account** (the Starter Pack's
people): there should be none on an install with real data.

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
(or the folder you name) holding the database and the keys that open stored source passwords, and,
while the bundled Keycloak is on, Keycloak's database (`keycloak-db.dump`: its people, groups and
one-time code setups), without which nobody could sign in to a restored install. That dump also
holds the realm's password hashes, one-time code secrets, signing keys and the client secret:
keep it as safe as the keys. Keep the keys file apart from the database dumps. `kilnmark.sh restore backups/kilnmark-<time> --yes` puts them all
back, on this machine or a new one, and the same backup restores on Linux or Windows. A backup
is restored only into an install of its own deployment mode, and never one from before there were
modes.
Schedule a daily backup, back up before every upgrade (`upgrade` does), and test a restore every
quarter: the runbook, with what to check after a restore, is
[Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md)
([ADR 0039](docs/adr/0039-backup-and-restore.md)). The release folder's `backup.sh` and `restore.sh`
are what the control script runs on Linux.

### Upgrading from 0.1.1 or earlier

Release 0.1.2 is the first with deployment modes. Installs from before it (0.1.1 and earlier, which
were previews, never production releases) **cannot be upgraded**: install Kilnmark afresh, in the
mode you want. Back up first anything you mean to carry across by hand. On such an install,
`upgrade` refuses before it changes anything, `up` refuses a `config/.env` with no
`KILNMARK_MODE`, and Kilnmark itself refuses to start on a database that was made before this
start with no mode recorded: only a new database records one. A backup with no mode recorded is
never restored. `KILNMARK_KEYCLOAK_DEMO_PASSWORD`, which 0.1.1 used for its example people, is not
read by these releases.

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
