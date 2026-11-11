#!/bin/bash
# The upgrade rehearsal (docs/development.md, "The upgrade rehearsal"): proves that the build under
# test upgrades from the previous published release, on a throwaway Linux machine with systemd and
# rootless Podman (the container ci/upgrade-rehearsal/machine.sh starts, or a WSL distribution of
# your own that holds nothing else). As root:
#
#   rehearse.sh --previous DIR --build DIR --key cosign.pub [--build-key PUB] [--expect-migration N] [--keep]
#
#   --previous DIR   the previous release as published: kilnmark-<v>.tar.gz, kilnmark-<v>-images.tar.gz,
#                    kilnmark-<v>-keycloak-images.tar.gz and SHA256SUMS, each with its .sigstore.json
#   --build DIR      the build under test: kilnmark-<v>.tar.gz and kilnmark-<v>-images.tar.gz, with
#                    their .sigstore.json when --build-key is given
#   --key PUB        Kilnmark's public key (config/release/cosign.pub), which the previous release
#                    must be signed with
#   --build-key PUB  the key the build is signed with (a release candidate's rc-cosign.pub); without
#                    it the upgrade does not check the build's signature (--skip-verify)
#   --expect-migration N   the number of the newest database migration in the build (V<N>__*.sql)
#   --keep           leave the install running at the end, or where it failed, to look at
#
# It checks the previous release's files, installs it with its own control script from its own
# images files (a Starter Pack install, which has every service: Keycloak, the samples database),
# puts state into it through the API and the control script, upgrades it as the upgrade guide says
# (docs/guide/install/upgrade.md), and checks that everything is still there and still works. Then,
# with the build's own control script: a backup, a restore of it, and an uninstall that leaves
# nothing. Every step prints "REHEARSAL [n] title: OK (t s)", and the first that fails ends the run.
# No secret is printed: tokens and passwords stay in files only root can read, removed at the end.
set -euo pipefail
umask 077

previous_dir="" build_dir="" key="" build_key="" expect_migration="" keep=false
while [ $# -gt 0 ]; do
  case "$1" in
    --previous) previous_dir="${2:?--previous needs a folder}"; shift ;;
    --build) build_dir="${2:?--build needs a folder}"; shift ;;
    --key) key="${2:?--key needs the public key}"; shift ;;
    --build-key) build_key="${2:?--build-key needs a public key}"; shift ;;
    --expect-migration) expect_migration="${2:?--expect-migration needs a number}"; shift ;;
    --keep) keep=true ;;
    *) echo "Unknown option $1." >&2; exit 2 ;;
  esac
  shift
done
[ -d "$previous_dir" ] && [ -d "$build_dir" ] && [ -f "$key" ] \
  || { echo "Say --previous DIR, --build DIR and --key cosign.pub (see the top of this script)." >&2; exit 2; }
[ "$(id -u)" -eq 0 ] || { echo "Run the rehearsal as root, on a throwaway machine." >&2; exit 2; }
previous_dir="$(readlink -f "$previous_dir")" build_dir="$(readlink -f "$build_dir")" key="$(readlink -f "$key")"
[ -z "$build_key" ] || build_key="$(readlink -f "$build_key")"

# The install: the default folders and account, with a project and ports of the rehearsal's own
# (none of them a port CLAUDE.md keeps for something else).
prog=/opt/kilnmark data=/var/opt/kilnmark account=kilnmark project=kmupgrade
port=28780 https_port=28443 db_port=25442 keycloak_port=28180
url="http://127.0.0.1:$port"
ctl="$prog/kilnmark.sh"

# One version from a folder's kilnmark-<version>.tar.gz.
version_in() {
  local f n=0 v=""
  for f in "$1"/kilnmark-*.tar.gz; do
    case "$(basename "$f")" in *-images.tar.gz) continue ;; esac
    v="$(basename "$f" .tar.gz)"; v="${v#kilnmark-}"; n=$((n + 1))
  done
  [ "$n" -eq 1 ] || { echo "$1 must hold exactly one kilnmark-<version>.tar.gz." >&2; exit 2; }
  printf '%s' "$v"
}
previous="$(version_in "$previous_dir")" && build="$(version_in "$build_dir")" || exit 2

