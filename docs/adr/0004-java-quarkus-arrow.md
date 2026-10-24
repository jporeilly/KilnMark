# 0004 Java 21, Quarkus and Apache Arrow

Status: Accepted (22 October 2026)

## Decision

- **Java 21** for the Kiln engine and the server; virtual threads give each step its own worker.
- **Quarkus** for the server: lower memory and faster start than Spring Boot, Kubernetes-ready.
- **Apache Arrow** columnar batches between steps (sprint 2), with a fallback to row objects
  behind the same interface if the spike in sprint 0 shows problems.
- **Python** only in an optional AI sidecar from v0.4, connected over gRPC and Arrow.
  *Superseded by [0012](0012-ai-runtime.md): AI runs in the Java server from v0.1, and the sidecar
  is used only if a model has no HTTP-served route.*
- **Gradle** (Kotlin DSL) multi-module build with the wrapper checked in.

## Why

JDBC drivers and the big-data file formats are Java-first, which makes connector breadth cheap.
Arrow makes in-stream profiling fast and passes data to Python without copying.
