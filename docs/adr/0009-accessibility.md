# 0009 WCAG 2.2 AA

Status: Accepted (24 October 2026)

## Decision

The whole UI, including the graph and pipeline canvases, meets **WCAG 2.2 Level AA**. This covers
the UK public sector regulations, EN 301 549 (WCAG 2.1 AA) for EU public buyers, and US
Section 508 and ADA Title II (WCAG 2.0/2.1 AA).

## How

- Design tokens checked for contrast: text 4.5:1, UI components and graphics 3:1.
- Automated axe-core checks run on every page in CI; failures block a release.
- Manual keyboard and screen-reader passes (NVDA, JAWS, VoiceOver) each major release.
- Every drag has a non-drag alternative; every graph has a text or table view.
- An accessibility statement and a VPAT 2.5 conformance report are kept current.

See the Accessibility compliance section of [the architecture spec](../product/architecture-spec.md).
