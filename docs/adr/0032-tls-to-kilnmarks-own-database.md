# 0032 Kilnmark's own database is reached over TLS, or the server does not start

Status: Accepted (3 November 2026)

## Context

The compliance map ([compliance.md](../product/compliance.md), "Encryption in transit") asks for TLS
"required (not just offered) to the server's own database". Kilnmark's database holds the catalogue,
profiles with sample values, encrypted source passwords, API token hashes and the audit log. Until
now the bundled PostgreSQL ran without TLS and Kilnmark connected with the driver's default,
`sslmode=prefer`, which uses TLS only when the server offers it and otherwise carries on in plain
text without a word. A bank that points Kilnmark at its own managed PostgreSQL had nothing to stop a
plain-text connection either.

## Decision

1. **The bundled PostgreSQL speaks TLS.** Its image starts through `kilnmark-tls.sh`, which on the
   first start makes a self-signed certificate (P-256, ten years) in the data volume, beside the
   data, and starts PostgreSQL with `ssl=on` and TLS 1.2 at least. Volumes made before this get a
   certificate on their next start, with their data as it was. An operator replaces `server.crt` and
   `server.key` in `/var/lib/postgresql/tls` with ones from their own authority to have them
   verified.
2. **Kilnmark demands TLS**: its connection settings say `sslmode=require` (the compose files and the
   default URL), or `verify-full` with the database's CA (`sslrootcert`) where the certificate can be
   verified.
3. **The server does not start otherwise** (`DatabaseTls`, `kilnmark.database.require-tls`, on by
   default): the settings must demand TLS (`require`, `verify-ca` or `verify-full`), and the session
   must be encrypted as PostgreSQL itself reports it (`pg_stat_ssl`). Both: `prefer` falls back without
   a word, and one encrypted session says nothing of the next, while a session check catches
   anything in between that misreports. The mode is worked out by the PostgreSQL driver's own parser
   (`Driver.parseURL`, `SslMode.of`), never a reading of Kilnmark's own: the driver lets the URL win
   over the driver property, matches the key `sslmode` exactly (`SSLMODE` is ignored) and does not
   decode it, and an own reading that differed would pass a connection the driver makes with
   `prefer`. What the driver is given is the pool's own (Agroal's URL and driver properties, plus
   whatever each security provider adds for each credential, in the pool's order), never
   configuration keys: Quarkus also takes the default datasource's URL as
   `quarkus.datasource."<default>".jdbc.url`, and a credentials provider can add `sslmode`, both of
   which a key lookup would miss. A provider asked again later could answer differently; it is
   trusted as the source of the database password already. The pool must open connections through
   the PostgreSQL driver itself (a DataSource class would let properties beat the URL, the other way
   round, and another driver could read `sslmode` its own way). The
   check is the first start-up step, and nothing reaches the database before it: Flyway does
   nothing at start on its own (`quarkus.flyway.migrate-at-start=false`), and the migrations run
   from the check, after it, through the checked pool. A start-up job of Flyway's (any
   `quarkus.flyway.*-at-start`) or a Flyway URL or login of its own stops the server, as Flyway
   would open connections the check never sees (with only a Flyway login, Quarkus uses the pool's
   URL without its driver properties, so `sslmode` set as a property is lost and the migration
   owner's password could cross in plain text). `kilnmark.database.require-tls=false` turns the
   check off, for a developer's own database only; the migrations still run. One thing comes
   before the check: Quarkus creates the pool at start, and with
   `quarkus.datasource.jdbc.initial-size` above 0 the pool logs in that many times at once. With a
   URL that does not demand TLS, those logins can cross in plain text before the server refuses to
   start; Kilnmark leaves `initial-size` at 0, and SCRAM (PostgreSQL's default) never sends the
   password itself.
4. **Tests run with it on**: the test database is the same image, and the tests connect with
   `sslmode=require`, so every test start passes the check.

## Consequences

- No installation of Kilnmark talks to its own database in plain text without someone turning the
  check off, which the log says at every start.
- `require` encrypts but does not verify the server: it stops eavesdropping, not an attacker who
  can impersonate the database on the network between them. Where that matters, and always for a
  database across a network the bank does not control, use `verify-full` with the CA; INSTALL.md says so.
- PostgreSQL itself still accepts plain-text logins from anywhere it can be reached (its
  `pg_hba.conf` is left as it was); Kilnmark never makes one. Both compose files therefore publish
  the database on 127.0.0.1 only, and the container network is the stack's own.
- The bundled PostgreSQL offers, for TLS 1.2, forward-secret AEAD suites only. An operator's own
  certificate and key on a read-only mount must already be owned by postgres (uid 999), the key
  0600, as the image cannot change them.
- A developer whose PostgreSQL has no TLS sees the server stop with what to do.
