# 0003 PostgreSQL with Apache AGE and pgvector as the only store

Status: Accepted (22 October 2026)

## Decision

PostgreSQL 18 is the single system of record:

- **Apache AGE** for the metadata graph, queried with openCypher
- **pgvector** (HNSW indexes) for embeddings
- **Full-text search plus pg_trgm** for keyword search
- **A queue table** (`SELECT … FOR UPDATE SKIP LOCKED`) for jobs, and an outbox with
  LISTEN/NOTIFY for events

Graph, search and vector access sit behind `GraphStore`, `SearchIndex` and `VectorIndex`
interfaces so another backend (Neo4j, Lucene, a vector database) can be added later.

## Why

One store keeps installs to two services, stays transactionally consistent, and needs no
second index to rebuild after a restore. Metadata volumes are small enough for PostgreSQL.

## Consequences

- Customers bringing their own PostgreSQL must allow the AGE and pgvector extensions.
- Neo4j is not bundled (GPLv3/AGPLv3); it can only be an optional adapter.
