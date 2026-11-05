# 0037 A longest token lifetime, and the access review

Status: Accepted (3 November 2026)

## Context

The compliance map ([compliance.md](../product/compliance.md), "Least privilege and access
reviews") asks for two things banks are examined on. NYDFS 500.7, NIST 800-53 AC-2 and AC-6 and
SOX ITGCs want access reviewed periodically: someone must be able to list who can do what, see
whether it is still used, and keep the list as evidence. And credentials should not last for ever:
until now an admin could make an API token that never expires, and nothing stopped it, including
from a provider sign-in that needs MFA (ADR 0036), turning an 8-hour session into a permanent key.

## Decision

1. **A longest token lifetime**, `kilnmark.auth.token-max-lifetime` (for example `90d`, between 1
   and 3650 days, or the server does not start), off by default so existing installations and
   scripts keep working; a bank sets it.
   - Every new token must then expire within it: one that never expires, or later, is refused,
     naming the limit. The UI offers only expiries within it.
   - A token older than it stops working, whatever its own expiry says, so tokens made before the
     limit was set, or with a longer expiry, are caught too. One SQL expression decides whether a
     token works, for sign-in, for the count of usable admins (so the first-start token is written
     when the last admin token passes the limit) and for notifications; the list and the review show
     that effective expiry. Creation allows five minutes' slack for a browser clock running ahead.
   - Every Kilnmark process (api, worker, scheduler) judges tokens by its own setting, so it is set
     on all of them; a bad value stops a process at start, before its mail and notification work.
   - The token from `KILNMARK_ADMIN_TOKEN` is not limited: it is the operator's, set in the
     server's environment and replaced by changing that setting. The 24-hour first-start token
     already expires.
2. **The access review**, `GET /api/v1/access-review` (admins), as JSON or CSV
   (`format=csv`), and **Settings › Access review** in the UI:
   - every API token: name, role, unmask grant, status, who made it, when, expiry, last use;
   - everyone who has signed in with the identity provider and been given a role: the role and
     grant of their last sign-in, when it was, and how many times, from the audit log (people have
     no account in Kilnmark: their role comes from their groups at each sign-in);
   - the longest token lifetime, if one is set.
   Each view and download is an audit event (`access.review`). A person whose latest sign-in was
   refused because no group gives them a role any more shows with none; one refused for another
   reason (no second factor, say) keeps the role their groups gave, with the refusal noted, so an
   admin is never hidden from the reviewer. CSV cells that a spreadsheet would run as a
   formula (a name starting `=`, `+`, `-` or `@`) start with a quote.
3. **Not yet: separation of duties for the unmask grant.** Admins make tokens, so an admin can make
   a designer token that sees unmasked values. A second person approving the grant is a later step;
   the review and the audit log (`token.created` names who made it, and whether it holds the grant)
   make it visible meanwhile.

## Consequences

- An auditor can be given a CSV of who can do what and when they last did, and a bank can show
  that no API token lasts longer than its policy.
- Setting the limit can stop tokens at once: any token already older than it. The CHANGELOG and
  INSTALL.md say to check the access review before setting it.
- A person who leaves stays in the review until the audit rows age out (they are kept); their
  access ends when the provider stops giving them the group, as the review's note says.
