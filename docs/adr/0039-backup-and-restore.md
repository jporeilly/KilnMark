# 0039 Backup and restore: a dump of the database, a copy of the keys, kept apart

Status: Accepted (3 November 2026)

## Context

Banks must be able to restore the systems they run, and show that they have tested it: DORA
Article 12 (backup policies, restoration and recovery, with backups kept apart from the source
systems), the FFIEC business continuity handbook, and NIST 800-53 CP-9 and CP-10. Kilnmark keeps
everything in two places:

- **its database** (the `pgdata` volume): sources and their sealed passwords, pipelines and their
  versions, flows, runs, profiles, rules, tokens, notifications and the audit log;
- **its keys volume** (`/var/lib/kilnmark`): `secret.key`, which seals and opens the stored source
  passwords (made when the first one is stored), and the drivers an admin registered.

The rest comes from the release folder and its `.env`, which the customer keeps already.

A logical dump is the usual way to back up one PostgreSQL database, and the only one that moves to a
newer PostgreSQL or a managed server. One part of Kilnmark's database needed checking: Apache AGE
keeps its graph's catalogue rows keyed by object ids, which a dump does not keep. We restored dumps
and tried every Cypher statement Kilnmark runs. A restored graph that holds only AGE's two built-in
labels works, and Kilnmark keeps nothing in the graph (ADR 0019): deleting a source, the one
statement it runs, works after a restore. A restored graph with labels of its own does not
(`graph with oid ... does not exist`).

## Decision

1. **Two scripts in the release folder.** `backup.sh` writes a new folder for each backup: the
   database as a `pg_dump` in PostgreSQL's custom format, the keys volume as a tar, a `backup.txt`
   (when it was taken, the Kilnmark version, the last migration) and `SHA256SUMS`. It reads the
   whole dump back (`pg_restore -f /dev/null`) before it finishes, so a dump cut short fails the
   backup. It runs while Kilnmark runs: the dump is one consistent snapshot.
2. **`restore.sh` never leaves Kilnmark without a working database.** It refuses, before changing
   anything, a backup whose files do not match their checksums (each file checked by name) or one
   from a newer Kilnmark than the release it is run from, and asks for `--yes`. It restores the dump
   into a database of its own in one transaction, while Kilnmark runs; only when that has worked
   does it stop Kilnmark (and check it stopped), rename the old database to
   `kilnmark_before_<time>` and the restored one to `kilnmark`, and replace the keys volume, keeping
   the old keys in it. If it fails part way, it says how far it got. The old database and keys stay
   until the operator removes them, so going back is two renames and a move. A later release
   migrates the restored database at its first start, as an upgrade does.
3. **The server records the restore**, not the script: the script leaves `restored.properties`
   beside the key, and at its next start the server records `database.restored` (the backup, when
   it was taken, the database kept) as a row and as the JSON line the SIEM collects, then deletes
   the file.
4. **The keys are kept apart from the dumps.** A dump holds the source passwords sealed; the key
   opens them. Kept together, one stolen copy gives both. If the keys are lost, `--without-keys`
   restores the database alone; every stored password must then be entered again. A database
   restored without its keys is named at start (how many stored values the key cannot open), and
   using such a value answers 409 saying what to do, rather than a server error.
5. **A restore sets everything back to the backup's time, the audit log too.** What happened since
   is in the SIEM's copy of the log (ADR 0029), and in the kept database. Tokens revoked since then work again, and grants
   given or taken since are undone: the runbook has the operator revoke them again and check the
   access review, and the restore script says so.
6. **Customers with their own PostgreSQL** use their own backups (snapshots, point-in-time
   recovery) for the database, and still back up the keys volume.
7. **A restore drill**, in the runbook, for the customer's schedule: restore a recent backup to a
   spare machine, and check what the runbook lists.

## Consequences

- Recovery point: the time since the last backup (daily is the runbook's starting point; with the
  customer's own PostgreSQL and point-in-time recovery, minutes). Recovery time: our drill on a
  small database (a fresh install from a destroyed machine, the restore, the server ready, a stored
  password tested) took just over a minute after the install; a large database takes as long as
  `pg_restore` needs for it, which the customer's drill measures.
- The graph limit above binds future work: before Kilnmark keeps nodes in the graph, restore must
  rebuild it (it can be made again from the relational tables) or repair its ids, with a test.
- The audit log stays append-only: the restore adds its event as a row like any other.
- A restore runs the dump with full rights on Kilnmark's database, so the checksums show damage,
  not tampering: backups must be kept where only the backup job can write.
- Kept databases and keys use disk and hold sensitive data until the operator removes them.
- The dump holds the audit log and what Kilnmark keeps about sources' data (profiles, quality
  results), so backups are as sensitive as the database: the customer encrypts them and controls
  who reads them.
- A full export in open formats, for exit (DORA Article 28(8)), is a separate item.
