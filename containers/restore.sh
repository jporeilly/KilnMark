#!/bin/sh
# Restores Kilnmark from a folder made by backup.sh (Documentation, "Backing up and restoring
# Kilnmark"; ADR 0039). Run it from the release folder, beside compose.yaml and its .env, of the
# backup's Kilnmark version or a later one:
#   ./restore.sh /path/to/backups/kilnmark-20261103T200000Z --yes
# The backup is restored into a database of its own first, while Kilnmark keeps running; only when
# that has worked is Kilnmark stopped and the restored database put in place. The database and keys
# it replaces are kept, as kilnmark_before_<time> and before-restore-<time> in the keys volume, to
# go back to. Everything changed since the backup is lost from the restored copy, including tokens
# revoked since: see the guide for what to check afterwards. --without-keys restores the database
# alone, when the keys file is lost: every stored password must then be entered again.
# KILNMARK_COMPOSE overrides the compose command.
set -eu

src=${1:-}
if [ -z "$src" ] || [ ! -f "$src/backup.txt" ] || [ ! -f "$src/SHA256SUMS" ]; then
  echo "Say which backup to restore, a folder made by backup.sh: ./restore.sh /path/to/backups/kilnmark-<time> --yes" >&2
  exit 2
fi
shift
yes=false
keys=true
for arg in "$@"; do
  case "$arg" in
    --yes) yes=true ;;
    --without-keys) keys=false ;;
    *) echo "Unknown option $arg: only --yes and --without-keys." >&2; exit 2 ;;
  esac
done
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
# database. Each file is checked by name, so a list missing one is refused too.
files="kilnmark-db.dump backup.txt"
if $keys; then files="$files kilnmark-keys.tar"; fi
for f in $files; do
  line=$(grep -E "^[0-9a-f]{64} [ *]$f\$" "$src/SHA256SUMS" || true)     # " *" marks binary mode
  if [ -z "$line" ] || ! (cd "$src" && printf '%s\n' "$line" | $sums -c > /dev/null 2>&1); then
    if [ "$f" = kilnmark-keys.tar ]; then
      echo "kilnmark-keys.tar is missing from the backup or does not match its checksum: put it back from where you keep keys." >&2
      echo "If it is lost for good, --without-keys restores the database alone, and every stored password must be entered again." >&2
    else
      echo "$f is missing from the backup or does not match its checksum: the backup is damaged. Nothing was changed." >&2
    fi
    exit 1
  fi
done
taken=$(sed -n 's/^taken_at=//p' "$src/backup.txt")
from=$(sed -n 's/^kilnmark_version=//p' "$src/backup.txt")
here=$(sed -n 's/^ *image: .*\/kilnmark-server:\(.*\)$/\1/p' compose.yaml | head -n 1)
if [ -z "$from" ] || [ -z "$here" ]; then
  echo "Cannot tell the backup's Kilnmark version, or this release's (its image line in compose.yaml). Nothing was changed." >&2
  exit 1
fi
if [ "$(printf '%s\n%s\n' "$from" "$here" | sort -V | tail -n 1)" != "$here" ]; then
  echo "The backup is from Kilnmark $from, newer than this release ($here). Restore it with $from or later. Nothing was changed." >&2
  exit 1
fi
if ! $yes; then
  echo "This puts Kilnmark back to the backup taken at $taken (Kilnmark $from)."
  echo "The database and keys it replaces are kept, to go back to. To go on: ./restore.sh $src --yes"
  exit 2
fi

stamp=$(date -u +%Y%m%d%H%M%S)     # digits only: PostgreSQL folds a database name to lower case
before="kilnmark_before_$stamp"
stage="checking the database"
# Says, if anything fails, how far the restore got and what state it left.
trap 'rc=$?; if [ $rc -ne 0 ]; then
  echo "Restore failed while $stage." >&2
  case "$stage" in
    "putting the restored database in place"|"restoring the keys volume")
      echo "Kilnmark is stopped. Its previous database is $before (or still kilnmark), and its previous keys are in before-restore-$stamp in the keys volume: see the guide, \"Going back\"." >&2 ;;
    *) echo "Kilnmark and its database are as they were." >&2 ;;
  esac
