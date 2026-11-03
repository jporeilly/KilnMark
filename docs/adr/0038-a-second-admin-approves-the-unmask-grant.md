# 0038 A second admin approves the unmask grant

Status: Accepted (3 November 2026)

## Context

Seeing values of columns marked sensitive unmasked is a grant of its own, held only by designers
(ADR 0027); admins see those values masked, so the people who give access are not the people who
read the data. But an admin makes tokens, and could make a designer token with the grant and use it
themselves. The compliance map ([compliance.md](../product/compliance.md), "Least privilege and
access reviews") and ADR 0037 left this open: banks are asked for separation of duties (SOX
segregation of duties, NIST 800-53 AC-5, NYDFS 500.7) on access to sensitive data, which means a
second person agrees before it is given.

## Decision

1. **A setting, `kilnmark.auth.unmask-approval`**, off by default (existing installations keep
   working as they do). On, a designer token asked to see unmasked values is made **without** the
   grant, and the request is recorded on the token: who asked, and when.
2. **Another admin approves or refuses it**, under **API tokens** (`POST
   /api/v1/tokens/{id}/unmask/approve` or `.../refuse`). The admin who asked is refused (403) when
   approving: names are compared as Kilnmark records callers (`token N (name)`, `sso:<name> (<sub>)`).
   Refusing, or the asker withdrawing it, leaves the token without the grant. A decided or revoked
   request cannot be approved later (409).
3. **Every step is in the audit log**: `unmask.requested`, `unmask.approved` and `unmask.refused`,
   each naming who and which token.
4. **People who sign in with the identity provider** get the grant from their groups (ADR 0036), so
   the second person there is whoever approves membership of the unmask group at the provider. The
   setting does not change that path.

## Consequences

- With the setting on, no single admin can give anyone, themselves included, a way to see sensitive
  values: it takes two, and the audit log shows both.
- The check compares callers, not people: someone who holds two admin tokens could approve their own
  request. Separation of duties needs each admin to have their own credentials (personal tokens, or
  better, provider sign-ins, which name the person); the access review (ADR 0037) shows who holds
  what.
- A designer waiting for approval works with masked values meanwhile; nothing else about the token
  changes.