# Whatever is in these folders at the end is removed with its data, so there must be nothing there
# at the start: the rehearsal is for a machine that holds no Kilnmark of its own.
installed() { [ -e "$prog" ] || [ -e "$data" ]; }
if installed; then
  echo "Kilnmark is on this machine already ($prog or $data is there): the rehearsal runs only on a throwaway machine with none, since it removes its install with its data at the end." >&2
  exit 2
fi

# --- steps ---------------------------------------------------------------------------------------

n=0 title="" began="$(date +%s)"
work="$(mktemp -d /root/kilnmark-upgrade.XXXXXX)"
took() { echo $(( $(date +%s) - began )); }
step() { n=$((n + 1)); title="$1"; began="$(date +%s)"; echo; echo "REHEARSAL [$n] $title"; }
ok() { echo "REHEARSAL [$n] $title: OK ($(took) s)"; title=""; }
# A command with a time limit ($1, seconds): install and upgrade wait for as long as a container
# takes to become healthy, which one that never does makes for ever.
limit() {
  local seconds="$1" rc=0; shift
  timeout -k 60 "$seconds" "$@" || rc=$?
  [ "$rc" -ne 124 ] && [ "$rc" -ne 137 ] || echo "It did not finish within $((seconds / 60)) minutes, and was stopped." >&2
  return "$rc"
}
# As the account that runs Kilnmark, as the guides say: sudo -iu kilnmark /opt/kilnmark/kilnmark.sh ...
km() { sudo -iu "$account" "$ctl" "$@"; }
# What a failure leaves to read: nothing here holds a secret (the control script and the server
# print none).
diagnose() {
  [ -x "$ctl" ] && [ -d "$data" ] || return 0
  echo "--- status ---"; km status 2>&1 | tail -n 30 || true
  echo "--- the server's last lines ---"; km logs kilnmark 2>&1 | tail -n 80 || true
  echo "--- the control script's last log ---"
  local last; last="$(ls -1t "$data"/logs/*.log 2> /dev/null | head -n 1 || true)"
  [ -z "$last" ] || { echo "$last"; tail -n 40 "$last"; } || true
}
fail() {
  echo "REHEARSAL [$n] ${title:-the rehearsal}: FAILED ($(took) s): $*" >&2
  diagnose >&2
  title=""
  exit 1
}
finish() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ] && [ -n "$title" ]; then
    echo "REHEARSAL [$n] $title: FAILED ($(took) s): the command above stopped (exit $rc)" >&2
    diagnose >&2 || true
  fi
  if $keep; then
    echo "Kept (--keep): the install in $prog and $data, and the rehearsal's files in $work (they hold tokens: remove them)."
  else
    if installed; then
      echo "Removing the rehearsal's install ..."
      echo DELETE | "$ctl" uninstall --purge > "$work/uninstall.log" 2>&1 || { echo "The rehearsal's install could not be removed:" >&2; tail -n 20 "$work/uninstall.log" >&2; }
    fi
    rm -rf "$work"
  fi
  exit "$rc"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# --- Kilnmark's API ----------------------------------------------------------------------------

# A request with a token kept in a curl settings file (never on a command line, never printed):
# $1 that file, $2 the method, $3 the path, $4 a file with the JSON body or nothing. The answer goes
# to $work/answer, and the HTTP code is printed.
request() {
  local conf="$1" method="$2" path="$3" body="${4:-}"
  local -a args=(-s -S --config "$conf" -X "$method" --max-time 900 -o "$work/answer" -w '%{http_code}')
  [ -z "$body" ] || args+=(-H 'Content-Type: application/json' --data-binary "@$body")
  : > "$work/answer"
  curl "${args[@]}" "$url$path" || echo 000
}
# The same, failing the step unless the code is $1 (one code, or several separated by |).
expect() {
  local want="$1" code; shift
  code="$(request "$@")"
  case "|$want|" in *"|$code|"*) ;; *) fail "$2 $3 answered HTTP $code, not $want: $(head -c 400 "$work/answer")" ;; esac
}
admin() { local want="$1"; shift; expect "$want" "$work/admin.curl" "$@"; }
steward() { local want="$1"; shift; expect "$want" "$work/steward.curl" "$@"; }
# A value from the last answer.
answer() { jq -r "$1" "$work/answer"; }
# A query on Kilnmark's own database, with the database's own tools (as recorded_setting does).
sql() { km compose exec -T postgres psql -U kilnmark -d kilnmark -X -q -At -c "$1" < /dev/null; }
random_password() { head -c 24 /dev/urandom | base64 | tr -d '/+=\n'; }

