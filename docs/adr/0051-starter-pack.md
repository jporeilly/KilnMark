# 0051 The Starter Pack: two deployment modes, example data turned on and off by an admin in a database of its own, and example people made by Keycloak's setup

Status: Accepted (6 November 2026; decision 5 amended the same day after a security review, and
decisions 5 to 8 after a second; amended 7 November 2026 by the owner: two deployment modes, fixed
at install (decisions 6 to 12), in place of a production setting and the start-up sweep; the
people commands and Keycloak's backup the same day)

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
are (ADR 0043), in both modes.

A setting that marked an install production, and a sweep at every start that undid what example
accounts had done on an install that became production, took four review rounds and still left
questions (how deep a chain of tokens to follow, what a run asked for long ago names). On
7 November 2026 the owner decided that an install's mode is chosen once, at install, and never
converted: Production or Starter Pack.

## Decision

1. **Every install gets a writable files folder.** The release compose file mounts a named volume,
   `files`, at `/srv/files` (which `KILNMARK_FILES_ROOTS` already names), writable by Kilnmark's
   user; the server image makes the folder its user's, so a new volume starts out writable. The
   samples and the pack write only there. A site's own folder, named by `KILNMARK_FILES_DIR` in
   `config/.env` without editing `versions/<v>/compose.yaml`, is mounted apart, read-only, at
   `/srv/share` (`:ro,Z`, the guidance before this decision): a security review asked that the pack
   never be able to write in a share other systems use.

2. **A Starter Pack install gets an empty database `kilnmark_samples`, with a login of its own,
   at every start.** The `samples-db` service of compose (a one-off, in Kilnmark's own
   `kilnmark-postgres` image, so it works offline with the signed images; in the compose profile
   `starter-pack`, which the control scripts add only on a Starter Pack install) runs
   `kilnmark-samples-db.sh` as the server's superuser over TLS: it makes, or keeps, the login
   `kilnmark_samples` (no superuser, database, role, replication or row-security powers, a member
   of no role) and the database `kilnmark_samples`, owned by it, and, when it makes the database
   and only then, closes it to everyone but its owner. It changes no other database: an earlier
   version closed every database on the server at every start, which changed grants a site may
   have chosen and was not needed (below). The login's password is generated into `config/.env`
   (`KILNMARK_SAMPLES_DB_PASSWORD`) by the control script, as every secret is; there is no
   default. Provisioning happens at every start, not when the pack is turned on, so that Kilnmark
   itself never makes databases or logins: the server only ever uses the samples' own login, and
   only on that database. A production install has neither.

3. **A separate database, not a schema, not another server.** A schema in Kilnmark's database would
   put the samples' login inside Kilnmark's catalogue: it could list every table name, role and
   function there, and a grant made by mistake would reach real data. A separate PostgreSQL
   container would cost memory, a port, a volume and its own backups for a feature used in trials.
   A separate database on the same server gives the isolation that matters: the samples' login
   reads and makes nothing in Kilnmark's database (its tables belong to Kilnmark's login, with no
   grants, and its schemas give no other login the right to make anything), and it is the only
   login let into its own. Kilnmark's backups (`backup`, `restore`)
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
   as a second admin (ADR 0038, and the second person of ADR 0045). And `manage-users` could reset anyone's password or second factor, delete people
   and put anyone in the admins group: a whole-realm credential in Kilnmark's environment.

   So, instead: the person who runs Kilnmark, who already holds Keycloak's admin password in
   `config/.env`, makes the people with the control script, `kilnmark.sh starter people`
   (`kilnmark.cmd starter people`). They type the password, twice with the echo off, or pipe it in;
   it is handed to that one run of `keycloak-setup` on its standard input and never shown, so no
   transcript, terminal recording or redirected output can keep it (an earlier version made it and
   showed it once, which a review found such a recording kept). Not in the run's environment: compose would put it in the container's settings, which `inspect` shows,
   and podman-compose passes each setting as `-e NAME=value` on podman's command line, which other
   users of the machine can read and which it can print. `keycloak-setup`, with Keycloak's own
   admin, makes the five (one per role, one designer seeing sensitive values) in their groups and in
   the group `kilnmark-starter-pack`, only on a Starter Pack install (`KILNMARK_MODE=starter-pack`,
   exactly) while `KILNMARK_STARTER=allowed`, which it checks itself. That group is the mark, and the
   group carries an attribute, `kilnmark_starter_pack`, that only setup sets: a group of that name
   setup did not make (one a directory federation brings, say) is never taken for it, and setup
   stops, changing nothing. Only admins can put people in a group, and setup puts a person in it
   only as it makes them, in the same step, so a person who registered, or an admin's account, with
   one of the addresses is never given the people's groups or deleted. The one exception is the five
   exact addresses an earlier version made with `KILNMARK_KEYCLOAK_DEMO_PASSWORD` (decision 7): on a
   Starter Pack install setup brings them into the group, saying so, so the pack's rules and its
   removal cover them. (A first version marked them with a user attribute and set the realm's
   unmanaged attributes to admins only; a review found that a person could set it before that, and
   that an admin choosing a stricter policy later would hide it, so the people would never be
   removed. A group depends on neither, and the realm's own settings are left as they are.) A run
   never resets the password of someone already there; `--new-password` gives them all a new one,
   `--remove` removes them. At every start (`keycloak-setup` runs on every `up`), once
   `KILNMARK_STARTER` is unset, it deletes the members of the group, whatever they are called now,
   then the group. While people may be made it turns on Keycloak's admin events, which record each
   change to the people; their details setting stays the site's.

   Kilnmark's own part is to show, under Settings > Starter Pack, the commands and which kilnmark.com
   accounts have signed in, from its own audit log, and to record what setup did: the control
   script reads setup's output and tells Kilnmark (`POST /api/v1/starter-pack/people`, admin), which
   records `starter.people`. It never learns or shows their password.

6. **Two deployment modes, chosen at install, never converted.** `KILNMARK_MODE` is `production` or
   `starter-pack`, in ASCII lower case, and nothing else: the server does not start with anything
   else, and neither do the control scripts. Install writes it (`--starter` for a Starter Pack
   install, else production). At its first start the server records it in its own database
   (`kilnmark.server_setting`, `deployment_mode`); from then on a setting that differs stops the
   server, either way round, saying so. Editing `config/.env` never changes an install's mode. A
   Starter Pack install never becomes a production one, and a production one never gets the pack:
   for production, install Kilnmark afresh. There is no conversion, so nothing an example account
   did can be on a production install.
   - **Production**: the Starter Pack can never be turned on (its page offers nothing; the API
     refuses, `force` or not, and records each refusal); no samples database and no example
     people. Kilnmark refuses the sign-in of anyone whose groups claim holds
     `kilnmark-starter-pack`, and, as a second line, of the five example addresses, each refusal
     recorded; `keycloak-setup` refuses a realm that has any of the five.
   - **Starter Pack**: the pack works as above, with a marking for everyone on every page,
     "Starter Pack: training and development only", saying too when its example data is on.
   - In code, whether the example data is allowed and whether the example people are allowed are
     two checks (`ModeCheck.samplesAllowed`, `ModeCheck.examplePeopleAllowed`), both true on a
     Starter Pack install only; nothing turns either on for production.
   - Templates are there in both modes (ADR 0043).

   Compose would take `KILNMARK_MODE`, `KILNMARK_STARTER` and the one-run settings from the shell's
   environment before `config/.env`, so the control scripts remove them from their own environment
   first, read an `export` in front of a line and the name's letter case exactly, as compose does,
   and refuse a one-run setting written in `config/.env`: `config/.env` alone decides.

7. **Upgrading an install from before there were modes.** Release 0.1.1 was a preview, not a
   production release, and it made the five example people when `KILNMARK_KEYCLOAK_DEMO_PASSWORD`
   was set. An install with no recorded mode that shows any sign of them may only become a Starter
   Pack install, and asking for production is refused with a plain message to install afresh; it
   is never offered a conversion. The signs, each checked where it can be seen: the demo password is
   set (the control scripts then write `starter-pack`); Kilnmark's own records name any of the five
   addresses (audit events, tokens, pipelines, flows, schedules and runs: the server will not
   record production); or the realm has any of them (`keycloak-setup` refuses). With no sign, the
   control scripts write `production`. `KILNMARK_KEYCLOAK_DEMO_PASSWORD` is retired: a production
   install does not start while it is set, and a Starter Pack install takes a typed password
   instead; the operator is told to delete it. The owner decided there is no route to production
   for a 0.1.1 install with the example people, even one whose people never signed in.

8. **The start-up sweep is dropped.** It revoked tokens an example account made (and chains of
   tokens those made, up to 64 deep), paused schedules they set and cancelled runs they asked for,
   on an install that became production. With the mode fixed at install, no install that has had the
   example people is ever production, so the sweep protected a conversion that no longer exists,
   and its lookups were themselves something to get wrong. What stays from it: a run or schedule a
   token asks for names the token by its id, `token <id> (<name>)`, not by a name anyone may choose.

9. **A Starter Pack install signs in with the bundled Keycloak only.** Its example people live in
   the bundled Keycloak, so install always turns on the `auth` profile, with new secrets, for a
   Starter Pack install; the control scripts refuse to start one without the profile; and the
   server refuses to start one unless sign-in is on against the bundled realm
   (`http://keycloak:8080/realms/kilnmark`), never an outside identity provider. Production signs in
   however the site chooses: install asks, recommending the organisation's own identity provider
   for regulated organisations, or the bundled Keycloak, or API tokens only.

10. **People of the organisation's own, added by the control script, in both modes, with the
    bundled Keycloak.** So that nobody makes the first person by hand in Keycloak's console, and a
    trainer can load a class at once, the control script has `people add` (one person, in any of
    Kilnmark's groups), `people import FILE` (a CSV, `email,first_name,last_name,groups`, with one
    temporary password for the batch) and `people list` (who is there, their Kilnmark groups, and
    who has not yet chosen their own password). Install offers Kilnmark's first admin, which is
    `people add` with `kilnmark-admins`. Like the example people, they are made by one run of
    `keycloak-setup` with Keycloak's own admin from `config/.env`: Kilnmark itself still holds no
    Keycloak credential (decision 5). The rules:
    - every password is temporary: Keycloak has the person choose their own at the first sign-in,
      and the one-time code applies as for everyone. It is typed with the echo off or piped in,
      never shown, in `.env`, on a command line or in `logs`;
    - `kilnmark-admins` only through `people add`, one person at a time, never from a file, to keep
      the two-person rules (ADR 0038, ADR 0045) from being got round in bulk;
    - an import checks the whole file before changing anything (a well-formed address, a known
      group, no address twice), names each bad line by its number, never its values, and then makes
      nobody; it ends with how many were made, already there and refused;
    - someone already there with that user name or email is left exactly as they are, their
      password and groups too;
    - the five example addresses, and anything at kilnmark.com, are refused, so a real person is
      never taken for an example one; and people added this way are never in the pack's group, so
      the pack's removal never deletes them;
    - each person made (`auth.person_added`, with their groups) and each import
      (`auth.people_imported`) is in Kilnmark's audit log, from what the control script reads in
      setup's output (`POST /api/v1/auth/people`, admin), never a password;
    - with the organisation's own identity provider the commands refuse: people are added there.

    Kilnmark's first admin person is not Keycloak's own admin (`KILNMARK_KEYCLOAK_ADMIN`), who
    signs in to Keycloak's console, never to Kilnmark.

11. **Guard rails.** Audit events `starter.added` and `starter.removed`, each with every step's
    outcome, a failed attempt or a refusal included; `starter.people` for each change to the
    example people; and the people's changes are Keycloak's admin events too. The access review
    marks every kilnmark.com account that has signed in as an example account. Turning the pack off
    is never refused.

12. **Keycloak is backed up with Kilnmark.** With the bundled Keycloak, its people, groups and
    one-time code setups are not in Kilnmark's database, and a restored install without them would
    have nobody able to sign in; every Starter Pack install has the bundled Keycloak. So `backup`
    and `restore` take Keycloak's database too, beside Kilnmark's (ADR 0039, decision 8).

## Consequences

- A trial install is one command (`install --starter`), with the bundled Keycloak, and every sample
  can run on day one. A production install never gets example data or example people, by any
  setting or API call.
- Only a Starter Pack install carries the `kilnmark_samples` database and its login. It costs a few
  megabytes and holds nothing until the pack is turned on.
- Restoring a backup leaves the samples out: turn the pack off and on again to recreate them.
- Kilnmark holds no Keycloak credential in any configuration. The example people's password is known
  to whoever runs `starter people`, who holds Keycloak's admin password already; on a Starter Pack
  install, every admin who has it can act as an example admin or a designer who sees sensitive
  values, which is why such an install is only for trials and training.
- Turning the pack off in Kilnmark does not remove the people: the page says so and gives the
  command.
- A site that tried Kilnmark with the Starter Pack installs afresh for production: nothing moves
  across, by design. A 0.1.1 install with the example people stays a Starter Pack install.
- A run a token asks for, and a schedule a token sets, now name it `token <id> (<name>)` where they
  named only `<name>`.
- The people's passwords stay permanent rather than temporary (Keycloak's update-password action):
  they are shared for demonstrations, and a person needs a second factor, which each sets up at
  first sign-in, besides the password. People added with the `people` commands get a temporary one.
- The pack writes a marker in the folder it makes, `starter-pack/.kilnmark-starter-pack`, holding a
  value it keeps in its own setting. A folder of that name without it, a site's own on a real share,
  is never written in or deleted: turning the pack on says so and turning it off leaves it.
- `KILNMARK_MODE` set to anything but `production` or `starter-pack` stops the server at start, and
  the control script says why.
- The developer stack (`compose.yaml` at the repository's root) has no Keycloak, so it is a
  production install unless `KILNMARK_MODE` says otherwise; `quarkusDev` and the tests run as a
  Starter Pack install and skip the bundled-Keycloak check, as they have none.
- The two samples that run SQL are not added by the pack (an admin may not see unmasked values,
  ADR 0027); a designer who may adds them under **Library > Add samples**.
