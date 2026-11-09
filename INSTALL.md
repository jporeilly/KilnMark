# Installing Kilnmark

Kilnmark installs from a release with one guided command, on Linux or Windows. It asks a few
questions (or reads them from a file), checks each answer as you give it, and then sets everything
up, step by step, saying how each step went. The rest of the install and deployment guide is in
[docs/guide/install](docs/guide/install/README.md). To develop Kilnmark, or build a release, see
the [development guide](docs/development.md).

## What to install first

| | Linux | Windows |
| --- | --- | --- |
| Container engine | Podman 5 or later **and podman-compose**, a package of its own, which `podman compose` needs (`sudo apt-get install -y podman podman-compose`; Ubuntu 25.04 and later, Debian 13, RHEL 9.5 and later have Podman 5), or Docker with its compose plugin | Podman Desktop (Podman 5 or later) with a WSL 2 machine, or Docker Desktop |
| WSL | In a WSL distribution, systemd on (`systemd=true` under `[boot]` in `/etc/wsl.conf`) | WSL **2.7.11**: WSL 3.0.x breaks crun's pids controller, and no Podman container starts. Do not run `wsl --update` |
| cgroups | v2, with the pids controller handed to the account that runs Kilnmark (systemd does this) | The Podman machine's, as WSL 2.7.11 gives them |
| cosign | 3.0 or later, to check the download and the images | `winget install Sigstore.Cosign` |
| curl, tar | From your distribution | Built into Windows 10 1803 and later |
| Memory and disk | 4 GB for Kilnmark's containers (5 GB with the bundled Keycloak), 10 GB of disk | The same, given to the Podman machine or Docker Desktop |

To check a machine before you install, run `check` from the unpacked release folder:
`./kilnmark.sh check` or `kilnmark.cmd check`. It changes nothing, and prints, for each problem, the
command that fixes it.

## Choosing a deployment

Kilnmark is installed in one of two modes, chosen at install and never changed: **Production**,
for real work, or a **Starter Pack** install, for training, demonstrations and development, with
example data and example people. Production signs in with your organisation's own identity
provider (recommended for banks and other regulated organisations) or with the Keycloak that comes
with Kilnmark. [Choosing a deployment](docs/guide/install/choosing-a-deployment.md) compares the
three ways to deploy, and has a page for each.

## Check the download

A release is `kilnmark-<version>.tar.gz` (or `.zip`), with `kilnmark-<version>-images.tar.gz` for a
machine without internet access, and a `.sigstore.json` signature beside each file. Check each file
before you unpack or load it:

```
cosign verify-blob --key cosign.pub --insecure-ignore-tlog=true \
  --bundle kilnmark-<version>.tar.gz.sigstore.json kilnmark-<version>.tar.gz
```

[Checking a release](docs/guide/install/checking-a-release.md) says where the key comes from and
what else to check.

## Install

