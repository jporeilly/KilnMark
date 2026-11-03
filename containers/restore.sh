#!/bin/sh
# Restores Kilnmark from a folder made by backup.sh (Documentation, "Backing up and restoring
# Kilnmark"; ADR 0039): replaces its database and its keys volume with the backup's, records the
# restore in the audit log, and starts Kilnmark. Run it from the release folder, beside compose.yaml
# and its .env, of the backup's Kilnmark version or a later one:
#   ./restore.sh /path/to/backups/kilnmark-20261103T200000Z --yes
# Everything changed since the backup is lost, including tokens revoked since: see the guide for
# what to check afterwards. KILNMARK_COMPOSE overrides the compose command.
set -eu

src=${1:-}
if [ -z "$src" ] || [ ! -f "$src/backup.txt" ]; then
  echo "Say which backup to restore, a folder made by backup.sh: ./restore.sh /path/to/backups/kilnmark-<time> --yes" >&2
  exit 2
fi
if [ ! -f compose.yaml ]; then
  echo "Run restore.sh from Kilnmark's release folder, beside compose.yaml." >&2
  exit 2
fi
compose=${KILNMARK_COMPOSE:-}
if [ -z "$compose" ]; then
  if command -v podman >/dev/null 2>&1; then compose="podman compose"; else compose="docker compose"; fi
fi
if command -v sha256sum >/dev/null 2>&1; then sums="sha256sum"; else sums="shasum -a 256"; fi

# The backup must be whole, and no newer than this release: an older server cannot run a newer
# database, and stops at its first start.
(cd "$src" && $sums -c SHA256SUMS > /dev/null) || {
  echo "The backup's files do not match its SHA256SUMS: it is damaged or incomplete. Nothing was changed." >&2
  exit 1
}
taken=$(sed -n 's/^taken_at=//p' "$src/backup.txt")
from=$(sed -n 's/^kilnmark_version=//p' "$src/backup.txt")
here=$(sed -n 's/^ *image: localhost\/kilnmark-server:\(.*\)$/\1/p' compose.yaml | head -n 1)
if [ "$(printf '%s\n%s\n' "$from" "$here" | sort -V | tail -n 1)" != "$here" ]; then
  echo "The backup is from Kilnmark $from, newer than this release ($here). Restore it with $from or later. Nothing was changed." >&2
  exit 1
fi
if [ "${2:-}" != "--yes" ]; then
  echo "This replaces Kilnmark's database and keys with the backup taken at $taken (Kilnmark $from)."
  echo "Everything changed since then is lost. To go on: ./restore.sh $src --yes"
  exit 2
fi

wait_for() {
  tries=0
  until eval "$2" > /dev/null 2>&1; do
    tries=$((tries + 1))
    if [ "$tries" -ge "$3" ]; then
      echo "$1 did not become ready. See: $compose logs" >&2
      exit 1
    fi
    sleep 2
  done
}

echo "Stopping Kilnmark; starting its database"
$compose stop kilnmark > /dev/null 2>&1 || true
$compose up -d postgres
wait_for "The database" "$compose exec -T postgres pg_isready -U kilnmark -d postgres" 60

echo "Restoring the database"
$compose exec -T postgres dropdb -U kilnmark --if-exists --force kilnmark
$compose exec -T postgres createdb -U kilnmark kilnmark
$compose exec -T postgres pg_restore -U kilnmark -d kilnmark --exit-on-error < "$src/kilnmark-db.dump"

echo "Restoring the keys volume"
$compose run --rm --no-deps -T --entrypoint sh kilnmark \
  -c 'find /var/lib/kilnmark -mindepth 1 -delete && tar -C /var/lib/kilnmark -xf -' < "$src/kilnmark-keys.tar"

# The audit log shows the restore, and the time back to which everything was set. The row is added,
# like every other: the log stays append-only.
printf '%s\n' "INSERT INTO kilnmark.audit_event (actor, action, target, detail) VALUES ('restore.sh', \
'database.restored', 'kilnmark', jsonb_build_object('backup', :'backup', 'takenAt', :'taken', 'version', :'from'));" \
  | $compose exec -T postgres psql -U kilnmark -d kilnmark -q -v ON_ERROR_STOP=1 \
      -v backup="$(basename "$src")" -v taken="$taken" -v from="$from" > /dev/null

echo "Starting Kilnmark"
$compose up -d
wait_for "Kilnmark" "$compose exec -T kilnmark bash -c \"exec 3<>/dev/tcp/127.0.0.1/8780 && printf 'GET /q/health/ready HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '\"" 90

echo "Restored Kilnmark to the backup taken at $taken. Now:"
echo "  - revoke again any token revoked after that time (your SIEM's token.revoked events), and"
echo "    check Settings > Access review;"
echo "  - run again the scans and pipelines that ran after it, if their results are needed."
