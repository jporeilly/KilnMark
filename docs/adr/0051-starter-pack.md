# 0051 The Starter Pack: example data turned on and off by an admin in a database of its own, and example people made by Keycloak's setup

Status: Accepted (6 November 2026; decision 5 amended the same day after a security review)

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
   user; the server image makes the folder its user's, so a new volume starts out writable.
   `KILNMARK_FILES_DIR` in `config/.env` puts the site's own folder there instead, without editing
   `versions/<v>/compose.yaml`.

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
   `keycloak-setup` in its environment (never a command line, never `config/.env`) and shows it once
   on the terminal, never in `logs`. `keycloak-setup`, with Keycloak's own admin, makes the five
   (one per role, one designer seeing sensitive values) in their groups, each carrying the attribute
   `kilnmark_starter_pack`, only while `KILNMARK_STARTER=allowed` and the install is not marked
   production, which it checks itself. A run never resets the password of someone already there;
   `--new-password` gives them all a new one, `--remove` removes them. At every start
   (`keycloak-setup` runs on every `up`), once `KILNMARK_STARTER` is unset or the install is marked
   production, it deletes exactly the five that carry the mark: a real person with one of those
   addresses is never touched. While people may be made it sets the realm's unmanaged attributes to
   admins only (`ADMIN_EDIT`), so no one can put the mark on themselves, and turns on Keycloak's admin
   events, which record each change to the people. `KILNMARK_KEYCLOAK_DEMO_PASSWORD`, the earlier way
   to make the same five, is retired: the control script empties it, and people made that way carry
   no mark and are left for an admin to remove in Keycloak's console.

   Kilnmark's own part is to show, under Settings > Starter Pack, the commands and which kilnmark.com
   accounts have signed in, from its own audit log. It never learns or shows their password.

6. **Guard rails.** A banner on every page, for every role, while the pack is on ("Starter Pack:
   example data and accounts, not for production"), from the server's own setting
   (`GET /api/v1/starter-pack`). Audit events `starter.added` and `starter.removed`, each with
   every step's outcome, a failed attempt included, and whether it was forced; the people's changes
   are Keycloak's admin events. The access review marks every kilnmark.com account that has signed
   in as an example account. A new server setting,
   `KILNMARK_PRODUCTION=true` (`kilnmark.production`, off by default), marks an install as
   production: turning the pack on is then refused by the server, with nothing changed, and
   `keycloak-setup` refuses the example people and removes any it made. Only an admin calling the API
   with `force=true` can add the example data there, which the audit event records; the page offers
   no way to force, and nothing forces the people. Turning it off is never refused.

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
- The two samples that run SQL are not added by the pack (an admin may not see unmasked values,
  ADR 0027); a designer who may adds them under **Library > Add samples**.
