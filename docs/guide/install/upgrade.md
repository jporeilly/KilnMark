# Upgrading

`upgrade` moves an install to a later release, keeping its answers, settings and data. Download the
release (and, without internet access, its images file) into your own Downloads folder, or another
folder only you (and root or the administrators) may change, and check them
([Checking a release](checking-a-release.md)): `upgrade` places what is in the archive as root or
an administrator, so nobody else may be able to change it. On Windows, `upgrade` refuses an archive
someone else may change before it asks for an administrator, and says how to fix it; it then copies
the archive into a folder of yours alone, and checks and unpacks that copy. Then:

| | Linux | Windows |
| --- | --- | --- |
| Online | `sudo /opt/kilnmark/kilnmark.sh upgrade kilnmark-<version>.tar.gz` | `& "C:\Program Files\Kilnmark\kilnmark.cmd" upgrade kilnmark-<version>.zip`, from a prompt that is not elevated |
| Offline | add `--offline kilnmark-<version>-images.tar.gz` | add `-Offline kilnmark-<version>-images.tar.gz` |

On Linux, `upgrade` runs as root, and first refuses a program or data folder with a folder above it
that anyone but root may change (not root's, a link, or writable by its group or others, `/tmp`
too), before it reads anything in the data folder: someone who could swap such a folder for a link
could have root write, change or remove wherever they liked. It says which folder. Make each folder
above it root's and writable by root alone (`sudo chown root:root FOLDER && sudo chmod go-w
FOLDER`), or, with Kilnmark stopped, move the data folder under one that is, as `/var/opt` is, and
write its new path in the program folder's `data-folder.txt`; then run `upgrade` again.

Kilnmark must be running, so `upgrade` can read the mode it recorded. Each step is OK or FAILED,
with the time it took:

1. **Check the new release**: its signature, its version (later than the running one; to go back,
   restore a backup), and that `config/.env` and the database say the same mode.
2. **Back up** the running version (skip it with `--no-backup`, `-NoBackup`).
3. **Get the images** of the new version, as the operator.
4. **Place the new version** beside the running one in the program folder, root's or the
   Administrators' like the rest, and switch to it: on Windows this one step asks for an
   administrator (UAC). The two newest versions are kept.
5. **Start it.** If it does not answer, `upgrade` stops it, switches back and starts the old one
   again, and says so; if that does not start either (the new version may have changed the
   database already), it says how to restore the backup it took.

Settings the new version's `.env.example` names that `config/.env` does not mention are listed at
the end: the new version works without them, and `configure` or `config/.env` sets them.

## From 0.1.1 or earlier

Release 0.1.2 is the first with deployment modes, and the first with the program and its data in
two folders. Installs from before it (0.1.1 and earlier, which were previews, never production
releases), and from the single Windows install folder of the pre-release builds before 0.1.2,
**cannot be upgraded**: install Kilnmark afresh, in the mode you want, and back up first anything
you mean to carry across by hand. On such an install, `upgrade` refuses before it changes anything,
`up` refuses a `config/.env` with no `KILNMARK_MODE`, and Kilnmark itself refuses to start on a
database that was made before this start with no mode recorded: only a new database records one. A
backup with no mode recorded is never restored. `KILNMARK_KEYCLOAK_DEMO_PASSWORD`, which 0.1.1 used
for its example people, is not read by these releases.
