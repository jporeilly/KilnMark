# HTTPS and TLS

## HTTPS for Kilnmark

Kilnmark should be reached over HTTPS only, and sign-in (with the bundled Keycloak or your own
identity provider) works only over HTTPS, or on `localhost`, which browsers treat as secure. Install
asks for TLS, and refuses a host other than `localhost` without it:

- **`tls=proxy`: a proxy in front ends TLS** (your load balancer or reverse proxy). Kilnmark stays
  on plain HTTP behind it, reachable only from the proxy, and is reached as `https://<host>`.
  Install sets `KILNMARK_HTTP_HSTS=always`, so browsers are told to keep to HTTPS, and
  `KILNMARK_OIDC_BEHIND_TLS_PROXY=true`, so the redirect URI the identity provider sees is
  `https://`.
- **`tls=on`: Kilnmark ends TLS itself.** Give your certificate (with its chain) and its private key,
  as PEM files (`tls_cert`, `tls_key`). Install copies them as `server.crt` and `server.key` into
  `certs/https` in the data folder, which compose mounts read-only, and sets
  `KILNMARK_TLS_CERT_FILE`, `KILNMARK_TLS_KEY_FILE` and `KILNMARK_HTTP_PLAIN=redirect`. Kilnmark
  then answers HTTPS on `https_port` (443 in production, as browsers that have seen HTTPS keep to
  it on every port; rootless Podman needs `sysctl net.ipv4.ip_unprivileged_port_start=443` for a
  port below 1024), with TLS 1.3 or 1.2 only, and sends plain HTTP on `port` to HTTPS. Only the
  container's own health check still uses plain HTTP.
- **`tls=off`**, on one machine only (`host=localhost`).

To change it later, `configure tls=on tls_cert=FILE tls_key=FILE` (or `tls=proxy`, or a new
certificate the same way): it copies the files and restarts Kilnmark. To renew a certificate,
run the same `configure` with the new files, or put them in `certs/https` yourself and run
`restart`.

Either way, every response carries the security headers in
[ADR 0031](../../adr/0031-https-and-security-headers.md): a Content-Security-Policy allowing only
Kilnmark's own files, no framing by other sites, no referrer, and HSTS over HTTPS.

## TLS to Kilnmark's own database

Kilnmark reaches its database over TLS only, and does not start otherwise
([ADR 0032](../../adr/0032-tls-to-kilnmarks-own-database.md)).

- **The bundled PostgreSQL** makes its own certificate on its first start and speaks TLS;
  `compose.yaml` connects with `sslmode=require`. Nothing to do. To have the certificate verified,
  put one from your authority as `server.crt` and `server.key` in `/var/lib/postgresql/tls` in its
  volume (owned by uid 999, the key readable by its owner only, `chmod 600`), and use `verify-full`
  as below.
- **Your own PostgreSQL** (a managed or shared server): turn TLS on in it, and set
  `QUARKUS_DATASOURCE_JDBC_URL` in `config/.env` to its address with
  `?sslmode=verify-full&sslrootcert=/path/to/ca.pem` (mount the CA certificate into the container).
  `sslmode=require` also works, but encrypts without checking who answers.

If the server stops with "does not demand TLS" or "not encrypted", its log says which and what to
change.
