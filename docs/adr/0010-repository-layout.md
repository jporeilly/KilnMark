# 0010 Docs in /docs, all build output in /dist

Status: Accepted (24 October 2026)

## Decision

- All documentation lives under `docs/`. The root keeps only the short README, LICENSE and NOTICE
  that tools and GitHub expect there.
- All build output goes to `dist/`, which is never committed:
  - `dist/build/<module>`: Gradle output per module
  - `dist/ui`: the built web UI
  - `dist/release/kilnmark-<version>`: the assembled release
- `./gradlew clean` and deleting `dist/` are equivalent.
