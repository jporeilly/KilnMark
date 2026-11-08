#!/bin/sh
# Makes, or keeps, the Starter Pack's database (ADR 0051): an empty database kilnmark_samples on
# Kilnmark's own PostgreSQL server, owned by a login of its own, kilnmark_samples, with no powers and
# no part in any other role. When it makes the database, and only then, it closes it to everyone but
# that login; it never changes another database. The samples-db service of compose.yaml runs this at
# every start of a Starter Pack install, as the server's superuser over TLS (PGHOST, PGUSER,
# PGPASSWORD), once Kilnmark's own record says starter-pack; it changes nothing that is already
# right, so running it again is safe. The Starter
# Pack itself, under Settings in Kilnmark, fills the database and empties it again; this only makes it.
#   KILNMARK_SAMPLES_DB_PASSWORD   the login's password, from config/.env (the control script makes
#                                  one, and hands it to each run in a file of its own); unset,
#                                  nothing is made and Kilnmark runs without the pack.
set -eu

# The two passwords come from a file the control script writes for each run, holding only them,
# mounted read-only at /run/kilnmark/env, not the environment, which podman-compose puts on
# podman's command line (the environment still works, for a compose of your own). Read as compose
# reads .env: an export in front, spaces around =, a value in double or single quotes, or bare up
# to a " #" comment.
setting_from_file() {
  f=/run/kilnmark/env
  [ -f "$f" ] && [ -r "$f" ] || return 0
  line=$(tr -d '\r' < "$f" | grep -E "^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=" | tail -n 1)
  [ -n "$line" ] || return 0
  v=$(printf '%s' "${line#*=}" | sed -e 's/^[[:space:]]*//')
  case "$v" in
    \"*) v=${v#\"}; v=${v%%\"*} ;;
    \'*) v=${v#\'}; v=${v%%\'*} ;;
    *) v=$(printf '%s' "$v" | sed -e 's/[[:space:]]#.*$//' -e 's/[[:space:]]*$//') ;;
  esac
  printf '%s' "$v"
}
[ -n "${PGPASSWORD:-}" ] || PGPASSWORD=$(setting_from_file KILNMARK_DB_PASSWORD)
[ -n "${KILNMARK_SAMPLES_DB_PASSWORD:-}" ] || KILNMARK_SAMPLES_DB_PASSWORD=$(setting_from_file KILNMARK_SAMPLES_DB_PASSWORD)
export PGPASSWORD KILNMARK_SAMPLES_DB_PASSWORD

if [ -z "${KILNMARK_SAMPLES_DB_PASSWORD:-}" ]; then
  echo "samples-db: KILNMARK_SAMPLES_DB_PASSWORD is not set, so the Starter Pack's database is not made." \
    "The control script sets it: kilnmark.sh up (Windows: kilnmark.cmd up)."
  exit 0
fi
if [ "${#KILNMARK_SAMPLES_DB_PASSWORD}" -lt 16 ]; then
  echo "samples-db: KILNMARK_SAMPLES_DB_PASSWORD must be at least 16 characters; nothing was changed." >&2
  exit 1
fi
export PGSSLMODE="${PGSSLMODE:-require}"
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-10}"

# Only on a Starter Pack install by Kilnmark's own record (ADR 0051), never by .env alone: the
# server records the mode at its first start, so wait for it (KILNMARK_SAMPLES_DB_WAIT seconds,
# 300 by default), then make the database only if the record says starter-pack.
waited=0
while :; do
  mode=$(psql -X -q -At -d kilnmark -c "SELECT value FROM kilnmark.server_setting WHERE key = 'deployment_mode'" 2>/dev/null || true)
  [ -z "$mode" ] || break
  if [ "$waited" -ge "${KILNMARK_SAMPLES_DB_WAIT:-300}" ]; then
    echo "samples-db: Kilnmark has not recorded its deployment mode, so the Starter Pack's database is not made. See: kilnmark.sh logs kilnmark" >&2
    exit 1
  fi
  sleep 2; waited=$((waited + 2))
done
if [ "$mode" != starter-pack ]; then
  echo "samples-db: Kilnmark's record says this is not a Starter Pack install, so the Starter Pack's database is not made." >&2
  exit 1
fi

# The password reaches psql through its environment (\getenv), never a command line, and no
# statement of this session is logged, even one that fails (log_min_error_statement would log it
# whole, password and all), whatever the server's own logging settings.
psql -X -q -v ON_ERROR_STOP=1 -d "${PGDATABASE:-postgres}" <<'SQL'
SET log_statement = 'none';
SET log_min_error_statement = panic;
SET log_min_duration_statement = -1;
\getenv samples_password KILNMARK_SAMPLES_DB_PASSWORD
-- The login: no superuser, no database or role making, no replication, no row-security bypass.
SELECT 'CREATE ROLE kilnmark_samples'
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kilnmark_samples')\gexec
ALTER ROLE kilnmark_samples WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS
  CONNECTION LIMIT 20 PASSWORD :'samples_password';
-- It belongs to no other role, so it inherits nothing.
SELECT format('REVOKE %I FROM kilnmark_samples', r.rolname)
  FROM pg_auth_members m JOIN pg_roles r ON r.oid = m.roleid
 WHERE m.member = (SELECT oid FROM pg_roles WHERE rolname = 'kilnmark_samples')\gexec
-- Its database, which it owns: made once, and then, once, closed to everyone but its owner. No
-- other database here is changed: Kilnmark's own tables are its owner's, and this login can read
-- or make nothing there.
SELECT NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kilnmark_samples') AS make_it \gset
\if :make_it
CREATE DATABASE kilnmark_samples OWNER kilnmark_samples;
REVOKE ALL ON DATABASE kilnmark_samples FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE kilnmark_samples TO kilnmark_samples;
\endif
ALTER DATABASE kilnmark_samples OWNER TO kilnmark_samples;
SQL
echo "samples-db: the Starter Pack's database kilnmark_samples is ready, with its own login."
