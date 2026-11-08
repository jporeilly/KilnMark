# Running Kilnmark

## Sizing the workers

Scans, quality checks and pipeline runs are jobs that wait in a queue in Kilnmark's database. A
process with the worker role takes them a few at a time. Each job it runs holds one of its
**slots**: `KILNMARK_WORKER_CONCURRENCY` (2 by default). Flows hold no slot, since they only decide
which pipeline runs next; a worker watches up to `KILNMARK_WORKER_FLOWS` of them at once (8 by
default). A job or flow beyond these waits in the queue until there is room
([ADR 0040](../../adr/0040-parallel-runs-and-limits.md)).

To run more at once, raise the slots, or add a second worker (below). Two limits keep that from
overwhelming what Kilnmark reads and writes: a source's **Most runs at once**, set on the source,
which caps the runs using it whichever worker runs them
([Sources that take a few runs at a time](../pipelines.md#sources-that-take-a-few-runs-at-a-time)),
and the database connections below.

More running at once needs more connections to Kilnmark's own database. Each running job holds one
for its lease and uses one or two more while it works; the flows a worker watches share one for
their locks, and each uses one now and then while it looks; and each worker keeps one open to hear
of new jobs. So each Kilnmark process needs a pool (`QUARKUS_DATASOURCE_JDBC_MAX_SIZE`, 30 by
default) of at least

```
concurrency × 3 + flows + 6
```

where the 6 is one for the worker's listening and five for the API and the scheduler. With the
defaults that is 2 × 3 + 8 + 6 = 20, so the default pool leaves room to raise the slots to 5. With
8 slots and 8 flows it is 38: set `QUARKUS_DATASOURCE_JDBC_MAX_SIZE=40`. A process with the worker
role only needs five fewer (`concurrency × 3 + flows + 1`), and one without it needs five.

Kilnmark checks this at start and refuses to start with a smaller pool, saying how many
connections it needs and which settings to raise or lower: a pool too small works while little
runs, then fails requests and runs under load. Raise the pool, or lower
`KILNMARK_WORKER_CONCURRENCY` or `KILNMARK_WORKER_FLOWS`, and start it again. PostgreSQL's
`max_connections` (100 in the bundled database) must be at least the sum of the pools of every
Kilnmark process, plus what backups and your own tools use.

The sources themselves see one connection, or a few, per run that reads or writes them; set their
**Most runs at once** where their administrators ask for a cap. It counts runs, not sessions, and
previews, scans and quality checks are outside it.

## A second worker

Any number of Kilnmark processes can share one database: the queue hands each job to one worker
only, and a worker that stops has its jobs taken up by another. To add capacity, run a second
Kilnmark with the worker role only, for example as another service in a compose file of your own,
copied from the `kilnmark` service with:

- `KILNMARK_ROLES: worker`, so it takes jobs but serves no one (keep `api` and `scheduler` on the
  first);
- the same database settings (`QUARKUS_DATASOURCE_*`);
- the same key for stored source passwords: mount the same `keys` volume on the same machine, or on
  another machine set `KILNMARK_SECRET_KEY` on both to the key from the first's
  `/var/lib/kilnmark/secret.key`. A worker with another key cannot open the sources' passwords, and
  says so in its log at start;
- the same files mounted at `KILNMARK_FILES_ROOTS`, and the same drivers (`drivers/` in the keys
  volume), so it can reach every source the first can;
- no published port, and the same `stop_grace_period`.

Size its pool as above, and count its pool in `max_connections`. Never edit
`versions/<version>/compose.yaml` in the program folder: it is root's (or the Administrators'), and
upgrades replace it.

## The files folder

File sources read, and with **Allow writing** on write, the folder `/srv/files` inside Kilnmark's
container (`KILNMARK_FILES_ROOTS`). Every install has one: the `files` volume (`kilnmark_files`),
writable by Kilnmark; the samples and the Starter Pack write only there. To let file sources read a
folder of your own, such as a share, set its full path in `config/.env` and apply it with `up`:
Kilnmark sees it read-only at `/srv/share`, and never writes in it.

```
KILNMARK_FILES_DIR=/srv/kilnmark-files
```

Kilnmark's user in the container (uid 1001) must be able to read it. On a host with SELinux,
Podman labels the folder for Kilnmark's container (`:Z`); if other services use the same folder,
give Kilnmark a copy. In Kilnmark, add a source of kind **files** with path `/srv/share` (your
folder) or `/srv/files` (Kilnmark's own), or a folder inside either. `backup` does not back the
files folder up: back up your own folder, or the volume, as you back up other files.

## Run pipelines and flows from your scheduler

The release folder holds `kilnmark-run.sh` (Linux and Unix) and `kilnmark-run.cmd` with
`kilnmark-run.ps1` (Windows). Copy them to the machines where Control-M, AutoSys, cron or Windows
Task Scheduler run their jobs: each starts a pipeline or flow, waits for it to end and exits with
what happened. Give the scheduler a steward token of its own, in a file only its jobs can read. See
[Running from an external scheduler](../external-schedulers.md)
([ADR 0047](../../adr/0047-external-schedulers.md)).