# What must be the same after the upgrade as before it, each in a file of its own: $1 the folder.
# Lists hold what was there before; the upgrade may add to them (a later release's samples), never
# take away.
snapshot() {
  local dir="$1"; mkdir -p "$dir"
  admin 200 GET /api/v1/sources; jq -S '[.[] | {name, kind, settings, hasPassword}] | sort_by(.name)' "$work/answer" > "$dir/sources.json"
  admin 200 GET /api/v1/pipelines; jq -S '[.[] | {name, latestVersion, valid}] | sort_by(.name)' "$work/answer" > "$dir/pipelines.json"
  admin 200 GET /api/v1/flows; jq -S '[.[] | {name, latestVersion, valid}] | sort_by(.name)' "$work/answer" > "$dir/flows.json"
  admin 200 GET /api/v1/schedules
  jq -S '[.[] | {id, pipeline, flow, cron, zone, runAs, enabled, description, createdBy}] | sort_by(.id)' "$work/answer" > "$dir/schedules.json"
  admin 200 GET /api/v1/tokens; jq -S '[.[] | {id, name, role, status, expiresAt}] | sort_by(.id)' "$work/answer" > "$dir/tokens.json"
  admin 200 GET "/api/v1/pipelines/$pipeline"; jq -r '.latest.yaml' "$work/answer" > "$dir/pipeline.yaml"
  admin 200 GET "/api/v1/pipelines/$pipeline/runs?limit=200"
  jq -S '[.[] | {id, version, status, rowsWritten, requestedBy}] | sort_by(.id)' "$work/answer" > "$dir/runs.json"
  admin 200 GET "/api/v1/flows/$flow/runs?limit=200"; jq -S '[.[] | {id, version, status}] | sort_by(.id)' "$work/answer" > "$dir/flow-runs.json"
  sql "SELECT key || '=' || value FROM kilnmark.server_setting WHERE key IN ('deployment_mode', 'install_id') ORDER BY key" > "$dir/settings.txt"
  sql "SELECT count(*) FROM kilnmark.audit_event" > "$dir/audit-count.txt"
  km people list 2> /dev/null | grep -o '[a-z0-9._%+-]*@[a-z0-9.-]*' | sort -u > "$dir/people.txt"
}
# Everything in the earlier list (a JSON array, $1) is in the later one ($2), unchanged.
all_kept() {
  jq -e -n --slurpfile a "$1" --slurpfile b "$2" '($a[0] - $b[0]) | length == 0' > /dev/null \
    || fail "$(basename "$1" .json): what was there before the upgrade is not all there after it, unchanged: $(jq -c -n --slurpfile a "$1" --slurpfile b "$2" '$a[0] - $b[0]' | head -c 600)"
}

# --- the previous release ------------------------------------------------------------------------

echo "The upgrade rehearsal: from Kilnmark $previous (published) to Kilnmark $build (the build under test)"
[ "$(printf '%s\n%s\n' "$previous" "$build" | sort -V | tail -n 1)" = "$build" ] && [ "$previous" != "$build" ] \
  || { echo "Kilnmark $build is not later than $previous: there is nothing to rehearse." >&2; exit 2; }

step "Check the previous release's files against Kilnmark's key and SHA256SUMS"
command -v cosign > /dev/null || fail "cosign is not on the PATH"
files=("kilnmark-$previous.tar.gz" "kilnmark-$previous-images.tar.gz" "kilnmark-$previous-keycloak-images.tar.gz")
for f in SHA256SUMS "${files[@]}"; do
  [ -f "$previous_dir/$f" ] && [ -f "$previous_dir/$f.sigstore.json" ] || fail "$f or its .sigstore.json is not in $previous_dir"
  cosign verify-blob --key "$key" --insecure-ignore-tlog=true --bundle "$previous_dir/$f.sigstore.json" "$previous_dir/$f" > /dev/null 2>&1 \
    || fail "$f does not match its signature with Kilnmark's key"
  echo "Signature checked: $f"
done
for f in "${files[@]}"; do
  grep -q "  $f\$" "$previous_dir/SHA256SUMS" || fail "SHA256SUMS does not list $f"
done
(cd "$previous_dir" && sha256sum -c SHA256SUMS --ignore-missing) || fail "a file does not match SHA256SUMS"
ok

