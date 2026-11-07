# 0051 The Starter Pack: example data turned on and off by an admin in a database of its own, and example people made by Keycloak's setup

Status: Accepted (6 November 2026; decision 5 amended the same day after a security review, and
decisions 5 to 8 after a second)

## Context

A release install had nowhere for the samples to write: `/srv/files` was a commented-out, read-only
mount, so **Library > Add samples** could not work out of the box, and the starter pack (ADR 0043)
needed a database and a file source that allow writing, which a new install does not have. People
trying Kilnmark, giving a demonstration or teaching a course want everything ready: the samples
running, and people to sign in as, one per role.

Kilnmark is sold to banks. Example data and example accounts must never be mistaken for real ones,
must never share a database with Kilnmark's own catalogue, must be easy to take out completely,
and must not arrive on a production install by accident.

The owner decided on 6 November 2026: an opt-in **Starter Pack** for trials, demos and training,
turned on and off by an admin under **Settings**, with Kilnmark doing the work itself, the same on
Linux and Windows; the sample data in a separate database on Kilnmark's own PostgreSQL server; the
example people made only where `.env` allows it. After a security review of the first version
(below, decision 5) the owner decided that the example people are made by Keycloak's own setup
step and that Kilnmark holds no Keycloak credential at all. Templates stay in the product as they
are (ADR 0043).

## Decision

1. **Every install gets a writable files folder.** The release compose file mounts a named volume,
   `files`, at `/srv/files` (which `KILNMARK_FILES_ROOTS` already names), writable by Kilnmark's
   user; the server image makes the folder its user's, so a new volume starts out writable. The
   samples and the pack write only there. A site's own folder, named by `KILNMARK_FILES_DIR` in
   `config/.env` without editing `versions/<v>/compose.yaml`, is mounted apart, read-only, at
   `/srv/share` (`:ro,Z`, the guidance before this decision): a security review asked that the pack
   never be able to write in a share other systems use.

2. **Every install gets an empty database `kilnmark_samples`, with a login of its own, at every
   start.** The `samples-db` service of compose (a one-off, in Kilnmark's own `kilnmark-postgres`
   image, so it works offline with the signed images) runs `kilnmark-samples-db.sh` as the
   server's superuser over TLS: it makes, or keeps, the login `kilnmark_samples` (no superuser,
   database, role, replication or row-security powers, a member of no role) and the database
   `kilnmark_samples`, owned by it; closes `kilnmark_samples` to everyone but its owner; and closes
   **every other database** on the server, Kilnmark's own included, to everyone but its owner
   (`REVOKE CONNECT, TEMPORARY ... FROM PUBLIC`, and any grant to `kilnmark_samples`). The login's
   password is generated into `config/.env` (`KILNMARK_SAMPLES_DB_PASSWORD`) by the control script,
   as every secret is; there is no default. Provisioning happens at install and every start, not
   when the pack is turned on, so that Kilnmark itself never makes databases or logins: the server
   only ever uses the samples' own login, and only on that database. Running it at every start also
   closes again a database a restore made (PostgreSQL opens a new database to everyone).

3. **A separate database, not a schema, not another server.** A schema in Kilnmark's database would
   put the samples' login inside Kilnmark's catalogue: it could list every table name, role and
   function there, and a grant made by mistake would reach real data. A separate PostgreSQL
   container would cost memory, a port, a volume and its own backups for a feature used in trials.
   A separate database on the same server gives the isolation that matters: the samples' login
   **cannot even connect** to Kilnmark's database, so it sees none of its catalogue; were it ever let
   in by mistake, it could still read none of Kilnmark's tables (they belong to Kilnmark's login,
   with no grants), and the next start shuts it out again. Kilnmark's backups (`backup`, `restore`)
   dump and restore Kilnmark's own database only (`pg_dump -d kilnmark`), never `kilnmark_samples`:
   the samples are recreated by turning the pack on again.

   Kilnmark's own login is, as before, the server's superuser (it owns the server, ADR 0032); no
   grant can limit a superuser, so none is made in `kilnmark_samples` for it. The server never
   connects there as that login: the pack works only through the samples' login, and checks on
   every connection that it is in `kilnmark_samples`, over TLS, as a login that is not a superuser,
   with none of Kilnmark's tables there, so a setting pointed at the wrong database is refused
   rather than emptied. Taking Kilnmark's login off the superuser role is left for later.

