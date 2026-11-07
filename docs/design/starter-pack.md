# The Starter Pack and the two deployment modes

Every install is **production** or a **Starter Pack install**, chosen once at install and never
converted ([ADR 0051](../adr/0051-starter-pack.md)). A Starter Pack install, for training,
demonstrations and development, has example data and samples ready to run, and five example people
made by Keycloak's own setup. Admins turn the example data on and off under **Settings**,
**Starter Pack**; the person who runs Kilnmark adds and removes the people with the control script.
A production install has none of it. The guide is INSTALL.md, "Choosing a deployment" and "Try
Kilnmark with the Starter Pack".

![The two deployment modes: install sets KILNMARK_MODE once and Kilnmark records it at its first start; production signs in with the organisation's own provider, the bundled Keycloak or tokens only, and has no samples database or example people; a Starter Pack install signs in with the bundled Keycloak only, and has the samples database and example people; a start whose setting differs from the record is refused](../guide/images/deployment-modes.svg)

## Parts

| Part | Where | What it does |
| --- | --- | --- |
| `ModeCheck` | `server/.../starter` | At every start (after the migrations): `kilnmark.mode` is `production` or `starter-pack`, exactly; recorded at the first start in `kilnmark.server_setting` (`deployment_mode`, `INSERT ... ON CONFLICT DO NOTHING`, so the first of several servers wins), and a setting that differs stops the server either way round. Only a new database records a mode: one whose first migration ran before this process started (0.1.1 or earlier) stops the server (`PREDATES`; not in `quarkusDev`), and on a running install production is never recorded where the role or database `kilnmark_samples` already is (`SAMPLES_THERE`). On a running install, a Starter Pack install must sign in with the bundled realm (`signInProblem`). Every refusal is recorded (`server.start_refused`) before the server stops, when the database answers. `samplesAllowed` and `examplePeopleAllowed`, two checks, are both true on a Starter Pack install only |
| `kilnmark-samples-db.sh` | `containers/postgres`, in the `kilnmark-postgres` image | Run by the `samples-db` one-off at every start of a Starter Pack install, as the server's superuser over TLS: makes or keeps the login and database `kilnmark_samples`; when it makes the database, and only then, revokes everything from `PUBLIC` on it. It changes no other database |
| `samples-db` | `containers/compose.release.yaml` | The one-off, in the compose profile `starter-pack`, which the control scripts add on a Starter Pack install only: depends on `postgres` being healthy, `restart: "no"`; reads its passwords from `config/.env` mounted read-only; waits for Kilnmark's recorded mode and makes nothing unless it is `starter-pack`. Unset `KILNMARK_SAMPLES_DB_PASSWORD`: makes nothing, exits 0 |
| `kilnmark-hba.sh` | `containers/postgres`, in the image | At every start of the database (and a new data folder's first), keeps lines at the top of `pg_hba.conf` that let `kilnmark_samples` reach its own database over TLS and reject it everywhere else |
| `files` volume | the same | `/srv/files`, Kilnmark's own, writable: the only folder the samples and the pack write in. A site's own folder (`KILNMARK_FILES_DIR`, a full path) is mounted apart, read-only, at `/srv/share` (`:ro,Z`). The pack's folder, `starter-pack`, is made new (`createDirectory`, never an existing folder or link) with a marker, `.kilnmark-starter-pack`, written new and never through a link, holding a value the pack keeps in its setting: a folder of that name without it is never adopted or deleted |
| `StarterPack` | `server/.../api` | Turns the example data on and off: each step caught and reported, the state in the server setting `starter_pack` (JSON: on, since, by, and the ids of the sources it made). Records what the control script says setup did to the example people (`starter.people`) |
| `StarterPackResource` | `/api/v1/starter-pack` | `GET` (viewer, for the marking: the mode, on and since, never who), `GET details`, `POST` and `DELETE` (admin); `POST people` (admin), from the control script |
| `AuthResource.people` | `/api/v1/auth/people` | Admin, from the control script's `people add` and `import`: records `auth.person_added` for each person made, with their groups, and `auth.people_imported` for each import. Refuses kilnmark.com addresses, groups that are not Kilnmark's, and an admin from a file |
| `ExampleAccounts` | `server/.../starter` | The five example addresses (what `starter.people` may name), and telling a kilnmark.com sign-in apart for the access review and the page. Kilnmark holds no Keycloak credential and makes no Keycloak call |
| `OidcAccess.decide` | `server/.../auth` | On production, refuses a sign-in, renewal or session whose groups claim holds `kilnmark-starter-pack` (or `/kilnmark-starter-pack`) (`STARTER_GROUP_ON_PRODUCTION`) |
| `keycloak-setup` | `containers/keycloak/setup.sh` | With Keycloak's own admin, read from `config/.env` mounted read-only. On a Starter Pack install: makes the five people in the marked group `kilnmark-starter-pack` when the control script asks, while `KILNMARK_STARTER=allowed`; deletes the group's members, then the group, when not. In both modes: adds people of your own (`people add`, `import`, `activate`) and lists them (`people list`); disables anyone whose temporary password went unused past `KILNMARK_PEOPLE_ACTIVATION_HOURS`; keeps Keycloak's admin events on |
| Control script | `containers/launch` | Before every start: refuses a `config/.env` with no mode (an install from before modes), a mode that is not one of the two, a Starter Pack install without the `auth` profile, a one-run setting in `config/.env`, a bad `KILNMARK_PEOPLE_ACTIVATION_HOURS` and a `KILNMARK_FILES_DIR` that is not a full path; adds `KILNMARK_SAMPLES_DB_PASSWORD` on a Starter Pack install. `install` writes the mode, asks how people sign in on production, offers the first admin with the bundled Keycloak, and on `--starter` turns the pack on. `upgrade`, `restore` and `starter people` read the mode the database recorded (`psql` in the `postgres` container) and refuse when it is missing or differs. `starter people` and `people` run `keycloak-setup` once and tell Kilnmark what it did; `people` first checks that Kilnmark answers |
| Marking | `ui/src/shell/StarterPackBanner.tsx` | Above the top bar on every page of a Starter Pack install: "Starter Pack: training and development only.", and while the example data is on, "Its example data and accounts are on." Never on production |

## The mode

The control scripts write `KILNMARK_MODE` at install: `starter-pack` with `--starter`, else
`production`. Release 0.1.2 is the first with modes; installs from 0.1.1 and earlier (previews)
are never upgraded: `up` refuses a `config/.env` with no mode, `upgrade` refuses before it changes
anything when the database has no mode recorded, `restore` refuses a backup with none (or of the
other mode, checked on the restored copy before anything is stopped), and the server refuses to
start on a database migrated before this start with nothing recorded. Each says to install afresh.

Compose would take `KILNMARK_MODE`, `KILNMARK_STARTER` and the one-run settings from the shell's
environment before `config/.env`, so the control scripts remove them from their own environment
before anything, and read `export NAME=` lines, and the name's case exactly, as compose does.

There is no start-up sweep. An earlier design marked an install production with a setting and, at
every start, revoked the tokens example accounts had made (and chains of tokens those made), paused
their schedules and cancelled their runs. With the mode fixed at install no production install has
had them, so the sweep and its lookups went (ADR 0051, decision 8). What stays: a run or schedule a
token asks for names it `token <id> (<name>)` (`AuditLog.requester`), never by a name anyone chooses.

## Turning it on

1. Refused (409, nothing changed) on a production install, always, `force` or not; when the samples
   are not offered; or when there is no password for the pack's database or no files root. Each
   refusal is recorded as `starter.added` with `refused` saying why (`production`,
   `samples not offered`, `not ready`): a 409 is not otherwise in the audit log.
2. `database`: connects as `kilnmark_samples` and runs the guard (below). A failure stops here and
   is recorded as `starter.added` with that step failed.
3. The state is set on, so the marking says so even if a later step fails.
4. `database source`, `files source`: added (its id kept in the setting), or, when the pack's own
   source of that name is there, set as the pack needs it. A source of that name whose id the pack
   did not keep is a team's own: left as it is, and the step fails saying so. Through
   `SourceService.draft`, so every check a form gets applies, with `audit.sourceSettings` recording
   Allow writing and Allow SQL.
5. `sample tables`: `Samples.createTables` into `public`, unless the `sample-tables` pipeline
   already targets the pack's source and its last run is queued, running or succeeded.
6. `samples`: `Samples.add` with every placeholder mapped to the pack's sources and schema `public`.
7. `starter.added` in the audit log, with each step's outcome.

A step that fails says why in the pack's own words, which name no address, value or secret. What a
service it calls says (a source's checks can name the host) goes to the server's log only, and the
step says the log has it.

## Turning it off

Only with `{"confirm": "DELETE"}`. The sample flows and pipelines (`library = 'sample'`), the
sources (each of the pack's two only if its id is the one the pack kept; `sample-data` only if no
pipeline or flow outside the Library names it, in either YAML form), the `sample_region` variable
when the pack set it, the tables (as the pack's login: every schema it owns but `public`, and every
table, view and sequence it owns in `public`, after the guard), and the folder `starter-pack`
(never following a link). Every step runs even if one fails; the state goes off only when all
worked. `starter.removed` records each step's outcome and names the flows, pipelines, sources and
variables it deleted; each schedule deleted with a sample is recorded as `schedule.deleted`, as a
pipeline's or flow's own delete records it, and a source's `source.deleted` says whether it had
columns marked sensitive, as the sources page's own delete does. The example people are not touched:
the page shows the control script's command, and which example accounts have signed in, from the
audit log. The install stays a Starter Pack install.

## The example people

`kilnmark.sh starter people` checks a Starter Pack install, `KILNMARK_STARTER=allowed` and the
`auth` profile, reads the password (typed twice with the echo off, `read -s` or
`Read-Host -AsSecureString`, or one line on standard input), and runs
`compose run --rm -T keycloak-setup` with `KILNMARK_STARTER_PEOPLE_ACTION` in compose's own
environment (which `compose.yaml` passes to `keycloak-setup`) and the password as one line on its
standard input (bash: `printf`, a builtin; Windows: the PowerShell pipeline). Never in the
environment: compose would keep it in the container's settings, and podman-compose puts every
setting on podman's command line as `-e NAME=value`. Never shown: an earlier version made the
password and showed it once, which a transcript or terminal recording kept. `setup.sh` reads the
line, dropping a CR and a byte-order mark, which Windows PowerShell can add.

podman-compose 1.0.x prints each podman command it runs, every container setting on it, joined
with spaces or as a Python list; those lines never reach the screen or `logs`: both editions drop
them from all they pass on from compose, whether or not they are logging.

`setup.sh` checks the same rules itself, before anything else, and refuses to add people otherwise.
It finds groups by their path (`group-by-path`), never by search, which also answers with the parent
of a subgroup of that name. The pack's group carries the attribute `kilnmark_starter_pack`, set only
as setup makes it: a top-level group of that name without it (a directory's, say) stops setup,
changing nothing. Every reader of kcadm's output reads all of it (`one` drains its input; `grep`
without `-q`): a reader that stopped early would end kcadm with SIGPIPE, which pipefail turns into
"not a member", and an example person would stay. It makes each missing person with one call,
already in the group and their role's groups, and deletes them again if the group did not take. Only members of the group are ever changed or deleted: someone else with
one of the addresses is left as they are. It never resets a member's password unless the action is
`new-password` (or a run stopped before giving one). On any run where the people are not allowed it
deletes every member of the group, whatever their user name now is, and then the group. It changes
no realm setting but turning admin events on while allowed.

After `starter people`, and after every `up` (from the latest run's lines, which start at
`keycloak-setup: started`), the control script reads what setup said it did and posts it to
`/api/v1/starter-pack/people`, so Kilnmark's audit log has `starter.people`.

## People of your own

`people add`, `people import FILE`, `people activate EMAIL` and `people list` run `keycloak-setup`
once, in either mode, with the bundled Keycloak only (the commands refuse without the `auth`
profile and say to add people at the organisation's provider), after checking that Kilnmark
answers as its admin (so nobody is made without a record), with `KILNMARK_PEOPLE_ACTION` and, for
`add` and `activate`, the person in compose's environment. A temporary password goes on standard
input; a Starter Pack install's import puts the class's shared one before the file; a production
import sends the file alone. No file is mounted into the container. Install's first admin is
`people add` with `kilnmark-admins`. `setup.sh`:

- refuses an address that is not one, or at kilnmark.com (any letter case), names that are empty,
  longer than 100 characters, hold a control character or a character Keycloak's user profile
  refuses (`( ) / \ ; & < > " $ % ! # ? * ~ | ^ = [ ] { }`), groups that are not Kilnmark's five,
  named twice, or none; `kilnmark-admins` only through `add`; on production
  `kilnmark-sensitive-data` only through `add` too; a password shorter than 12 or holding a control
  character;
- for `import`, checks every line before making anyone: the header `email,first_name,last_name,groups`
  first, `#` lines and blank lines skipped, four fields, each line's problems named by its number,
  never its values; a duplicate address; at most 1000 people. Any bad line: nobody is made, and the
  summary says how many lines were refused. Should Keycloak still refuse someone, it stops there
  and says how many it made first;
- leaves anyone with that user name or email exactly as they are, their password and groups too
  ("left ... as they are");
- `add`, and a Starter Pack install's import, make each person enabled in their groups, never the
  pack's, with `credentials` `temporary: true`, so Keycloak's `UPDATE_PASSWORD` action has them
  choose their own at the first sign-in; a production import makes each disabled with no password
  ("waiting to be activated"); a person whose group did not take is deleted again;
- `activate` resets one person's password to a temporary one (`reset-password`) and enables them,
  only if they are disabled and either have no password or still must choose one; never someone
  active, anyone who has chosen their own, or one of the pack's people;
- at every run, disables each enabled person still holding `UPDATE_PASSWORD` whose password was
  set (its credential's `createdDate`) more than `KILNMARK_PEOPLE_ACTIVATION_HOURS` (72; 1 to 720)
  ago, saying "disabled ...", which the control scripts record (`auth.person_disabled`) after
  every start and every `people` command;
- for `list`, prints each person, their Kilnmark groups, `active`, `waiting-to-be-activated` or
  `disabled`, and whether `UPDATE_PASSWORD` is still pending, never a password.

The control script posts the people made and kept, an import's refused count, who was activated
and who was disabled, to `/api/v1/auth/people` (attributed to the admin token it uses).

## Sign-in on a Starter Pack install

A Starter Pack install always has the bundled Keycloak: install turns the `auth` profile on with new
secrets for it, the control scripts refuse to start one without the profile, and the server refuses
to start one unless `quarkus.oidc.tenant-enabled` is true and `quarkus.oidc.auth-server-url` is the
bundled realm, `http://keycloak:8080/realms/kilnmark` (a trailing slash aside). An outside identity
provider is refused: the example people live in the bundled Keycloak.

## The guard

Every connection the pack makes to its database must be to a database called `kilnmark_samples`,
over TLS (`pg_stat_ssl`), as a login that is not a superuser, in a database with no
`kilnmark.flyway_schema_history`. So `KILNMARK_SAMPLES_DB_URL` pointed at Kilnmark's own database,
or the pack given Kilnmark's own login, is refused before anything is dropped.

## Tests

- `ModeCheckTest`: only the two modes, exactly; a setting that differs from the record refused
  either way round; a Starter Pack install with sign-in off, or an outside issuer, refused.
- `StarterPackProductionTest`, on a database of its own recorded as production: the pack refused
  through the API, `force` or not, and recorded; the people endpoint refused; templates listed; a
  sign-in by the pack's group refused and recorded; a database migrated before the start never
  records a mode, production never where `kilnmark_samples` is, and a refused start is recorded;
  the samples never offered, added or made; the people commands' records, never with a password,
  never a kilnmark.com address, never an admin from a file, nor on production someone who sees
  sensitive values.
- `SamplesDatabaseIsolationTest`: the real image and this tree's script, copied in; the login owns
  its database, reached over TLS, with no powers; the database is closed to other logins; in
  Kilnmark's database it reads and makes nothing; no other database's grants change; a later start
  leaves the samples database's grants as the site set them.
- `StarterPeopleSetupTest`: the pinned Keycloak image and `setup.sh`; production refuses a realm
  with the five; only `starter-pack`, exactly, makes people; the password from standard input only;
  the marked group, an unmarked same-named group refused; someone else with an address left alone;
  passwords kept unless `new-password`; exactly the group's members go once the switch does or
  `remove` is asked. `people add` makes a person once in their groups with a temporary password and
  leaves an existing one alone; refuses kilnmark.com and strange groups; `people import` takes the
  shipped example file, checks the whole file first (a bad line makes nobody, an admin refused, no
  value echoed, Keycloak's refused name characters caught first), leaves those there alone; on
  production makes people disabled with no password, `people activate` one at a time, refusing the
  active and anyone who chose their own; an unused temporary password expires after the window;
  `people list`; the pack's removal never takes imported
  people.
- `StarterPackApiTest`: the API end to end, the guard, a team's own source of the pack's name left
  alone, `sample-data` kept while a team's flow reads it, turning off recording each schedule
  deleted, and the people endpoint.
- `ControlScriptStarterTest`: both editions, run for real: the mode's rules (only the two, picked
  refused for an install from before modes, the activation window checked, a Starter Pack
  install without the `auth` profile refused), the files folder rules, an `export` in front, the
  settings in the outside environment, which never reach compose; through an engine that prints what
  podman-compose 1.0.x prints, no command line on the screen or in `logs`; the people's and a
  person's password on standard input only, never shown or in `logs`, and in no environment; what
  `people` sends Kilnmark; and no Keycloak credential reaches the kilnmark service in any setting.
