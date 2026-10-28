# 0007 Libraries yes, platforms no

Status: Accepted (22 October 2026)

## Decision

- A dependency that is a **JAR or a database extension** is welcome.
- One that **runs as its own process** needs a decision record.
- A **platform with its own storage, scheduler or UI** is out.

Adopted: Apache Arrow, Parquet, ORC, Avro, DataSketches, AGE, Commons, Tika (v0.2), Calcite (v0.5).
Optional plugins only: Iceberg, Hadoop client, Kafka client.
Avoided: Apache Atlas, Kafka broker, Airflow, NiFi, Solr/OpenSearch, Spark/Flink clusters,
Ranger, Superset, Apache Hop.

## Why

The product must stay two services on one VM. Each extra platform is something every customer
has to install, secure and upgrade.

## Consequences

- Every new dependency's licence is checked in CI; GPL and AGPL are blocked.

## Licence check (added 24 October 2026)

`./gradlew release` runs `:server:checkLicense` over everything the server ships and fails on a
licence not listed in `config/allowed-licenses.json`: Apache 2.0, MIT, MIT-0, BSD (0, 2 and 3
clause), Eclipse Distribution 1.0, public domain, and Eclipse Public License 2.0. EPL 2.0 is there
only for the Jakarta and Parsson APIs that Quarkus brings, which are dual-licensed (EPL 2.0 or GPL
2.0 with the Classpath Exception) and shipped unmodified. Anything else needs a decision record.
The generated `THIRD-PARTY-NOTICES.md` ships in every release.

## LGPL for the MariaDB driver (added 25 October 2026)

The MySQL and MariaDB connector needs a JDBC driver. MySQL's own, Connector/J, is GPL 2.0 with
the Universal FOSS Exception, which does not fit a Business Source License product, so it is not
used, not even in tests. MariaDB Connector/J speaks to MySQL 5.7, 8.x and 9.x as well as MariaDB
and is LGPL 2.1 or later, which allows a proprietary or source-available program to use the
library as long as the library itself stays free: it is shipped unmodified, as its own jar
(`lib/main/org.mariadb.jdbc.mariadb-java-client-<version>.jar` in the server's folder), so anyone
can replace it with another build, and its licence and source location are listed in
`THIRD-PARTY-NOTICES.md`.

`config/allowed-licenses.json` therefore allows `LGPL-2.1-or-later` **for
`org.mariadb.jdbc:mariadb-java-client` only** (the entry names the module). LGPL in general is
still not allowed: another LGPL library needs its own decision. Kilnmark never changes the
driver's code and does not copy any of it; it calls its public API (including
`setLocalInfileInputStream`, to bulk-load from memory). The driver's optional dependencies
(Waffle, the AWS SDK, Bouncy Castle, SLF4J) are not shipped.

## Drivers we cannot bundle are supplied by the customer (added 28 October 2026)

Some databases' JDBC drivers have licences that do not let Kilnmark ship them. Oracle's
(`com.oracle.database.jdbc:ojdbc11`) is under the Oracle Free Use Terms and Conditions: it may be
redistributed unmodified only if the end users are charged no fees for using it, which a product
sold beyond its free tier cannot promise. So these drivers are never in a release: the customer
installs them, and the connector loads them from the **drivers folder**
(`kilnmark.drivers.dir`; in the container, `/var/lib/kilnmark/drivers` in the data volume),
as [docs/design/drivers.md](../design/drivers.md) describes:

- Each connector that needs one declares it: Maven coordinates, a pinned version, the jar's
  SHA-256, the licence and its link, and the driver class.
- An admin accepts the licence under **Settings › Drivers**, on the organisation's behalf; the
  acceptance is recorded, and the server downloads that exact version from Maven Central (or the
  organisation's mirror, `kilnmark.drivers.maven-url`) and keeps it only if the checksum matches.
  A server without internet access (`kilnmark.drivers.download=false`) says which file to put in
  the folder.
- The verified jar is loaded in a class loader of its own, which sees only the JDK; nothing else
  in the folder is ever read. The connector uses only the standard JDBC interfaces.
- The driver is a test-only dependency of the build. `./gradlew release` checks that no such
  driver reaches the release folder (`checkNoCustomerDrivers`), and `checkLicense` never sees it.

This is not an exception to the licence rules above: nothing under these licences ships. A driver
whose licence allows bundling (`bundleable` in its declaration) still goes through `checkLicense`
like any other dependency.
