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
| `ModeCheck` | `server/.../starter` | At every start (after the migrations): `kilnmark.mode` is `production` or `starter-pack`, exactly; recorded at the first start in `kilnmark.server_setting` (`deployment_mode`, `INSERT ... ON CONFLICT DO NOTHING`, so the first of several servers wins), and a setting that differs stops the server either way round. With nothing recorded, production is refused when Kilnmark's records name one of the five example addresses (`exampleTraces`). On a running install (not `quarkusDev` or the tests), a Starter Pack install must sign in with the bundled realm (`signInProblem`). `samplesAllowed` and `examplePeopleAllowed`, two checks, are both true on a Starter Pack install only |
| `kilnmark-samples-db.sh` | `containers/postgres`, in the `kilnmark-postgres` image | Run by the `samples-db` one-off at every start of a Starter Pack install, as the server's superuser over TLS: makes or keeps the login and database `kilnmark_samples`; when it makes the database, and only then, revokes everything from `PUBLIC` on it. It changes no other database |
| `samples-db` | `containers/compose.release.yaml` | The one-off, in the compose profile `starter-pack`, which the control scripts add on a Starter Pack install only: depends on `postgres` being healthy, `restart: "no"`. Unset `KILNMARK_SAMPLES_DB_PASSWORD`: makes nothing, exits 0 |
| `files` volume | the same | `/srv/files`, Kilnmark's own, writable: the only folder the samples and the pack write in. A site's own folder (`KILNMARK_FILES_DIR`, a full path) is mounted apart, read-only, at `/srv/share` (`:ro,Z`). The pack's folder, `starter-pack`, is made new (`createDirectory`, never an existing folder or link) with a marker, `.kilnmark-starter-pack`, written new and never through a link, holding a value the pack keeps in its setting: a folder of that name without it is never adopted or deleted |
| `StarterPack` | `server/.../api` | Turns the example data on and off: each step caught and reported, the state in the server setting `starter_pack` (JSON: on, since, by, and the ids of the sources it made). Records what the control script says setup did to the example people (`starter.people`) |
| `StarterPackResource` | `/api/v1/starter-pack` | `GET` (viewer, for the marking: the mode, on and since, never who), `GET details`, `POST` and `DELETE` (admin); `POST people` (admin), from the control script |
| `AuthResource.people` | `/api/v1/auth/people` | Admin, from the control script's `people add` and `import`: records `auth.person_added` for each person made, with their groups, and `auth.people_imported` for each import. Refuses kilnmark.com addresses, groups that are not Kilnmark's, and an admin from a file |
| `ExampleAccounts` | `server/.../starter` | The five example addresses, and telling a kilnmark.com sign-in apart for the access review and the page. Kilnmark holds no Keycloak credential and makes no Keycloak call |
| `OidcAccess.decide` | `server/.../auth` | On production, refuses a sign-in, renewal or session whose groups claim holds `kilnmark-starter-pack` (or `/kilnmark-starter-pack`), or whose user name, email or UPN is one of the five (`STARTER_GROUP_ON_PRODUCTION`) |
| `keycloak-setup` | `containers/keycloak/setup.sh` | With Keycloak's own admin. On a Starter Pack install: makes the five people in the marked group `kilnmark-starter-pack` when the control script asks, while `KILNMARK_STARTER=allowed`; brings in the five exact addresses an earlier version made; deletes the group's members, then the group, when not allowed. On production: refuses a realm that has any of the five. In both: adds people of your own (`people add`, `import`) and lists them (`people list`) |
| Control script | `containers/launch` | Before every start: picks the mode for an install that has none, refuses a mode that is not one of the two, a Starter Pack install without the `auth` profile, `KILNMARK_KEYCLOAK_DEMO_PASSWORD` on production, a one-run setting in `config/.env` and a `KILNMARK_FILES_DIR` that is not a full path; adds `KILNMARK_SAMPLES_DB_PASSWORD` on a Starter Pack install. `install` writes the mode, asks how people sign in on production, offers the first admin with the bundled Keycloak, and on `--starter` turns the pack on. `starter people` and `people` run `keycloak-setup` once and tell Kilnmark what it did |
| Marking | `ui/src/shell/StarterPackBanner.tsx` | Above the top bar on every page of a Starter Pack install: "Starter Pack: training and development only.", and while the example data is on, "Its example data and accounts are on." Never on production |

## The mode