Unpack the release in your own Downloads folder, or another folder only you (and root or the
administrators) may change, and run install from there. Install runs what is in that folder as
root or an administrator, so a folder someone else may change would let them change what runs.
On Windows, a folder made under `C:\` lets every signed-in account change what is in it: install
refuses a release there, and an archive or folder others may change, before it asks for an
administrator, and says how to fix it. Unpack it outside OneDrive too: OneDrive keeps its files
in the cloud, as links Windows fetches when they are read, and install refuses those. On Linux,
the program and data folders (`program_dir`, `data_dir`) go only under folders root alone may
change, as `/opt` and `/var/opt` are: install refuses any other. From the release's folder:

| | Linux | Windows |
| --- | --- | --- |
| Install, answering the questions | `sudo ./kilnmark.sh install` | `kilnmark.cmd install`, from a prompt that is **not** elevated |
| Without internet access | add `--offline ../kilnmark-<version>-images.tar.gz` | add `-Offline ..\kilnmark-<version>-images.tar.gz` |
| From an answers file, asking nothing | add `--answers install.conf` | add `-Answers install.conf` |
| Check the answers, changing nothing | add `--dry-run` | add `-DryRun` |
| A Starter Pack install | add `--starter` | add `-Starter` |

Install writes to folders only root or an administrator may change, so it asks, and says why
first: on Linux it runs itself again with `sudo`; on Windows it asks Windows for an administrator
(UAC) for the one step that makes the folders, which runs in a window of its own. Everything else
runs as the operator, the account that runs Kilnmark day to day: on Linux the service account
`kilnmark` (install makes it), on Windows you: run install as the account that will run Kilnmark.
Podman runs as the operator.

### The questions

Each question shows its default in brackets; press Enter to take it. Each answer is checked as you
give it, and asked again until it can be taken. Then install shows all the answers and asks to go
on.

| Question | Key in an answers file | Default | Checked |
| --- | --- | --- | --- |
| The deployment mode | `mode` | `production` | `production` or `starter-pack`; never changed later |
| How people sign in | `identity` | `own` on production; always `bundled` on a Starter Pack install | `own` (your organisation's identity provider) or `bundled` (Kilnmark's own Keycloak) |
| Your organisation's email domain | `email_domain` | none | A domain name in lower case, never kilnmark.com |
| The host name people reach Kilnmark on | `host` | `localhost` | A host name, an IPv4 address or `localhost`; anything but `localhost` needs TLS, and with the bundled Keycloak `tls=proxy` |
| TLS | `tls` (`tls_cert`, `tls_key`) | `off` | `off`, `on` (with your certificate and key, PEM files) or `proxy` (a proxy in front ends it) |
| Kilnmark's ports | `port`, `https_port` | `8780`, `8443` | Free, and no two answers on one port |
| Its database's port, on this machine only | `db_port` | `5442` | The same |
| With the bundled Keycloak: Keycloak's port, on this machine only | `keycloak_port` | `8180` | The same |
| With the bundled Keycloak: the first admin, a person of your own | `admin_email`, `admin_first_name`, `admin_last_name` | none (skip) | An address at the email domain, never kilnmark.com; then a temporary password, typed twice and not shown |
| With your own identity provider: its issuer, client id, name and groups | `oidc_issuer`, `oidc_client_id`, `oidc_provider_name`, `oidc_admin_groups` and the other groups | the groups `kilnmark-admins` and so on | An `https://` issuer; then its client secret, typed twice and not shown |

`project` (the compose project's name, `kilnmark`), `program_dir`, `data_dir`, and `user` (Linux)
or `operator` (Windows) are never asked: give them in an answers file, or as `--program-dir`,
`--data-dir`, `--user` (`-ProgramDir`, `-DataDir`).

### An install with no questions

An answers file holds the same answers, one `KEY=VALUE` a line. The release's
`install.conf.example` explains every key: copy it, change what you need, and check it with
`--dry-run` (`-DryRun`). **It holds no secrets**: install refuses a key naming a password, secret or
token, an unknown key, or a key given twice, naming each by its line. Every secret is generated, or
typed when install asks, or piped in on one line of standard input: the first admin's temporary
password with the bundled Keycloak, or your identity provider's client secret.

```
sudo ./kilnmark.sh install --answers install.conf < first-admin-password.txt
```

### What install does

It prints each step as `[n/N]`, then OK or FAILED with the time it took and, on a failure, one
line saying why. On Linux:

1. Check the answers.
2. Check the prerequisites (as `check` does), stopping at any problem, with its fix.
3. Make the account `kilnmark` (or keep it, when it is there), with rootless Podman's id ranges and
   lingering, so its containers start at boot.
4. Place the release in the program folder, `/opt/kilnmark`, root's and read-only to everyone else.
5. Make the data folder, `/var/opt/kilnmark`, the account's alone, with `config/.env` (new secrets,
   readable by the account only) and `config/install.conf` (the answers, no secrets).
6. Get the images: load the offline file, pull them (checking their signatures), or build them
   with `--build`.
7. Start Kilnmark and wait until it answers, setting up the hourly expiry of unused temporary
   passwords with the bundled Keycloak.
