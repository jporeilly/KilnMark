# Walking the Linux install (release 0.1.2)

The unit tests run the Linux edition as an ordinary user, never as root, so they cannot prove the
parts install runs as root, the account it makes, rootless Podman, the systemd timer or the
folders' real owners. This walk does, in a throwaway WSL distribution, with three accounts:
`kmadmin` (an admin, who may sudo), `kilnmark` (the account install makes, which runs Kilnmark) and
`kmuser2` (another ordinary user).

## Set it up

On Windows, with WSL 2.7.11 (`wsl --version`), from a fresh Ubuntu image (here 26.04.1, checked
against Ubuntu's `SHA256SUMS`), never one of your own distributions:

```
wsl --import km-clean D:\km-walk\km-clean D:\km-walk\download\ubuntu-26.04.1-wsl-amd64.wsl
wsl -d km-clean -u root -- bash /mnt/c/<checkout>/docs/development/walk/setup.sh
```

`walk/setup.sh` makes `kmadmin` (in `sudo`) and `kmuser2`, and installs Podman (5.7.0 on 26.04) with
podman-compose (1.5.0), curl and tar. The release is built on Windows (`./gradlew release`) and
packed as `kilnmark-<version>.tar.gz`.

## Walk it

```
wsl -d km-clean -u root -- bash /mnt/c/<checkout>/docs/development/walk/walk.sh /mnt/d/km-walk/kilnmark-<version>.tar.gz
```

[`walk/walk.sh`](walk/walk.sh) runs each step as the account a real
install uses and prints `WALK [n] title: OK (t s)` or `FAILED`. A Starter Pack install with the
default folders, then a Production install with the bundled Keycloak in folders with spaces:

1. `kmadmin` unpacks the release, runs `check` (the machine) and `install --dry-run`; an answers
   file with a secret is refused.
2. `kmadmin` runs `./kilnmark.sh install --answers starter.conf --build --skip-verify`, without
   sudo: install says why it needs root and runs itself again with `sudo`, then builds the images,
   starts Kilnmark, adds the first admin and turns the Starter Pack on.
3. The folders: `/opt/kilnmark` root's, `/var/opt/kilnmark` `kilnmark`'s, `config/.env` `600`,
   `install.conf` with no secret.
4. As `kilnmark`: `status`, `up`, the hourly timer (its `ExecStart` quoted) and a run of it,
   `starter people`, a sign-in as `vera.lane@kilnmark.com` through Keycloak ([`walk/signin.py`](walk/signin.py): the
   password, a one-time code set up, the sign-in again with a code, then `/api/v1/auth/status`),
   `people add`, `people list`, `check`, `configure` (show, `email_domain`, `mode` refused, `port`
   and back), `backup`.
5. `kmuser2` cannot write `/opt/kilnmark` or its launcher, cannot write or read the data folder,
   and cannot run Kilnmark; root's `up` is refused.
6. `kmadmin` runs `uninstall --purge`: nothing is left.
7. A Production install, `program_dir=/opt/kilnmark walk`, `data_dir=/var/opt/kilnmark walk`:
   install, `status`, `check`, the timer's quoted path and a run, `people list`, `starter people`
   refused, `backup` into a folder with a space, `kmuser2` refused, `uninstall --purge`.

Remove the distribution afterwards: `wsl --unregister km-clean`.

## The walk of 8 November 2026

Ubuntu 26.04.1, Podman 5.7.0, podman-compose 1.5.0, WSL 2.7.11 with mirrored networking; the
release built from this branch (`./gradlew release`, version 0.1.1 in `gradle.properties` until the
0.1.2 bump), images built by install (`--build`). **40 steps, 0 FAILED.**

| # | Step | Result |
| --- | --- | --- |
| 1-4 | Unpack, `check` the machine, `install --dry-run`, an answers file with a secret refused | OK (0.5, 9.2, 19.5, 0.4 s) |
| 5 | Starter Pack install as kmadmin, through sudo: [1/10] answers 8.4 s, [2/10] prerequisites 10.8 s, [3/10] account 1.8 s, [4/10] program folder 0.5 s, [5/10] the account's Podman 11.6 s, [6/10] data folder 1.6 s, [7/10] images 86.3 s, [8/10] start 297.4 s, [9/10] first admin 115.3 s, [10/10] Starter Pack on 6.8 s | OK (541.1 s) |
| 6 | `/opt/kilnmark` and its files root's (`755`, `data-folder.txt` `644`); `/var/opt/kilnmark`, `config` `700`, `.env` and `install.conf` `600`, kilnmark's; no secret in `install.conf` | OK |
| 7-10 | `status`; `up` again (184.8 s); the timer, `ExecStart="/opt/kilnmark/kilnmark.sh" people expire`; a run of it (`people expire: done.`) | OK |
| 11-12 | `starter people` (password piped in, 100.9 s); vera.lane signs in through Keycloak: the password, a one-time code set up, Kilnmark's sign-in again with a code, then `/api/v1/auth/status` `{"signedIn": true, "refusal": null}` | OK (11.5 s) |
| 13-15 | `people add` bob (110.6 s); `people list` shows ada and bob; `check` | OK |
| 16-19 | `configure` shows the answers; `email_domain` changed (nothing to run again); `mode` refused; `port=18781` (restart, health 200) and back (287.5 s) | OK |
| 20 | `backup` | OK (10.3 s) |
| 21-26 | kmuser2 cannot write `/opt/kilnmark` or its launcher, cannot write the data folder or read `.env`, cannot run Kilnmark; root's `up` refused | OK |
| 27-28 | `uninstall --purge` (16.4 s): folders, timer and containers gone | OK |
| 29 | Production install, bundled Keycloak, `/opt/kilnmark walk` and `/var/opt/kilnmark walk`: [1/9] to [9/9], start 240.5 s, first admin 182.8 s | OK (484.2 s) |
| 30-36 | Folders with spaces as install leaves them; `status`; `check`; the unit quotes `/opt/kilnmark walk/kilnmark.sh` and its run; `people list` shows the first admin; `starter people` refused; `backup` into `/var/opt/kilnmark walk/backups` | OK |
| 37-40 | kmuser2 refused on both folders; `uninstall --purge`; nothing left | OK |

What the walk found and fixed on the way (four earlier tries): a connection to a free port can hang
under WSL's mirrored networking (port checks now give up after two seconds); `localhost` there can
reach the wrong place (every wait and probe uses `127.0.0.1`); podman-compose 1.5's `run` stopped
the database and removed the server while it set up `keycloak-setup`'s dependencies (the one-off
now runs with `--no-deps`); `compose exec` read the lines `people add` was piped (it now reads
nothing); an install log listed before it existed ended a run under `set -e`; and `configure`
re-checked the first admin against a new email domain.

Not walked here: `upgrade` (it needs a second release), TLS (`tls=on` or `proxy`), your own
identity provider, `restore`, and Docker. Since 0.1.3 CI walks `upgrade` from the previous release
and `restore` on every pull request: [the upgrade rehearsal](../development.md#the-upgrade-rehearsal).
