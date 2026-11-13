### Details

- **Changelog entries are fragments.** Each pull request writes its entry to a file of its own,
  `changelog.d/<branch>.md`, with the same sections, instead of editing CHANGELOG.md's Unreleased
  part, so open pull requests no longer conflict there. `ui/scripts/changelog.mjs` merges the
  fragments into the Unreleased part wherever the changelog is read: the app's What's new (a Vite
  plugin), the anchor check, and the server's bundled docs, so the assistant and the product-only
  check see them as before. The UI build checks the fragments (`--check`) and fails on an unknown
  section, text before the first heading, an empty fragment or a `#` or `##` heading. A release
  merges them into CHANGELOG.md and deletes them (`--collect`, docs/development.md "Releasing").
