# Changelog fragments

Each pull request writes its changelog entry here, in a file of its own named after its branch
(`changelog.d/<branch>.md`), and never edits the Unreleased part of [CHANGELOG.md](../CHANGELOG.md).
Pull requests then never conflict over the changelog. This README is not a fragment.

A fragment has one or more section headings, each followed by its entries, written as the
entries in CHANGELOG.md are (a bullet each, starting with a bold summary where it helps):

```markdown
### Fixed

- **Run now on a flow's page shows the run.** Pressed while another tab was showing, it ...

### Details

- The run tab is chosen by ...
```

The sections, in the order a release lists them: **What's new**, **Security** (each fix with its
severity, and whether the flaw was in a release), **Fixed**, **Changed** (anything a customer must
act on, or that behaves differently) and **Details**. Use only these, each once, with `###`;
nothing goes before the first heading, and a fragment has no heading other than `###`. Each
section's entries are `- ` bullets (a bullet's continuation lines are indented under it).

Everything that shows the changelog (the app's **What's new**, the assistant's bundled docs, the
product-only check) reads CHANGELOG.md with the fragments merged into its Unreleased part:
section by section, CHANGELOG.md's own entries first, then the fragments in file-name order. The
merge is `ui/scripts/changelog.mjs`; `node ui/scripts/changelog.mjs --check` checks the fragments,
and the UI build runs it, so a malformed fragment fails CI. At a release,
`node ui/scripts/changelog.mjs --collect` merges them into CHANGELOG.md for good and deletes them
(docs/development.md, "Releasing").