step "Unpack Kilnmark $previous and check the machine with its control script"
mkdir "$work/previous" && tar -xzf "$previous_dir/kilnmark-$previous.tar.gz" -C "$work/previous"
release="$work/previous/kilnmark-$previous"
[ -f "$release/kilnmark.sh" ] || fail "the archive does not hold kilnmark-$previous/kilnmark.sh"
[ "$("$release/kilnmark.sh" version)" = "Kilnmark $previous (not installed)" ] || fail "the control script does not say it is Kilnmark $previous"
# the release's own key is the one in the repository
cmp -s "$release/cosign.pub" "$key" || fail "the cosign.pub in the release is not config/release/cosign.pub"
cat > "$work/install.conf" <<EOF
mode=starter-pack
email_domain=rehearsal.example
host=localhost
tls=off
port=$port
https_port=$https_port
db_port=$db_port
keycloak_port=$keycloak_port
admin_email=ada@rehearsal.example
admin_first_name=Ada
admin_last_name=Rehearsal
project=$project
EOF
"$release/kilnmark.sh" install --answers "$work/install.conf" --dry-run < /dev/null
ok

# As INSTALL.md says for a machine without internet access: sudo ./kilnmark.sh install --answers
# install.conf --offline ../kilnmark-<version>-images.tar.gz, the first admin's temporary password
# piped in. Kilnmark's two images come from the release's own file, whose signature install checks
# itself, never from the registry (its packages are private). Keycloak's comes from quay.io, as
# compose.yaml names it by digest.
step "Install Kilnmark $previous with its own control script, from its offline images file"
random_password > "$work/admin-person.pw"; random_password > "$work/viewer-person.pw"
(cd "$release" && limit 2400 ./kilnmark.sh install --answers "$work/install.conf" \
  --offline "$previous_dir/kilnmark-$previous-images.tar.gz" < "$work/admin-person.pw")
[ "$(km version)" = "Kilnmark $previous" ] || fail "the install does not say it is Kilnmark $previous"
[ "$(stat -c '%U %a' "$data/config/.env")" = "$account 600" ] || fail "config/.env is not the account's alone"
if grep -E '^KILNMARK_(DB_PASSWORD|ADMIN_TOKEN)=$' "$data/config/.env" > /dev/null; then fail "install left a secret empty"; fi
ok

step "Kilnmark $previous runs: status, check, health, the admin token, the Starter Pack"
km status | tee "$work/status.txt"
grep -q "^Kilnmark $previous in $prog" "$work/status.txt" || fail "status does not say Kilnmark $previous"
grep -q "^Health: ready" "$work/status.txt" || fail "status does not say the health is ready"
km check || fail "check found a problem"
# the admin token install made, from config/.env into a file only root reads
{ printf 'header = "Authorization: Bearer %s"\n' "$(sed -n 's/^KILNMARK_ADMIN_TOKEN=//p' "$data/config/.env" | tail -n 1)"; } > "$work/admin.curl"
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$url/q/health/ready")" = 200 ] || fail "the health endpoint is not ready"
[ "$(curl -s --max-time 30 "$url/api/v1/info" | jq -r .version)" = "$previous" ] || fail "the server does not say it is $previous"
admin 200 GET /api/v1/me
[ "$(answer .role)" = admin ] || fail "the admin token is not an admin's"
admin 200 GET /api/v1/sources
for s in starter-samples-db starter-samples-files; do
  jq -e --arg s "$s" 'any(.[]; .name == $s)' "$work/answer" > /dev/null || fail "install did not turn the Starter Pack on: there is no source $s"
done
ok

step "Put state in: a person, a source, a pipeline saved and run, a flow run, a schedule, a token"
# a second person of the install's own in the bundled Keycloak, beside the first admin install made
printf '%s\n' bob@rehearsal.example Bob Rehearsal kilnmark-viewers "$(cat "$work/viewer-person.pw")" | km people add
km people list > "$work/people.txt"
for p in ada@rehearsal.example bob@rehearsal.example; do
  grep -q "$p" "$work/people.txt" || fail "people list does not show $p"
done
# a files source of the rehearsal's own, in Kilnmark's files folder
km compose exec -T kilnmark mkdir -p /srv/files/rehearsal < /dev/null
cat > "$work/source.json" <<'EOF'
{"name": "rehearsal-files", "kind": "files", "displayName": "Rehearsal files",
 "settings": {"path": "/srv/files/rehearsal", "allow_writing": "true", "description": "Made by the upgrade rehearsal."}}
