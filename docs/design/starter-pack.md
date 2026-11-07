# The Starter Pack

Example data and samples ready to run, for trials, demonstrations and training, and five example
people made by Keycloak's own setup where `.env` allows it ([ADR 0051](../adr/0051-starter-pack.md)).
Admins turn the example data on and off under **Settings**, **Starter Pack**; the person who runs
Kilnmark adds and removes the people with the control script. The guide is INSTALL.md, "Try
Kilnmark with the Starter Pack".

## Parts

| Part | Where | What it does |
| --- | --- | --- |
| `kilnmark-samples-db.sh` | `containers/postgres`, in the `kilnmark-postgres` image | Run by the `samples-db` one-off at every start, as the server's superuser over TLS: makes or keeps the login and database `kilnmark_samples`, and revokes `CONNECT` and `TEMPORARY` from `PUBLIC` on every other database, and anything granted to the login there |
| `samples-db` | `containers/compose.release.yaml` | The one-off: depends on `postgres` being healthy, `restart: "no"`. Unset `KILNMARK_SAMPLES_DB_PASSWORD`: makes nothing, exits 0 |
| `files` volume | the same | `/srv/files`, writable; `KILNMARK_FILES_DIR` replaces it with the site's folder |
| `StarterPack` | `server/.../api` | Turns the example data on and off: each step caught and reported, the state in the server setting `starter_pack` (JSON: on, since, by, and the ids of the sources it made) |
| `StarterPackResource` | `/api/v1/starter-pack` | `GET` (viewer, for the banner), `GET details`, `POST` and `DELETE` (admin) |
| `ExampleAccounts` | `server/.../starter` | Tells a kilnmark.com sign-in apart, for the access review, the page and production. Kilnmark holds no Keycloak credential and makes no Keycloak call |
| `ProductionCheck` | `server/.../starter` | At every start: stops the server unless `kilnmark.production` is `true` or `false` (any case); on production, revokes the example accounts' tokens and pauses their schedules (below) |
| `OidcAccess.decide` | `server/.../auth` | On production, refuses a kilnmark.com sign-in, renewal or session (`EXAMPLE_ON_PRODUCTION`) |
| `keycloak-setup` | `containers/keycloak/setup.sh` | With Keycloak's own admin: makes the five people in the group `kilnmark-starter-pack` when the control script asks, while `KILNMARK_STARTER=allowed` and not production; deletes the group's people, then the group, when not; turns on admin events while allowed |
| Control script | `containers/launch` | Before every start, refuses a `KILNMARK_PRODUCTION` that is not `true` or `false`, adds `KILNMARK_SAMPLES_DB_PASSWORD` when missing and empties the retired `KILNMARK_KEYCLOAK_DEMO_PASSWORD`; `install --starter` calls `POST /api/v1/starter-pack` (and `starter people` with `--auth`); `starter people [--new-password \| --remove]` runs `keycloak-setup` once with the action in compose's environment and a new password on its standard input |
| Banner | `ui/src/shell/StarterPackBanner.tsx` | Above the top bar on every page while the server says the pack is on |

## Turning it on

1. Refused (409, nothing changed) when `kilnmark.production` is true and `force` is not, when the
   samples are not offered, or when there is no password for the pack's database or no files root.
   Each refusal is recorded as `starter.added` with `refused` saying why (`production`,
   `samples not offered`, `not ready`): a 409 is not otherwise in the audit log.
2. `database`: connects as `kilnmark_samples` and runs the guard (below). A failure stops here and
   is recorded as `starter.added` with that step failed.
3. The state is set on, so the banner shows even if a later step fails.
4. `database source`, `files source`: added (its id kept in the setting), or, when the pack's own
   source of that name is there, set as the pack needs it. A source of that name whose id the pack
   did not keep is a team's own: left as it is, and the step fails saying so. Through
   `SourceService.draft`, so every check a form gets applies, with `audit.sourceSettings` recording
   Allow writing and Allow SQL.
5. `sample tables`: `Samples.createTables` into `public`, unless the `sample-tables` pipeline
   already targets the pack's source and its last run is queued, running or succeeded.
6. `samples`: `Samples.add` with every placeholder mapped to the pack's sources and schema `public`.
7. `starter.added` in the audit log, with each step's outcome and whether it was forced.

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
columns marked sensitive, as the sources page's own delete does. The example people are not touched: the page shows the
control script's command, and which example accounts have signed in, from the audit log.

## The example people

`kilnmark.sh starter people` checks `KILNMARK_STARTER=allowed`, the `auth` profile and that the
install is not marked production, makes a 43-character password, and runs
`compose run --rm -T keycloak-setup` with `KILNMARK_STARTER_PEOPLE_ACTION` in compose's own
environment (which `compose.yaml` passes to `keycloak-setup`) and the password as one line on its
standard input (bash: `printf`, a builtin; Windows: the PowerShell pipeline). Never in the
environment: compose would keep it in the container's settings, and podman-compose puts every
setting on podman's command line as `-e NAME=value`. `setup.sh` reads the line, dropping a CR and a
byte-order mark, which Windows PowerShell can add. The password goes to `/dev/tty` (Windows:
`[Console]::WriteLine`, past the transcript), never to `logs`.

