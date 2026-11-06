# 0051 The Starter Pack: example data and people, turned on and off by an admin, in a database of its own

Status: Accepted (6 November 2026)

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
example people managed through a narrow Keycloak service account only where `.env` allows it.
Templates stay in the product as they are (ADR 0043).

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
   tables (the `sample-tables` pipeline, run at once), every sample into the Library reading them;
   and the example people where allowed (below). `DELETE /api/v1/starter-pack` with
   `{"confirm": "DELETE"}` turns it off: every sample pipeline and flow in the Library, the two
   sources (and `sample-data` unless a team's own pipeline reads it), the `sample_region` variable
   the pack set, every table and schema the samples' login owns in `kilnmark_samples` (the database
   stays, empty), the pack's folder in the files folder, and the people it added. Nothing else. Both
   are safe to repeat; each answers with every step and how it went, which the page shows. The pack
   writes its files in a folder of its own, not `/srv/files` itself, so turning it off never touches
   a site's own files there. `install --starter` (`-Starter`) is a convenience that installs and
   then calls the same API; the server is the one place that does the work.

5. **The example people, through a narrow service account, only where allowed.** With
   `KILNMARK_STARTER=allowed` in `config/.env` and the bundled Keycloak (the `auth` profile), the
   control script generates `KILNMARK_KEYCLOAK_STARTER_SECRET`, and `keycloak-setup` (run at every
   start) makes the client `kilnmark-starter` whose service account holds `realm-management`'s
   `manage-users`, `view-users` and `query-groups` in the `kilnmark` realm and nothing else: no
   role in `master`, no realm, client or sign-in-flow management, not even the realm's default
   roles. Compose passes that client's secret to Kilnmark; Keycloak's admin password never reaches
   it. With the setting off, on production, or without Keycloak, the control script empties the
   secret and `keycloak-setup` deletes the client, so no Keycloak credential reaches Kilnmark at
   all; the page then says why it cannot manage people. Kilnmark calls Keycloak at the sign-in
   issuer's own address, which must be a host it may reach (`kilnmark.sources.allowed-hosts`), never
   following a redirect; errors never echo an address or a secret.

   Turning the pack on adds the five kilnmark.com people as `setup.sh`'s demonstration list names
   them (one per role, one designer seeing sensitive values), in their groups, each carrying the
   attribute `kilnmark_starter_pack`, with one new password the admin sees once in the dialog (or
   the control script prints once, to the terminal only, never into its `logs` folder): Kilnmark
   keeps it nowhere, not in its database, its logs or the audit log. Turning it on again gives them a new password. Turning it off deletes exactly those
   five, and each only if it carries the mark, so a real person with one of those addresses is
   never touched. The realm's user profile lets only admins see and set attributes it does not
   declare (`ADMIN_EDIT`), so no one can mark themselves. `KILNMARK_KEYCLOAK_DEMO_PASSWORD` stays
   as it was, for a demonstration without the pack; people made that way carry no mark and the
   pack leaves them alone.

6. **Guard rails.** A banner on every page, for every role, while the pack is on ("Starter Pack:
   example data and accounts, not for production"), from the server's own setting
   (`GET /api/v1/starter-pack`). Audit events `starter.added` and `starter.removed`
   (each step's outcome, and whether it was forced) and `starter.people_added` and
   `starter.people_removed` (the addresses; never a password). The access review marks every
   kilnmark.com account that has signed in as an example account. A new server setting,
   `KILNMARK_PRODUCTION=true` (`kilnmark.production`, off by default), marks an install as
   production: turning the pack on is then refused, with nothing changed, and the control script
   will not make the service account's secret. Only an admin calling the API with `force=true` can
   add it there, which the audit event records; the page offers no way to force. Turning it off is
   never refused.

## Consequences

- A trial install is one command (`install --starter`, with `--auth` for the people) and every
  sample can run on day one; a production install never gets example data unless an admin forces it
  through the API, and the audit log says so.
- Every install carries an empty `kilnmark_samples` database and a login that can open nothing
  else. It costs a few megabytes and holds nothing until the pack is turned on.
- Restoring a backup leaves the samples out: turn the pack off and on again to recreate them.
- With `KILNMARK_STARTER=allowed`, Kilnmark holds a credential that can manage every person and group
  in the `kilnmark` realm, including putting a person in the admins group. That is why it is off
  by default, refused on production, and gone (client and secret) as soon as the setting is.
- The two samples that run SQL are not added by the pack (an admin may not see unmasked values,
  ADR 0027); a designer who may adds them under **Library > Add samples**.