fi' EXIT

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
sql() {
  $compose exec -T postgres psql -U kilnmark -d postgres -q -v ON_ERROR_STOP=1 -Atc "$1"
}

$compose up -d postgres
# Over TCP: the database's first start answers on its socket before it is ready.
wait_for "The database" "$compose exec -T postgres pg_isready -h 127.0.0.1 -U kilnmark -d postgres" 60

stage="restoring the backup into kilnmark_restoring"
echo "Restoring the backup into a database of its own (Kilnmark keeps running)"
$compose exec -T postgres dropdb -U kilnmark --if-exists kilnmark_restoring
$compose exec -T postgres createdb -U kilnmark kilnmark_restoring
$compose exec -T postgres pg_restore -U kilnmark -d kilnmark_restoring --exit-on-error --single-transaction \
  < "$src/kilnmark-db.dump"

stage="stopping Kilnmark"
echo "Stopping Kilnmark"
$compose stop kilnmark
if $compose exec -T kilnmark true > /dev/null 2>&1; then
  echo "Kilnmark is still running after the stop: nothing was replaced. Stop it, then run restore.sh again." >&2
  exit 1
fi

stage="putting the restored database in place"
echo "Putting the restored database in place; the previous one is kept as $before"
sql "ALTER DATABASE kilnmark RENAME TO $before"
sql "ALTER DATABASE kilnmark_restoring RENAME TO kilnmark"

stage="restoring the keys volume"
# The restore is recorded by Kilnmark at its next start, in the audit log and to your SIEM, from
# restored.properties beside the key.
record="backup=$(basename "$src")
takenAt=$taken
version=$from
previousDatabase=$before
keys=$keys"
if $keys; then
  echo "Restoring the keys volume; the previous keys are kept in before-restore-$stamp"
  # shellcheck disable=SC2016
  $compose run --rm --no-deps -T --entrypoint sh kilnmark -c '
    set -e; cd /var/lib/kilnmark
    rm -rf .restoring && mkdir .restoring && tar -C .restoring -xf -
    mkdir "before-restore-$1"
    for f in * .[!.]*; do
      case "$f" in .restoring|before-restore-*|"*"|".[!.]*") ;; *) mv "$f" "before-restore-$1/" ;; esac
    done
    for f in .restoring/* .restoring/.[!.]*; do [ -e "$f" ] && mv "$f" . ; done
    rmdir .restoring
    printf "%s\n" "$2" > restored.properties' sh "$stamp" "$record" < "$src/kilnmark-keys.tar"
else
  $compose run --rm --no-deps -T --entrypoint sh kilnmark -c \
    'printf "%s\n" "$1" > /var/lib/kilnmark/restored.properties' sh "$record" < /dev/null
fi

stage="starting Kilnmark"
echo "Starting Kilnmark"
$compose up -d
wait_for "Kilnmark" "$compose exec -T kilnmark bash -c \"exec 3<>/dev/tcp/127.0.0.1/8780 && printf 'GET /q/health/ready HTTP/1.0\\r\\nHost: localhost\\r\\n\\r\\n' >&3 && head -n 1 <&3 | grep -q ' 200 '\"" 90

echo "Restored Kilnmark to the backup taken at $taken. Now:"
echo "  - revoke again any token revoked after that time (your SIEM's token.revoked events), and"
echo "    check Settings > Access review;"
if ! $keys; then
  echo "  - enter every stored password again (Sources; AI models' keys; the mail password);"
fi
echo "  - run again the scans and pipelines that ran after it, if their results are needed;"
echo "  - once all is well, drop the previous database: podman compose exec postgres dropdb -U kilnmark $before"
echo "    and remove before-restore-$stamp from the keys volume (the guide says how)."
