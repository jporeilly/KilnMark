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
| `ExampleAccounts` | `server/.../starter` | Tells a kilnmark.com sign-in apart, for the access review and the page. Kilnmark holds no Keycloak credential and makes no Keycloak call |
| `keycloak-setup` | `containers/keycloak/setup.sh` | With Keycloak's own admin: makes the five people when the control script asks, while `KILNMARK_STARTER=allowed` and not production; deletes those carrying the mark when not; forces the realm's unmanaged attributes to `ADMIN_EDIT` and turns on admin events while allowed; deletes a `kilnmark-starter` client left by an earlier draft |
| Control script | `containers/launch` | Before every start, adds `KILNMARK_SAMPLES_DB_PASSWORD` when missing and empties the retired `KILNMARK_KEYCLOAK_DEMO_PASSWORD`; `install --starter` calls `POST /api/v1/starter-pack` (and `starter people` with `--auth`); `starter people [--new-password \| --remove]` runs `keycloak-setup` once with the action and a new password in compose's environment |
| Banner | `ui/src/shell/StarterPackBanner.tsx` | Above the top bar on every page while the server says the pack is on |

## Turning it on

1. Refused (409, nothing changed) when `kilnmark.production` is true and `force` is not, when the
   samples are not offered, or when there is no password for the pack's database or no files root.
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

## Turning it off

Only with `{"confirm": "DELETE"}`. The sample flows and pipelines (`library = 'sample'`), the
sources (each of the pack's two only if its id is the one the pack kept; `sample-data` only if no
pipeline or flow outside the Library names it, in either YAML form), the `sample_region` variable
when the pack set it, the tables (as the pack's login: every schema it owns but `public`, and every
table, view and sequence it owns in `public`, after the guard), and the folder `starter-pack`
(never following a link). Every step runs even if one fails; the state goes off only when all
worked. `starter.removed` records each step. The example people are not touched: the page shows the
control script's command, and which example accounts have signed in, from the audit log.

## The example people

`kilnmark.sh starter people` checks `KILNMARK_STARTER=allowed`, the `auth` profile and that the
install is not marked production, makes a 43-character password, and runs
`compose run --rm -T keycloak-setup` with `KILNMARK_STARTER_PEOPLE_ACTION` and
`KILNMARK_STARTER_PEOPLE_PASSWORD` in compose's own environment only, which `compose.yaml` passes to
`keycloak-setup` (podman-compose takes no `-e NAME` without a value). The password goes to `/dev/tty` (Windows:
`[Console]::WriteLine`, past the transcript), never to `logs`. `setup.sh` checks the same rules
itself, makes the missing people with the mark and their groups, never resets a marked person's
password unless the action is `new-password`, never adopts or deletes a person without the mark,
and on any run where the people are not allowed deletes the marked ones.

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
  or on production; made marked, in their groups, leaving someone else with an address alone;
  passwords kept unless `new-password`; the account console cannot set the mark; exactly the marked
  people go once the switch does, or production is set, or `remove` is asked.
- `StarterPackApiTest`, `StarterPackProductionTest`: the API end to end, the guard, production, a
  team's own source of the pack's name left alone, and `sample-data` kept while a team's flow reads it.
- `ControlScriptStarterTest`: both editions, run for real; and no Keycloak credential reaches the
  kilnmark service in any setting.
