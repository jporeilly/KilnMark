# 0002 Linux servers, on-prem first, one image with roles

Status: Accepted (22 October 2026)

## Decision

- Kilnmark runs on **Linux** servers (x86_64 first, arm64 later): Ubuntu or RHEL-family VMs.
- **On-prem first.** v0.1 targets one VM running two containers with Podman or Docker
  ([ADR 0011](0011-container-runtime.md)):
  PostgreSQL and the Kilnmark server. Installs work offline from a bundle.
- **One image, many roles.** The same image starts as `api`, `scheduler`, `worker`, or all three
  (`KILNMARK_ROLES`). Small installs run every role in one container; Kubernetes runs each role
  as its own deployment from v0.5.
- Development works on Windows, macOS or Linux because the server is plain Java; the full stack
  runs locally under Podman (a WSL 2 Podman machine on Windows) or on a Linux VM.

## Why

On-prem buyers run Linux servers; containers keep installs identical across sites. One image
avoids a distributed system that only exists in production.

## Consequences

- No message broker or service mesh: workers pull jobs from a PostgreSQL queue.
- Every role is stateless; all state is in PostgreSQL.
- A non-container install (systemd service on the customer's own PostgreSQL) is an open question
  for v0.2. Podman Quadlet units already give container installs native systemd management.