EOF
admin 201 POST /api/v1/sources "$work/source.json"
admin 200 POST /api/v1/sources/rehearsal-files/test
[ "$(answer .ok)" = true ] || fail "the new source does not connect: $(answer .problem)"
# a pipeline of its own: the Starter Pack's accounts table, read with the password Kilnmark stored
# for that source (so the key in the keys volume must still open it after the upgrade), to a CSV file
pipeline=rehearsal-accounts
cat > "$work/pipeline.yaml" <<'EOF'
kilnmark: pipeline/v1
name: rehearsal-accounts
description: Every account of the Starter Pack's CRM, as a CSV file (the upgrade rehearsal).

nodes:
  - id: accounts
    type: read
    source: starter-samples-db
    asset: public.crm_accounts

  - id: out
    type: write
    input: accounts
    source: rehearsal-files
    path: accounts.csv
    mode: replace
EOF
jq -n --rawfile yaml "$work/pipeline.yaml" '{name: "rehearsal-accounts", displayName: "Rehearsal accounts", yaml: $yaml, message: "The upgrade rehearsal"}' > "$work/pipeline.json"
admin 201 POST /api/v1/pipelines "$work/pipeline.json"
[ "$(answer .latest.valid)" = true ] || fail "the pipeline was saved with problems: $(jq -c '.latest.problems' "$work/answer")"
echo '{}' > "$work/empty.json"
admin 200 POST "/api/v1/pipelines/$pipeline/runs?wait=600" "$work/empty.json"
[ "$(answer .status)" = succeeded ] || fail "the pipeline's run ended $(answer .status): $(answer .error)"
rows="$(answer .rowsWritten)"
[ "$rows" -gt 0 ] 2> /dev/null || fail "the pipeline's run wrote no rows"
echo "The pipeline $pipeline wrote $rows rows."
# one of the Starter Pack's flows, run once: it runs a pipeline for each of three regions
flow=sample-for-each-region
admin 200 POST "/api/v1/flows/$flow/runs?wait=600" "$work/empty.json"
[ "$(answer .status)" = succeeded ] || fail "the flow $flow ended $(answer .status): $(answer .error)"
flow_runs="$(answer .pipelineRuns)"
echo "The flow $flow ran $flow_runs pipeline runs."
# a schedule on the pipeline, far enough away never to fire during the rehearsal
cat > "$work/schedule.json" <<EOF
{"pipeline": "$pipeline", "cron": "30 4 1 1 *", "zone": "Europe/London", "runAs": "steward", "enabled": true,
 "description": "Made by the upgrade rehearsal."}
EOF
admin 201 POST /api/v1/schedules "$work/schedule.json"
schedule="$(answer .id)"
# a steward's token, which must still work after the upgrade
jq -n --arg at "$(date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)" '{name: "rehearsal-steward", role: "steward", expiresAt: $at}' > "$work/token.json"
# (its answer holds the token, so it is never shown, whatever the code)
code="$(request "$work/admin.curl" POST /api/v1/tokens "$work/token.json")"
if [ "$code" != 201 ]; then : > "$work/answer"; fail "POST /api/v1/tokens answered HTTP $code, not 201"; fi
{ printf 'header = "Authorization: Bearer %s"\n' "$(answer .token)"; } > "$work/steward.curl"
: > "$work/answer"
steward 200 GET /api/v1/me
[ "$(answer .role)" = steward ] || fail "the new token is not a steward's"
snapshot "$work/before"
grep -qx 'deployment_mode=starter-pack' "$work/before/settings.txt" || fail "the database did not record the Starter Pack mode"
migration_before="$(sql "SELECT max(version::int) FROM kilnmark.flyway_schema_history WHERE version IS NOT NULL")"
echo "Before the upgrade: $(jq length "$work/before/sources.json") sources, $(jq length "$work/before/pipelines.json") pipelines, $(jq length "$work/before/flows.json") flows, $(jq length "$work/before/schedules.json") schedules, $(wc -l < "$work/before/people.txt") people, migration $migration_before."
ok

# --- the upgrade ---------------------------------------------------------------------------------