8. Add the first admin, with the bundled Keycloak and a first admin.
9. Turn the Starter Pack on, on a Starter Pack install.

Windows does the same, with the folders' step (3 to 5 on Linux) as one step run as an
administrator: `C:\Program Files\Kilnmark`, the Administrators', read-only to everyone else, and
`C:\ProgramData\Kilnmark`, the operator's, SYSTEM's and the Administrators', nobody else's.

A fresh install only: install refuses a program folder that already holds Kilnmark, and a data
folder that is already there. Installs from 0.1.1 and earlier are installed afresh
([Upgrading](docs/guide/install/upgrade.md)).

### Where things are

| | Linux | Windows |
| --- | --- | --- |
| The program: releases (`versions/<version>/`), `current` or `current.txt`, the launchers, `data-folder.txt` | `/opt/kilnmark`, root's | `C:\Program Files\Kilnmark`, the Administrators' |
| Settings and secrets | `/var/opt/kilnmark/config/.env` | `C:\ProgramData\Kilnmark\config\.env` |
| The answers | `/var/opt/kilnmark/config/install.conf` | `C:\ProgramData\Kilnmark\config\install.conf` |
| HTTPS's certificate and key | `/var/opt/kilnmark/certs/https` | `C:\ProgramData\Kilnmark\certs\https` |
| Backups and logs | `/var/opt/kilnmark/backups`, `logs` | `C:\ProgramData\Kilnmark\backups`, `logs` |
| Kilnmark's data | the compose project's volumes (`kilnmark_pgdata`, `kilnmark_keys`, `kilnmark_files`, and Keycloak's) | the same, in the Podman machine |

Paths with spaces work: the Windows default has one.

## After install

Open Kilnmark at the address install printed and sign in with the admin token,
`KILNMARK_ADMIN_TOKEN` in `config/.env`, or as the first admin. Under **API tokens**, make a
permanent admin token for yourself and keep it safe. Then, as the operator (on Linux
`sudo -iu kilnmark /opt/kilnmark/kilnmark.sh <command>`; on Windows
`"C:\Program Files\Kilnmark\kilnmark.cmd" <command>`, never elevated):

- `up`, `down`, `status`, `logs` start, stop and show Kilnmark;
- `check` checks the machine and the install, with the fix for each problem;
- `configure KEY=VALUE` changes an answer later (never the mode);
- `people add` adds people to the bundled Keycloak;
- `backup`, every day: [Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md).

Every command is in [The control script](docs/guide/install/control-script.md).

## Looking for a section that was here

| Was "INSTALL.md, ..." | Now |
| --- | --- |
| "A. Install a release", "The control script", "Start and stop with one click" | [The control script](docs/guide/install/control-script.md) |
| "Choosing a deployment" | [Choosing a deployment](docs/guide/install/choosing-a-deployment.md) |
| "Try Kilnmark with the Starter Pack" | [A Starter Pack install](docs/guide/install/starter-pack.md) |
| "Keycloak, if you have no identity provider", "Adding people to the bundled Keycloak" | [Production with the bundled Keycloak](docs/guide/install/production-bundled-keycloak.md) |
| "Sign in with your identity provider", "Review who has access", "Two people for sensitive values" | [Production with your own identity provider](docs/guide/install/production-own-identity-provider.md) |
| "HTTPS", "Kilnmark's own database" | [HTTPS and TLS](docs/guide/install/https.md) |
| "Sizing the workers", "A second worker", "The files folder", "Run pipelines and flows from your scheduler" | [Running Kilnmark](docs/guide/install/running-kilnmark.md) |
| "Check a release" | [Checking a release](docs/guide/install/checking-a-release.md) |
| "Upgrading from 0.1.1 or earlier" | [Upgrading](docs/guide/install/upgrade.md) |
| "Back up and restore" | [Backing up and restoring Kilnmark](docs/guide/backup-and-restore.md) |
| "B. From source, with the reference lab and the samples", "Check it works" | [Development](docs/development.md) |
