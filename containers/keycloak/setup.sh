#!/bin/bash
# Sets up Kilnmark's realm in the bundled Keycloak (ADR 0014; INSTALL.md, "Keycloak, if you have no
# identity provider"). Run by the keycloak-setup service in compose.yaml, profile auth, after
# Keycloak starts; run it again with the control script (kilnmark.sh restart), which hands it its
# two secrets in a file for that run alone.
#
# The realm is called kilnmark. In it:
# - groups that give Kilnmark's roles: kilnmark-viewers, kilnmark-stewards, kilnmark-designers,
#   kilnmark-admins, and kilnmark-sensitive-data for designers who see sensitive values;
# - the client kilnmark, whose ID tokens carry the person's groups and how they signed in (amr);
# - a sign-in flow of a password and a one-time code, so every sign-in has two factors, as Kilnmark
#   requires. A person sets up the code (any authenticator app) on their first sign-in; Keycloak does
#   not count setting it up as using it, so Kilnmark then asks them to sign in once more, with it;
# - the Kilnmark sign-in page (themes/kilnmark).
# - the Starter Pack's five example people (ADR 0051), one per role, at kilnmark.com addresses that
#   receive no mail, in the group kilnmark-starter-pack: on a Starter Pack install only
#   (KILNMARK_MODE=starter-pack), made when the control script asks (kilnmark.sh starter people)
#   while KILNMARK_STARTER=allowed, and deleted at the next run once it is not. Never for real use.
# - people of the organisation's own, in both modes, when the control script's people add (install
#   asks for Kilnmark's first admin this way), people import or people activate asks; a temporary
#   password these set, marked kilnmark-temporary, left unused for KILNMARK_PEOPLE_ACTIVATION_HOURS
#   disables its person, at every run and at people expire, which the control script's timer runs
#   hourly; and, for people list, who is here. Never Keycloak's own admin (KILNMARK_KEYCLOAK_ADMIN),
#   who signs in to Keycloak's console, not to Kilnmark.
#
# Each part is checked on its own and made if it is missing, so a run that stopped half way is
# finished by the next. What is there is kept, except the client's address and secret, which follow
# the settings. Secrets never go on a command line, where other users of the machine could see them:
# kcadm reads the admin password from KC_CLI_PASSWORD, and the rest come in as JSON on its input.
set -euo pipefail
# The first line of every run: the control script reads what the latest run did from here on.
echo "keycloak-setup: started"

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KILNMARK_URL="${KILNMARK_URL:-http://localhost:8780}"
KILNMARK_URL="${KILNMARK_URL%/}"
: "${KC_ADMIN_USER:?Set KILNMARK_KEYCLOAK_ADMIN to the Keycloak admin user name}"
# The two secrets come from a file the control script writes for each run, holding only them,
# mounted read-only at /run/kilnmark/env, not from the environment: podman-compose puts each
# environment setting on podman's command line, which other users of the machine can read. (The
# environment still works, for a compose of your own.) Read as compose reads .env: an export in
# front, spaces around =, a value in double or single quotes, or bare up to a " #" comment.
setting_from_file() {
  local f=/run/kilnmark/env line v=""
  [ -f "$f" ] && [ -r "$f" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    line="${line#"${line%%[![:space:]]*}"}"
    if [[ "$line" =~ ^export[[:space:]]+(.*)$ ]]; then line="${BASH_REMATCH[1]}"; fi
    [[ "$line" =~ ^$1[[:space:]]*=(.*)$ ]] || continue
    v="${BASH_REMATCH[1]}"
    v="${v#"${v%%[![:space:]]*}"}"
    case "$v" in
      \"*) v="${v#\"}"; v="${v%%\"*}" ;;
      \'*) v="${v#\'}"; v="${v%%\'*}" ;;
      *) v="${v%%[[:space:]]#*}"; v="${v%"${v##*[![:space:]]}"}" ;;
    esac
  done < "$f"
  printf '%s' "$v"
}
[ -n "${KC_ADMIN_PASSWORD:-}" ] || KC_ADMIN_PASSWORD="$(setting_from_file KILNMARK_KEYCLOAK_ADMIN_PASSWORD)"
[ -n "${KILNMARK_OIDC_CLIENT_SECRET:-}" ] || KILNMARK_OIDC_CLIENT_SECRET="$(setting_from_file KILNMARK_OIDC_CLIENT_SECRET)"
: "${KC_ADMIN_PASSWORD:?Set KILNMARK_KEYCLOAK_ADMIN_PASSWORD (see .env.example)}"
: "${KILNMARK_OIDC_CLIENT_SECRET:?Set KILNMARK_OIDC_CLIENT_SECRET (see .env.example)}"
# How long a temporary password may wait unused before its person is disabled: 1 to 720 hours.
ACTIVATION_HOURS="${KILNMARK_PEOPLE_ACTIVATION_HOURS:-72}"
if ! [[ "$ACTIVATION_HOURS" =~ ^[0-9]{1,3}$ ]] || [ "$ACTIVATION_HOURS" -lt 1 ] || [ "$ACTIVATION_HOURS" -gt 720 ]; then
  echo "keycloak-setup: KILNMARK_PEOPLE_ACTIVATION_HOURS is a whole number of hours from 1 to 720. Nothing was changed" >&2
  exit 1
fi
# The Starter Pack's example people (below). KILNMARK_STARTER_PEOPLE_ACTION comes only from the
# control script's starter people command, for this one run, and their password on standard input.
# KILNMARK_MODE is production or starter-pack (ADR 0051), as the server reads it: anything but
# starter-pack, exactly, is production here, where the example people never are.
STARTER_MODE=false
if [ "${KILNMARK_MODE:-}" = starter-pack ]; then STARTER_MODE=true; fi
STARTER_ALLOWED=false
if [ "${KILNMARK_STARTER:-}" = allowed ] && [ "$STARTER_MODE" = true ]; then STARTER_ALLOWED=true; fi
PEOPLE_ACTION="${KILNMARK_STARTER_PEOPLE_ACTION:-}"
PEOPLE_PASSWORD=""
case "$PEOPLE_ACTION" in
  ""|add|new-password|remove) ;;
  *) echo "keycloak-setup: KILNMARK_STARTER_PEOPLE_ACTION is add, new-password or remove" >&2; exit 1 ;;