step "Upgrade to Kilnmark $build, as the upgrade guide says"
[ -z "$(ls -A "$data/backups")" ] || fail "there is a backup before the upgrade took one"
mkdir "$work/build"
cp "$build_dir/kilnmark-$build.tar.gz" "$build_dir/kilnmark-$build-images.tar.gz" "$work/build/"
if [ -n "$build_key" ]; then
  cp "$build_dir/kilnmark-$build.tar.gz.sigstore.json" "$build_dir/kilnmark-$build-images.tar.gz.sigstore.json" "$work/build/"
  # sudo /opt/kilnmark/kilnmark.sh upgrade kilnmark-<version>.tar.gz --offline kilnmark-<version>-images.tar.gz,
  # with the release candidate's key in place of Kilnmark's
  KILNMARK_COSIGN_PUB="$build_key" limit 2400 "$ctl" upgrade "$work/build/kilnmark-$build.tar.gz" --offline "$work/build/kilnmark-$build-images.tar.gz"
else
  echo "The build is not signed, so the upgrade does not check its signature (--skip-verify)."
  limit 2400 "$ctl" upgrade "$work/build/kilnmark-$build.tar.gz" --offline "$work/build/kilnmark-$build-images.tar.gz" --skip-verify
fi
ok

step "After the upgrade: the version, status, check and health"
[ "$(km version)" = "Kilnmark $build" ] || fail "the install says $(km version), not Kilnmark $build"
[ "$(readlink "$prog/current")" = "versions/$build" ] || fail "$prog/current does not lead to versions/$build"
cmp -s "$prog/kilnmark.sh" "$prog/versions/$build/kilnmark.sh" || fail "the control script in $prog is not the new version's"
[ -d "$prog/versions/$previous" ] || fail "the previous version was not kept beside the new one"
km status | tee "$work/status.txt"
grep -q "^Kilnmark $build in $prog" "$work/status.txt" || fail "status does not say Kilnmark $build"
grep -q "^Health: ready" "$work/status.txt" || fail "status does not say the health is ready"
grep -q "^People expiry: hourly" "$work/status.txt" || fail "status does not say the hourly expiry is set up"
km check || fail "check found a problem"
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$url/q/health/ready")" = 200 ] || fail "the health endpoint is not ready"
[ "$(curl -s --max-time 30 "$url/api/v1/info" | jq -r .version)" = "$build" ] || fail "the server does not say it is $build"
for image in "kilnmark-server:$build" "kilnmark-postgres:$build"; do
  sudo -iu "$account" podman ps --filter "label=com.docker.compose.project=$project" --format '{{.Image}}' | grep -q "/$image\$" \
    || fail "no container runs $image"
done
ok

step "After the upgrade: the database migrations ran"
failed="$(sql "SELECT count(*) FROM kilnmark.flyway_schema_history WHERE NOT success")"
[ "$failed" = 0 ] || fail "$failed migrations are recorded as failed"
migration_after="$(sql "SELECT max(version::int) FROM kilnmark.flyway_schema_history WHERE version IS NOT NULL")"
[ "$migration_after" -ge "$migration_before" ] || fail "the newest migration went back from $migration_before to $migration_after"
if [ -n "$expect_migration" ]; then
  [ "$migration_after" = "$expect_migration" ] || fail "the database is at migration $migration_after, and the build's newest is $expect_migration"
fi
echo "The database is at migration $migration_after (it was at $migration_before); none failed."
ok

step "After the upgrade: the backup upgrade took"
backup="$(ls -1d "$data"/backups/kilnmark-* 2> /dev/null | tail -n 1 || true)"
[ -n "$backup" ] && [ "$(ls -1d "$data"/backups/kilnmark-* | wc -l)" = 1 ] || fail "upgrade did not leave exactly one backup in $data/backups"
ls -la "$backup"
(cd "$backup" && sha256sum -c SHA256SUMS) || fail "the backup's files do not match its SHA256SUMS"
for f in kilnmark-db.dump kilnmark-keys.tar keycloak-db.dump; do
  [ -s "$backup/$f" ] || fail "the backup has no $f"
done
# taken from the previous version, before anything changed
grep -qx "kilnmark_version=$previous" "$backup/backup.txt" || fail "the backup does not say it was taken from Kilnmark $previous"
grep -qx "migration=$migration_before" "$backup/backup.txt" || fail "the backup does not say migration $migration_before"
grep -qx "deployment_mode=starter-pack" "$backup/backup.txt" || fail "the backup does not say the Starter Pack mode"
ok