4. **Settings > Starter Pack, for admins, and Kilnmark does the work.** `POST /api/v1/starter-pack`
   (admin) turns it on: a PostgreSQL source `starter-samples-db` on `kilnmark_samples` (its own
   login, over TLS, **Allow writing** and **Allow SQL** on, so the two samples that run SQL can be
   added by a designer who may see unmasked values) and a files source `starter-samples-files` on
   `/srv/files/starter-pack` (**Allow writing** on); then the existing starter pack: the sample
   tables (the `sample-tables` pipeline, run at once) and every sample into the Library reading
   them. `DELETE /api/v1/starter-pack` with `{"confirm": "DELETE"}` turns it off: every sample
   pipeline and flow in the Library, the two sources (and `sample-data` unless a team's own pipeline
   or flow reads it), the `sample_region` variable the pack set, every table and schema the samples'
   login owns in `kilnmark_samples` (the database stays, empty) and the pack's folder in the files
   folder. Nothing else; the pack is reported off only once every step has worked. Both are safe to
   repeat; each answers with every step and how it went, which the page shows and the audit event
   records. The pack remembers the ids of the two sources it made: a source of either name that it
   did not make (a team's own) is never repointed, made writable or deleted, and the step says so.
   The pack
   writes its files in a folder of its own, not `/srv/files` itself, so turning it off never touches
   a site's own files there. `install --starter` (`-Starter`) is a convenience that installs and
   then calls the same API; the server is the one place that does the work on the data.

5. **The example people, made by Keycloak's own setup step; Kilnmark holds no Keycloak
   credential.** The first version gave Kilnmark a Keycloak service account (`manage-users` in the
   `kilnmark` realm) and showed the admin turning the pack on the five people's shared password. A
   security review found that this broke Kilnmark's two-person rules: one admin then knew the
   password of `priya.shah` (a designer who sees sensitive values) and of `alex.morgan` (an admin),
   so they could see unmasked values on every source (ADR 0027) and approve their own unmask grant
   as a second admin (ADR 0038, and the second person of ADR 0045), on production too through
   `force=true`. And `manage-users` could reset anyone's password or second factor, delete people
   and put anyone in the admins group: a whole-realm credential in Kilnmark's environment.

   So, instead: the person who runs Kilnmark, who already holds Keycloak's admin password in
   `config/.env`, makes the people with the control script, `kilnmark.sh starter people`
   (`kilnmark.cmd starter people`). It makes a new password, hands it to that one run of
   `keycloak-setup` on its standard input and shows it once on the terminal, never in `logs`. Not in
   the run's environment: compose would put it in the container's settings, which `inspect` shows,
   and podman-compose passes each setting as `-e NAME=value` on podman's command line, which other
   users of the machine can read and which it can print. `keycloak-setup`, with Keycloak's own
   admin, makes the five (one per role, one designer seeing sensitive values) in their groups and in
   the group `kilnmark-starter-pack`, only while `KILNMARK_STARTER=allowed` and the install is not
   marked production, which it checks itself. That group is the mark. Only admins can put people in
   a group, and setup puts a person in it only as it makes them, in the same step, so a person who
   registered, or an admin's account, with one of the addresses is never adopted, given the people's
   groups or deleted. (A first version marked them with a user attribute and set the realm's
   unmanaged attributes to admins only; a review found that a person could set it before that, and
   that an admin choosing a stricter policy later would hide it, so the people would never be
   removed. A group depends on neither, and the realm's own settings are left as they are.) A run
   never resets the password of someone already there; `--new-password` gives them all a new one,
   `--remove` removes them. At every start (`keycloak-setup` runs on every `up`), once
   `KILNMARK_STARTER` is unset or the install is marked production, it deletes exactly the five in
   the group, then the group. While people may be made it turns on Keycloak's admin events, which
   record each change to the people; their details setting stays the site's.
   `KILNMARK_KEYCLOAK_DEMO_PASSWORD`, the earlier way to make the same five, is retired: the control
   script empties it, and people made that way are not in the group and are left for an admin to
   remove in Keycloak's console.

   Kilnmark's own part is to show, under Settings > Starter Pack, the commands and which kilnmark.com
   accounts have signed in, from its own audit log. It never learns or shows their password.

6. **Guard rails.** A banner on every page, for every role, while the pack is on ("Starter Pack:
   example data and accounts, not for production"), from the server's own setting
   (`GET /api/v1/starter-pack`). Audit events `starter.added` and `starter.removed`, each with
   every step's outcome, a failed attempt included, and whether it was forced; the people's changes
   are Keycloak's admin events. The access review marks every kilnmark.com account that has signed
   in as an example account. A new server setting,
   `KILNMARK_PRODUCTION=true` (`kilnmark.production`, off by default), marks an install as
   production: turning the pack on is then refused by the server, with nothing changed and the
   refusal recorded, and `keycloak-setup` refuses the example people and removes any it made. Only
   an admin calling the API with `force=true` can add the example data there, which the audit event
   records; the page offers no way to force, and nothing forces the people. Turning it off is never
   refused.

7. **Production means one thing everywhere.** The server, the control scripts and
   `keycloak-setup` all read `KILNMARK_PRODUCTION`. Read as an ordinary boolean the server would
   take `1`, `yes` or `on` as true while a script comparing text took them as not production, and
   the example people would be made on an install the server calls production. So the server starts
   only with `true` or `false`, in any case; the control scripts read it the same way and refuse
   anything else; and `keycloak-setup` takes anything but `false` as production, so a value the
   server refuses never lets the people in. Compose would take `KILNMARK_PRODUCTION`,
   `KILNMARK_STARTER` and the people's action from the shell's environment before `config/.env`, so
   the control scripts remove them from their own environment first, and read an `export` in front
   of a line, and the name's letter case exactly, as compose does: `config/.env` alone decides.

8. **Nothing an example account did outlives the trial on production.** Deleting the people in
   Keycloak does not touch what they made in Kilnmark, and Kilnmark cannot see Keycloak. So on an
   install marked production, Kilnmark refuses every sign-in, renewal and session of a kilnmark.com
   account, and at every start cancels each pipeline and flow run one of them (or a token or
   schedule of one) asked for that has not finished, revokes each API token one of them made or
   whose unmask grant one of them approved (so a grant one person approved for themselves with an
   example admin does not survive, ADR 0038), and each token such a token made, and pauses each
   schedule one of them set (ADR 0046). Each cancellation, revocation and pause is an audit event,
   with the reason. The start-up sweep is not the only line: such a token is refused whenever it is
   presented, such a run is cancelled when a worker would start it, and a schedule such a person set
   does not fire, so nothing acts in the moments before the sweep has run. A token is known by its
   id, never by its name, which anyone making a token chooses and need not be unique: a run names a
   token as `token <id> (<name>)`, and a scheduled run is traced through its schedule to the token
   or person who set it. So an example admin cannot stop a real token's work by giving a token of
   their own the same name. On an install not marked production these stay, as the trial's own; the
   access review shows which accounts are examples. A run or schedule recorded before
   this version names a token by its name alone and cannot be traced to it: it is not cancelled.
   The pack's example people did not exist then, so no such row was theirs.

## Consequences

- A trial install is one command (`install --starter`, with `--auth` for the people) and every
  sample can run on day one; a production install never gets example data unless an admin forces it
  through the API, and the audit log says so.
- Every install carries an empty `kilnmark_samples` database and a login that can open nothing
  else. It costs a few megabytes and holds nothing until the pack is turned on.
- Restoring a backup leaves the samples out: turn the pack off and on again to recreate them.
- Kilnmark holds no Keycloak credential in any configuration. The example people's password is known
  to whoever runs `starter people`, who holds Keycloak's admin password already; on an install with
  the people, every admin who has it can act as an example admin or a designer who sees sensitive
  values, which is why they are only for trials and training, and gone at the next start once
  `KILNMARK_STARTER` is removed or the install is marked production.
- Turning the pack off in Kilnmark does not remove the people: the page says so and gives the
  command.
- A trial install that becomes production keeps nothing an example account left that acts: their
  sign-ins are refused, their unfinished runs cancelled, their tokens (and any token one of those
  made) refused and revoked, and their schedules paused, each recorded. What they changed, such as a
  source, a setting or which tokens a local assistant uses, stays as any admin's change does: the
  audit log says who did it. Quality runs are not cancelled: they run with no one's role, whoever
  asked.
- A run a token asks for, and a schedule a token sets, now name it `token <id> (<name>)` where they
  named only `<name>`; the Starter Pack page's banner state no longer says who turned it on (the
  details, for admins, do).
- On Windows, `install -Auth -Starter` stops its own log while the people are made, as Windows
  PowerShell writes a child's console output into a running transcript; and the password is never
  printed when the output goes to a file. The people's passwords stay permanent rather than
  temporary (Keycloak's update-password action): they are shared for demonstrations, and a person
  needs a second factor, which each sets up at first sign-in, besides the password.
- The pack writes a marker in the folder it makes, `starter-pack/.kilnmark-starter-pack`, holding a
  value it keeps in its own setting. A folder of that name without it, a site's own on a real share,
  is never written in or deleted: turning the pack on says so and turning it off leaves it.
- `KILNMARK_PRODUCTION` set to anything but `true` or `false` stops the server at start, and the
  control script says why.
- The two samples that run SQL are not added by the pack (an admin may not see unmasked values,
  ADR 0027); a designer who may adds them under **Library > Add samples**.
