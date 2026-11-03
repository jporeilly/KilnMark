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
