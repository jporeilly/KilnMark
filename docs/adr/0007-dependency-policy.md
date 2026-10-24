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
