# The Starter Pack

Example data, samples ready to run and, where allowed, example people, for trials, demonstrations
and training ([ADR 0051](../adr/0051-starter-pack.md)). Admins turn it on and off under
**Settings**, **Starter Pack**; the guide is INSTALL.md, "Try Kilnmark with the Starter Pack".

## Parts

| Part | Where | What it does |
| --- | --- | --- |
| `kilnmark-samples-db.sh` | `containers/postgres`, in the `kilnmark-postgres` image | Run by the `samples-db` one-off at every start, as the server's superuser over TLS: makes or keeps the login and database `kilnmark_samples`, and revokes `CONNECT` and `TEMPORARY` from `PUBLIC` on every other database, and anything granted to the login there |
| `samples-db` | `containers/compose.release.yaml` | The one-off: depends on `postgres` being healthy, `restart: "no"`. Unset `KILNMARK_SAMPLES_DB_PASSWORD`: makes nothing, exits 0 |
| `files` volume | the same | `/srv/files`, writable; `KILNMARK_FILES_DIR` replaces it with the site's folder |
| `StarterPack` | `server/.../api` | Turns the pack on and off: each step caught and reported, the state in the server setting `starter_pack` (JSON: on, since, by) |
| `StarterPackResource` | `/api/v1/starter-pack` | `GET` (viewer, for the banner), `GET details`, `POST` and `DELETE` (admin) |
| `KeycloakPeople` | `server/.../starter` | The five people, through the client `kilnmark-starter` (client credentials), at the sign-in issuer's address |
| `keycloak-setup` | `containers/keycloak/setup.sh` | Makes the client while `KILNMARK_STARTER=allowed` and its secret are set, with `manage-users`, `view-users` and `query-groups` only, and deletes it otherwise; sets the realm's unmanaged attributes to `ADMIN_EDIT` |
| Control script | `containers/launch` | Before every start, adds `KILNMARK_SAMPLES_DB_PASSWORD` when missing and sets or empties `KILNMARK_KEYCLOAK_STARTER_SECRET`; `install --starter` calls `POST /api/v1/starter-pack` |
| Banner | `ui/src/shell/StarterPackBanner.tsx` | Above the top bar on every page while the server says the pack is on |

## Turning it on

1. Refused (409, nothing changed) when `kilnmark.production` is true and `force` is not, when the
   samples are not offered, or when there is no password for the pack's database or no files root.
2. `database`: connects as `kilnmark_samples` and runs the guard (below). A failure stops here.
3. The state is set on, so the banner shows even if a later step fails.
4. `database source`, `files source`: added, or, when the pack's own source of that name is there,
   set as the pack needs it. Through `SourceService.draft`, so every check a form gets applies,
   with `audit.sourceSettings` recording Allow writing and Allow SQL.
5. `sample tables`: `Samples.createTables` into `public`, unless the `sample-tables` pipeline
   already targets the pack's source and its last run is queued, running or succeeded.
6. `samples`: `Samples.add` with every placeholder mapped to the pack's sources and schema `public`.
7. `example people`: when allowed, `KeycloakPeople.add` with a new password, returned once.
8. `starter.added` in the audit log, with each step's outcome and whether it was forced.

## Turning it off

Only with `{"confirm": "DELETE"}`. People first (only those carrying `kilnmark_starter_pack`), then
the sample flows and pipelines (`library = 'sample'`), the sources (`sample-data` only if nothing
outside the Library names it), the `sample_region` variable when the pack set it, the tables (as
the pack's login: every schema it owns but `public`, and every table, view and sequence it owns in
`public`, after the guard), and the folder `starter-pack` (never following a link). Every step runs
even if one fails; the state goes off only when all worked. `starter.removed` records each step.

## The guard

Every connection the pack makes to its database must be to a database called `kilnmark_samples`,
over TLS (`pg_stat_ssl`), as a login that is not a superuser, in a database with no
`kilnmark.flyway_schema_history`. So `KILNMARK_SAMPLES_DB_URL` pointed at Kilnmark's own database,
or the pack given Kilnmark's own login, is refused before anything is dropped.

## Tests

- `SamplesDatabaseIsolationTest`: the real image and script; the login reaches only its own
  database, over TLS, with no powers; it cannot open Kilnmark's, `postgres` or `template1`; let in
  by mistake it reads and makes nothing, and the next start shuts it out; a database a restore made
  is closed at the next start.
- `StarterKeycloakTest`: the pinned Keycloak image and `setup.sh`; no client without the setting;
  the account is refused clients, flows, the realm, the user profile, realm roles and `master`;
  people added marked, an unmarked person never removed; the client gone once the setting is.
- `StarterPackApiTest`, `StarterPackProductionTest`: the API end to end, the guard, production.
- `ControlScriptStarterTest`: both editions, run for real, keep the secrets as the settings say.