The control scripts write `KILNMARK_MODE` at install: `starter-pack` with `--starter`, else
`production`. For an install made before there were modes, `up` and `upgrade` write it once:
`starter-pack` when `KILNMARK_KEYCLOAK_DEMO_PASSWORD` is set (0.1.1 made the example people with
it), else `production`. Release 0.1.1 was a preview: no install that has had the example people
becomes production. The other two signs are checked where they can be seen: the server refuses to
record production when its own records name one of the five addresses (audit events, as actor or
in details; tokens; pipelines, flows and schedules, as makers; runs, as askers), and
`keycloak-setup` refuses production when its realm has any of them. Each says to install afresh,
never how to convert. Production then does not start while `KILNMARK_KEYCLOAK_DEMO_PASSWORD` is set.

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
already in the group and their role's groups, and deletes them again if the group did not take. The
five exact addresses an earlier version made are brought into the group, each said ("brought ...
into the Starter Pack"). Only members of the group are ever changed or deleted: someone else with
one of the addresses is left as they are. It never resets a member's password unless the action is
`new-password` (or a run stopped before giving one). On any run where the people are not allowed it
deletes every member of the group, whatever their user name now is, and then the group. It changes
no realm setting but turning admin events on while allowed.

After `starter people`, and after every `up` (from the latest run's lines, which start at
`keycloak-setup: started`), the control script reads what setup said it did and posts it to
`/api/v1/starter-pack/people`, so Kilnmark's audit log has `starter.people`.

## People of your own

`people add`, `people import FILE` and `people list` run `keycloak-setup` once, in either mode, with
the bundled Keycloak only (the commands refuse without the `auth` profile and say to add people at
the organisation's provider), with `KILNMARK_PEOPLE_ACTION` and, for `add`, the person's email,
names and groups in compose's environment. The temporary password goes on standard input; for
`import`, the file follows it, so no file is mounted into the container. Install's first admin is
`people add` with `kilnmark-admins`. `setup.sh`:

- refuses an address that is not one, or at kilnmark.com (any letter case), names that are empty,
  longer than 100 characters or hold a control character, and groups that are not Kilnmark's five,
  named twice, or none; `kilnmark-admins` only through `add`, one person at a time;
- for `import`, checks every line before making anyone: the header `email,first_name,last_name,groups`
  first, `#` lines and blank lines skipped, four fields, each line's problems named by its number,
  never its values; a duplicate address; at most 1000 people. Any bad line: nobody is made, and the
  summary says how many lines were refused;
- leaves anyone with that user name or email exactly as they are, their password and groups too
  ("left ... as they are");
- makes each person in their groups, never the pack's, with `credentials` `temporary: true`, so
  Keycloak's `UPDATE_PASSWORD` action has them choose their own at the first sign-in, and deletes
  them again if a group did not take;
- for `list`, prints each person, their Kilnmark groups and whether `UPDATE_PASSWORD` is still
  pending, never a password.

The control script posts the people made and kept, and an import's refused count, to
`/api/v1/auth/people`.

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
  sign-in by the pack's group or by one of the five addresses refused and recorded; production not
  recorded for an install whose records name one of the five; the people commands' records, never
  with a password, never an example address, never an admin from a file.
- `SamplesDatabaseIsolationTest`: the real image and this tree's script, copied in; the login owns
  its database, reached over TLS, with no powers; the database is closed to other logins; in
  Kilnmark's database it reads and makes nothing; no other database's grants change; a later start
  leaves the samples database's grants as the site set them.
- `StarterPeopleSetupTest`: the pinned Keycloak image and `setup.sh`; production refuses a realm
  with the five; only `starter-pack`, exactly, makes people; the password from standard input only;
  the marked group, an unmarked same-named group refused; an earlier version's people brought in;
  passwords kept unless `new-password`; exactly the group's members go once the switch does or
  `remove` is asked. `people add` makes a person once in their groups with a temporary password and
  leaves an existing one alone; refuses kilnmark.com and strange groups; `people import` takes the
  shipped example file, checks the whole file first (a bad line makes nobody, an admin refused, no
  value echoed), leaves those there alone; `people list`; the pack's removal never takes imported
  people.
- `StarterPackApiTest`: the API end to end, the guard, a team's own source of the pack's name left
  alone, `sample-data` kept while a team's flow reads it, turning off recording each schedule
  deleted, and the people endpoint.
- `ControlScriptStarterTest`: both editions, run for real: the mode's rules (only the two, picked
  for an older install by the demo password, production refused while it is set, a Starter Pack
  install without the `auth` profile refused), the files folder rules, an `export` in front, the
  settings in the outside environment, which never reach compose; through an engine that prints what
  podman-compose 1.0.x prints, no command line on the screen or in `logs`; the people's and a
  person's password on standard input only, never shown or in `logs`, and in no environment; what
  `people` sends Kilnmark; and no Keycloak credential reaches the kilnmark service in any setting.
