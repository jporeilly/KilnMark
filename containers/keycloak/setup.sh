#!/bin/bash
# Sets up Kilnmark's realm in the bundled Keycloak (ADR 0014; INSTALL.md, "Keycloak, if you have no
# identity provider"). Run by the keycloak-setup service in compose.yaml, profile auth, after
# Keycloak starts; run it again with: podman compose --profile auth run --rm keycloak-setup
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
#   receive no mail: made only when the control script asks (kilnmark.sh starter people), while
#   KILNMARK_STARTER=allowed and the install is not marked production, and deleted at the next run
#   once either stops being so. Never for real use.
#
# Each part is checked on its own and made if it is missing, so a run that stopped half way is
# finished by the next. What is there is kept, except the client's address and secret, which follow
# the settings. Secrets never go on a command line, where other users of the machine could see them:
# kcadm reads the admin password from KC_CLI_PASSWORD, and the rest come in as JSON on its input.
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://keycloak:8080}"
KILNMARK_URL="${KILNMARK_URL:-http://localhost:8780}"
KILNMARK_URL="${KILNMARK_URL%/}"
: "${KC_ADMIN_USER:?Set KILNMARK_KEYCLOAK_ADMIN to the Keycloak admin user name}"
: "${KC_ADMIN_PASSWORD:?Set KILNMARK_KEYCLOAK_ADMIN_PASSWORD (see .env.example)}"
: "${KILNMARK_OIDC_CLIENT_SECRET:?Set KILNMARK_OIDC_CLIENT_SECRET (see .env.example)}"
# The Starter Pack's example people (below). KILNMARK_STARTER_PEOPLE_ACTION and the password come
# only from the control script's starter people command, in this one run's environment.
STARTER_ALLOWED=false
if [ "${KILNMARK_STARTER:-}" = allowed ] && [ "${KILNMARK_PRODUCTION:-false}" != true ]; then STARTER_ALLOWED=true; fi
PEOPLE_ACTION="${KILNMARK_STARTER_PEOPLE_ACTION:-}"
PEOPLE_PASSWORD="${KILNMARK_STARTER_PEOPLE_PASSWORD:-}"
case "$PEOPLE_ACTION" in
  ""|add|new-password|remove) ;;
  *) echo "keycloak-setup: KILNMARK_STARTER_PEOPLE_ACTION is add, new-password or remove" >&2; exit 1 ;;
esac
if [ "$PEOPLE_ACTION" = add ] || [ "$PEOPLE_ACTION" = new-password ]; then
  if [ "${#PEOPLE_PASSWORD}" -lt 12 ]; then
    echo "keycloak-setup: the example people's password must be at least 12 characters (the realm's password policy)" >&2
    exit 1
  fi
fi
REALM=kilnmark
FLOW="kilnmark browser"
FORMS="kilnmark sign-in"
ROLE_GROUPS="kilnmark-viewers kilnmark-stewards kilnmark-designers kilnmark-admins kilnmark-sensitive-data"
export KC_CLI_PASSWORD="$KC_ADMIN_PASSWORD"
unset KC_ADMIN_PASSWORD KILNMARK_STARTER_PEOPLE_PASSWORD

kc() { /opt/keycloak/bin/kcadm.sh "$@"; }
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
one() { head -n 1 || true; }

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

# The groups.
for g in $ROLE_GROUPS; do
  if [ -z "$(kc get groups -r "$REALM" -q search="$g" -q exact=true --fields id --format csv --noquotes | one)" ]; then
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
      --fields id,authenticationConfig --format csv --noquotes | grep -q "^$id,." || return 1
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

# A client kilnmark-starter, the service account an earlier draft of the Starter Pack used, is
# removed: Kilnmark holds no Keycloak credential (ADR 0051).
starter="$(kc get clients -r "$REALM" -q clientId=kilnmark-starter --fields id --format csv --noquotes | one)"
if [ -n "$starter" ]; then
  kc delete "clients/$starter" -r "$REALM"
  say "removed the client kilnmark-starter: Kilnmark holds no Keycloak credential"
fi

# The Starter Pack's example people (ADR 0051). Each carries the attribute kilnmark_starter_pack, and
# only people carrying it are ever changed or deleted here: someone else with one of these addresses
# is left as they are. While they may be made, the realm lets only admins see and set attributes its
# user profile does not declare (ADMIN_EDIT), so no one can put the mark on themselves, and Keycloak
# records every change to people as an admin event (Realm settings, Events, Admin events).
MARK=kilnmark_starter_pack
PEOPLE="vera.lane@kilnmark.com:Vera:Lane:kilnmark-viewers
sam.okafor@kilnmark.com:Sam:Okafor:kilnmark-stewards
dana.reyes@kilnmark.com:Dana:Reyes:kilnmark-designers
priya.shah@kilnmark.com:Priya:Shah:kilnmark-designers,kilnmark-sensitive-data
alex.morgan@kilnmark.com:Alex:Morgan:kilnmark-admins"

