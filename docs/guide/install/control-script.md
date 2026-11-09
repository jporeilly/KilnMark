# The control script

One script installs, starts, stops, configures, checks, backs up and upgrades Kilnmark:
`kilnmark.sh` on Linux, `kilnmark.cmd` (which runs `kilnmark.ps1`) on Windows
([ADR 0044](../../adr/0044-installing-with-one-control-script.md)). After install it is in the
program folder: `/opt/kilnmark/kilnmark.sh`, or `C:\Program Files\Kilnmark\kilnmark.cmd`. Run with
no command, it starts Kilnmark. On Windows, options are written `-Offline`, `-SkipVerify` and so on.
In PowerShell, start the line with `&`, as the commands in these guides and the script's own
messages do: `& "C:\Program Files\Kilnmark\kilnmark.cmd" status` (the path is in quotes because of
its space, and PowerShell runs a quoted path only after `&`). In Command Prompt, leave out the `& `.

## Who runs what

| Commands | Linux | Windows |
| --- | --- | --- |
| `install`, `upgrade`, `uninstall` | As root: the script says why and runs itself again with `sudo` | From a prompt that is **not** elevated: Windows asks for an administrator (UAC) for the one step that changes the folders, saying why |
| Every other command | As the account that runs Kilnmark, never as root: `sudo -iu kilnmark /opt/kilnmark/kilnmark.sh <command>` | As the operator install named, from a prompt that is not elevated |

