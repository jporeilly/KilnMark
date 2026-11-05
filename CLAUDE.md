# Working on Kilnmark

Kilnmark is a pipeline-first data integration and quality platform (Java 25, Quarkus, Arrow; React
UI), meant to run inside banks and other regulated organisations. Start with
[docs/README.md](docs/README.md); the work, in order, is in
[docs/product/backlog.md](docs/product/backlog.md), and the controls regulators expect are in
[docs/product/compliance.md](docs/product/compliance.md).

## How work is done

- One branch and one pull request per change, against `main`. Never push to `main`.
- A PR merges only with CI green on its head. The owner merges and tags releases: do neither.
- Every substantial change gets one independent review (a fresh agent given the diff, not your
  conclusions). Security changes are reviewed again after each fix until a review finds no real
  bypass. At most two agents at a time.
- Each change ships with its tests, samples or templates where it has them, the user guide
  (`docs/guide`), the design notes (`docs/design`), an ADR for a decision (`docs/adr`), and a
  CHANGELOG entry under Unreleased.
- Ask before deleting data, rewriting history, force-pushing, or changing repository settings.
- Stacked PRs: when a PR is moved onto `main` after the one under it merges, GitHub can report a
  conflict that is not there until the branch gets a new push. Merge `main` into the branch
  (a merge commit, never a rebase) and push.

## Checks to run before pushing

- Server: `./gradlew build` (Windows: `build.ps1`). CI compiles with JDK 25 and
  `-Xlint:all -Werror`: a doc comment not attached to a declaration fails the build.
- UI, in `ui/`: `npx tsc --noEmit -p .`, `npx vitest run`, `node scripts/check-contrast.mjs`,
  `node scripts/check-slugs.mjs`.
- `SightsUseTest` reads the api, mcp and assistant packages as text: never put `//` inside a Java
  string literal there (write `":/"` or `"https:" + "/" + "/"`).
- Database migrations are `graph/src/main/resources/db/migration/V<n>__*.sql`, numbered in the
  order they merge; Flyway refuses one older than the last applied.

## Rules that never bend

- **Names.** Never use the names ADR 0006 lists for the tool Kiln borrows from, its designer or
  Apache Hop, nor their terms (hops, transformations, job entries), in code, the UI, guides,
  samples or the changelog. Kilnmark has nodes, edges, pipelines and flows.
- **Borrowed code** only from that tool's Apache-licensed releases 10.2 and earlier, with its
  headers kept and an entry in [docs/legal/provenance.md](docs/legal/provenance.md). Nothing from
  Apache Hop.
- **The lab is not the product** (ADR 0024): its data is synthetic, it is never shipped, named or
  depended on by the release (`checkProductOnly` fails the build otherwise).
- **No default secrets**, no GPL drivers or dependencies (ADR 0007).
- **Ports.** New services avoid 8080, 8780, 3306, 9000, 9099, 5442, 8050, 3307, 1522, 14330, 5433,
  1025 and 8025.
- **Sensitive values** are seen unmasked only by designer tokens holding the unmask grant; admins
  see them masked, and a model never sees them (ADR 0027). Every view of values goes through
  `Sights`.
- **SQL a person writes** runs only on PostgreSQL sources with Allow SQL on, checked as custom SQL
  rules are, on a read-only connection (ADR 0028). Whatever a query returns is marked sensitive.
- **Database operations** (ADR 0045) run only statements Kilnmark writes from fixed shapes, never
  text a person typed: behind a source's Allow changes, for designers and admins, previewed,
  confirmed by name for a drop or truncate, approved by a second person on production sources,
  and each an audit event.
- **Generic JDBC** reaches database servers over the network only, with vetted driver settings
  (ADR 0030); drivers that move files for ordinary SQL are refused.
- **The audit log** is append-only; new security-relevant changes record an event (ADR 0029).

## Writing

Plain, short sentences in British English, saying what the reader can do and why. Docs and
messages say what happened and what to do next; error messages never echo a URL, a value or a
secret.
