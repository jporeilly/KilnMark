# 0014 Sign-in through OpenID Connect; Keycloak optional

Status: Accepted (24 October 2026)

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
