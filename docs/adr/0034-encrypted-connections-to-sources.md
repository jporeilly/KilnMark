# 0034 Encrypted connections to sources, when the administrator requires them

Status: Accepted (3 November 2026)

## Context

Kilnmark reads from and writes to the customer's databases and object storage, with their
credentials, over the customer's network. NYDFS 500.15, DORA and NIST 800-53 SC-8 want data
encrypted in transit, and a bank's review asks whether a product can be made to refuse plain-text
connections rather than trust each person who adds a source to choose well. Kilnmark's own database
is reached over TLS only (ADR 0032); sources were left to their settings, and several drivers'
defaults fall back to plain text without a word: PostgreSQL's `sslmode=prefer`, MySQL's
`tls_mode` `preferred`, Oracle's `encryption` `accepted`.

## Decision

1. **A setting, `kilnmark.sources.require-tls`, off by default.** On, it refuses a source whose
   connections could go unencrypted. Off by default because existing installations, the reference
   lab and many test databases have sources without TLS, and turning it on is a decision the
   customer's administrator makes with their database teams.
2. **Each connector says for itself** (`Connector.unencryptedReason`) why a source's connections
   could go unencrypted, naming the setting to change and never the host, or nothing when every
   connection is encrypted or fails, or when the source reaches no network. The default cannot
   tell, so a connector that does not say is refused: a new connector fails closed.
   - **PostgreSQL**: the sslmode the driver will use, worked out by the driver's own parser from
     the URL and the properties the source adds (as ADR 0032 does for Kilnmark's own database):
     `require`, `verify-ca`, `verify-full` (or `ssl=true`).
   - **SQL Server**: the `encrypt` the driver itself reads from the URL: `true` (its default),
     `mandatory`, `strict`. A URL the driver cannot read is refused rather than guessed.
   - **MySQL and MariaDB**: `tls_mode` `required`, `verify-ca` or `verify-full`.
   - **Oracle**: `encryption` `tls` (TCPS) or `required`. Oracle's native encryption, insisted on
     by `required`, counts: it encrypts every byte with AES, many banks run Oracle that way, and
     requiring TCPS alone would refuse them. The setting's name says TLS because that is what
     every other kind uses.
   - **S3**: an `https://` endpoint, or none, which means AWS over HTTPS. The SDK is told to ignore
     endpoint URLs configured on this server (`aws.ignoreConfiguredEndpointUrls`, which outranks
     `AWS_ENDPOINT_URL`, `aws.endpointUrl` and a profile's `endpoint_url`), so a source without an
     endpoint only ever reaches AWS's own endpoints for its region, over HTTPS. Those are worked
     out as before (FIPS, dual-stack, every partition); the allowed-hosts list still names only
     `s3.<region>.amazonaws.com` for them, a separate gap.
   - **Files in a folder and the sample data**: Kilnmark opens no network connection of its own. A
     root that is a mounted network share (NFS, SMB, a UNC path) crosses the network as the
     operating system mounted it: its encryption is the administrator's, who sets the roots
     (`kilnmark.files.roots`).
   - **Generic JDBC** is refused: Kilnmark cannot tell how an arbitrary driver encrypts, and an
     administrator's word for it would be a hole in a control meant to need no one's word.
3. **Checked where hosts are checked.** The allowed-hosts policy (`HostPolicy`) already runs when a
   source is added, edited, imported, tested or listed, and again just before every scan, quality
   run, preview, pipeline read or write, document read and flow `sql` node connects. The TLS
   requirement is part of the same policy, so every one of those places checks it, and a source
   saved before the setting was turned on stops at once, its jobs saying which setting to change.

## Consequences

- With the setting on, a customer can show that Kilnmark never moves their data or credentials
  over a plain-text connection, whoever adds the source.
- `require` (PostgreSQL), `required` (MySQL), Oracle's native encryption, and SQL Server with
  `trust_server_certificate` (or `trustServerCertificate` or `hostNameInCertificate` in its URL)
  encrypt without checking the server's certificate, so an attacker on the path could still stand
  in for the server. Checking certificates is the next step: `verify-full` and TCPS
  already do, and a later setting could require them.
- Generic JDBC sources cannot be used while the setting is on. Databases that need one get a
  connector of their own (Snowflake, Vertica and Exasol are on the backlog).
- The setting reads each source's own settings and the drivers' own parsers. It does not ask the
  server whether a session is encrypted, as the check on Kilnmark's own database does: a
  connection that demands encryption fails rather than going plain, and asking would cost a query
  on every connection to every kind of database.
