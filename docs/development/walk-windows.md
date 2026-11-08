# Walking the Windows install (release 0.1.2)

The unit tests (`ControlScriptInstallTest`) run the Windows edition as they are, never elevated, so
they cannot prove the parts that run as an administrator, the folders' real permissions, or UAC.
This walk does, on a **clean Windows 11 machine** (a VM), with two accounts. Run it before a
release, and record each step's output.

## The machine

- A fresh Windows 11 VM, nothing of Kilnmark on it (no `C:\Program Files\Kilnmark`, no
  `C:\ProgramData\Kilnmark`), at least 16 GB of memory and 60 GB of disk.
- Two local accounts:
  - `kmadmin`, a member of **Administrators**: approves UAC.
  - `kmop`, a **standard user**: the operator, who installs and runs Kilnmark day to day.
  - (A third, `kmother`, a standard user, for step 9.)
- As `kmop`: Podman Desktop 1.x with Podman 5, a machine made with `podman machine init --rootful`
  and started. WSL **2.7.11** (`wsl --version`), not 3.0.x: install it from
  `wsl.2.7.11.0.x64.msi` (GitHub's WSL release 2.7.11) if Podman Desktop updated it. Docker Compose
  (`winget install Docker.DockerCompose`) for `podman compose`. cosign
  (`winget install Sigstore.Cosign`).
- The release folder `kilnmark-0.1.2` (and its `kilnmark-0.1.2-images.tar.gz` for an offline walk)
  in `C:\Users\kmop\Downloads`. Check them first (`docs/guide/install/checking-a-release.md`).
- Throwaway ports, none of 8080, 8780, 3306, 9000, 9099, 5442, 8050, 3307, 1522, 14330, 5433, 1025
  or 8025: below, 18780 (Kilnmark), 18443 (HTTPS), 15442 (database), 18180 (Keycloak).

Keep a log: run each step in **Windows PowerShell** as `kmop` (not elevated) and copy its whole
output, with the time, into `walk-windows-log.txt`; or start a transcript once:
`Start-Transcript $HOME\walk-windows-log.txt`.

## The answers

`C:\Users\kmop\Downloads\starter.conf`:

```
mode=starter-pack
email_domain=walk.example
host=localhost
tls=off
port=18780
https_port=18443
db_port=15442
keycloak_port=18180
admin_email=ada@walk.example
admin_first_name=Ada
admin_last_name=Walker
project=kmwalk
```

`C:\Users\kmop\Downloads\production.conf`: the same, with `mode=production` and `identity=bundled`
in place of `mode=starter-pack`, and `project=kmwalkprod`.

## The walk

Tick each step, and note OK or FAILED with the time it took (install prints `[n/N] OK (12.3 s)`).

| # | As | Do | Expect |
| --- | --- | --- | --- |
| 1 | kmop | `cd $HOME\Downloads\kilnmark-0.1.2; .\kilnmark.cmd check` | `Checking this machine`; `OK: Podman 5...`, `OK: WSL 2.7.11...`, cgroups v2 with pids; no `Problem:`. Each warning has a `Fix:` line |
| 2 | kmop | `.\kilnmark.cmd install -Answers ..\starter.conf -DryRun` | `[1/2] OK`, `[2/2] OK`, the answers listed, `Nothing was changed (-DryRun).` No UAC prompt. `C:\Program Files\Kilnmark` and `C:\ProgramData\Kilnmark` do not exist |
| 3 | kmop | Break an answer: copy `starter.conf`, add `admin_password=x`, run step 2 with it | `line 13: admin_password is a secret, and an answers file holds none`, nothing changed, the value never shown |
| 4 | kmop | Elevated refusal: open PowerShell **as administrator** (sign in as kmadmin at the prompt) and run `.\kilnmark.cmd install -Answers ..\starter.conf` | `Run install from a prompt that is not elevated`; nothing made. Close that window |
| 5 | kmop | `.\kilnmark.cmd install -Answers ..\starter.conf` (or with `-Offline ..\kilnmark-0.1.2-images.tar.gz`); type the first admin's temporary password twice | `[1/7]` to `[7/7]`, each OK with its time. At step 3 the text says why, and **UAC asks for kmadmin's password**: give it. A second window runs that step and closes. Then images, start, the first admin, the Starter Pack on |
| 6 | kmop | `icacls "C:\Program Files\Kilnmark"`; `icacls "C:\ProgramData\Kilnmark"`; `icacls C:\ProgramData\Kilnmark\config\.env`; `(Get-Acl "C:\Program Files\Kilnmark").Owner` | Program folder: `BUILTIN\Administrators:(OI)(CI)(F)`, `NT AUTHORITY\SYSTEM:(OI)(CI)(F)`, `BUILTIN\Users:(OI)(CI)(RX)`, nothing inherited (no `(I)`), owner Administrators. Data folder and `.env`: Administrators (F), SYSTEM (F), `kmop` (M), nothing else |
| 7 | kmop | `& "C:\Program Files\Kilnmark\kilnmark.cmd" status`; `... check` | Version 0.1.2, both folders named, containers up, `Health: ready`, `People expiry: hourly`. `check`: every folder check OK, `nobody else may change what the hourly run uses` OK |
| 8 | kmop | `schtasks /Query /TN "Kilnmark kmwalk people expire" /V /FO LIST` | Task To Run: `"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" ... -File "C:\Program Files\Kilnmark\kilnmark.ps1" people expire -Project kmwalk`, the path quoted. Run it now: `schtasks /Run /TN "Kilnmark kmwalk people expire"`; `C:\ProgramData\Kilnmark\logs\people-expire-<date>.log` ends `people expire: done.` |
| 9 | kmother | Sign in as `kmother`. Try: `New-Item "C:\Program Files\Kilnmark\x.txt"`; `Get-Content C:\ProgramData\Kilnmark\config\.env`; `Add-Content "C:\Program Files\Kilnmark\kilnmark.ps1" '#'`; `& "C:\Program Files\Kilnmark\kilnmark.cmd" up` | Each refused: access denied for the first three; `up`: `Kilnmark runs as ...\kmop: sign in to Windows as them`. Sign out |
| 10 | kmop | Elevated day-to-day refusal: in an elevated prompt, `& "C:\Program Files\Kilnmark\kilnmark.cmd" up` | `up runs as the operator, from a prompt that is not elevated` |
| 11 | kmop | Sign in to Kilnmark: open http://localhost:18780, **Sign in with Keycloak** as `ada@walk.example` with the temporary password; choose a new one; set up a one-time code; sign in again with it | Kilnmark opens, as an admin; the marking "Starter Pack: training and development only" on every page; **Settings > Starter Pack** on |
| 12 | kmop | Check the sign-in through the API: `curl.exe -s -o NUL -w "%{http_code}" http://localhost:18780/q/health/ready` (200); with the admin token from `.env` in a curl config file (never on the command line): `curl.exe -s -K token.conf http://localhost:18780/api/v1/audit?limit=5` | 200; the audit log shows `auth.person_added` for ada and `starter.added`. Delete `token.conf` |
| 13 | kmop | `& "C:\Program Files\Kilnmark\kilnmark.cmd" starter people` (type a password twice) | The five example people made; sign in as `vera.lane@kilnmark.com` works |
| 14 | kmop | `... people add` (a person `bob@walk.example`, `kilnmark-viewers`, a temporary password) | `made bob@walk.example`, recorded |
| 15 | kmop | `... configure` then `... configure email_domain=walk2.example`, then `... configure mode=production` | Answers listed; `Nothing to run again`; `An install's mode never changes` |
| 16 | kmop | `... configure port=18781` | `[3/3] OK`; Kilnmark restarted, answers on http://localhost:18781; `configure port=18780` back |
| 17 | kmop | `... backup` | A folder `C:\ProgramData\Kilnmark\backups\kilnmark-<time>` with `kilnmark-db.dump`, `kilnmark-keys.tar`, `keycloak-db.dump`, `backup.txt`, `SHA256SUMS` |
| 18 | kmop | `... uninstall -Purge`, type `DELETE`; UAC asks: give kmadmin's password | `[1/2] OK` (task removed, containers, volumes, images gone), `[2/2] OK`; `C:\Program Files\Kilnmark` and `C:\ProgramData\Kilnmark` gone; `schtasks /Query /TN "Kilnmark kmwalk people expire"` finds nothing |
| 19 | kmop | `.\kilnmark.cmd install -Answers ..\production.conf` | `[1/6]` to `[6/6]` OK: no Starter Pack step; the first admin added. Sign in as ada works; no marking; **Settings > Starter Pack** offers nothing; `starter people` refused (`This is a production install`) |
| 20 | kmop | `... check` | OK, a production install; no Starter Pack setting |
| 21 | kmop | `... uninstall -Purge` | Both folders gone |
| 22 | kmop | Spaces: copy `production.conf` with `program_dir=C:\Program Files\Kilnmark Walk` and `data_dir=C:\ProgramData\Kilnmark Walk`, `project=kmspace` and other ports; install, `status`, step 8's task check, `backup`, `uninstall -Purge` | Each OK; the task's path quoted |

## What to send back

The log, the table with each step OK or FAILED and its time, and for any FAILED step the output
and `C:\ProgramData\Kilnmark\logs\install-elevated-*.log` (or the `logs` of the step's command).
