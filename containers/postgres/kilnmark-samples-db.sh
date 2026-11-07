#!/bin/sh
# Makes, or keeps, the Starter Pack's database (ADR 0051): an empty database kilnmark_samples on
# Kilnmark's own PostgreSQL server, owned by a login of its own, kilnmark_samples, with no powers and
# no part in any other role. When it makes the database, and only then, it closes it to everyone but
# that login; it never changes another database. The samples-db service of compose.yaml runs this at
# every start of a Starter Pack install, as the server's superuser over TLS (PGHOST, PGUSER,
# PGPASSWORD); it changes nothing that is already right, so running it again is safe. The Starter
# Pack itself, under Settings in Kilnmark, fills the database and empties it again; this only makes it.
#   KILNMARK_SAMPLES_DB_PASSWORD   the login's password, from config/.env (the control script makes
#                                  one); unset, nothing is made and Kilnmark runs without the pack.
set -eu

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

# The password reaches psql through its environment (\getenv), never a command line, and the
# statement that sets it is not logged (log_statement stays none).
psql -X -q -v ON_ERROR_STOP=1 -d "${PGDATABASE:-postgres}" <<'SQL'
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