The control scripts remove `KILNMARK_PRODUCTION`, `KILNMARK_STARTER` and
`KILNMARK_STARTER_PEOPLE_ACTION` from their own environment before anything, since compose prefers
the environment to `config/.env`, and read `export NAME=` lines as compose does. podman-compose
1.0.x prints each podman command it runs, every container setting on it; those lines never reach
the screen or `logs`: the bash edition drops them from everything it shows and logs, the Windows
edition from all it passes on from compose.

`setup.sh` checks the same rules itself, before anything else, and refuses to add people otherwise.
It finds groups by their path (`group-by-path`), never by search, which also answers with the parent
of a subgroup of that name.
It makes each missing person with one call, already in the group `kilnmark-starter-pack` and their
role's groups, and deletes them again if the group did not take. Only members of that group are
ever changed or deleted: someone else with one of the addresses is left as they are, never given
groups or a password, whatever attributes they set on themselves or the realm's user profile hides.
It never resets a member's password unless the action is `new-password` (or a run stopped before
giving one). On any run where the people are not allowed it deletes the members among the five, and
the group once it is empty. It changes no realm setting but turning admin events on while allowed.

## On production

`KILNMARK_PRODUCTION` is `true` or `false`, in any case, everywhere: the server does not start with
anything else (`ProductionCheck`), the control scripts refuse anything else, and `setup.sh` takes
anything but `false` as production. On an install marked production:

- a sign-in, renewal or session of a kilnmark.com account (by the name Kilnmark gives the sign-in,
  or its email) is refused with `EXAMPLE_ON_PRODUCTION`, recorded as `auth.sign_in_refused`;
- `ApiTokens.madeByExample` is the one rule for tokens: made by such an account, or its unmask
  grant approved by one, or made by a token that was (`token <id> (...)`), however far up. On
  production such a token is refused by `ApiTokens.authenticate` whenever it is presented, and has
  no working role for a schedule, before and after the sweep;
- at every start, each unfinished pipeline and flow run whose asker is such an account, a token the
  rule finds (a run names a token by its name), or a schedule one of them set (`schedule <id> (set
  by ...)`) is cancelled, each `run.cancelled`; and a worker cancels such a run instead of starting
  it, should one be queued before the sweep;
- at every start, each API token whose maker, or whose unmask grant's approver, is such an account
  is revoked, and so is each token one of those made (its maker is `token <id> (...)`), however far
  down; each is `token.revoked` with the reason, and the schedules it set pause as for any revoked
  token;
- each schedule such an account set (by the person key their sign-ins recorded) is paused, each
  `schedule.paused` with the reason.

## The guard

Every connection the pack makes to its database must be to a database called `kilnmark_samples`,
over TLS (`pg_stat_ssl`), as a login that is not a superuser, in a database with no
`kilnmark.flyway_schema_history`. So `KILNMARK_SAMPLES_DB_URL` pointed at Kilnmark's own database,
or the pack given Kilnmark's own login, is refused before anything is dropped.

## Tests

- `SamplesDatabaseIsolationTest`: the real image and this tree's script, copied in; the login
  reaches only its own database, over TLS, with no powers; it cannot open Kilnmark's, `postgres`
  or `template1`; let in by mistake it reads and makes nothing, and the next start shuts it out; a
  database a restore made is closed at the next start.
- `StarterPeopleSetupTest`: the pinned Keycloak image and `setup.sh`; no people without the switch
  or on production (`true`, `TRUE`, `1`, `yes`, `on`); the password from standard input only, CR
  dropped; made in the group and their groups, leaving someone else with an address alone though they
  set the old mark on themselves, and a team's own group with a subgroup of the pack's group's name
  left alone; the realm's policy and event settings kept; passwords kept unless
  `new-password`; exactly the group's people go, with the realm's attributes hidden, once the switch
  does, or production is set, or `remove` is asked.
- `ProductionCheckTest`, and `StarterPackProductionTest`: only `true` or `false`; on production the
  example accounts' sign-in refused, their tokens refused before any sweep and, with those down the
  line, revoked, a real person's kept, their schedules paused, their runs cancelled by a worker and,
  with no job, by the sweep, each recorded; a refused turn-on recorded.
- `StarterPackApiTest`, `StarterPackProductionTest`: the API end to end, the guard, production, a
  team's own source of the pack's name left alone, `sample-data` kept while a team's flow reads it,
  and turning off recording each schedule deleted and naming what went.
- `ControlScriptStarterTest`: both editions, run for real, with `KILNMARK_PRODUCTION` `TRUE`, `1`
  and the rest, an `export` in front, and the settings in the outside environment, which never reach
  compose; through an engine that prints what podman-compose 1.0.x prints, no command line on the
  screen or in `logs`; the people's password on standard input and in no environment; and no Keycloak
  credential reaches the kilnmark service in any setting.