step "After the upgrade: everything put in before is still there"
admin 200 GET /api/v1/me
snapshot "$work/after"
for what in sources pipelines flows schedules tokens runs flow-runs; do all_kept "$work/before/$what.json" "$work/after/$what.json"; done
cmp -s "$work/before/pipeline.yaml" "$work/after/pipeline.yaml" || fail "the pipeline's YAML changed"
cmp -s "$work/before/settings.txt" "$work/after/settings.txt" || fail "the recorded mode or the install's id changed"
[ -z "$(comm -23 "$work/before/people.txt" "$work/after/people.txt")" ] || fail "people are missing: $(comm -23 "$work/before/people.txt" "$work/after/people.txt" | tr '\n' ' ')"
[ "$(cat "$work/after/audit-count.txt")" -ge "$(cat "$work/before/audit-count.txt")" ] || fail "the audit log is shorter than before"
admin 200 GET "/api/v1/schedules/$schedule"
[ "$(answer .enabled)" = true ] && [ "$(answer .nextAt)" != null ] || fail "the schedule is not on with a next time"
admin 200 GET /api/v1/starter-pack
steward 200 GET /api/v1/me
[ "$(answer .role)" = steward ] || fail "the steward's token no longer gives its role"
ok

step "After the upgrade: the pipeline and the flow still run"
for s in starter-samples-db rehearsal-files; do
  admin 200 POST "/api/v1/sources/$s/test"
  [ "$(answer .ok)" = true ] || fail "the source $s no longer connects: $(answer .problem)"
done
steward 200 POST "/api/v1/pipelines/$pipeline/runs?wait=600" "$work/empty.json"
[ "$(answer .status)" = succeeded ] || fail "the pipeline's run ended $(answer .status): $(answer .error)"
[ "$(answer .rowsWritten)" = "$rows" ] || fail "the pipeline wrote $(answer .rowsWritten) rows, and $rows before the upgrade"
echo "The pipeline $pipeline wrote $rows rows again, started with the steward's token."
steward 200 POST "/api/v1/flows/$flow/runs?wait=600" "$work/empty.json"
[ "$(answer .status)" = succeeded ] || fail "the flow $flow ended $(answer .status): $(answer .error)"
[ "$(answer .pipelineRuns)" = "$flow_runs" ] || fail "the flow $flow ran $(answer .pipelineRuns) pipeline runs, and $flow_runs before the upgrade"
echo "The flow $flow ran $flow_runs pipeline runs again."
ok

# --- the build's own control script, day to day ---------------------------------------------------

step "With Kilnmark $build: a backup, a restore of it, and the state again"
km backup
newest="$(ls -1d "$data"/backups/kilnmark-* | tail -n 1)"
[ "$newest" != "$backup" ] || fail "backup made no new backup"
(cd "$newest" && sha256sum -c SHA256SUMS) || fail "the new backup's files do not match its SHA256SUMS"
km restore "$newest" --yes
[ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 30 "$url/q/health/ready")" = 200 ] || fail "the health endpoint is not ready after the restore"
snapshot "$work/restored"
for what in sources pipelines flows schedules tokens; do all_kept "$work/after/$what.json" "$work/restored/$what.json"; done
steward 200 POST "/api/v1/pipelines/$pipeline/runs?wait=600" "$work/empty.json"
[ "$(answer .status)" = succeeded ] || fail "after the restore the pipeline's run ended $(answer .status): $(answer .error)"
ok

if $keep; then
  echo
  echo "REHEARSAL passed: Kilnmark $previous upgraded to $build with its state kept ($n steps). The install is left running (--keep)."
  exit 0
fi

step "Uninstall with --purge: nothing is left"
echo DELETE | "$ctl" uninstall --purge
[ ! -e "$prog" ] && [ ! -e "$data" ] || fail "the program or the data folder is still there"
left="$(sudo -iu "$account" podman ps -a --format '{{.Names}}' | grep "^$project" || true)"
[ -z "$left" ] || fail "containers are left: $left"
left="$(sudo -iu "$account" podman volume ls --format '{{.Name}}' | grep "^${project}_" || true)"
[ -z "$left" ] || fail "volumes are left: $left"
ok

echo
echo "REHEARSAL passed: Kilnmark $previous upgraded to $build with its state kept ($n steps)."
