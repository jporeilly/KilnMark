# Backing up and restoring Kilnmark

This is the runbook for keeping Kilnmark recoverable: what to back up, how, how to restore, and how
to test a restore. It covers a release installed with compose (INSTALL.md, A). Why it works this way
is in [ADR 0039](../adr/0039-backup-and-restore.md).

## What to back up

| What | Where | How |
| --- | --- | --- |
| The database: sources and their sealed passwords, pipelines and their versions, flows, runs, profiles, rules, tokens, notifications, the audit log | The `pgdata` volume | `backup.sh` dumps it |
| The keys: `secret.key`, which opens the stored source passwords, and the drivers registered under **Settings › Drivers** | The `keys` volume | `backup.sh` copies it |
| The settings: `.env` (the database password, the admin token, the sign-in settings) | The release folder | Your secrets store, once and after each change |
| Your own files: `tls/`, `branding/`, and the certificate for the database if you gave it one | The release folder, and the `pgdata` volume's `tls` folder | Your usual file backups |

The AI models volume needs no backup: download the models again. The release folder itself can be
fetched again, but keep the version you run.

**Keep the keys apart from the database dumps.** A dump holds the source passwords sealed, and the
key opens them: one copy holding both gives an attacker every password. Put the keys file in a
different place (a secrets store or a separate vault), and encrypt both. Without the key, a restore
still works, but every stored source password must be entered again under **Sources**.

Backups are as sensitive as the database: they hold the audit log, people's names, and what
Kilnmark keeps about your sources' data. Limit who can read them as you limit who can reach the
database.

## Back up

From the release folder, beside `compose.yaml`, while Kilnmark runs:

```
./backup.sh /path/to/backups
```

It writes a new folder, `kilnmark-<UTC time>`, holding:

- `kilnmark-db.dump`: the database, as one consistent snapshot, in PostgreSQL's custom format;
- `kilnmark-keys.tar`: the keys volume;
- `backup.txt`: when it was taken, the Kilnmark version, and the last database migration;
- `SHA256SUMS`: a checksum of each file, which the restore checks.

The script reads the dump back before it finishes, so a backup it reports is one that can be
restored. It uses `podman compose`, or `docker compose` if Podman is not installed;
`KILNMARK_COMPOSE` overrides that. Then move `kilnmark-keys.tar` to where you keep keys, and the rest
to your backup store.

**How often.** Daily is a sensible start: you lose at most a day of changes. Run it from cron or a
systemd timer, for example at 02:00:

```
0 2 * * * cd /opt/kilnmark-0.1.0 && ./backup.sh /srv/backups >> /var/log/kilnmark-backup.log 2>&1
```

Keep as many as your retention policy asks, and at least one copy off the machine. Back up again
before every upgrade.

**Your own PostgreSQL.** If Kilnmark runs on your own PostgreSQL server rather than the bundled one,
use that server's backups for the database (snapshots, or point-in-time recovery, which loses
minutes rather than a day), and still back up the keys volume.

## Restore

A restore puts Kilnmark back as it was when the backup was taken. Everything since is lost.

1. **Install the release** you ran, or a later one (INSTALL.md, A), with your `.env`. A later
   release updates the database at its first start, as an upgrade does; an earlier one cannot run a
   newer database, and the script refuses it.
2. **Restore**, from the release folder:

   ```
   ./restore.sh /path/to/backups/kilnmark-<time>
   ```

   It checks the backup's files against `SHA256SUMS`, then says what it will replace and asks you to
   run it again with `--yes`. With `--yes`, it stops Kilnmark, replaces the database and the keys
   volume with the backup's, records the restore in the audit log, starts Kilnmark and waits until
   it is ready. Put `kilnmark-keys.tar` back in the backup's folder first.
3. **Redo what security changes were made since the backup.** The restored database is from before
   them:
   - **Tokens revoked since then work again.** Find them in your SIEM's copy of the audit log
     (`token.revoked` events after the backup's time) and revoke them again under **API tokens**.
   - Check **Settings › Access review**, and redo marks set or cleared and grants refused since then
     (`mark.*` and `unmask.*` events).
   - People who sign in with your identity provider are not affected: their roles come from the
     provider each time.
4. **Run again** the scans, quality checks, pipelines and flows that ran after the backup, if you
   need their results.

**Settings › Audit log** shows the restore as **Database restored**, with the backup it came from
and when that was taken. Events between the backup and the restore are only in your SIEM; note the
restore in your change record too.

## Test a restore

A backup is proven only by restoring it. Every quarter, or as your policy asks:

1. On a spare machine, install the same release with a copy of `.env` (a different admin token is
   fine), and restore the latest backup with `restore.sh`.
2. Check that:
   - Kilnmark starts, and you can sign in;
   - **Sources** lists your sources, and **Test** on one with a stored password succeeds (the key
     was restored with the database);
   - **Pipelines** and **Flows** list yours, with their versions;
   - **Settings › Audit log** shows the restore, and the events up to the backup's time.
3. Write down how long the restore took (your recovery time) and the backup's age (your recovery
   point), and anything that did not work.
4. Remove the spare machine's copy: it holds the same sensitive data as the backup.

## When something goes wrong

| What you see | What to do |
| --- | --- |
| `Backup failed: the database could not be dumped` | Check the database runs: `podman compose ps`, and its log with `podman compose logs postgres` |
| `The backup's files do not match its SHA256SUMS` | The backup is damaged or incomplete (often the keys file was not put back). Restore an earlier one |
| `The backup is from Kilnmark X, newer than this release` | Install release X or later, then restore |
| `Kilnmark did not become ready` | Read `podman compose logs kilnmark`: it says why it stopped |
| **Test** on a source, or Kilnmark's log at start, says "A stored password cannot be opened with this server's key" | The keys were not restored, or came from another backup: restore the database and the keys from the same backup, or enter each password again under **Sources** (and AI models' keys and the mail password under **Settings**) |
