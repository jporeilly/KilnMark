# 0044 Installing and running Kilnmark with one control script, from signed images

Status: Accepted (5 November 2026)

## Context

A release was a folder to copy, a `.env` to fill in by hand with two secrets, and
`compose up --build`, which built both images on the customer's machine. That had five problems:

- **What ran was never what was checked.** The images were built from floating tags
  (`eclipse-temurin:25-jre`, `postgres:18`) and `apt-get`, on each site, so the SBOMs and the
  scan in CI never covered the operating-system layers a customer ran, and nothing was signed.
- **A first start needed the internet**, for Docker Hub and the PostgreSQL apt repository. Many
  banks install from a file.
- **The compose project took the folder's name**, so a new version's folder started with new,
  empty volumes.
- **Nothing came back after a reboot**: no service had a restart policy.
- **Every step was by hand**: prerequisites, secrets, permissions on `.env`, and on Windows no
  backup or restore at all, since both were shell scripts.

## Decision

1. **CI builds the images once, from the release folder, on the tag.**
   - Both base images are pinned by digest, and Dependabot moves the digests.
   - The images are pushed to `ghcr.io/jporeilly/kilnmark-server` and `-postgres` and signed by
     digest with the release key (ADR 0033).
   - They are also saved as two signed offline files, `kilnmark-<version>-images.tar.gz` and
     `-keycloak-images.tar.gz`, which `docker load` and `podman load` both read.
   - The image packages are public; the repository stays private. The images hold the built
     server, and no default secret, so anyone may pull them.
2. **One control script per platform**, at the root of the release and of an install:
   - `kilnmark.sh` on Linux;
   - `kilnmark.ps1` on Windows, Windows PowerShell 5.1 and ASCII only, run through
     `kilnmark.cmd`.

   They take the same commands, and on Linux `prepare` (below): `install`, `up`, `down`, `restart`, `status`, `logs`, `check`,
   `verify`, `load-images`, `pull`, `build`, `backup`, `restore`, `upgrade` and `uninstall`.
   With no command they start Kilnmark, so the existing shortcuts call them.
3. **On Linux, a service account that is not root.** `sudo kilnmark.sh prepare`, once, makes the
   `kilnmark` system account (no password; reached with `sudo -iu`). It gives the account rootless
   Podman (its own id ranges in `/etc/subuid` and `/etc/subgid`, and lingering, so its containers
   start at boot without a sign-in) or the `docker` group. It makes `/opt/kilnmark` the account's
   own, `750`, and places the release there. Every other command runs as that account (owner
   decision, 5 November 2026). On Windows, Kilnmark runs as the person who installs it.
4. **An install root**: `/opt/kilnmark` or `C:\Kilnmark` by default, any folder without spaces
   with `--root`. It holds:
   - `versions/<version>/`, a release that is never edited, with `current` (a symlink) or
     `current.txt` naming the one that runs;
   - `config/.env` and the version's `.env.example` beside it;
   - `certs/https`, `backups` and `logs`.

   `config`, `certs` and `backups` are readable by the installing account only (`700`, or an
   ACL on Windows), and `.env` is `600`. Data stays in named volumes, not folders: on Windows a
   bind mount crosses into the WSL VM, where it is slow and PostgreSQL cannot set its owner or
   mode.
5. **Install generates the secrets and never overwrites them.**
   - It makes the database password and the admin token (32 random bytes each) into
     `config/.env`, and with `--auth`, Keycloak's two passwords and the client secret too.
   - An existing `.env` is kept as it is. `check` and `upgrade` list the settings a new
     `.env.example` adds.
   - No command prints a secret: install says where the admin token is.
6. **Signatures are checked with cosign** where the script can:
   - the offline images file and an upgrade's archive, against their `.sigstore.json`;
   - pulled images, by digest.

   Without cosign the script refuses unless given `--skip-verify`, which it records in the log.
   It cannot check the archive it came in: a first install is checked by hand before unpacking
   (INSTALL.md, "Check a release").
7. **The compose project is always `kilnmark`.** It is `name: kilnmark` in `compose.yaml` and `-p`
   on every call, or `KILNMARK_PROJECT` for a second install on one machine. Every long-running
   service gets `restart: unless-stopped`. On Linux with rootless Podman, install enables the
   `podman-restart` user service and says to turn on lingering.
8. **Upgrade goes forward only.**
   1. It checks the new archive and backs up, unless given `--no-backup`.
   2. It unpacks the new release beside the old one, gets its images and starts it.
   3. If the new version does not start healthy, `current` goes back to the old version and the
      script prints the restore command, since migrations only go forward.
   4. The two newest versions are kept.
9. **Backup and restore on Windows** do what `backup.sh` and `restore.sh` do, with the same files,
   so a backup moves between platforms. They copy files in and out with `cp`, never through a
   pipe, because Windows PowerShell 5.1 would change the bytes.

## Choices made without the owner, to change if wished

- **No self-signed HTTPS for trials.** Kilnmark on one machine is reached at `http://localhost`.
  For other machines, the customer's certificate goes in `certs/https`, or a proxy ends TLS.
- **No start at sign-in on Windows by default.** The Kilnmark shortcut can be put in
  `shell:startup`.
- **amd64 images only** for now.
- **Keycloak's images ship as their own offline file**, which only `--auth` sites need.
- **Secrets stay in `.env`**, readable by its owner only, rather than compose secret files. That
  would need the server to read settings from files.
- **Docker and Podman are both supported on both platforms.** CI proves Docker on Linux. Podman
  on Windows was run by hand for this change.

## Consequences

- Every site runs the images CI scanned and signed. An offline site needs two files and cosign.
- A 0.0.9 install, a flat folder whose project took the folder's name, moves over by backing up
  with its `backup.sh`, installing this version, and restoring the backup.
- The release workflow's rehearsal runs `prepare`, then, as the kilnmark account, installs the
  release with `kilnmark.sh` from the offline file on Docker, and backs up, restores, stops, starts
  and purges it, so a broken script fails the pull request.
- The owner makes the two image packages public on GitHub once, after the first tag pushes them.
