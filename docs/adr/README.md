# Decision records

One file per decision that is hard to reverse. Each says what was decided, why, and what it costs.
Add a new record rather than editing an accepted one; mark the old one superseded.

| # | Decision | Status |
| --- | --- | --- |
| [0001](0001-standalone-product.md) | Standalone product, built clean-room | Accepted |
| [0002](0002-linux-on-prem-one-image.md) | Linux servers, on-prem first, one image with roles | Accepted |
| [0003](0003-postgresql-age-pgvector.md) | PostgreSQL with Apache AGE and pgvector as the only store | Accepted |
| [0004](0004-java-quarkus-arrow.md) | Java 21, Quarkus and Apache Arrow | Accepted |
| [0005](0005-node-plugin-interface.md) | Node plugin interface stays provisional until sprint 3 | Accepted |
| [0006](0006-clean-engine-borrowed-code.md) | Clean engine, selective borrowing of Apache-licensed code | Accepted |
| [0007](0007-dependency-policy.md) | Libraries yes, platforms no | Accepted |
| [0008](0008-licence.md) | Business Source License 1.1 | Accepted |
| [0009](0009-accessibility.md) | WCAG 2.2 AA | Accepted |
| [0010](0010-repository-layout.md) | Docs in /docs, all build output in /dist | Accepted |
| [0011](0011-container-runtime.md) | Podman first, any OCI runtime | Accepted |
| [0012](0012-ai-runtime.md) | AI is core, local by default, and people approve | Accepted |
| [0013](0013-java-25.md) | Java 25 LTS | Accepted |
| [0014](0014-authentication.md) | Sign-in through OpenID Connect; Keycloak optional | Accepted |
| [0015](0015-file-formats.md) | File formats: Parquet through Hardwood, S3 through the AWS SDK | Accepted |
| [0021](0021-pipeline-first.md) | Pipeline-first: a data integration engine, not a catalogue | Accepted |
| [0022](0022-pipelines-as-graphs.md) | Pipelines run as graphs of nodes with named ports | Accepted |
| [0016](0016-ai-runtime-choices.md) | AI runtime: LangChain4j modules, model profiles, one egress gate, embeddings in one table | Accepted |
| [0019](0019-relationships.md) | Relationships: declared and inferred, in PostgreSQL tables, proposed for people to decide | Accepted |
| [0017](0017-documents.md) | Documents: Tika's parsers, POI's streaming reader for sheets, text in chunks | Accepted |
| [0020](0020-quality-standard-formats.md) | Data quality in open formats: OpenLineage facets and ODCS 3.1 | Accepted |
| [0024](0024-the-lab-is-not-the-product.md) | The reference lab is for development and tests only, never shipped, and a release check enforces it | Accepted |
| [0025](0025-registered-jdbc-drivers.md) | Registered JDBC drivers: the admin vouches, Kilnmark checks the checksum | Accepted |
| [0026](0026-flows.md) | Flows orchestrate pipelines; the scheduler comes later and is built in | Accepted |
| [0027](0027-unmasking-is-a-grant.md) | Seeing unmasked values is a grant of its own, held only by designers | Accepted |
| [0029](0029-audit-log.md) | An append-only audit log in the server's own database | Accepted |
| [0030](0030-generic-jdbc-vetted-settings.md) | Generic JDBC takes only settings Kilnmark has vetted; isolated drivers later | Accepted |
| [0028](0028-sql-in-reads-and-flow-actions.md) | SQL a person writes runs in two places only: a read's query, and a flow's SQL action | Accepted |
| [0031](0031-https-and-security-headers.md) | HTTPS on Kilnmark itself, and the security headers every response carries | Accepted |
| [0032](0032-tls-to-kilnmarks-own-database.md) | Kilnmark's own database is reached over TLS, or the server does not start | Accepted |
| [0033](0033-sboms-scanning-and-signed-releases.md) | SBOMs, vulnerability scanning and signed releases | Accepted |
| [0034](0034-encrypted-connections-to-sources.md) | Encrypted connections to sources, when the administrator requires them | Accepted |
| [0035](0035-s3-hosts-as-the-sdk-reaches-them.md) | S3 hosts for the allowed-hosts list, as the AWS SDK reaches them | Accepted |
| [0036](0036-sign-in-with-the-identity-provider.md) | Sign-in with the organisation's identity provider, with MFA | Accepted |
| [0037](0037-token-lifetime-and-access-review.md) | A longest token lifetime, and the access review | Accepted |
| [0038](0038-a-second-admin-approves-the-unmask-grant.md) | Two people for the unmask grant | Accepted |
| [0039](0039-backup-and-restore.md) | Backup and restore: a dump of the database, a copy of the keys, kept apart | Accepted |
| [0040](0040-parallel-runs-and-limits.md) | Parallel runs, and the limits around them | Accepted |