Each day-to-day command refuses root (Linux), an elevated prompt (Windows), and on Windows anyone
but the operator, saying how to run it instead. `check` runs as anyone, and says so. An account that is always elevated (Windows' built-in Administrator, or any administrator with UAC turned off) can never be the operator: run Kilnmark as an ordinary account, with UAC on. Podman runs as
the operator: on Windows the Podman machine is theirs, on Linux the account's rootless Podman.

## Commands

| Command | What it does |
| --- | --- |
| `install [--answers FILE] [--dry-run]` | The guided install ([INSTALL.md](../../../INSTALL.md#install)): the questions, or an answers file; `--dry-run` checks them and the prerequisites and changes nothing. Also `--starter`, `--offline FILE`, `--build`, `--skip-verify`, `--no-start`, `--program-dir`, `--data-dir`, `--user` |
| `up` | Starts Kilnmark (and Keycloak with the bundled Keycloak), waits until it answers, and opens it in the browser. First it adds to `config/.env` any secret this version needs that it lacks |
| `down` | Stops it. Every volume, so all data, is kept |
| `restart [service]` | Restarts everything (a stop, then `up`, so `keycloak-setup` and `samples-db` run again), or one service |
| `status` | Version, folders, containers, health, the last backup, and, with the bundled Keycloak, the hourly expiry of unused temporary passwords |
| `logs [service] [-f]` | The last 200 lines of the logs, or follows them |
| `compose ARGS` | Runs compose as Kilnmark runs it, with the install's project, settings, secrets and profiles, for a command by hand: `compose ps`, `compose exec postgres ...` |
| `check` | Checks the machine and the install, for its mode, and prints the command that fixes each problem (below). Changes nothing |
| `configure [KEY=VALUE ...]` | Shows the install's answers, or changes them (below) |
| `verify FILE` | Checks a downloaded file against its signature |
| `load-images FILE` | Checks and loads an offline images file |
| `pull` | Pulls this version's images and checks their signatures |
| `build` | Builds the images from the release's `containers` folder instead |
| `backup [DIR]` | Backs up Kilnmark's own database and the keys, and the bundled Keycloak's database when it is on, into the data folder's `backups` by default ([Backing up and restoring Kilnmark](../backup-and-restore.md)) |
| `restore BACKUP --yes` | Puts Kilnmark back to a backup |
| `upgrade ARCHIVE` | [Upgrading](upgrade.md) |
| `uninstall [--purge]` | [Uninstalling](uninstall.md) |
| `people add`, `import FILE`, `activate EMAIL`, `list`, `expire` | People of your own in the bundled Keycloak ([Adding people](production-bundled-keycloak.md#adding-people-to-the-bundled-keycloak)) |
| `starter people [--new-password \| --remove]` | The Starter Pack's example people ([A Starter Pack install](starter-pack.md#the-example-people)) |

Every command writes what it said to the data folder's `logs`, and ends with a failure code when it
fails, so it can be scheduled. No command prints a secret. Without cosign, `install`, `upgrade`,
`load-images` and `pull` refuse to go on unless given `--skip-verify` (`-SkipVerify`), which the
log records. Commands whose names start with `_` are the parts of install, upgrade and uninstall
that run as another account; run them only through those three.

On Windows, the logs leave out the error text of a check that finds nothing it needs to worry
about, such as `image not known` before the images are there, or `The system cannot find the file
specified` before the hourly task is set up. Such a check is part of a command working as it
should, so it leaves nothing in the log that reads like a failure. When a check finds something
wrong, the command says so in its own words.

## configure: change an answer later

`configure` with no words shows the answers install was given, from `config/install.conf` in the
data folder. `configure KEY=VALUE [KEY=VALUE ...]` changes them, in three steps, each OK or FAILED:
it checks each answer as install does (a port must be free, the answers must go together), writes
`config/.env` and `config/install.conf`, and runs again only what the change affects:

| Answers | What runs again |
| --- | --- |
| `host`, `tls`, `tls_cert`, `tls_key`, `port`, `https_port`, `db_port`, `keycloak_port`, `identity`, the `oidc_` answers | A restart (a stop, then `up`) when Kilnmark is running, so every container, and `keycloak-setup`, takes the new settings; else nothing until the next `up` |
| `admin_email`, `admin_first_name`, `admin_last_name` | `people add` for that first admin, with a temporary password you type (or pipe in) |
| `email_domain` | Nothing |
| `mode`, `project`, `program_dir`, `data_dir`, `user`, `operator` | Refused: the mode never changes (ADR 0051), and the rest need a new install |

A secret is never given on its command line: changing to your own identity provider, or its client,
asks for the client secret, typed twice and not shown, or one line piped in. Moving to the bundled
Keycloak makes its secrets afresh.

```
kilnmark.sh configure port=9780
kilnmark.sh configure host=kilnmark.bank.example tls=proxy
kilnmark.cmd configure tls=on tls_cert=C:\certs\kilnmark.crt tls_key=C:\certs\kilnmark.key
```

## check: what it checks, and the fixes

`check` knows the install's mode, and checks the machine and the install. Each line is `OK`,
`Warning` or `Problem`, and each warning and problem is followed by `Fix:` and the command that
fixes it. It ends with a failure code when there is a problem.

| Check | A fix it prints |
| --- | --- |
| Podman is there, 5 or later, with a compose tool | `sudo apt-get install -y podman podman-compose`; `winget upgrade RedHat.Podman-Desktop`; `winget install Docker.DockerCompose` |
| The Podman machine runs (Windows) | `podman machine start` |
| WSL is 2.7.11. On this kind of machine Podman needs WSL 2.7.11: WSL 3.0.x breaks crun's pids controller, and no container starts | `Invoke-WebRequest https://github.com/microsoft/WSL/releases/download/2.7.11/wsl.2.7.11.0.x64.msi ...; Start-Process msiexec.exe ...; wsl --shutdown` |
| cgroups v2, with the pids controller | `.wslconfig`'s `kernelCommandLine`, or systemd's `Delegate=` for the account's session |
| Memory (4 GB, 5 GB with the bundled Keycloak) and disk (10 GB) | `podman machine set --memory 6144`; `podman system prune` |
| The ports are free (while Kilnmark is not running) | `kilnmark.sh configure port=9781`, naming a free port |
| curl, tar, cosign | The package to install |
| Root or an elevated prompt | `sudo -iu kilnmark /opt/kilnmark/kilnmark.sh check`; a prompt that is not elevated |
| The program folder is root's or the Administrators', and nobody else may change it | `sudo chown -R root:root /opt/kilnmark && sudo chmod -R go-w /opt/kilnmark`; `icacls ... /setowner *S-1-5-32-544 ...` |
| The data folder is the account's alone (Windows: the operator's, SYSTEM's and the Administrators') | `sudo chown -R kilnmark:kilnmark /var/opt/kilnmark && sudo chmod 700 ...`; `icacls ... /inheritance:r /grant:r ...` |
| What the hourly run uses (below) | The same |
| The mode: production or starter-pack, and the one Kilnmark recorded | The line to put back in `config/.env` |
| A Starter Pack install has the bundled Keycloak and `KILNMARK_STARTER=allowed`; production has no `KILNMARK_STARTER`, and your identity provider's settings | The line to add or delete |
| Kilnmark and Keycloak answer; the hourly expiry is set up | `kilnmark.sh up`; `kilnmark.sh restart` |
| HTTPS from other machines | `kilnmark.sh configure tls=on ...` or `tls=proxy` |
| A backup from the last day | `kilnmark.sh backup` |

Before install, run `check` from the unpacked release: it checks the machine only. Unpack it in your
own Downloads folder, or another folder only you may change ([INSTALL.md](../../../INSTALL.md#install)
says why).

## Where things are, and who may change them

| | Linux | Windows |
| --- | --- | --- |
| Program folder | `/opt/kilnmark`: root's, `755`, read-only to everyone else | `C:\Program Files\Kilnmark`: full control for the Administrators and SYSTEM, read and run for Users, nothing inherited |
| It holds | `versions/<version>/`, `current` (a link to the one that runs), `kilnmark.sh` and the launchers, `data-folder.txt` | `versions\<version>\`, `current.txt`, `kilnmark.cmd`, `kilnmark.ps1` and the launchers, `data-folder.txt` |
| Data folder | `/var/opt/kilnmark`: the account's, `700`; `config/.env` `600` | `C:\ProgramData\Kilnmark`: modify for the operator, full control for SYSTEM and the Administrators, nobody else, nothing inherited |
| It holds | `config/.env`, `config/.env.example`, `config/install.conf`, `config/run`, `certs/https`, `backups`, `logs` | The same |

`data-folder.txt` names the data folder; only root or the Administrators may change it. On Windows
the data folder is on the program folder's drive: compose reaches it by a path relative to the
version's `compose.yaml`. Each release in `versions` is never edited: `upgrade` places the next one
beside it. Kilnmark's data is in the compose project's volumes (`<project>_pgdata`, `_keys`,
`_files`, and `_keycloak-pgdata` with Keycloak), not in the folders. Every long-running container
restarts by itself after a reboot: on Linux, install turns on lingering for the account, and `up`
turns on its `podman-restart` service.

A compose command of your own: `kilnmark.sh compose ARGS` (Windows: `kilnmark.cmd compose ARGS`),
as the operator, which runs compose as the control script does, with the install's project,
settings, secrets and profiles (for example `kilnmark.sh compose ps`, or `compose exec postgres
psql -U kilnmark`). A plain `podman compose` from the program folder lacks them and stops. Start
Kilnmark through the control script: it writes the one-offs' secrets for each run and removes them after, and,
before every compose command, each long-running service's secrets into a file of its own in
`config/run/services` (yours alone), which compose mounts read-only into that service alone. No
secret is a container's setting in `compose.yaml`, nor in an `env_file`: podman-compose puts both
on Podman's command line, where any account on the machine can read them. A secret changed by hand in `config/.env` reaches its service at the next
`restart` (a stop, then `up`), which `configure` runs itself.

## Who may change what a run uses

The hourly `people expire` runs the control script as the operator, so its folders are checked as
a second line of defence, behind the permissions install set
([ADR 0051](../../adr/0051-starter-pack.md), decisions 15 and 17). Before `up` sets the task or timer
up, and at the start of every `people expire` (before it reads anything else), the script checks
what a run uses: in the program folder the launcher, `data-folder.txt`, `current` or `current.txt`,
the current version's `compose.yaml` and `keycloak-setup` script; in the data folder
`config/.env` and `logs`; and every folder from each root down to them. Each must be root's or the
account's (Windows: the operator's, SYSTEM's or the Administrators') and writable by nobody else; a
root-owned or Administrators-owned program folder passes. So nobody can move a folder away and put
another in its place, the folders above each root count too: on Linux root's or the account's,
writable by nobody else (a sticky one such as `/tmp` only when the next one down is the account's or
root's); on Windows nobody but the operator, SYSTEM, the Administrators and TrustedInstaller may
delete what is in them or change who may. On Linux the container engine and the units' folder, and
the folders above them, must be the account's or root's too.

If anything fails, it says which: `up` does not set the task or timer up, and `people expire`
refuses, reads no settings and sends nothing, and the refusal goes to the system's journal (Linux)
or Task Scheduler's history (Windows), and to `logs`. `check` shows the same, with the fix.

The control script sends the admin token only to a listener of the operator's (or root's, on Linux)
on Kilnmark's port, at `127.0.0.1`: if another account's program listens there, the command says so
and sends nothing. With WSL's mirrored networking Windows shows no listener for a container's port,
so there is nothing to check.

## Start and stop with one click

| | Windows 11 | Ubuntu |
| --- | --- | --- |
| Make the shortcuts, once, as the operator | `powershell -ExecutionPolicy Bypass -File "C:\Program Files\Kilnmark\create-shortcut.ps1"` | `/opt/kilnmark/create-shortcut.sh`, as the account, on a desktop it signs in to |
| They go | on the desktop and in the Start menu | in the applications menu and on the desktop |

The shortcuts run `up` and `down`. If something goes wrong, the window stays open and says what, and
which logs to look at. To have Kilnmark start when the operator signs in to Windows, put the
Kilnmark shortcut in `shell:startup`.

## A second install on one machine

A second Kilnmark on the same machine, or one beside a source checkout that already uses the
project name `kilnmark`, needs its own project, folders and ports, in its answers:

```
project=kilnmark2
program_dir=/opt/kilnmark2
data_dir=/var/opt/kilnmark2
port=9780
https_port=9443
db_port=9442
keycloak_port=9180
```

Install writes the project into `config/.env`, so every later command finds it.
