# Signing in, roles and tokens

How you get into Kilnmark, and what your role lets you do. Setting up API tokens, your
organisation's sign-in and the bundled Keycloak, and the controls an auditor will ask about, are
for administrators: see [People and access](people-and-access.md).

## The short version

- **Two ways in.** Scripts and tools use **API tokens**. People can also **sign in with your
  organisation's own login** (Microsoft Entra ID, Okta and the like), with a second factor.
- **Four roles**, each including the one below: viewer, steward, designer, admin. Seeing
  **sensitive values unmasked** is a separate grant that only designers can hold.
- **Roles for people come from their groups** at your identity provider, as you map them in
  Kilnmark's settings. Kilnmark never stores anyone's password.
- **Tokens can be made to expire**: set a longest lifetime (say 90 days) and no token lasts longer.
- **Settings › Access review** shows what each role can do, the rules in force, and who can do what
  and when they last did anything, as a CSV for auditors ([People and access](people-and-access.md#token-lifetime-and-the-access-review)). Every
  sign-in, refusal and review download is in the [audit log](audit-log.md).

## Two ways in

![Two ways in, one set of roles: a script sends its API token, a person signs in with your organisation's login and MFA; Kilnmark checks either, and a role (and the unmask grant, when held) decides what they can do](images/signin-two-ways.svg)

| | API token | Sign in with your organisation |
| --- | --- | --- |
| **Who it's for** | Scripts, CI pipelines, the MCP server, AI assistants, and people who prefer it | People in a browser |
| **What it is** | A long secret starting `km_`, sent with each request | Your organisation's normal login page, then a session in that browser |
| **Who decides the role** | The admin who made the token | Your groups at the identity provider, mapped in Kilnmark's settings |
| **Second factor (MFA)** | No: the token itself is the credential | Yes, required by default |
| **How long it lasts** | Until its expiry, or the server's longest lifetime | At most 8 hours, then sign in again |

Both can be used on the same server. If your organisation's login is not set up, the sign-in page
shows only the token box.

## Roles, and seeing sensitive values

Everyone, and every token, has exactly one role. Each role can do everything the one below it can:

- **Viewer**: search and read the catalogue, pipelines, flows and runs.
- **Steward**: also run pipelines and flows, scan sources, and look after quality rules and issues.
- **Designer**: also build pipelines and flows, give them schedules, add and edit sources. With the
  sensitive-data grant (below), also write SQL into what they build: custom SQL rules, a pipeline
  read that runs a query, a flow's sql node.
- **Admin**: also Settings, API tokens and the audit log.

The full table, line by line, is on **Settings › Access review** for admins
([People and access](people-and-access.md#token-lifetime-and-the-access-review)).

![The four roles as steps, each including the one below; the unmask grant sits beside designers, and admins see values masked](images/signin-roles.svg)

**Seeing sensitive values unmasked** (columns marked sensitive, such as customer emails) is not part
of any role. It is a separate **grant** that only a designer can hold. Admins always see those
values masked, on purpose: the people who manage access are not the people who read the data. For a
token, the admin ticks it when making a designer token. For a person signing in through your
organisation, they need to be in a designer group *and* an "unmask" group.

## Using an API token

An API token is a password for a program. It starts with `km_` and is shown **once**, when an admin
makes it ([People and access](people-and-access.md#api-tokens)).

- **Using one:** the program sends it in a header with every request. The web UI can use one too:
  paste it on the sign-in page. A scheduler that runs pipelines and flows needs a **Steward**
  token, kept in a file only its jobs can read: see
  [Running from an external scheduler](external-schedulers.md#before-you-start).
- **If it stops working**, it was revoked or it expired: ask an admin for a new one.

## Signing in with your organisation

This uses **OpenID Connect**, the standard behind "Sign in with Microsoft" and the like. Kilnmark is
registered with your identity provider once (INSTALL.md, "Sign in with your identity provider");
after that:

1. On Kilnmark's sign-in page, the person chooses **Sign in with** *your organisation*.
2. They go to your normal login page, sign in there with their usual second factor, and come back.
3. Kilnmark reads what the provider says about them (their groups, how they signed in, when) and
   gives them a role.

![The three checks after your organisation's login: MFA used, signed in within 8 hours, in a mapped group; all three give a role, any no gives none, with the reason shown and recorded](images/signin-checks.svg)

**Your role comes from your groups** at the provider, as an admin maps them in Kilnmark
([People and access](people-and-access.md#your-identity-provider)). Someone in no mapped group signs
in but gets **no role**, and the page tells them why.

**MFA is checked, not assumed.** The provider says how the person signed in. Kilnmark wants either
"mfa", or two different *kinds* of factor: something you know (a password), something you have (a
code, a key, a phone), something you are (a fingerprint). One factor alone, even a strong one, or two
of the same kind, is not enough. Without it: no role, and the reason shown.

**How long a sign-in lasts.** While the person works, their sign-in renews itself every few
minutes, without asking them anything: Kilnmark gets a fresh token from the provider (with Keycloak,
every 5 minutes) and checks it as it checked the sign-in, MFA, groups and all. It ends, and the page
says "Your sign-in has ended. Sign in again.", when:

- it is **8 hours** since the person last signed in at the provider. Renewing never moves this on.
  Kilnmark asks the provider to make them sign in again after that, and refuses an older sign-in if
  the provider does not;
- the provider's own session ends: with the Keycloak that comes with Kilnmark, after 2 hours
  without using it, or 8 hours at most; or the person is disabled at the provider;
- a renewal no longer passes: they left every group that gives a role, or the provider stopped
  showing a second factor. Signing in again says why;
- they sign out.

**On screen**, Kilnmark greets a person by their name at the provider, for example "Alex
Morgan", with their initials in the top bar. **In the records** (the audit log, usage, "created
by"), they show as `sso:` plus their user name and the provider's fixed ID for them, for example
`sso:alex.morgan@kilnmark.com (3f2a…)`, so a chosen display name cannot pass for anyone else. The
menu under their name shows both.

## Signing in with Keycloak

Some organisations use the **Keycloak** that comes with Kilnmark as their identity provider. It
works as above, with Kilnmark's own look:

![Kilnmark's sign-in page with a Sign in with Keycloak button, and API token sign-in below it](images/sign-in-with-provider.png)

![Keycloak's sign-in page in Kilnmark's colours: the Kilnmark name and logo above a card asking for a username or email and a password](images/keycloak-sign-in.png)

- **Every sign-in has two factors**: a password and a one-time code from an authenticator app on
  the person's phone (Microsoft Authenticator, Google Authenticator, FreeOTP and the like).
- **The first time**, Keycloak asks the person to set up the code: scan the QR code with the app,
  then type the code it shows. Keycloak does not count setting it up as using it, so Kilnmark then
  says the sign-in did not use a second factor: choose **Sign in with Keycloak** again, give the
  password and the code, and you are in. A code works once, so wait for the app to show a new one
  if it still shows the code you just set up with. From then on, each sign-in asks for both.

![Keycloak's page for setting up the one-time code: install an authenticator app, scan the QR code (hidden here), type the code it shows and a name for the device](images/keycloak-one-time-code.png)
