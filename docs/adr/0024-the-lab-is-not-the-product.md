# 0024 The lab is not the product

Status: Accepted, 25 October 2026

## Decision

The reference lab (`lab/`: synthetic databases, object storage, files and the services added for
each connector) is for **development, testing and demos only**.

- **Never shipped.** No release folder, image or jar contains `lab/` or its data, nor `tests/`,
  nor documentation other than the user guides and the changelog the app bundles.
- **Never referenced by shipped code.** Server and engine code, the web UI and shipped messages
  never name a lab source, service, path or port. Examples use neutral names: `sales-db`,
  `warehouse`, `landing-bucket`, `file-share`.
- **Never a default.** Production settings and the release's `compose.yaml` have no lab defaults.
  The developer `compose.yaml` at the root of the repository may keep conveniences for the lab
  (its file share as the default folder, a fixed network the lab joins), and the server's `%dev`
  and `%test` profiles may point at it.
- **Never in the guides.** The user guides describe the product only. Walking through Kilnmark
  against the lab belongs in `lab/README.md` and `docs/development.md`.
- **A connector's lab dataset lives only in `lab/`:** its compose service, seed data and row in
  `lab/sources.csv`, and test resources that use it.
- **The changelog may say what changed in the lab** in its details, since it describes the
  repository, but its "What's new" sections are for customers and do not mention the lab.
- **The samples ship, the lab's mapping does not** (30 October 2026). The pipelines and flows in
  `samples/` are product templates: they name their sources by placeholders (`crm-db`, `shop-db`,
  `files`), never a lab source, and the server bundles them for **Add samples**, which maps each
  placeholder to one of the person's sources. Which lab source each placeholder means lives only in
  `lab/sample-sources.csv`, which `lab.sh samples` and the tests read.
- **The sample data is the product's own, not the lab's** (30 October 2026). The tables the samples
  read ship as synthetic data generated for Kilnmark (`samples/data/generate.py`), with the columns
  the samples use; none of the lab's data is copied into it, and it never mentions the lab. The
  starter pack copies it into a database of the person's choosing.

The release build enforces this: `./gradlew release` runs `checkProductOnly`
(`gradle/product-only.gradle.kts`), which fails when the release folder holds a lab, tests or docs
folder, or when the shipped `compose.yaml`, anything under the release's `containers/`, Kilnmark's
own jars (classes, the UI bundle, `application.properties` outside `%dev` and `%test`), the
bundled guides or the samples name a lab source (any `lab-<name>`, `labadmin`), a path into `lab/` or a lab-only
port (5433, 9020). The compose file, the Containerfiles, the guides, the samples and "What's new"
may not mention the lab at all. Justified exceptions are listed, each with its reason, in
`config/product-only-allow.txt`. CI runs `release` on every push and pull request.

## Why

Customers install the release, not the repository. Anything of the lab that reaches them is at
best noise (a template that reads a database they do not have) and at worst a fault: a compose
file that mounts a folder that is not there, or a default that points at a lab port. The lab is
open about being a development tool, with weak, published credentials, which must never look like
something the product relies on.

A rule people remember is not enough: the lab grows with every connector, and examples get copied
from wherever they work. A check in the release build catches what review misses.

## Consequences

- The release has its own `compose.yaml`, made from `containers/compose.release.yaml` with the
  version filled in. Changes to the stack are made in both files.
- The New pipeline template prefills the person's own sources, or placeholders the check reports.
- A new lab service or source name needs nothing more: the check matches any `lab-<name>`. A
  shipped word that happens to look like one goes in the allow-list with its reason.
- Branches that add lab items to "What's new" fail the release build until those items move to
  the details.