esac
# People of the organisation's own (ADR 0051, decision 10), in both modes, only when the control
# script's people command asks: KILNMARK_PEOPLE_ACTION add (one person: KILNMARK_PERSON_EMAIL,
# _FIRST_NAME, _LAST_NAME, _GROUPS, a ;-separated list), import (a CSV on standard input: on a
# Starter Pack install after the class's shared password; on production, nobody gets a password:
# each waits, disabled, for activate), activate (KILNMARK_PERSON_EMAIL), list, or expire (only the
# expiry of unused temporary passwords, below, which the control script's timer runs hourly). A temporary
# password comes on standard input, never the environment or a command line. Never in the Starter
# Pack's group, so its removal never touches them; never at kilnmark.com, the example people's.
PERSON_ACTION="${KILNMARK_PEOPLE_ACTION:-}"
case "$PERSON_ACTION" in
  ""|add|import|activate|list|expire) ;;
  *) echo "keycloak-setup: KILNMARK_PEOPLE_ACTION is add, import, activate, list or expire" >&2; exit 1 ;;
esac
if [ -n "$PERSON_ACTION" ] && [ -n "$PEOPLE_ACTION" ]; then
  echo "keycloak-setup: people of your own and the Starter Pack's example people are separate runs. Nothing was changed" >&2
  exit 1
