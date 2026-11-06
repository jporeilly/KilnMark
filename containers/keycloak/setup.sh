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
# With KILNMARK_KEYCLOAK_DEMO_PASSWORD set, also five example people, one per role, at
# kilnmark.com addresses that receive no mail. Never set it for real use.
# With KILNMARK_STARTER=allowed and KILNMARK_KEYCLOAK_STARTER_SECRET set, the Starter Pack's service
# account (the client kilnmark-starter), which manages people and groups only; removed otherwise.
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
DEMO_PASSWORD="${KILNMARK_KEYCLOAK_DEMO_PASSWORD:-}"
if [ -n "$DEMO_PASSWORD" ] && [ "${#DEMO_PASSWORD}" -lt 12 ]; then
  echo "keycloak-setup: KILNMARK_KEYCLOAK_DEMO_PASSWORD must be at least 12 characters (the realm's password policy)" >&2
  exit 1
fi
REALM=kilnmark
FLOW="kilnmark browser"
FORMS="kilnmark sign-in"
ROLE_GROUPS="kilnmark-viewers kilnmark-stewards kilnmark-designers kilnmark-admins kilnmark-sensitive-data"
# The Starter Pack's service account (below): made only while both are set.
STARTER_ALLOWED="${KILNMARK_STARTER:-}"
STARTER_SECRET="${KILNMARK_KEYCLOAK_STARTER_SECRET:-}"
if [ "$STARTER_ALLOWED" = allowed ] && [ -n "$STARTER_SECRET" ] && [ "${#STARTER_SECRET}" -lt 32 ]; then
  echo "keycloak-setup: KILNMARK_KEYCLOAK_STARTER_SECRET must be at least 32 characters" >&2
  exit 1
fi
export KC_CLI_PASSWORD="$KC_ADMIN_PASSWORD"
unset KC_ADMIN_PASSWORD KILNMARK_KEYCLOAK_DEMO_PASSWORD KILNMARK_KEYCLOAK_STARTER_SECRET

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

# The Starter Pack's service account (ADR 0051): the client kilnmark-starter, made while
# KILNMARK_STARTER=allowed and KILNMARK_KEYCLOAK_STARTER_SECRET is set, and removed on the next run
# once either is not. Kilnmark signs in as it (client credentials) to add and remove the pack's five
# example people. Its service account holds realm-management's manage-users, view-users and
# query-groups in this realm, and nothing else: no role in master, no realm, client or sign-in flow
# management, not even the realm's default roles. The people it adds carry the attribute
# kilnmark_starter_pack; attributes the user profile does not declare are seen and set by admins
# only (ADMIN_EDIT), so no one can put the mark on themselves.
STARTER_CLIENT=kilnmark-starter
starter="$(kc get clients -r "$REALM" -q clientId="$STARTER_CLIENT" --fields id --format csv --noquotes | one)"
if [ "$STARTER_ALLOWED" = allowed ] && [ -n "$STARTER_SECRET" ]; then
  if [ -z "$starter" ]; then
    say "creating the client $STARTER_CLIENT (the Starter Pack's service account)"
    kc create clients -r "$REALM" -s clientId="$STARTER_CLIENT" -s name="Kilnmark Starter Pack" \
      -s protocol=openid-connect -s publicClient=false -s clientAuthenticatorType=client-secret \
      -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false \
      -s implicitFlowEnabled=false -s frontchannelLogout=false > /dev/null
    starter="$(kc get clients -r "$REALM" -q clientId="$STARTER_CLIENT" --fields id --format csv --noquotes | one)"
  fi
  printf '{"secret":%s}' "$(json "$STARTER_SECRET")" | kc update "clients/$starter" -r "$REALM" -f -
  account="service-account-$STARTER_CLIENT"
  kc add-roles -r "$REALM" --uusername "$account" --cclientid realm-management \
    --rolename manage-users --rolename view-users --rolename query-groups
  kc remove-roles -r "$REALM" --uusername "$account" --rolename "default-roles-$REALM" 2> /dev/null || true
  profile="$(kc get users/profile -r "$REALM")"
  case "$profile" in
    *'"unmanagedAttributePolicy"'*) ;;
    *) printf '{"unmanagedAttributePolicy":"ADMIN_EDIT",%s' "${profile#*\{}" | kc update users/profile -r "$REALM" -f - ;;
  esac
  say "the client $STARTER_CLIENT manages people and groups in $REALM only"
elif [ -n "$starter" ]; then
  kc delete "clients/$starter" -r "$REALM"
  say "removed the client $STARTER_CLIENT: KILNMARK_STARTER is not allowed, or its secret is not set"
fi

# Example people, one per role, only when asked for. A person already there keeps their password;
# one an earlier run left without a password or a group gets them now.
if [ -n "$DEMO_PASSWORD" ]; then
  while IFS=: read -r email first last groups; do
    uid="$(kc get users -r "$REALM" -q email="$email" -q exact=true --fields id --format csv --noquotes | one)"
    if [ -z "$uid" ]; then
      kc create users -r "$REALM" -s username="$email" -s email="$email" -s emailVerified=true \
        -s firstName="$first" -s lastName="$last" -s enabled=true > /dev/null
      uid="$(kc get users -r "$REALM" -q email="$email" -q exact=true --fields id --format csv --noquotes | one)"
      say "added $email ($groups)"
    fi
    if ! kc get "users/$uid/credentials" -r "$REALM" --fields type --format csv --noquotes | grep -qx password; then
      printf '{"type":"password","temporary":false,"value":%s}' "$(json "$DEMO_PASSWORD")" \
        | kc update "users/$uid/reset-password" -r "$REALM" -f -
    fi
    for g in ${groups//,/ }; do
      gid="$(kc get groups -r "$REALM" -q search="$g" -q exact=true --fields id --format csv --noquotes | one)"
      kc update "users/$uid/groups/$gid" -r "$REALM" -n > /dev/null
    done
  done <<'PEOPLE'
vera.lane@kilnmark.com:Vera:Lane:kilnmark-viewers
sam.okafor@kilnmark.com:Sam:Okafor:kilnmark-stewards
dana.reyes@kilnmark.com:Dana:Reyes:kilnmark-designers
priya.shah@kilnmark.com:Priya:Shah:kilnmark-designers,kilnmark-sensitive-data
alex.morgan@kilnmark.com:Alex:Morgan:kilnmark-admins
PEOPLE
fi
say "done: open $KILNMARK_URL and choose Sign in with your provider"
