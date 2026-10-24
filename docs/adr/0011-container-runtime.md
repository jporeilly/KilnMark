# 0011 Podman first, any OCI runtime

Status: Accepted (24 October 2026)

## Decision

- Development uses **Podman** (Podman Desktop on Windows, with a WSL 2 Podman machine).
- Images are plain OCI images built from **Containerfiles**; `compose.yaml` follows the Compose
  specification. Both work unchanged with Podman and Docker.
- Base images are fully qualified (`docker.io/library/...`) so Podman never asks which registry
  to use.
- Containers run as a non-root user, so they work under **rootless** Podman.
- For production on RHEL-family servers, Podman **Quadlet** units (from v0.2) run Kilnmark as
  ordinary systemd services: start on boot, restart on failure, logs in journald, no daemon.
  The same images still run under Docker Compose or Kubernetes.

## Why

Podman is the default container tool on RHEL, Rocky and Alma Linux, which on-prem buyers in
regulated sectors often run. It is daemonless and rootless by default, which security teams
prefer, and needs no Docker Desktop licence.

## Consequences

- Testcontainers (integration tests) needs `DOCKER_HOST` pointed at the Podman socket; see the
  development guide.
- SELinux hosts need `:Z` on any bind mounts we add later; the current setup uses named volumes only.
- CI keeps building and testing with plain Java; image builds in CI can use Podman or Buildah.
