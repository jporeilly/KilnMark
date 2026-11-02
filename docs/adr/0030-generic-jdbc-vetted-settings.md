# 0030 Generic JDBC takes only settings Kilnmark has vetted; isolated drivers later

Status: Accepted (1 November 2026)

## Context

Generic JDBC lets an admin register any vendor's JDBC driver and connect sources through it. A
driver runs inside the Kilnmark server, with its files, its network and its identity. The rule since
ADR 0027 is that no source, an admin's included, may use a driver to open a database inside the
server, load code into it, read its files or sign in as it: banks running Kilnmark must be able to
say that no administrator can turn the platform into a way round its own controls.

Until now that rule was a block-list: URL forms (embedded H2, SQLite, DuckDB, Derby, HSQLDB, OCI,
Firebird embedded and native, Redshift IAM, and more) and property names (files, paths, classes,
factories, interceptors, plugins, authentication, Kerberos, JAAS, GSS, certificates and more) that
no admin may allow. Four independent reviews in a row each found more: properties that load a class
(MySQL `propertiesTransform`, Trino `dnsResolver`), look a name up in JNDI (Db2
`clientRerouteServerListJNDIName`), run a command (Teradata `BROWSER`), write logs (Snowflake
`tracing`) or sign in as the server (Teradata `LOGMECH=KRB5`). A block-list of names across every
vendor's driver will not be complete.

Other tools have learnt the same:

- **Apache Druid** keeps an admin's allow-list of JDBC properties, TLS only by default, and has
  still had two CVEs where a crafted MySQL URL got properties past it (CVE-2021-26919,
  CVE-2024-45537): an allow-list holds only with strict parsing of the URL.
- **Metabase**'s pre-authentication remote code execution (CVE-2023-38646) came through testing a
  crafted connection string; H2's `INIT=RUNSCRIPT` was the first way tried.
- **Apache Superset** refuses SQLite, and a different spelling of the driver's name got past it
  (CVE-2023-39265).
- **Airbyte** runs every connector in a container of its own, away from the platform.

Some drivers also read and write the machine's files for ordinary SQL, which no setting governs:
Snowflake (`PUT`, `GET`), Vertica (`COPY ... FROM LOCAL`), Exasol (`IMPORT`/`EXPORT ... LOCAL`), and
MySQL and MariaDB when a server asks for `LOAD DATA LOCAL`.

## Decision

1. **Only vetted settings.** A registered driver may be allowed only settings on Kilnmark's vetted
   list (`RegisteredDriver.VETTED`): time limits (names ending in `timeout`, with or without a
   unit), TLS, characters, database, schema, catalog, warehouse and role, the application's name,
   fetch and batch sizes, compression, time zones, keep-alive, failover servers (whose hosts are
   checked as the URL's), and a few named databases' harmless settings (`readonly`, `mode`,
   `create`, `tmode`, `informixserver`, SQL Server's `instancename`, which names an instance on the
   URL's own host). Anything else is refused at registration. The block-list stays as a second
   check that no list overrides, and the URL is parsed strictly as before (no quotes, encoding or
   settings the list does not hold).
2. **What the URL opens is checked first**, before its settings, so a URL refused for what it opens
   says so whatever the driver allows.
3. **Drivers that move files for SQL are refused**: Snowflake, Vertica and Exasol URLs. They get
   connectors of their own when they are needed, with those statements refused.
4. **MySQL and MariaDB never load local files**: on every connection whose URL is a MySQL,
   MariaDB or SingleStore one, Kilnmark sets `allowLoadLocalInfile`, `allowLocalInfile`,
   `allowUrlInLocalInfile` and `autoDeserialize` to false, whatever the source says. Other drivers'
   connections are left as they are, as those names mean nothing to them.
5. **Next (roadmap): drivers in a process of their own.** Generic JDBC drivers run in a separate,
   locked-down process with no access to the server's files, credentials or identity, only to the
   network hosts the source names, and talk to the server over a narrow channel. Then any driver's
   own settings can be allowed, as Airbyte allows any connector. Until then, the vetted list grows
   as customers need settings, each one checked.

## Consequences

- Banks can be told that Generic JDBC cannot be used, by anyone, to reach the Kilnmark server's
  files, code or identity, and why each refused setting is refused.
- An admin cannot switch on an unusual driver's own setting until Kilnmark vets it. Registrations
  saved before this whose lists hold unvetted names are no longer used, and the server log says
  which; they are registered again without them.
- Snowflake, Vertica and Exasol need connectors of their own.
