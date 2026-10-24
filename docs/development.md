# Development guide

## Platforms

Kilnmark servers run on **Linux** (see [ADR 0002](adr/0002-linux-on-prem-one-image.md)).
You can develop on Windows, macOS or Linux: the server is plain Java, and the database runs in
a container.

| Tool | Version | Needed for |
| --- | --- | --- |
| Java (JDK) | 21 | Building and running the server |
| Git | any recent | Source control |
| Node.js | 22 or later | The web UI |
| Docker Desktop (WSL 2 on Windows) or Docker Engine on Linux | current | PostgreSQL and the full stack |

Gradle is not needed: use the wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Build and test

```
./gradlew build                 # compile, test, write output to dist/build
./gradlew :server:quarkusDev    # live-reload server on http://localhost:8080
./gradlew release               # assemble dist/release/kilnmark-<version>
```

Useful endpoints in dev mode:

- `GET /api/v1/info`: product, version and roles
- `GET /q/health`: health checks
- `GET /q/openapi`: the API specification

## Run the stack

```
docker compose up --build       # PostgreSQL (AGE, pgvector) and the Kilnmark server
```

The server applies database migrations on start. To run separate roles, set `KILNMARK_ROLES`,
for example `KILNMARK_ROLES=worker`.

## Web UI

```
cd ui
npm install
npm run dev        # http://localhost:5173, calls the server on :8080
npm run build      # writes dist/ui
```

## Rules

- Versions go up by exactly 0.0.1 per release.
- Run the full regression suite before every package.
- Anything hard to reverse gets a [decision record](adr/README.md).
- Code adapted from Kettle 10.2 is logged in [provenance.md](legal/provenance.md).
