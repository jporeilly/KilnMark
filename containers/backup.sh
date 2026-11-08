#!/bin/sh
# Backs up Kilnmark (Documentation, "Backing up and restoring Kilnmark"; ADR 0039): its database, as a
# PostgreSQL dump, and its keys volume (the key that decrypts stored source passwords, and the
# drivers registered under Settings > Drivers), into a new folder of their own; with the bundled
# Keycloak (the auth profile), Keycloak's database too (its people, groups and one-time code
# setups), without which nobody could sign in to a restored install. Run it from the release
# folder, beside compose.yaml, while Kilnmark runs:
#   ./backup.sh /path/to/backups
# The dump and the keys together give every stored source password: keep the keys file somewhere
# other than the dumps, and both encrypted. KILNMARK_COMPOSE overrides the compose command
# (podman compose if Podman is installed, else docker compose); KILNMARK_KEYCLOAK (on or off) says
# whether the bundled Keycloak is on, else COMPOSE_PROFILES (or .env beside compose.yaml) does.
set -eu
umask 077

dest=${1:-}
if [ -z "$dest" ]; then
  echo "Say where to put the backup: ./backup.sh /path/to/backups" >&2
  exit 2
fi
if [ ! -f compose.yaml ]; then
  echo "Run backup.sh from Kilnmark's release folder, beside compose.yaml." >&2
  exit 2
fi
compose=${KILNMARK_COMPOSE:-}
if [ -z "$compose" ]; then
  if command -v podman >/dev/null 2>&1; then compose="podman compose"; else compose="docker compose"; fi
fi
if command -v sha256sum >/dev/null 2>&1; then sums="sha256sum"; else sums="shasum -a 256"; fi
keycloak=${KILNMARK_KEYCLOAK:-}
if [ -z "$keycloak" ]; then
  profiles=${COMPOSE_PROFILES:-$(sed -n 's/^COMPOSE_PROFILES=//p' .env 2>/dev/null | tail -n 1)}
  case ",$(printf '%s' "$profiles" | tr -d ' \r')," in *,auth,*) keycloak=on ;; *) keycloak=off ;; esac
fi

taken=$(date -u +%Y-%m-%dT%H:%M:%SZ)
name="kilnmark-$(date -u +%Y%m%dT%H%M%SZ)"
dir="$dest/$name"
if [ -e "$dir" ]; then
  echo "$dir already exists: wait a second and run again." >&2
  exit 1
fi
mkdir -p "$dir"
fail() {
  echo "Backup failed: $1. Nothing usable is in $dir; delete it and run again." >&2
  exit 1
}

# One consistent snapshot of the database while Kilnmark keeps working.
$compose exec -T postgres pg_dump -U kilnmark -d kilnmark --format=custom > "$dir/kilnmark-db.dump" \
  || fail "the database could not be dumped"
# A dump that cannot be read back is no backup: read every part of it, as a restore would.
$compose exec -T postgres pg_restore -f /dev/null < "$dir/kilnmark-db.dump" \
  || fail "the dump cannot be read back"
migration=$($compose exec -T postgres psql -U kilnmark -d kilnmark -Atc \
  "SELECT version FROM kilnmark.flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1") \
  || fail "the database's migration could not be read"
# The install's deployment mode and its own id, made at its first start (ADR 0051): a restore takes
# the backup only into an install of the same mode, and checks Keycloak's database against the id.
recorded() {
  $compose exec -T postgres psql -U kilnmark -d kilnmark -X -q -At \
    -c "SELECT value FROM kilnmark.server_setting WHERE key = '$1'" | tr -d '\r'
}
mode=$(recorded deployment_mode) || fail "the database's deployment mode could not be read"
install=$(recorded install_id) || fail "the install's id could not be read"
if [ -z "$mode" ] || [ -z "$install" ]; then
  fail "the database has no deployment mode or install id recorded yet (Kilnmark records them as it starts: start it, then back up)"
fi

# Not the keys an earlier restore set aside, nor its note for the audit log (restore.sh).
$compose exec -T kilnmark tar -C /var/lib/kilnmark --exclude='./before-restore-*' --exclude=./restored.properties \
  -cf - . > "$dir/kilnmark-keys.tar" \
  || fail "the keys volume could not be copied"
tar -tf "$dir/kilnmark-keys.tar" > /dev/null || fail "the copy of the keys volume cannot be read back"
# The key is made when the first source password is stored: until then there is none to keep.
if ! tar -tf "$dir/kilnmark-keys.tar" | grep -q 'secret\.key$'; then
  echo "Note: no source password is stored yet, so the keys volume holds no key."
fi

# The bundled Keycloak's database, the same way: dumped inside its container, as its own user,
# so no password is needed or shown.
files="kilnmark-db.dump kilnmark-keys.tar backup.txt"
if [ "$keycloak" = on ]; then
  $compose exec -T keycloak-db pg_dump -U keycloak -d keycloak --format=custom > "$dir/keycloak-db.dump" \
    || fail "Keycloak's database could not be dumped"
  $compose exec -T keycloak-db pg_restore -f /dev/null < "$dir/keycloak-db.dump" \
    || fail "the dump of Keycloak's database cannot be read back"
  files="keycloak-db.dump $files"
fi

version=$(sed -n 's/^ *image: .*\/kilnmark-server:\(.*\)$/\1/p' compose.yaml | head -n 1)
{
  echo "taken_at=$taken"
  echo "kilnmark_version=$version"
  echo "migration=$(echo "$migration" | tr -d '\r')"
  echo "keycloak=$( [ "$keycloak" = on ] && echo yes || echo no )"
  echo "deployment_mode=$mode"
  echo "install_id=$install"
} > "$dir/backup.txt"
# shellcheck disable=SC2086
(cd "$dir" && $sums $files > SHA256SUMS)

echo "Backed up Kilnmark $version to $dir"
echo "  kilnmark-db.dump   the database ($(wc -c < "$dir/kilnmark-db.dump" | tr -d ' ') bytes)"
echo "  kilnmark-keys.tar  the key for stored source passwords, and drivers: keep it apart from the dumps"
if [ "$keycloak" = on ]; then
  echo "  keycloak-db.dump   Keycloak's database: its people, groups and one-time code setups ($(wc -c < "$dir/keycloak-db.dump" | tr -d ' ') bytes)"
fi
