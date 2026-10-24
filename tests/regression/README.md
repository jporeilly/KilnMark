# Regression suite

End-to-end tests run against the reference lab before every package (plan task 23):

- Install from scratch, then ingest and profile the whole lab
- Re-run an unchanged source and confirm every asset is skipped
- Profile a 10 million-row table within a 4 GB heap
- Automated WCAG 2.2 AA checks (axe-core) on every UI page

A release is packaged only when the full suite passes.
