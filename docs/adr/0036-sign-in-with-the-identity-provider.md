# 0036 Sign-in with the organisation's identity provider, with MFA

Status: Accepted (3 November 2026)

## Context

ADR 0014 decided that people sign in through the customer's own identity provider over OpenID
Connect, and planned it for v0.2. The compliance map ([compliance.md](../product/compliance.md))
brought it forward: NYDFS 500.12 requires multi-factor authentication for every person reaching
non-public information, and a bank's review asks for sign-in through its own provider, roles from
its own groups, session limits, and a record of every sign-in. Until now people used API tokens
pasted into the sign-in page.

## Decision

1. **Quarkus's OIDC client, authorisation code flow**, beside API tokens, off unless the
   administrator turns it on (`quarkus.oidc.tenant-enabled`, with the provider's issuer, client ID
   and secret). Kilnmark keeps no passwords. API tokens are unchanged, for scripts, CI and the MCP
   server; a browser may still use one.
   - Token sign-in is tried first, so a request without credentials is answered 401, as scripts
     expect, and is never redirected to the provider. Only `/api/v1/auth/login` starts a provider
     sign-in, protected at the HTTP layer with the code flow mechanism; it ends on the web UI.
   - The session is the OIDC client's encrypted cookie, SameSite=Lax (the provider's redirect back
     is a cross-site navigation, which Strict would not carry).
2. **Roles from groups, in Kilnmark's settings.** `kilnmark.auth.oidc.<role>-groups` list the
   groups (as the provider's `groups` claim names them, exactly) that give each role; a person in
   several gets the highest. The unmask grant needs a group in `unmask-groups` and the designer
   role, as for tokens (ADR 0027). The identity is built afresh from those settings: the OIDC client
   maps a `groups` claim to roles by itself, and none of that survives, so a provider group called
   "admin" grants nothing unless a setting names it.
3. **MFA required by default.** The ID token's `amr` must hold `mfa`, or methods of two different
   kinds as RFC 8176 names them: something known (`pwd`, `pin`, `kba`), something held (`otp`,
   `hwk`, `swk`, `sms`, `tel`, `sc`), something the person is (`fpt`, `face`, `iris`, `retina`,
   `vbm`); or its `acr` must be one the administrator lists. One method alone (a passwordless SMS,
   code or face sign-in), two of one kind (`sms` and `tel`, both the phone), and methods that are no
   factor (`user`, `pop`, a blank) are one factor or none, and are refused. `mfa` itself is taken
   at the provider's word; to require more, require it in the provider's policy. Without it,
   and without a group that has a role, the person is signed in with no role; the sign-in page says
   why, and the audit log records it. Kilnmark cannot make the provider ask for a second factor;
   the provider's own policy does, and this check refuses a sign-in that shows none.
4. **Sessions end.** The longest session (`kilnmark.auth.oidc.max-session`, 8 hours) is counted
   from the person's last sign-in at the provider (`auth_time`), which the ID token must carry
   (OpenID Connect requires it when `max_age` is sent): never the token's issue time instead, which
   is new on every token the provider re-issues and would restart the session for ever. Every
   redirect to the provider carries `max_age`, so the provider asks them to sign in again after it;
   a sign-in older than that (from a provider that ignores it, or a session that has run its time)
   gets no role, and the page says why. The ID token's own expiry ends a session
   sooner, and a fresh one comes from the provider without a prompt while its session lasts.
5. **Cookie requests.** A request that may change something, signed in by the session cookie, must
   carry `X-Requested-With: JavaScript`, which only Kilnmark's pages send: a page on another site can
   make a browser send the cookie but not that header. The same header makes the OIDC client answer
   499, not a redirect, when a page's session has ended.
6. **Audit.** Each provider sign-in is an event (`auth.sign_in`, with its role and grant, or
   `auth.sign_in_refused`, with the reason, too old included), and so is signing out
   (`auth.sign_out`) (ADR 0029). A provider sign-in is named `sso:<name> (<sub>)` everywhere Kilnmark
   names a caller (the audit log, usage, "created by"): the name is whatever the provider sends, which
   some let people choose, so the prefix and the provider's fixed ID keep it from passing for an
   anonymous caller, a token or the server. Its refused requests are counted apart from others'.
7. **A sign-in given no role can be replaced**: **Sign in with** on the sign-in page
   (`/api/v1/auth/login?again=true`) ends it and goes back to the provider with `prompt=login`, so
   the provider asks the person afresh rather than answering from its own session. Only when asked:
   the provider's redirect back lands on the same path, and sending a refused session round again
   there would loop through the provider for ever. API tokens may not be named `sso:...`, and every
   ID token must carry `sub` (`quarkus.oidc.token.subject-required`).
8. **HTTPS.** In production the session cookies are Secure, so a provider sign-in needs Kilnmark on
   HTTPS; behind a proxy that ends TLS, `KILNMARK_OIDC_BEHIND_TLS_PROXY=true` makes the redirect URI
   the provider sees `https://`.

## Consequences

- A bank can sign people in with its own provider and MFA, give roles from its own groups, and show
  both in Kilnmark's audit log.
- Signing out ends Kilnmark's session in that browser; the provider's own session is the
  provider's. The session cookie is not kept on the server, so a copy of it taken from the browser
  works until it expires (at most the ID token's lifetime). A server-side session store, so signing
  out ends every copy, is a later step if a customer needs it.
- Kilnmark checks what the provider says (`amr`, `acr`, `auth_time`); a provider that sends none
  of them cannot be used with MFA required, and its administrator either configures the claims or
  turns the requirement off.
- An admin signed in with the provider can create API tokens, which need neither MFA nor a session
  limit; the audit log shows who did (`sso:...`). Requiring an expiry for tokens is on the backlog
  (token policy).
- ADR 0014's Keycloak profile, for customers without a provider, is still to come.
