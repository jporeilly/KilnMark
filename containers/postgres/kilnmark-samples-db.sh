#!/bin/sh
# Makes, or keeps, the Starter Pack's database (ADR 0051): an empty database kilnmark_samples on
# Kilnmark's own PostgreSQL server, owned by a login of its own, kilnmark_samples, which can connect
# to that database only. Kilnmark's own database, and every other one on the server, is closed to
# everyone but its owner. The samples-db service of compose.yaml runs this at every start, as the
# server's superuser over TLS (PGHOST, PGUSER, PGPASSWORD); it changes nothing that is already right,
# so running it again is safe. The Starter Pack itself, under Settings in Kilnmark, fills the
# database and empties it again; this only makes it.
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
-- Its database, which it owns: the only one it owns.
SELECT 'CREATE DATABASE kilnmark_samples OWNER kilnmark_samples'
 WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kilnmark_samples')\gexec
ALTER DATABASE kilnmark_samples OWNER TO kilnmark_samples;
-- Only its owner connects to kilnmark_samples ...
REVOKE CONNECT, TEMPORARY ON DATABASE kilnmark_samples FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE kilnmark_samples TO kilnmark_samples;
-- ... and no one but its owner connects to any other database here: Kilnmark's own, postgres,
-- template1, and a copy a restore set aside. Run at every start, so a restored database, which
-- PostgreSQL makes open to everyone, is closed again.
SELECT format('REVOKE CONNECT, TEMPORARY ON DATABASE %I FROM PUBLIC', datname)
  FROM pg_database WHERE datallowconn AND datname <> 'kilnmark_samples'\gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM kilnmark_samples', datname)
  FROM pg_database WHERE datname <> 'kilnmark_samples'\gexec
SQL
echo "samples-db: the Starter Pack's database kilnmark_samples is ready; its login connects to it only."
