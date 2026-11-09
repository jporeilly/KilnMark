# Production with your own identity provider

People sign in with your organisation's identity provider over OpenID Connect (Microsoft Entra
ID, Okta, Ping, ADFS, Keycloak and others), with its second factor
([ADR 0036](../../adr/0036-sign-in-with-the-identity-provider.md)). API tokens keep working for
scripts. This is the deployment recommended for banks and other regulated organisations: people
are added, removed and given roles where the rest of your organisation's access is, and Kilnmark
holds no credential that could make an account.

## Before you install

In the provider, register Kilnmark as a web application:

- its redirect URI is `https://<host>/api/v1/auth/login` (with `tls=on` and another HTTPS port,
  `https://<host>:<https_port>/api/v1/auth/login`);
- its post-logout redirect URI (where its sign-out page sends the browser back) is
  `https://<host>/` (or `https://<host>:<https_port>/`): signing out of Kilnmark takes the browser
  to the provider's sign-out page so the provider's session ends too, and a provider that does not
  know this address shows an error there instead of returning to Kilnmark;
- have the provider put the person's groups in the ID token's `groups` claim;
- require a second factor for the application, and let it issue refresh tokens.

Note its issuer URL, the client id it gave Kilnmark, and its client secret, and the groups that
are to give each role.

## Install it

Answer `production` and then `own`, or in an answers file:

```
mode=production
identity=own
email_domain=bank.example
host=kilnmark.bank.example
tls=on
tls_cert=/etc/ssl/kilnmark/server.crt
tls_key=/etc/ssl/kilnmark/server.key
port=8780
https_port=443
db_port=5442
oidc_issuer=https://login.bank.example/realms/staff
oidc_client_id=kilnmark
oidc_provider_name=Bank SSO
oidc_admin_groups=kilnmark-admins
oidc_designer_groups=kilnmark-designers
oidc_steward_groups=kilnmark-stewards
oidc_viewer_groups=kilnmark-viewers
oidc_unmask_groups=kilnmark-sensitive-data
```

Install asks for the client secret (typed twice, not shown; or one line piped in for an install
with no questions), and writes it to `config/.env` only. The group answers are comma-separated,
exactly as the provider names them. A person in none of them signs in with no role, and is told
so; designers in `oidc_unmask_groups` see values of columns marked sensitive. The sign-in page then
shows **Sign in with** your provider's name. There is no first admin to add: sign in with the admin
token, `KILNMARK_ADMIN_TOKEN` in `config/.env`, and give your admins the provider's admin group.
The people commands refuse on this install: add people in the provider.

To change any of it later, `configure` (it asks for the client secret again when the issuer's
client changes):

```
kilnmark.sh configure oidc_issuer=https://login.bank.example/realms/staff oidc_client_id=kilnmark-new
```

## What Kilnmark asks of the provider

Kilnmark requires a second factor: the provider must say so in the ID token's `amr` (`mfa`, or two
methods of two kinds, such as a password and a one-time code; one method alone, or two of one kind,
is not enough), or in its `acr` with a value you list in `KILNMARK_OIDC_MFA_ACR` in `config/.env`.
A sign-in that shows none gets no role. A sign-in lasts at most 8 hours from the person's last
sign-in at the provider (`KILNMARK_OIDC_MAX_SESSION`); the ID token must say when that was
(`auth_time`), which every provider can send. Every sign-in, and every one given no role, is in
**Settings**, **Audit log**, under `sso:` and the person's name.

A sign-in renews itself while the provider's session lasts: when the provider's ID token expires,
Kilnmark gets a fresh one with the refresh token and checks it again, MFA and groups included,
without asking the person anything. For that the provider must issue a refresh token. Keycloak does;
Microsoft Entra ID and Okta do only when asked for `offline_access`, so for them set
`KILNMARK_OIDC_SCOPES=offline_access` (never for Keycloak, where it asks for a token that outlives
the sign-in). Without a refresh token, a sign-in ends when its first ID token does, often within an
hour. Signing out asks the provider to revoke the refresh token, where it offers that (Keycloak and
Okta do; Entra ID does not).

A page's requests can renew at the same moment with the same refresh token. If the provider rotates
refresh tokens, let it accept a token used twice in quick succession (Okta's grace period; in
Keycloak, leave **Revoke Refresh Token** off), or the session ends.

Kilnmark must be reached over HTTPS for this: its session cookies are sent over HTTPS only. Install
asks for TLS, and refuses a host other than `localhost` without it. Behind a proxy that ends TLS
(`tls=proxy`), install sets `KILNMARK_OIDC_BEHIND_TLS_PROXY=true` for you ([HTTPS and TLS](https.md)).

## Review who has access

**Settings**, **Access review** lists every API token (role, whether it sees sensitive values,
expiry, last use) and everyone who signs in with your identity provider, with the role of their last
sign-in. **Download CSV** keeps it as evidence for your auditors; each download is in the audit log.
Every kilnmark.com account that has signed in is marked **Example account** (the Starter Pack's
people): there should be none on an install with real data, and a production install refuses them.

To limit how long tokens last, set `KILNMARK_TOKEN_MAX_LIFETIME` in `config/.env`, for example
`90d`, and restart. New tokens must then expire within it, and **tokens already older than it stop
working**: check the review first, and replace any a script still needs. The admin token from
`KILNMARK_ADMIN_TOKEN` is not limited
([ADR 0037](../../adr/0037-token-lifetime-and-access-review.md)).

## Two people for sensitive values

Designer tokens can see values of columns marked sensitive. To make that take two people, set
`KILNMARK_UNMASK_APPROVAL=true` in `config/.env` and restart. A designer token asked to see them can
then be made only by someone signed in with the provider, and is made without; another person, also
signed in with it, approves it under **API tokens**. Tokens cannot ask or approve, because a token
names no one, and the provider's fixed id tells the two people apart even if one renames
themselves. Each step is in the audit log, and the access review shows who approved each grant. For
people's own sign-ins, the second person is whoever approves membership of the unmask group at the
provider ([ADR 0038](../../adr/0038-a-second-admin-approves-the-unmask-grant.md)).

- Use one issuer for your own organisation: never a multi-tenant endpoint or
  `QUARKUS_OIDC_TOKEN_ISSUER=any`, which would let one person arrive as two.
- Set it on every Kilnmark api process alike.
- Designer tokens given sensitive values before you turned it on keep them: find them in the access
  review (no "approved by") and revoke any that should not.