fi
PERSON_PASSWORD=""
# Kilnmark's groups. An admin is added one at a time (add), never from a file (import); on
# production, so is someone who sees sensitive values. A Starter Pack install's training data is
# made up, so a class may be given kilnmark-sensitive-data from a file.
KNOWN_GROUPS="kilnmark-viewers kilnmark-stewards kilnmark-designers kilnmark-admins kilnmark-sensitive-data"
# One line on standard input, never the environment or a command line; from Windows it can start
# with a byte-order mark and ends in CR. $1 says whose it is, for the refusal.
read_password() {
  local p=""
  IFS= read -r p || true
  while [[ "$p" == $'\xef\xbb\xbf'* ]]; do p="${p#$'\xef\xbb\xbf'}"; done
  p="${p%$'\r'}"
  if [ "${#p}" -lt 12 ]; then
    echo "keycloak-setup: $1 password, on standard input, must be at least 12 characters (the realm's password policy). Nothing was changed" >&2
    exit 1
  fi
  if [[ "$p" == *[[:cntrl:]]* ]]; then
    echo "keycloak-setup: $1 password holds a control character (a tab, say), which it may not. Nothing was changed" >&2
    exit 1
  fi
  printf '%s' "$p"
}
trim() { local t="$1"; t="${t#"${t%%[![:space:]]*}"}"; printf '%s' "${t%"${t##*[![:space:]]}"}"; }
# Why an address (in lower case) is refused, or nothing. Never echoes it.
address_problem() {
  if ! [[ "$1" =~ ^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$ ]]; then echo "not an email address"
  elif [[ "$1" == *@kilnmark.com ]] || [[ "$1" == *.kilnmark.com ]]; then
    echo "a kilnmark.com address: those are the Starter Pack's example people; use an address of your own organisation"
  fi
}
# Keycloak's user profile refuses these in a person's name, so a file is checked for them first.
NAME_REFUSED='[][<>&"$%!#?;*~/\\|^={}()]'
name_problem() {
  if [ -z "$1" ] || [ "${#1}" -gt 100 ] || [[ "$1" == *[[:cntrl:]]* ]]; then
    echo "needs a first and a last name, up to 100 characters each"
  elif printf '%s' "$1" | grep "$NAME_REFUSED" > /dev/null; then
    echo "a name holds a character Keycloak refuses in names: ( ) / \\ ; & < > \" \$ % ! # ? * ~ | ^ = [ ] { }"
  fi
}
# Why a ;-separated group list is refused, or nothing; $2: true where an admin may be added (add);
# $3: true where someone who sees sensitive values may be (add, and a Starter Pack install's import).
groups_problem() {
  local g seen=" " any=false list
  IFS=';' read -r -a list <<< "$1"
  for g in "${list[@]}"; do
    g="$(trim "$g")"
    [ -n "$g" ] || continue
    any=true
    if [ "$g" = kilnmark-admins ] && [ "$2" != true ]; then
      echo "admins are added one at a time, with people add, never from a file"; return
    fi
    if [ "$g" = kilnmark-sensitive-data ] && [ "$3" != true ]; then
      echo "people who see sensitive values are added one at a time, with people add, never from a file on a production install"; return
    fi
    if [[ " $KNOWN_GROUPS " != *" $g "* ]]; then
      echo "a group that is not one of Kilnmark's ($KNOWN_GROUPS)"; return
    fi
    if [[ "$seen" == *" $g "* ]]; then echo "a group named twice"; return; fi
    seen="$seen$g "
  done
  $any || echo "no group: each person needs at least one of Kilnmark's groups"
}
# The groups, cleaned: trimmed, ;-separated.
clean_groups() {
  local g out="" list
  IFS=';' read -r -a list <<< "$1"
  for g in "${list[@]}"; do g="$(trim "$g")"; [ -z "$g" ] || out="${out:+$out;}$g"; done
  printf '%s' "$out"
}
PERSON_ROWS=()   # email, first, last, groups, joined by the unit separator
SEP=$'\x1f'
if [ "$PERSON_ACTION" = add ]; then
  email="$(trim "${KILNMARK_PERSON_EMAIL:-}")"; email="${email,,}"
  first="$(trim "${KILNMARK_PERSON_FIRST_NAME:-}")"; last="$(trim "${KILNMARK_PERSON_LAST_NAME:-}")"
  groups="${KILNMARK_PERSON_GROUPS:-}"; groups="${groups//,/;}"
  why="$(address_problem "$email")"
  [ -n "$why" ] || why="$(name_problem "$first")"
  [ -n "$why" ] || why="$(name_problem "$last")"
  [ -n "$why" ] || why="$(groups_problem "$groups" true true)"
  if [ -n "$why" ]; then
    echo "keycloak-setup: the person was not made: $why. Nothing was changed" >&2
    exit 1
  fi
  PERSON_PASSWORD="$(read_password "the person's temporary")"
  PERSON_ROWS+=("$email$SEP$first$SEP$last$SEP$(clean_groups "$groups")")
elif [ "$PERSON_ACTION" = activate ]; then
  email="$(trim "${KILNMARK_PERSON_EMAIL:-}")"; email="${email,,}"
  why="$(address_problem "$email")"
  if [ -n "$why" ]; then
    echo "keycloak-setup: nobody was activated: $why. Nothing was changed" >&2
    exit 1
  fi
  PERSON_PASSWORD="$(read_password "the person's temporary")"
elif [ "$PERSON_ACTION" = import ]; then
  # A class on a Starter Pack install shares one temporary password; on production nobody gets
  # one: each person is made disabled, and an operator activates them one by one.
  if [ "$STARTER_MODE" = true ]; then PERSON_PASSWORD="$(read_password "the class's temporary")"; fi
  # The whole file is checked before anyone is made: each bad line is named by its number, never
  # its values, and then nobody is made.
  bad=() n=0 header=false seen=" "
  while IFS= read -r line || [ -n "$line" ]; do
    n=$((n + 1))
    line="${line%$'\r'}"
    while [[ "$line" == $'\xef\xbb\xbf'* ]]; do line="${line#$'\xef\xbb\xbf'}"; done
    line="$(trim "$line")"
    [ -n "$line" ] || continue
    [[ "$line" != \#* ]] || continue
    if [ "$header" = false ]; then
      header=true
      [ "$line" = "email,first_name,last_name,groups" ] || bad+=("line $n: the first line must be the header email,first_name,last_name,groups")
      continue
    fi
    commas="${line//[^,]/}"
    if [ "${#commas}" -ne 3 ]; then bad+=("line $n: not four fields (email, first name, last name, groups), or a comma inside one"); continue; fi
    IFS=, read -r email first last groups <<< "$line"
    email="$(trim "$email")"; email="${email,,}"; first="$(trim "$first")"; last="$(trim "$last")"
    why="$(address_problem "$email")"
    [ -n "$why" ] || why="$(name_problem "$first")"
    [ -n "$why" ] || why="$(name_problem "$last")"
    [ -n "$why" ] || why="$(groups_problem "$groups" false "$STARTER_MODE")"
    if [ -z "$why" ] && [[ "$seen" == *" $email "* ]]; then why="the same address as an earlier line"; fi
    if [ -n "$why" ]; then bad+=("line $n: $why"); continue; fi
    seen="$seen$email "
    PERSON_ROWS+=("$email$SEP$first$SEP$last$SEP$(clean_groups "$groups")")
  done
  if [ "${#PERSON_ROWS[@]}" -gt 1000 ]; then bad+=("more than 1000 people: split the file"); fi
  if [ "${#bad[@]}" -gt 0 ]; then
    for b in "${bad[@]}"; do echo "keycloak-setup: $b" >&2; done
    echo "keycloak-setup: people import: made 0, already there 0, refused ${#bad[@]}. Nothing was changed: fix those lines, then import the file again" >&2
    exit 1
  fi
  if [ "${#PERSON_ROWS[@]}" -eq 0 ]; then
    echo "keycloak-setup: the file has no people after its header. Nothing was changed" >&2
    exit 1
  fi
fi
if [ "$PEOPLE_ACTION" = add ] || [ "$PEOPLE_ACTION" = new-password ]; then
  if [ "$STARTER_ALLOWED" != true ]; then
    echo "keycloak-setup: the example people were not made: they need a Starter Pack install (KILNMARK_MODE=starter-pack) with KILNMARK_STARTER=allowed. Nothing was changed" >&2
    exit 1
  fi
  PEOPLE_PASSWORD="$(read_password "the example people's")"
fi
REALM=kilnmark
FLOW="kilnmark browser"
FORMS="kilnmark sign-in"
ROLE_GROUPS="kilnmark-viewers kilnmark-stewards kilnmark-designers kilnmark-admins kilnmark-sensitive-data"
export KC_CLI_PASSWORD="$KC_ADMIN_PASSWORD"
unset KC_ADMIN_PASSWORD
# kcadm keeps a token for Keycloak's master realm in its config file: a new one in a private folder
# for each run, never ~/.keycloak, removed however the run ends, so an exited container never holds one.
KC_CONFIG_DIR="$(mktemp -d)"
trap 'rm -rf "$KC_CONFIG_DIR"' EXIT

kc() { /opt/keycloak/bin/kcadm.sh "$@" --config "$KC_CONFIG_DIR/kcadm.config"; }
say() { echo "keycloak-setup: $*"; }
# A text as a JSON string, for secrets sent on kcadm's input.
json() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; printf '"%s"' "$s"; }
path() { echo "${1// /%20}"; }

# Keycloak takes a while on its first start: keep trying to sign in for up to five minutes. Its
# first admin is made only on that first start: changing the password in .env later does not
# change it (change it in Keycloak's console instead).
for i in $(seq 1 150); do
  if kc config credentials --server "$KEYCLOAK_URL" --realm master --user "$KC_ADMIN_USER" > /dev/null 2>&1; then
    break
  fi
  if [ "$i" = 150 ]; then
    say "could not sign in to Keycloak at $KEYCLOAK_URL as $KC_ADMIN_USER: it did not start, or the admin password differs from the one it was first started with"
    exit 1
  fi
  sleep 2
done

# The id of a flow's step, by its provider or its name (kcadm prints id,provider,name per line; the
# image has no awk).
step_id() {
  local id provider name
  while IFS=, read -r id provider name; do
    if [ "$provider" = "$2" ] || [ "$name" = "$2" ]; then echo "$id"; return; fi
  done < <(kc get "authentication/flows/$(path "$1")/executions" -r "$REALM" \
    --fields id,providerId,displayName --format csv --noquotes)
}
# Sets a step's requirement (REQUIRED, ALTERNATIVE, ...).
require() {
  kc update "authentication/flows/$(path "$1")/executions" -r "$REALM" -b "{\"id\":\"$2\",\"requirement\":\"$3\"}"
}
# The first line of its input. It reads all of it: a reader that stopped early would end the
# writer with SIGPIPE, which pipefail turns into a failure of the whole pipeline.
one() { local first=""; IFS= read -r first || true; printf '%s\n' "$first"; cat > /dev/null; }

# Every line of a listing kcadm pages through ($1 the path; $2 its fields), 500 at a time: never only
# the first page, however many there are. Fails when a page fails, so nobody is missed unsaid.
# KILNMARK_SETUP_PAGE, a smaller page, is for the tests only (compose never passes it).
PAGE=500
if [[ "${KILNMARK_SETUP_PAGE:-}" =~ ^[1-9][0-9]{0,2}$ ]] && [ "$KILNMARK_SETUP_PAGE" -le 500 ]; then PAGE="$KILNMARK_SETUP_PAGE"; fi
all_of() {
  local first=0 page line n
  while :; do
    page="$(kc get "$1" -r "$REALM" -q first="$first" -q max="$PAGE" --fields "$2" --format csv --noquotes)" || return 1
    n=0
    while IFS= read -r line; do
      [ -n "$line" ] || continue
      printf '%s\n' "$line"; n=$((n + 1))
    done <<< "$page"
    [ "$n" -eq "$PAGE" ] || return 0
    first=$((first + PAGE))
  done
}
# The temporary password the people commands set (add, import, activate) is marked with this label,
# so only it expires: Keycloak drops the mark when its person chooses their own (a new password has
# no label), and a password an admin set in Keycloak's console never has it.
TEMPORARY_LABEL=kilnmark-temporary
# When person $1's (an id) temporary password, by its mark, was set, in milliseconds; false when
# they have anything else: no password, one of their own, or another credential (a one-time code) too.
temporary_set_at() {
  local type label created n=0 at="" list
  list="$(kc get "users/$1/credentials" -r "$REALM" --fields type,userLabel,createdDate --format csv --noquotes)" || return 1
  while IFS=, read -r type label created; do
    [ -n "$type" ] || continue
    n=$((n + 1))
    if [ "$type" = password ] && [ "$label" = "$TEMPORARY_LABEL" ]; then at="$created"; fi
  done <<< "$list"
  [ "$n" -eq 1 ] && [[ "$at" =~ ^[0-9]+$ ]] || return 1
  printf '%s' "$at"
}
# Unused temporary passwords expire, at every run (every start runs setup), at people expire (the
# control script's timer, hourly) and before people list: anyone enabled who still has to choose
# their own password more than ACTIVATION_HOURS after their marked temporary one was set is
# disabled, each said for Kilnmark's audit log. people activate gives them a new one. The operator's
# KILNMARK_ADMIN_TOKEN stays the way in should a first admin expire.
expire_unused() {
  local now_ms limit_ms users id username enabled rest set_at
  now_ms=$(( $(date +%s) * 1000 ))
  # a later "now", for the tests only (compose never passes it): an earlier one would expire nobody
  if [[ "${KILNMARK_SETUP_NOW_MS:-}" =~ ^[0-9]{13}$ ]] && [ "$KILNMARK_SETUP_NOW_MS" -gt "$now_ms" ]; then
    now_ms="$KILNMARK_SETUP_NOW_MS"
  fi
  limit_ms=$(( ACTIVATION_HOURS * 3600 * 1000 ))
  users="$(all_of users id,username,enabled,requiredActions)"
  while IFS=, read -r id username enabled rest; do
    [ -n "$id" ] && [ "$enabled" = true ] || continue
    [[ ",$rest," == *",UPDATE_PASSWORD,"* ]] || continue
    set_at="$(temporary_set_at "$id")" || continue
    if [ $(( now_ms - set_at )) -gt "$limit_ms" ]; then
      kc update "users/$id" -r "$REALM" -s enabled=false
      say "disabled $username: their temporary password was not used within $ACTIVATION_HOURS hours (people activate gives them a new one)"
    fi
  done <<< "$users"
}

# people expire: only the expiry, on a realm setup made before; nothing else is changed (so the
# hourly run adds nothing to Keycloak's admin events but what it disables).
if [ "$PERSON_ACTION" = expire ]; then
  if ! kc get "realms/$REALM" > /dev/null 2>&1; then
    say "the realm kilnmark is not made yet: nothing to expire"
    exit 0
  fi
  expire_unused
  say "people expire: done"
  exit 0
fi

# Brute-force protection on Keycloak's own admin realm too: its admin signs in on the same pages.
kc update realms/master -s bruteForceProtected=true -s failureFactor=10

# The realm, in one call.
if ! kc get "realms/$REALM" > /dev/null 2>&1; then
  say "creating the realm $REALM"
  kc create realms -s realm="$REALM" -s enabled=true \
    -s displayName=Kilnmark -s displayNameHtml=Kilnmark \
    -s loginTheme=kilnmark -s accountTheme=keycloak.v3 \
    -s sslRequired=external \
    -s registrationAllowed=false -s resetPasswordAllowed=false -s rememberMe=false \
    -s loginWithEmailAllowed=true -s duplicateEmailsAllowed=false \
    -s bruteForceProtected=true -s failureFactor=10 \
    -s 'passwordPolicy=length(12) and notUsername and notEmail' \
    -s ssoSessionIdleTimeout=7200 -s ssoSessionMaxLifespan=28800
fi

# A top-level group's id by its path, or nothing: never by search, which also answers with the parent
# of any subgroup that has the name.
group_id() { kc get "group-by-path/$1" -r "$REALM" --fields id --format csv --noquotes 2> /dev/null | one || true; }

# The groups.
for g in $ROLE_GROUPS; do
  if [ -z "$(group_id "$g")" ]; then
    kc create groups -r "$REALM" -s name="$g" > /dev/null
    say "added the group $g"
  fi
done

# Sign-in: a session cookie, or a password and then a one-time code. Each step names the factor it
# is (RFC 8176) for as long as Kilnmark's longest session (8 hours), so the ID token's amr says pwd
# and otp; with no age set, Keycloak leaves the methods out of amr altogether. A flow left half made
# by an earlier run (not yet in use) is made again.
flow_complete() {
  local provider id
  for provider in auth-username-password-form auth-otp-form; do
    id="$(step_id "$FORMS" "$provider" 2> /dev/null || true)"
    [ -n "$id" ] || return 1
    kc get "authentication/flows/$(path "$FORMS")/executions" -r "$REALM" \
      --fields id,authenticationConfig --format csv --noquotes | grep "^$id,." > /dev/null || return 1
  done
}
browser_flow="$(kc get "realms/$REALM" --fields browserFlow --format csv --noquotes)"
flow_id="$(kc get authentication/flows -r "$REALM" --fields id,alias --format csv --noquotes | { grep ",$FLOW\$" || true; } | cut -d, -f1 | one)"
if [ -n "$flow_id" ] && [ "$browser_flow" != "$FLOW" ] && ! flow_complete; then
  say "the sign-in flow was left half made: making it again"
  kc delete "authentication/flows/$flow_id" -r "$REALM"
  flow_id=""
fi
if [ -z "$flow_id" ]; then
  say "creating the sign-in flow (a password and a one-time code)"
  kc create authentication/flows -r "$REALM" -s alias="$FLOW" -s providerId=basic-flow \
    -s topLevel=true -s builtIn=false -s description="Kilnmark: a password and a one-time code" > /dev/null
  kc create "authentication/flows/$(path "$FLOW")/executions/execution" -r "$REALM" -s provider=auth-cookie > /dev/null
  require "$FLOW" "$(step_id "$FLOW" auth-cookie)" ALTERNATIVE
  kc create "authentication/flows/$(path "$FLOW")/executions/flow" -r "$REALM" \
    -s alias="$FORMS" -s type=basic-flow -s provider=registration-page-form > /dev/null
  require "$FLOW" "$(step_id "$FLOW" "$FORMS")" ALTERNATIVE
  for step in auth-username-password-form:pwd auth-otp-form:otp; do
    provider="${step%%:*}"; method="${step##*:}"
    kc create "authentication/flows/$(path "$FORMS")/executions/execution" -r "$REALM" -s provider="$provider" > /dev/null
    id="$(step_id "$FORMS" "$provider")"
    require "$FORMS" "$id" REQUIRED
    kc create "authentication/executions/$id/config" -r "$REALM" \
      -s alias="kilnmark-$method" -s "config.\"default.reference.value\"=$method" \
      -s "config.\"default.reference.maxAge\"=28800" > /dev/null
  done
fi
# In use unless an administrator chose another flow for the realm since.
if [ "$browser_flow" = "browser" ] || [ -z "$browser_flow" ]; then
  kc update "realms/$REALM" -s browserFlow="$FLOW"
fi

# The Kilnmark client, its two claims, and its address and secret, which follow the settings.
client="$(kc get clients -r "$REALM" -q clientId=kilnmark --fields id --format csv --noquotes | one)"
if [ -z "$client" ]; then
  say "creating the client kilnmark"
  kc create clients -r "$REALM" -s clientId=kilnmark -s name=Kilnmark -s protocol=openid-connect \
    -s publicClient=false -s clientAuthenticatorType=client-secret \
    -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s implicitFlowEnabled=false \
    -s serviceAccountsEnabled=false -s frontchannelLogout=false > /dev/null
  client="$(kc get clients -r "$REALM" -q clientId=kilnmark --fields id --format csv --noquotes | one)"
fi
mappers="$(kc get "clients/$client/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes)"
if ! grep -qx groups <<< "$mappers"; then
  kc create "clients/$client/protocol-mappers/models" -r "$REALM" -s name=groups -s protocol=openid-connect \
    -s protocolMapper=oidc-group-membership-mapper \
    -s 'config."claim.name"=groups' -s 'config."full.path"=false' \
    -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' -s 'config."userinfo.token.claim"=true' > /dev/null
fi
if ! grep -qx amr <<< "$mappers"; then
  kc create "clients/$client/protocol-mappers/models" -r "$REALM" -s name=amr -s protocol=openid-connect \
    -s protocolMapper=oidc-amr-mapper \
    -s 'config."id.token.claim"=true' -s 'config."access.token.claim"=true' > /dev/null
fi
printf '{"redirectUris":[%s],"webOrigins":[%s],"attributes":{"post.logout.redirect.uris":%s},"secret":%s}' \
  "$(json "$KILNMARK_URL/api/v1/auth/login")" "$(json "$KILNMARK_URL")" "$(json "$KILNMARK_URL/")" \
  "$(json "$KILNMARK_OIDC_CLIENT_SECRET")" | kc update "clients/$client" -r "$REALM" -f -
say "the client kilnmark signs in to $KILNMARK_URL"

# The Starter Pack's example people (ADR 0051), on a Starter Pack install only. The group
# kilnmark-starter-pack is their mark: only its members are ever changed or deleted here, and only
# admins can put people in a group. The group itself carries the attribute kilnmark_starter_pack,
# which only setup sets: a group of that name it did not make (one a directory federation brings,
# say) is never taken for it. Setup puts in it the people it makes, and only them.
STARTER_GROUP=kilnmark-starter-pack
GROUP_MARK=kilnmark_starter_pack
PEOPLE="vera.lane@kilnmark.com:Vera:Lane:kilnmark-viewers
sam.okafor@kilnmark.com:Sam:Okafor:kilnmark-stewards
dana.reyes@kilnmark.com:Dana:Reyes:kilnmark-designers
priya.shah@kilnmark.com:Priya:Shah:kilnmark-designers,kilnmark-sensitive-data
alex.morgan@kilnmark.com:Alex:Morgan:kilnmark-admins"

user_id() { kc get users -r "$REALM" -q username="$1" -q exact=true --fields id --format csv --noquotes | one; }
# Whether person $1 is in group $2 (ids). grep reads all of kcadm's output (no -q), so kcadm never
# ends with SIGPIPE, which pipefail would turn into "not a member".
in_group() { kc get "users/$1/groups" -r "$REALM" --fields id --format csv --noquotes | grep -x "$2" > /dev/null; }
# Whether person $1 (an id) is someone else's: from a directory (a federation link) or signing in
# through another identity provider (a federated identity). The people commands never change them.
federated() {
  [ -n "$(kc get "users/$1" -r "$REALM" --fields federationLink --format csv --noquotes | tr -d ' \r\n')" ] && return 0
  [ -n "$(kc get "users/$1/federated-identity" -r "$REALM" --fields identityProvider --format csv --noquotes | tr -d ' \r\n')" ]
}
set_password() {
  printf '{"type":"password","temporary":false,"value":%s}' "$(json "$PEOPLE_PASSWORD")" \
    | kc update "users/$1/reset-password" -r "$REALM" -f -
}
# Whether group $1 (an id) carries the mark only setup sets (the whole group: kcadm's --fields
# drops attributes).
group_marked() {
  local a; a="$(kc get "groups/$1" -r "$REALM" 2> /dev/null | tr -d ' \n\r\t')"
  [[ "$a" == *"\"$GROUP_MARK\":[\"true\"]"* ]]
}
# The pack's group: its id when setup made it, nothing when there is none. A group of that name
# without the mark is never taken for it: setup stops, changing nothing.
starter_group() {
  local gid; gid="$(group_id "$STARTER_GROUP")"
  [ -n "$gid" ] || return 0
  if ! group_marked "$gid"; then
    echo "keycloak-setup: a group $STARTER_GROUP is here that keycloak-setup did not make (from a directory, say): its members are never touched. Rename it, then run up again. Nothing was changed" >&2
    exit 1
  fi
  echo "$gid"
}
# The pack's group, made with its mark when there is none.
make_starter_group() {
  local gid; gid="$(starter_group)"
  if [ -z "$gid" ]; then
    printf '{"name":%s,"attributes":{"%s":["true"]}}' "$(json "$STARTER_GROUP")" "$GROUP_MARK" \
      | kc create groups -r "$REALM" -f - > /dev/null
    gid="$(starter_group)"
  fi
  echo "$gid"
}
# The people the pack made are the members of its group at a kilnmark.com address, Keycloak's own
# (no directory's), whatever their user name is now (a realm can let people change it): each is
# deleted. Anyone else in the group (put there by hand, or by a default group) is only taken out of
# it, and said so; then the group goes. Nobody outside it is touched.
remove_people() {
  local gid members id username email link
  gid="$(starter_group)"
  [ -n "$gid" ] || return 0
  members="$(all_of "groups/$gid/members" id,username,email,federationLink)"
  while IFS=, read -r id username email link; do
    [ -n "$id" ] || continue
    if [[ "${email,,}" == *@kilnmark.com ]] && [ -z "$link" ] && ! federated "$id"; then
      kc delete "users/$id" -r "$REALM"
      say "removed $username (a Starter Pack example person)"
    else
      kc delete "users/$id/groups/$gid" -r "$REALM"
      say "took $username out of the group $STARTER_GROUP and left them in Keycloak: not one of the example people (an address outside kilnmark.com, or from a directory or another identity provider)"
    fi
  done <<< "$members"
  kc delete "groups/$gid" -r "$REALM"
}

# Keycloak records every change to people as an admin event (Realm settings, Events, Admin events),
# in both modes; whether it keeps their details (representations) stays the site's setting.
kc update "realms/$REALM" -s adminEventsEnabled=true
# The install this realm belongs to, and its mode (ADR 0051), as Kilnmark recorded them: the control
# script's people commands hand them. Kept on the realm, so a restore refuses a backup's Keycloak
# database that belongs to another install.
if [[ "${KILNMARK_INSTALL_ID:-}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  kc update "realms/$REALM" -s "attributes.kilnmark_install_id=$KILNMARK_INSTALL_ID" \
    -s "attributes.kilnmark_mode=$( [ "$STARTER_MODE" = true ] && echo starter-pack || echo production )"
fi
if [ "$STARTER_MODE" != true ]; then
  :
elif [ "$PEOPLE_ACTION" = remove ] || [ "$STARTER_ALLOWED" = false ]; then
  # Asked to, or no longer allowed (KILNMARK_STARTER unset).
  remove_people
elif [ "$PEOPLE_ACTION" = add ] || [ "$PEOPLE_ACTION" = new-password ]; then
  gid="$(make_starter_group)"
  while IFS=: read -r email first last groups; do
    uid="$(user_id "$email")"
    if [ -z "$uid" ]; then
      # Made already in the group and their role's groups, in one step: no run that stops half way
      # leaves a person the pack made outside its mark.
      paths="$(json "/$STARTER_GROUP")"
      for g in ${groups//,/ }; do paths="$paths,$(json "/$g")"; done
      printf '{"username":%s,"email":%s,"emailVerified":true,"firstName":%s,"lastName":%s,"enabled":true,"groups":[%s]}' \
        "$(json "$email")" "$(json "$email")" "$(json "$first")" "$(json "$last")" "$paths" | kc create users -r "$REALM" -f - > /dev/null
      uid="$(user_id "$email")"
      if ! in_group "$uid" "$gid"; then
        kc delete "users/$uid" -r "$REALM"
        echo "keycloak-setup: $email could not be put in the group $STARTER_GROUP, so was removed again; nothing more was done" >&2
        exit 1
      fi
      set_password "$uid"
      say "added $email ($groups)"
    elif ! in_group "$uid" "$gid"; then
      say "left $email as they are: someone not made by the Starter Pack has this address"
    elif [ "$PEOPLE_ACTION" = new-password ]; then
      set_password "$uid"
      say "gave $email the new password"
    elif ! kc get "users/$uid/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -x password > /dev/null; then
      set_password "$uid"   # made by a run that stopped before their password
      say "gave $email a password: an earlier run left them without one"
    else
      say "$email is there already: their password is unchanged"
    fi
  done <<< "$PEOPLE"
fi
unset PEOPLE_PASSWORD

# A person's id by user name, else by email: anyone with that address counts as already there.
person_id() {
  local id; id="$(user_id "$1")"
  [ -n "$id" ] || id="$(kc get users -r "$REALM" -q email="$1" -q exact=true --fields id --format csv --noquotes | one)"
  printf '%s' "$id"
}
# Whether person $1 (an id) has a password, and whether Keycloak still asks them to choose their
# own (a temporary one not yet used).
has_password() { kc get "users/$1/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -x password > /dev/null; }
must_choose() { kc get "users/$1" -r "$REALM" --fields requiredActions --format csv --noquotes | grep UPDATE_PASSWORD > /dev/null; }
no_credentials() { [ -z "$(kc get "users/$1/credentials" -r "$REALM" --fields type --format csv --noquotes | tr -d ' \r\n')" ]; }
temporary_password() { printf '{"type":"password","temporary":true,"value":%s}' "$(json "$PERSON_PASSWORD")"; }
# Marks person $1's (an id) password as the temporary one the people commands set (TEMPORARY_LABEL),
# and checks it took; false when it did not.
mark_temporary() {
  local cid
  cid="$(kc get "users/$1/credentials" -r "$REALM" --fields id,type --format csv --noquotes | { grep ',password$' || true; } | cut -d, -f1 | one)"
  [ -n "$cid" ] || return 1
  kc update "users/$1/credentials/$cid/userLabel" -r "$REALM" -h Content-Type=text/plain -b "$TEMPORARY_LABEL" > /dev/null 2>&1 || return 1
  temporary_set_at "$1" > /dev/null
}
# Takes person $1's (an id) passwords away again, when their mark did not take.
drop_passwords() {
  local cid
  while IFS=, read -r cid _; do
    [ -z "$cid" ] || kc delete "users/$1/credentials/$cid" -r "$REALM" || true
  done < <(kc get "users/$1/credentials" -r "$REALM" --fields id,type --format csv --noquotes | { grep ',password$' || true; })
}

# People of the organisation's own, asked for by the control script's people command (add or
# import): in their groups, never in the Starter Pack's. people add gives a temporary password, so
# Keycloak has them choose their own at the first sign-in (and set up the one-time code, as
# everyone does); so does a Starter Pack install's import, the class's shared one. A production
# import gives nobody a password: each is made disabled, waiting for people activate. Made once:
# anyone with that user name or email already is left exactly as they are, their password and
# groups too. Each line says what was done, for Kilnmark's audit log; a run that stops part way says
# how many it made first.
if [ "$PERSON_ACTION" = add ] || [ "$PERSON_ACTION" = import ]; then
  made=0 kept=0
  waiting=false
  if [ "$PERSON_ACTION" = import ] && [ "$STARTER_MODE" != true ]; then waiting=true; fi
  for row in "${PERSON_ROWS[@]}"; do
    IFS="$SEP" read -r email first last groups <<< "$row"
    if [ -n "$(person_id "$email")" ]; then
      say "left $email as they are: someone with this address is here already (their password and groups are unchanged)"
      kept=$((kept + 1))
      continue
    fi
    paths=""
    IFS=';' read -r -a list <<< "$groups"
    for g in "${list[@]}"; do paths="${paths:+$paths,}$(json "/$g")"; done
    if $waiting; then
      body="$(printf '{"username":%s,"email":%s,"emailVerified":true,"firstName":%s,"lastName":%s,"enabled":false,"groups":[%s]}' \
        "$(json "$email")" "$(json "$email")" "$(json "$first")" "$(json "$last")" "$paths")"
    else
      body="$(printf '{"username":%s,"email":%s,"emailVerified":true,"firstName":%s,"lastName":%s,"enabled":true,"groups":[%s],"credentials":[%s]}' \
        "$(json "$email")" "$(json "$email")" "$(json "$first")" "$(json "$last")" "$paths" "$(temporary_password)")"
    fi
    uid=""
    if printf '%s' "$body" | kc create users -r "$REALM" -f - > /dev/null; then uid="$(user_id "$email")"; fi
    ok=true
    [ -n "$uid" ] || ok=false
    if $ok; then
      for g in "${list[@]}"; do in_group "$uid" "$(group_id "$g")" || ok=false; done
    fi
    # Never in a group of the Starter Pack's name (a default group of the realm, say): the pack's
    # removal deletes members there, and production refuses their sign-in.
    pack="$(group_id "$STARTER_GROUP")"
    if $ok && [ -n "$pack" ] && in_group "$uid" "$pack"; then
      kc delete "users/$uid" -r "$REALM"
      echo "keycloak-setup: $email was put in the group $STARTER_GROUP as they were made (it is one of the realm's default groups, say), so was removed again: take it out of the default groups. $made made before it stay; nothing more was done" >&2
      [ "$PERSON_ACTION" != import ] || echo "keycloak-setup: people import: made $made, already there $kept, refused 0, stopped part way" >&2
      exit 1
    fi
    if $ok && ! $waiting && ! mark_temporary "$uid"; then ok=false; fi
    if ! $ok; then
      [ -z "$uid" ] || kc delete "users/$uid" -r "$REALM"
      echo "keycloak-setup: $email could not be made (Keycloak refused them, or their groups): it stopped there. $made made before it stay; nothing more was done" >&2
      [ "$PERSON_ACTION" != import ] || echo "keycloak-setup: people import: made $made, already there $kept, refused 0, stopped part way" >&2
      exit 1
    fi
    if $waiting; then
      say "made $email ($groups): waiting to be activated, with people activate"
    else
      say "made $email ($groups): they choose their own password at their first sign-in"
    fi
    made=$((made + 1))
  done
  if [ "$PERSON_ACTION" = import ]; then say "people import: made $made, already there $kept, refused 0"; fi
fi

# people activate: one person waiting to be activated (imported on production, or disabled because
# their temporary password went unused), enabled with a temporary password of their own. Never
# someone already active, nor anyone who has chosen their own password (they have signed in).
if [ "$PERSON_ACTION" = activate ]; then
  uid="$(person_id "$email")"
  if [ -z "$uid" ]; then
    echo "keycloak-setup: nobody here has that address. Nothing was changed" >&2
    exit 1
  fi
  if kc get "users/$uid" -r "$REALM" --fields enabled --format csv --noquotes | grep -x true > /dev/null; then
    echo "keycloak-setup: $email is active already: nothing was changed (reset a password in Keycloak's console)" >&2
    exit 1
  fi
  # Only someone waiting for a first password: none at all (imported on production), or only the
  # marked temporary one, unused. Never anyone with a password of their own or a one-time code (they
  # have signed in, and were disabled on purpose), nor anyone from a directory or another identity
  # provider.
  if federated "$uid"; then
    echo "keycloak-setup: $email comes from a directory or another identity provider: the people commands never change them. Nothing was changed" >&2
    exit 1
  fi
  if ! no_credentials "$uid" && { ! temporary_set_at "$uid" > /dev/null || ! must_choose "$uid"; }; then
    echo "keycloak-setup: $email has signed in (a password of their own, or a one-time code): they were disabled on purpose, so they are left as they are (enable them in Keycloak's console)" >&2
    exit 1
  fi
  if [ -n "$(kc get "users/$uid/groups" -r "$REALM" --fields name --format csv --noquotes | grep -x "$STARTER_GROUP" || true)" ]; then
    echo "keycloak-setup: that is one of the Starter Pack's example people: use starter people. Nothing was changed" >&2
    exit 1
  fi
  temporary_password | kc update "users/$uid/reset-password" -r "$REALM" -f -
  if ! mark_temporary "$uid"; then
    drop_passwords "$uid"
    echo "keycloak-setup: $email's temporary password could not be marked as one, so it was taken away again and they stay disabled. Nothing more was done" >&2
    exit 1
  fi
  kc update "users/$uid" -r "$REALM" -s enabled=true
  say "activated $email: they choose their own password at their first sign-in"
fi
unset PERSON_PASSWORD

# Unused temporary passwords expire (expire_unused, above).
expire_unused

# The realm's people, with their Kilnmark groups, whether they are active, waiting to be activated
# (imported, or their temporary password expired) or disabled, and whether they must still choose
# their own password. No password is shown.
if [ "$PERSON_ACTION" = list ]; then
  count=0
  users="$(all_of users id,username,enabled,requiredActions)"
  while IFS=, read -r id username enabled rest; do
    [ -n "$id" ] || continue
    names=""
    while IFS= read -r g; do
      if [[ " $KNOWN_GROUPS $STARTER_GROUP " == *" $g "* ]]; then names="${names:+$names;}$g"; fi
    done < <(kc get "users/$id/groups" -r "$REALM" --fields name --format csv --noquotes)
    change=no
    [[ ",$rest," != *",UPDATE_PASSWORD,"* ]] || change=yes
    if [ "$enabled" = true ]; then status=active
    elif ! has_password "$id" || [ "$change" = yes ]; then status=waiting-to-be-activated
    else status=disabled
    fi
    say "person $username groups=${names:-none} status=$status must-choose-password=$change"
    count=$((count + 1))
  done <<< "$users"
  say "people: $count"
fi

say "done: open $KILNMARK_URL and choose Sign in with your provider"
