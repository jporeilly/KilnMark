# 0014 Sign-in through OpenID Connect; Keycloak optional

Status: Accepted (24 October 2026). The Keycloak profile was built on 5 November 2026: see
"Built" below.

## Decision

- **People sign in through the customer's own identity provider** over OpenID Connect: Microsoft
  Entra ID, Okta, Ping, ADFS, Google, Keycloak or any other OIDC provider. Kilnmark keeps no
  passwords for people. Its roles (viewer, steward, admin) come from the provider's group claims,
  mapped in Kilnmark's settings. Delivered in v0.2 (plan: OIDC sign-in).
- **Keycloak is bundled as an optional Compose profile (`auth`)** for customers with no OIDC
  provider, or with only Active Directory or LDAP, which Keycloak federates. It is never required.
- **Machines use API tokens** issued by Kilnmark (scripts, CI, the MCP server), stored only as
  hashes, scoped to a role and expiring. Delivered in v0.1 (plan task 9).
- **Until then** (v0.0.x) the server has no sign-in and is for lab use on a trusted machine.

## Why

On-prem customers already run an identity provider and will not accept another user store to
manage. OIDC is the one standard every current provider speaks, and Quarkus supports it directly.
Keycloak is the strongest self-hosted option where nothing exists: Apache 2.0, AD and LDAP
federation, SAML brokering, and built on Quarkus. It costs 0.5 to 1 GB of memory, which is why it
is optional rather than part of every install.

## Alternatives considered

- **Keycloak always:** one more service to patch for customers who already have Entra ID or Okta.
- **Built-in user accounts:** another password store to secure and audit, which buyers of a
  trusted-data product will question.
- **Authentik, Zitadel, Authelia:** lighter, but weaker at Active Directory federation, which
  on-prem customers most often need.

## Built (5 November 2026)

- **The `auth` profile** in the release's `compose.yaml`: Keycloak (pinned by version and digest),
  its own PostgreSQL in the Kilnmark database image, with TLS, and a one-off `keycloak-setup`
  service that runs `containers/keycloak/setup.sh` with Keycloak's admin tool. Nothing is set up by
  a realm export: the script is short enough to read, safe to run again, and takes every secret
  from `.env`, none with a default. Each is optional to compose, so Kilnmark starts without the
  profile; without one, keycloak-db stops (PostgreSQL names its own setting) or keycloak-setup
  stops and names the `.env` setting. Secrets never go on a command line: kcadm reads the admin
  password from `KC_CLI_PASSWORD` and the others as JSON on its input. Each part (groups, flow,
  its use, the client's claims, a person's password and groups) is checked and made on its own, so
  a run that stopped half way is finished by the next.
- **The realm `kilnmark`**: groups for the four roles and the unmask grant, the confidential client
  `kilnmark` (its redirect URI and secret follow `.env` on every run), a groups claim, and its own
  sign-in flow: the session cookie, or a password and then a one-time code, both required. Each
  step carries the authentication method it is (`pwd`, `otp`) for 8 hours, so the ID token's `amr`
  shows two kinds of factor and Kilnmark's MFA check (ADR 0036) passes. Without that age, Keycloak
  leaves the methods out of `amr` altogether.
- **First sign-in.** Setting up the code is a required action, not the code step, so the first
  sign-in's `amr` holds only `pwd` and Kilnmark refuses it; the person signs in again, with the code.
  We kept that over weakening the check.
- **Addresses.** Browsers reach Keycloak at `KILNMARK_KEYCLOAK_URL`; Kilnmark reaches it inside the
  stack (`http://keycloak:8080`), with Keycloak's back-channel address taken from the request, so
  one issuer serves both. Keycloak serves plain HTTP, published on this machine only, for browsers
  on it or a proxy on it that ends TLS (`KC_PROXY_HEADERS=xforwarded`); its admin realm gets
  brute-force protection too. PKCE is not required of the client: Kilnmark does not send it yet.
- **The look**: a login theme (`themes/kilnmark`) on Keycloak's own, changing only colours, fonts
  (Inter and Manrope, OFL 1.1, with their licence) and words.
- **Demonstration people**, only with `KILNMARK_KEYCLOAK_DEMO_PASSWORD`: five at `kilnmark.com`, one
  per role. (Superseded 7 November 2026 by ADR 0051: the five are made only on a Starter Pack install,
  with a password the operator types, and people of the organisation's own with the control
  script's `people` commands; `KILNMARK_KEYCLOAK_DEMO_PASSWORD` is retired.)