user_id() { kc get users -r "$REALM" -q username="$1" -q exact=true --fields id --format csv --noquotes | one; }
# Whether a person carries the mark, with the value true (the whole person: kcadm's --fields drops attributes).
marked() {
  local a; a="$(kc get "users/$1" -r "$REALM" 2> /dev/null | tr -d ' \n\r\t')"
  [[ "$a" == *"\"$MARK\":[\"true\"]"* ]]
}
set_password() {
  printf '{"type":"password","temporary":false,"value":%s}' "$(json "$PEOPLE_PASSWORD")" \
    | kc update "users/$1/reset-password" -r "$REALM" -f -
}
# The realm's unmanaged attributes: admins only, whatever they were.
admin_edit_only() {
  local profile before
  profile="$(kc get users/profile -r "$REALM")"
  before="$profile"
  if [[ "$profile" =~ (\"unmanagedAttributePolicy\"[[:space:]]*:[[:space:]]*\")([A-Z_]*)(\") ]]; then
    [ "${BASH_REMATCH[2]}" = ADMIN_EDIT ] || profile="${profile/"${BASH_REMATCH[0]}"/"${BASH_REMATCH[1]}ADMIN_EDIT${BASH_REMATCH[3]}"}"
  else
    profile="{\"unmanagedAttributePolicy\":\"ADMIN_EDIT\",${profile#*\{}"
  fi
  if [ "$profile" != "$before" ]; then
    printf '%s' "$profile" | kc update users/profile -r "$REALM" -f -
    say "only admins may now see and set attributes the user profile does not declare (ADMIN_EDIT)"
  fi
}
remove_people() {
  local email first last groups uid
  while IFS=: read -r email first last groups; do
    uid="$(user_id "$email")"
    [ -n "$uid" ] || continue
    if marked "$uid"; then
      kc delete "users/$uid" -r "$REALM"
      say "removed $email (a Starter Pack example person)"
    else
      say "left $email as they are: not made by the Starter Pack"
    fi
  done <<< "$PEOPLE"
}

if [ "$STARTER_ALLOWED" = true ]; then
  admin_edit_only
  kc update "realms/$REALM" -s adminEventsEnabled=true -s adminEventsDetailsEnabled=false
fi
if [ "$PEOPLE_ACTION" = remove ] || [ "$STARTER_ALLOWED" = false ]; then
  # Asked to, or no longer allowed (KILNMARK_STARTER unset, or the install marked production).
  remove_people
  if [ "$PEOPLE_ACTION" = add ] || [ "$PEOPLE_ACTION" = new-password ]; then
    echo "keycloak-setup: the example people were not made: they need KILNMARK_STARTER=allowed, on an install not marked production (KILNMARK_PRODUCTION)" >&2
    exit 1
  fi
elif [ "$PEOPLE_ACTION" = add ] || [ "$PEOPLE_ACTION" = new-password ]; then
  while IFS=: read -r email first last groups; do
    uid="$(user_id "$email")"
    if [ -z "$uid" ]; then
      printf '{"username":%s,"email":%s,"emailVerified":true,"firstName":%s,"lastName":%s,"enabled":true,"attributes":{"%s":["true"]}}' \
        "$(json "$email")" "$(json "$email")" "$(json "$first")" "$(json "$last")" "$MARK" | kc create users -r "$REALM" -f - > /dev/null
      uid="$(user_id "$email")"
      marked "$uid" || { echo "keycloak-setup: $email was made without the Starter Pack's mark; nothing more was done" >&2; exit 1; }
      set_password "$uid"
      say "added $email ($groups)"
    elif ! marked "$uid"; then
      say "left $email as they are: someone not made by the Starter Pack has this address"
      continue
    elif [ "$PEOPLE_ACTION" = new-password ]; then
      set_password "$uid"
      say "gave $email the new password"
    elif ! kc get "users/$uid/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -qx password; then
      set_password "$uid"   # made by a run that stopped half way
      say "gave $email a password: an earlier run left them without one"
    else
      say "$email is there already: their password is unchanged"
    fi
    for g in ${groups//,/ }; do
      gid="$(kc get groups -r "$REALM" -q search="$g" -q exact=true --fields id --format csv --noquotes | one)"
      kc update "users/$uid/groups/$gid" -r "$REALM" -n > /dev/null
    done
  done <<< "$PEOPLE"
fi
unset PEOPLE_PASSWORD

say "done: open $KILNMARK_URL and choose Sign in with your provider"
