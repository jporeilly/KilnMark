# 0051 The Starter Pack: two deployment modes, example data turned on and off by an admin in a database of its own, and example people made by Keycloak's setup

Status: Accepted (6 November 2026; decision 5 amended the same day after a security review, and
decisions 5 to 8 after a second; amended 7 November 2026 by the owner: two deployment modes, fixed
at install (decisions 6 to 13), in place of a production setting and the start-up sweep; the
people commands and Keycloak's backup the same day; then no upgrade from before modes, activation
on production, and expiring temporary passwords; amended 8 November 2026 by the owner: the
install layout and the guided install, decision 17, in place of the temporary Windows root of
decision 15)

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
   then calls the same API; the server is the one place that does the work on the data. The kind of
   source that reads the example tables bundled with the server, `sample-data`, is the Starter
   Pack's alone: on production it is refused when a source is added or imported, and left out of
   **Add connection** and the list of kinds.

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
   one of the addresses is never given the people's groups or deleted. (A first version marked them with a user attribute and set the realm's
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
     people, nor the samples by any route (Add samples and the sample tables included). Kilnmark
     refuses the sign-in of anyone whose groups claim holds `kilnmark-starter-pack`, each refusal
     recorded.
   - **Starter Pack**: the pack works as above, with a marking for everyone on every page,
     "Starter Pack: training and development only", saying too when its example data is on.
   - In code, whether the example data is allowed and whether the example people are allowed are
     two checks (`ModeCheck.samplesAllowed`, `ModeCheck.examplePeopleAllowed`), both true on a
     Starter Pack install only; nothing turns either on for production (the owner decided
     Production stays clean).
   - What decides is the mode recorded in the database, not `config/.env` alone: the control
     scripts read it (with the database's own `psql`) before `starter people`, `upgrade` and
     `restore`, and `samples-db` waits for it and makes the samples database only when it says
     `starter-pack`.
   - Templates are there in both modes (ADR 0043).

   Compose would take `KILNMARK_MODE`, `KILNMARK_STARTER` and the one-run settings from the shell's
   environment before `config/.env`, so the control scripts remove them from their own environment
   first, read an `export` in front of a line and the name's letter case exactly, as compose does,
   and refuse a one-run setting written in `config/.env`: `config/.env` alone decides.

7. **No upgrade from before there were modes.** Release 0.1.2 is the first with them. 0.1.1 and
   earlier were previews, never production releases, and the owner decided they are not carried
   across: such an install is installed afresh. Only a new database records a mode: at the first
   start with nothing recorded, a database whose first migration ran before this start predates
   modes, and the server refuses to start ("install afresh"); the control scripts' `up` refuses a
   `config/.env` with no `KILNMARK_MODE`, `upgrade` refuses before it changes anything, and
   `restore` refuses a backup with no mode recorded, or of the other mode. (A first design
   looked for signs of 0.1.1's example people, the demo password, its records and its realm, to
   decide which mode such an install could have; with no upgrade at all, that went.) A new
   database on a server that already has the Starter Pack's database or login (`kilnmark_samples`)
   is never recorded as production. Each refused start is recorded in the audit log first, when
   the database answers (`server.start_refused`).

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
    Kilnmark's groups), `people import FILE` (a CSV, `email,first_name,last_name,groups`), `people
    activate EMAIL`, `people list` (who is there, their Kilnmark groups, whether active or
    waiting, and who has not yet chosen their own password) and `people expire` (below). Install offers Kilnmark's first admin,
    which is `people add` with `kilnmark-admins`. Like the example people, they are made by one run
    of `keycloak-setup` with Keycloak's own admin from `config/.env`: Kilnmark itself still holds no
    Keycloak credential (decision 5). The rules:
    - every password is temporary: Keycloak has the person choose their own at the first sign-in,
      and the one-time code applies as for everyone. It is typed with the echo off or piped in,
      never shown, in `.env`, on a command line or in `logs`, and may hold no control character;
    - an import on a **Starter Pack install** gives the class one shared temporary password; on
      **production** a shared password would let anyone who learns it sign in as anyone not yet
      signed in, so nobody is given one: each person is made disabled, with no password, waiting
      for `people activate`, which gives one person a temporary password of their own and enables
      them. It takes only someone waiting for a first password: no credential at all, or only the
      temporary one these commands set, unused. It refuses anyone already active, anyone with a
      password of their own or a one-time code (they have signed in; disabled on purpose), and
      anyone from a directory (a federation link) or another identity provider (a federated
      identity);
    - **people cannot change who they look like**: setup makes email, first and last name editable
      by admins only in the realm's user profile, and user names fixed (`editUsernameAllowed`
      off), so nobody makes themselves look like an example person, or anyone else;
    - **unused temporary passwords expire**, at most an hour late: anyone still holding one more
      than `KILNMARK_PEOPLE_ACTIVATION_HOURS` (72 unless set; 1 to 720) after it was set is
      disabled, recorded (`auth.person_disabled`); `people activate` gives them a new one. Only the
      temporary passwords these commands set expire: each is marked (its credential label,
      `kilnmark-temporary`), and Keycloak drops the mark when its person chooses their own, so a
      long-standing person an admin asked in Keycloak's console to change their password is never
      disabled for it. The marked password and Keycloak still asking for a new one decide, whatever
      else the person has: Keycloak asks for a one-time code before the new password, so someone
      who intercepted a temporary password could set up a code and stop there; they are still
      disabled in time, and any credential made since that password was set (the code) is deleted,
      so the real person can be activated again. The check runs at every run of `keycloak-setup` (every start, every `people`
      command) and, because nothing else may run for days, every hour as `people expire`: `up` sets
      it up with the bundled Keycloak (on Linux a systemd timer of the service account, a system one
      as root; on Windows a scheduled task of the user, which runs while they are signed in, as the
      Podman machine or Docker Desktop does), removes it without, and `uninstall` removes it;
      `status` and `check` show it. Kilnmark still holds no Keycloak credential: the timer runs the
      control script, which runs `keycloak-setup`. It covers people from `people add`, the first
      admin too: the operator's `KILNMARK_ADMIN_TOKEN` stays the way in. Every listing pages
      through all of the realm's people, however many, and the control script records them 500 to
      a record. The timer runs the control script as the operator (Windows) or the account (Linux), so it
      is set up, and runs, only where nobody else may change what a run uses (decision 15). The
      compose project's name (`KILNMARK_PROJECT`), which names the task, must be lower-case letters,
      digits, `-` and `_`, or the script stops. The window holds only while the timer and Kilnmark's
      database run: on Windows the task runs only while the operator is signed in, and a stopped
      install expires nobody until its next start, which expires them first;
    - `kilnmark-admins` only through `people add`, one person at a time, never from a file, to keep
      the two-person rules (ADR 0038, ADR 0045) from being got round in bulk; on production so is
      `kilnmark-sensitive-data` (on a Starter Pack install the data is made up, so a class may have it).
      A group `config/.env` maps to the admin role (`KILNMARK_OIDC_ADMIN_GROUPS`) counts as
      `kilnmark-admins` here, and one it maps to seeing sensitive values (`KILNMARK_OIDC_UNMASK_GROUPS`)
      as `kilnmark-sensitive-data`, whatever its name;
    - an import checks the whole file before changing anything (a well-formed address, a known
      group, no address twice, no character Keycloak refuses in a name), names each bad line by its
      number, never its values, and then makes nobody; it ends with how many were made, already
      there and refused, and should Keycloak refuse someone part way, with how many it made first;
    - someone already there with that user name or email is left exactly as they are, their
      password and groups too;
    - anything at kilnmark.com is refused, so a real person is never taken for an example one; and
      people added this way are in exactly the Kilnmark groups they were given: someone made who
      lands in another one, or in the pack's group (a default group of the realm, say), is deleted
      again, and the command stops. The pack's removal deletes only members of its group whose user
      name is one of the five example addresses and who are Keycloak's own (no directory, no other
      identity provider), never by email; anyone else in the group is only taken out of it, and named;
    - the commands act only where the mode Kilnmark recorded in its own database equals
      `config/.env`'s, and refuse when the database does not answer; `keycloak-setup` is handed the
      recorded mode, never `.env`'s alone, and refuses one other than the realm's stamp (decision 16);
    - a person is made disabled and their groups checked (none that gives a role but those named:
      Kilnmark's, the pack's, or any `config/.env` maps to a role); whoever add, import or activate
      gives a temporary password stays disabled until Kilnmark's audit log has recorded them, and
      only then does the control script ask `keycloak-setup` to enable them (`enable`: only someone
      disabled, waiting for a first sign-in with a marked temporary password set within the window,
      never from a directory nor the pack's). A run cut short, or a record refused, leaves them
      disabled, never able to sign in unrecorded (fail closed); `people activate` then gives them
      a new temporary password;
    - on production a sign-in is refused, and recorded, when its groups hold the pack's group or its
      user name or email is at kilnmark.com;
    - the commands check that Kilnmark answers before changing anything, and each person made
      (`auth.person_added`, with their groups), each import (`auth.people_imported`) and each
      activation (`auth.person_activated`) is in Kilnmark's audit log, from what the control script
      reads in setup's output (`POST /api/v1/auth/people`, admin, attributed to that admin), never a
      password; Keycloak's admin events are on in both modes, without representations;
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
    and `restore` take Keycloak's database too, beside Kilnmark's (ADR 0039, decision 8). Each
    install has an id of its own, made at its first start beside its mode (one made later, for a
    database whose mode an earlier build recorded, is logged and audited, `server.install_id_made`).
    The control script hands both to `keycloak-setup` at every start once the server has recorded
    them (and tells the realm straight after the first start), so every realm carries them.
    `backup.txt` names them, and a restore refuses, before anything is stopped, a backup whose
    `backup.txt` differs from its dump, a Keycloak database whose realm names another install or
    mode, and, on production, one whose realm names none, or holding the pack's group (its mark) or
    any of the five example user names. `restore.sh` run by hand refuses when neither the database
    nor `.env` says the install's mode. `uninstall --purge` removes an image only when no other
    container on the machine uses it. A known limit: a backup carries checksums, not a signature or
    a MAC, so a restore trusts whoever can write the backup's files; keep backups where only the
    operators can write. The first start's record of the mode (`server.mode_recorded`) and the
    realm's stamp (`auth.realm_stamped`) are audit events. On production a sign-in's user name and
    email are read as its groups are (each string of the claim), so a JSON string's quotes never
    hide a kilnmark.com address.

13. **Secrets stay off command lines, and the samples' login is held by PostgreSQL itself.**
    podman-compose puts every environment setting of a container on podman's command line, which
    other users of the machine can read; so the two one-off services read their secrets from a file
    the control script writes for each run, holding only the lines that one needs, in a new folder
    only the account can open (`config/run`), mounted read-only, and removed once the run is over
    (`keycloak-setup`: Keycloak's admin password and the client secret; `samples-db`: the database's
    and the samples login's passwords). Neither sees `config/.env` whole; both run with no Linux
    capabilities and no new privileges, `keycloak-setup` as the image's own user, keeping kcadm's
    master-realm token in a private folder it removes at the end. `samples-db` sets the password
    with nothing in that session logged, a failing statement included. Lines at the top of
    `pg_hba.conf`, kept there at every start of the database image, let `kilnmark_samples` reach its
    own database over TLS and refuse it every other one, whatever the grants say.

14. **A new database is known before it is migrated.** The server records the mode at its first
    start only into a database that had no migration history when that start began. Before the
    first migration it writes that mark durably, in a table of its own
    (`kilnmark_first_start.mark`, outside Kilnmark's schema, which Flyway makes and migrates),
    created first, in a transaction of its own that commits before anything else. The mark is
    cleared only when the mode is recorded. Flyway commits each migration on its own, so a first
    start stopped part way through the migrations, or failing after them, leaves the mark, and the
    next start records the mode; a database migrated before modes has no mark and is refused. No
    clock is compared.

15. **The hourly run runs only what nobody else may change.** Before the task or timer is set up,
    and again at the start of every `people expire`, before anything of the install is read or
    written (its log too), the control script checks everything a run uses: the launcher, the
    current version's `compose.yaml` and `keycloak-setup`'s script, `config/.env` and `logs`, and
    every folder from the install root down to each (the root, `versions`, the version's folder,
    `containers`, `containers/keycloak`, `config`). The `current` link (Linux) must be the account's
    own and lead to a folder directly in `versions/`; `current.txt` (Windows) must name one. A
    refusal reads nothing more: never `config/.env`, nor the admin token in it, so nothing is sent
    to Kilnmark's API (whatever answers on its port). It goes to standard error, the system's
    journal (Linux; a timer's run goes there anyway) or Task Scheduler's history (Windows), and a
    line in `logs` where that folder passes the same check.
    - Linux: each must be the running account's own (root's, as root), no link but `current`, and
      writable by neither its group nor others. So nobody can move the root away and put another
      in its place, every folder above it up to `/` must be root's or the account's and writable by
      neither group nor others; one others may write (`/tmp`) passes only where it is sticky and
      the next folder down is root's or the account's, and never as root (as sshd's StrictModes
      asks). The unit names the root by its canonical path, which must hold nothing a unit file
      reads as more than a path (a space, `%`, a quote, a backslash, a control character). The
      engine a run uses (`KILNMARK_ENGINE`, else podman or docker as `PATH` finds it) and the
      folder the units are in must be root's or the account's and writable by nobody else, and so
      must every folder above each (where `KILNMARK_ENGINE` is a link, the link's own folders
      too): when the timer is set up, and again at every `people expire`. The run's log is never
      written through a link, and `logs` is the account's alone (700).
    - Since decision 17 the program and its data are in two folders whose permissions install
      sets once, as root or an administrator (`/opt/kilnmark` and `/var/opt/kilnmark`;
      `C:\Program Files\Kilnmark` and `C:\ProgramData\Kilnmark`). The checks below stay as a
      second line of defence, over both folders: what a run uses in the program folder (the
      launcher, `data-folder.txt`, `current` or `current.txt`, the current version's
      `compose.yaml` and `keycloak-setup`'s script) and in the data folder (`config/.env`,
      `logs`), and every folder from each root down to it. A folder owned by root (Linux) or
      the Administrators (Windows) and writable by nobody else passes, so the program folder
      does.
    - Windows: each must be there, no link or junction, owned by the operator, SYSTEM or the
      Administrators, and nobody else may write it (a conditional entry for anyone else counts,
      whatever it grants; an app container's or capability's SID never does, since it only
      narrows what a process of someone who holds the right may do). Every folder above each root
      must be no link or junction (it would lead wherever someone made it lead), owned by the
      operator, SYSTEM, the Administrators or TrustedInstaller (which holds `C:\` and
      `C:\Program Files`), with nobody else able to delete what is in it, delete it, or change
      its permissions or owner; TrustedInstaller's rights on `C:\Program Files` count as the
      Administrators' do. `up` no longer writes the folders' permissions: install set them, and a
      folder that fails the checks stops the task being set up, and the hourly run, saying which.
      The task names Windows PowerShell by its full path, never whichever one its `PATH` finds,
      and quotes the script's path, which has a space in it (`C:\Program Files`).
    - The admin token goes only to a listener of the account's own. Before any call to Kilnmark's
      API (`people` commands, the hourly run's records), the control script checks who listens on
      Kilnmark's port, on the loopback or all addresses, and connects to `127.0.0.1`: on Linux
      every such socket must be the account's or root's (the kernel shows every socket's owner to
      everyone; rootless Podman's port forwarder is the account's, Docker's root's); on Windows
      every listener Windows shows must be a program of the user's (one whose owner Windows will
      not name, another account's or a service's, counts against it). Otherwise nothing is sent
      and the command says why. What remains: with WSL's mirrored networking Windows shows no
      listener for a container's port, so there is nothing to check (and nothing else to find
      either, unless someone else binds the port on Windows, which is seen); a listener could
      change in the moment between the check and the connection; and with HTTPS on, the control
      script still takes Kilnmark's own certificate without checking its name (`-k`), as that name
      need not be localhost: the listener is what is checked. Pinning the certificate would need
      a certificate on every install, which the default plain-HTTP install behind a proxy has not.
    - A known limit: the release's signed `SHA256SUMS` covers the archives, not each file of an
      install, so the files a run uses are not checked against a signature before each run; once
      nobody else may write them, nobody else can change them.
    - `KILNMARK_ENGINE` goes into a unit only as the full path of podman or docker. The tests' hooks
      (`KILNMARK_TEST_UID`, `KILNMARK_SYSTEMCTL`, `KILNMARK_SYSTEMD_DIR`, `KILNMARK_SCHTASKS`) count
      only with a marker no release sets.

16. **A realm is one install's, once.** The realm is stamped with the install's id and mode the
    first time the control script hands them over, and never written over: every run of
    `keycloak-setup` refuses a realm stamped for another install or mode, and on production one
    holding the pack's marked group or any of the five example user names (a Keycloak volume kept
    from a Starter Pack install, say). It fails closed: a realm, group or user lookup Keycloak does
    not answer counts against the realm (the pack's group is looked for by an exact search, whose
    empty answer is the only "not there"), and a stamp is read whole and compared exactly, so half
    of one, or one not of Kilnmark's shape, is refused. So do the people commands' own lookups:
    whether someone is from a directory, enabled, in the pack's group or holds a credential, when
    Keycloak does not answer, leaves them as they are (`enable`) or stops (`activate`). A person
    made is checked against their groups by full path: a subgroup with a role's name is another
    group, though the ID token, which carries names, would give that role.
    Restore Kilnmark and its Keycloak together, from the same install's backup. On production the
    sample-data kind of source has no connector at all: it is left out where the server loads its
    connectors, the one place every source, run, scan, rule, document and catalogue view gets its
    connector from, so nothing reaches it, whatever a database kept from a Starter Pack install
    holds. The only code that adds a source of that kind is the samples' own (their sample data,
    for the sample that reads it), and both ways to it refuse on production first: the samples
    API (`/api/v1/samples`, refused on a production install) and the Starter Pack's own setting
    up, which runs only on a Starter Pack install. Adding a source of that kind by hand or in a bulk
    import is refused on production with its own reason.

17. **The install layout and the guided install** (owner, 8 November 2026). Foundation exists
    to install and test the real thing, so release 0.1.2 installs where an operating system
    keeps a program and its data, with folders whose permissions are set once by root or an
    administrator, and asks its questions itself. There is no upgrade to it from 0.1.1 or from
    the temporary Windows root of #135: 0.1.2 is a fresh install.
    - **Two folders.** The program folder holds the releases (`versions/<version>/`, never
      edited), `current` (Linux, a link) or `current.txt` (Windows), the launchers and
      `data-folder.txt`, which names the data folder. The data folder holds `config/.env`,
      `config/install.conf` (the answers), `config/run`, `certs/https`, `backups` and `logs`.
      Volumes stay as they were.
      - Linux: `/opt/kilnmark`, root's, read-only to everyone else; `/var/opt/kilnmark`, where the
        Filesystem Hierarchy Standard puts the variable data of a package in `/opt`, the
        account's (`700`; `config/.env` `600`). The account is `kilnmark` unless install is told
        another: a system account made by install when it is not there, with rootless Podman's
        id ranges and lingering (or the `docker` group).
      - Windows: `C:\Program Files\Kilnmark`, the Administrators' (full control for them and
        SYSTEM, read and run for Users, nothing inherited); `C:\ProgramData\Kilnmark`, the
        operator's (modify), SYSTEM's and the Administrators' (full control), nobody else's,
        nothing inherited. Each is made with those permissions from its first moment. The data
        folder is on the program folder's drive: compose reaches the run's secrets and the
        certificate by a path relative to `compose.yaml`, as some compose tools split a Windows
        path at its drive letter's colon.
      - Paths with spaces work everywhere: the task's `/TR` and the timer's `ExecStart` quote the
        script; compose gets each path as one word; `backup.sh` and `restore.sh` take the compose
        command a word a line (`KILNMARK_COMPOSE_LINES`).
    - **Who runs what.** `install`, `upgrade` and `uninstall` change those folders, so they run as
      root (Linux: the script says why and runs itself again with `sudo`) or ask Windows for an
      administrator (UAC) for the one step that needs one, saying why, in a window of its own
      whose log goes to the data folder's `logs`. Every other command runs as the operator (Linux:
      the account; Windows: the person install named, by default whoever ran it), never as root
      and never elevated: each refuses otherwise, and Windows refuses an operator other than the
      one install named. Podman runs as the operator, so its machine (Windows) or its rootless
      store (Linux) is theirs, and they can read and write the data folder. On Windows install
      itself runs from a prompt that is not elevated: only the folders' step is.
    - **The guided install.** Install asks, each with its default and checked as it is answered:
      the deployment mode (Starter Pack or Production; a Starter Pack install always has the
      bundled Keycloak), the bundled Keycloak or the organisation's own OpenID Connect provider
      (its issuer, client id and groups), the email domain (the first admin's address must be at
      it), the host name, the ports (free, and two answers never one port), TLS (off, on with a
      certificate, or a proxy that ends it; anything but `localhost` needs one of the last two,
      as sign-in works over HTTPS only), and the first admin. API tokens only is no longer an
      answer: a production install signs in with one of the two. Then it shows the answers and
      asks to go on, and every step prints `[n/N]`, then OK or FAILED with the time it took and
      one line saying why.
    - **An answers file**, `--answers install.conf` (`-Answers`), holds the same answers for an
      install with no questions, `install.conf.example` saying every key. It holds no secrets:
      install refuses a key naming a password, secret or token, an unknown key or one given
      twice, naming each by its line, never its value. The secrets are generated, or typed hidden
      when install asks, or piped in on standard input (the identity provider's client secret,
      or the first admin's temporary password). `--dry-run` checks the answers and the
      prerequisites and changes nothing.
    - **`configure KEY=VALUE`** changes an answer later, checks it as install does, writes
      `config/.env` and `config/install.conf`, and runs again only what the answer affects: a
      restart (a stop, then `up`) when Kilnmark is running for a setting of its containers, `people
      add` for the first admin, nothing for the email domain. It refuses the mode (a mode change
      is a new install, decision 6), the project and the folders.
    - **`check`** knows the mode, and covers the machine and the install: Podman's version, its
      compose, its machine, cgroups v2 and the pids controller, WSL's version (Podman needs WSL
      2.7.11 on this kind of machine: 3.0.x breaks crun's pids controller), memory (5 GB with the
      bundled Keycloak), disk, free ports, elevation or root, the folders' owners and who may
      write them, the hourly run's checks, and what is running. Each problem prints the command
      that fixes it.
    - Carried over from #135, and true of the new layout: the admin token goes only to a listener
      of the operator's (decision 15), the task names Windows PowerShell by its full path,
      `keycloak-setup`'s lookups fail closed, and a person's groups are checked by full path
      (decision 16).

## Consequences

- A trial install is one command (`install --starter`, then the questions), with the bundled
  Keycloak, and every sample can run on day one.
- An install is two folders the operating system already protects; the second line of defence
  over them stays. Installs from 0.1.1, and from the temporary Windows root of #135, are installed
  afresh.
- Install, upgrade and uninstall need root or an administrator once each; nothing else does. A production install never gets example data or example people, by any
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
  across, by design. Installs from 0.1.1 and earlier are installed afresh too.
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
