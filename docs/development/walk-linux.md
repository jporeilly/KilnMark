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
wsl -d km-clean -u root -- bash /mnt/d/km-walk/scripts/setup.sh
```

`setup.sh` makes `kmadmin` (in `sudo`) and `kmuser2`, and installs Podman (5.7.0 on 26.04) with
podman-compose (1.5.0), curl and tar. The release is built on Windows (`./gradlew release`) and
packed as `kilnmark-<version>.tar.gz`.

## Walk it

```
wsl -d km-clean -u root -- bash /mnt/d/km-walk/scripts/walk.sh /mnt/d/km-walk/kilnmark-<version>.tar.gz
```

`walk.sh` (kept beside this page's log, in the walk's folder) runs each step as the account a real
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
   `starter people`, a sign-in as `vera.lane@kilnmark.com` through Keycloak (`signin.py`: the
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

See the summary below; the whole log is kept with the PR's walk record.
