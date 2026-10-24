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
