#!/bin/sh
# Backs up Kilnmark (Documentation, "Backing up and restoring Kilnmark"; ADR 0039): its database, as a
# PostgreSQL dump, and its keys volume (the key that decrypts stored source passwords, and the
# drivers registered under Settings > Drivers), into a new folder of their own. Run it from the
# release folder, beside compose.yaml, while Kilnmark runs:
#   ./backup.sh /path/to/backups
# The dump and the keys together give every stored source password: keep the keys file somewhere
# other than the dumps, and both encrypted. KILNMARK_COMPOSE overrides the compose command
# (podman compose if Podman is installed, else docker compose).
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

# Not the keys an earlier restore set aside, nor its note for the audit log (restore.sh).
$compose exec -T kilnmark tar -C /var/lib/kilnmark --exclude='./before-restore-*' --exclude=./restored.properties \
  -cf - . > "$dir/kilnmark-keys.tar" \
  || fail "the keys volume could not be copied"
tar -tf "$dir/kilnmark-keys.tar" > /dev/null || fail "the copy of the keys volume cannot be read back"
# The key is made when the first source password is stored: until then there is none to keep.
if ! tar -tf "$dir/kilnmark-keys.tar" | grep -q 'secret\.key$'; then
  echo "Note: no source password is stored yet, so the keys volume holds no key."
fi

version=$(sed -n 's/^ *image: localhost\/kilnmark-server:\(.*\)$/\1/p' compose.yaml | head -n 1)
{
  echo "taken_at=$taken"
  echo "kilnmark_version=$version"
  echo "migration=$(echo "$migration" | tr -d '\r')"
} > "$dir/backup.txt"
(cd "$dir" && $sums kilnmark-db.dump kilnmark-keys.tar backup.txt > SHA256SUMS)

echo "Backed up Kilnmark $version to $dir"
echo "  kilnmark-db.dump   the database ($(wc -c < "$dir/kilnmark-db.dump" | tr -d ' ') bytes)"
echo "  kilnmark-keys.tar  the key for stored source passwords, and drivers: keep it apart from the dumps"
