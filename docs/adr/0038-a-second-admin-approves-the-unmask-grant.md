# 0038 Two people for the unmask grant

Status: Accepted (3 November 2026)

## Context

Seeing values of columns marked sensitive unmasked is a grant of its own, held only by designers
(ADR 0027); admins see those values masked, so the people who give access are not the people who
read the data. But an admin makes tokens, and could make a designer token with the grant and use it
themselves. Banks are asked for separation of duties on access to sensitive data (SOX segregation of
duties, NIST 800-53 AC-5, NYDFS 500.7): a second person agrees before it is given.

A first design compared API tokens: the admin who asked could not approve. Its review showed why that
cannot work: any admin can make another admin token in one call and approve with it, so tokens can
never show that two people were involved. Only the organisation's identity provider says who a person
is.

## Decision

1. **A setting, `kilnmark.auth.unmask-approval`**, off by default (existing installations keep
   working as they do). It needs sign-in with the identity provider (ADR 0036): with the setting on
   and no provider set up, the server does not start.
2. **Only a person asks.** With the setting on, a designer token asked to see unmasked values can be
   made only by someone signed in with the provider, and is made **without** the grant: the request
   records who asked, as their name and as the provider's fixed id for them (issuer and subject),
   and when. A token asking for it is refused: a token names no one.
3. **Only another person approves**, also signed in with the provider, under **API tokens** (`POST
   /api/v1/tokens/{id}/unmask/approve`). People are compared by that fixed id, so neither another
   token nor the asker under a changed name can approve (403). Who approved, and when, stays on the
   token and shows in the access review (ADR 0037). Anyone may refuse or withdraw a request (`.../refuse`);
   the token stays, without the grant. A decided request, or one whose token was revoked or has
   expired, cannot be approved. With sign-in off, nothing can be approved or refused.
4. **Every step is in the audit log**: `unmask.requested`, `unmask.approved` and `unmask.refused`,
   with the token and who had asked.
5. **People's own grant** comes from the provider's groups (ADR 0036): there, the second person is
   whoever approves membership of the unmask group at the provider.

## Consequences

- With the setting on, no one person can give a token the grant: it takes two people the provider
  tells apart, and the audit log and the access review show both.
- The provider is the control: one person with two accounts there could still do it. Accounts are
  the provider's to manage.
- The setting is read by each process: set it on every Kilnmark api process alike. Grants given
  before it was turned on stay, and requests still waiting when it is turned off stay waiting; the
  access review shows both.
- A designer waiting for approval works with masked values meanwhile.
