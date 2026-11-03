# 0031 HTTPS on Kilnmark itself, and the security headers every response carries

Status: Accepted (3 November 2026)

## Context

The compliance map ([compliance.md](../product/compliance.md), "Encryption in transit") puts HTTPS on
Kilnmark, HSTS and security headers next after the audit log: NYDFS 500.15, DORA and NIST 800-53
SC-8 want data encrypted in transit, and a bank's security review runs a scanner that checks a web
application's response headers before anything else. Until now Kilnmark served plain HTTP on 8780
and sent none of those headers, leaving TLS to a proxy in front of it and every browser defence off.

## Decision

1. **Security headers on every response**, from one Vert.x filter (`SecurityHeaders`), API and
   error responses included:
   - `Content-Security-Policy`: `default-src 'self'`; scripts, styles, fonts and connections from
     Kilnmark only, with no inline script or style; images also from `data:` and `blob:` (logos and
     downloads); `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`, and
     `frame-ancestors 'none'`. The page's one inline script (the theme before first paint) moved to
     `/theme-boot.js`, so the policy needs no hash or nonce.
   - `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` (for browsers without
     `frame-ancestors`), `Referrer-Policy: no-referrer`, a `Permissions-Policy` refusing camera,
     microphone, location, payment and USB, and `Cross-Origin-Opener-Policy` and
     `Cross-Origin-Resource-Policy` set to `same-origin`.
   - **One exception**: the API's own page, `/api/docs` (Swagger UI), starts itself with inline
     script and style, so its policy adds `'unsafe-inline'` for those two, and only there. It shows
     the API's description; calls from it take a token typed into it. An installation that does
     not want it turns it off (`QUARKUS_SWAGGER_UI_ENABLE=false`).
2. **HSTS** (`max-age` a year) on responses whose connection is TLS. Not `includeSubDomains`:
   Kilnmark often lives under a bank's shared domain, where that would force HTTPS on services it
   does not own. Behind a proxy that ends TLS and passes plain HTTP on, `kilnmark.http.hsts=always`
   sends it on every response; `never` turns it off. Browsers ignore HSTS over plain HTTP, so
   `when-https` is the default. HSTS applies to every port of a host name, so after a browser has
   seen it, `http://host:8780` becomes `https://host:8780` and fails: publish HTTPS on 443 in
   production (`KILNMARK_HTTPS_PORT=443`) and give people that address.
3. **HTTPS on Kilnmark itself**, for installations without a proxy: given a certificate (with its
   chain) and its private key as PEM files (`KILNMARK_TLS_CERT_FILE`, `KILNMARK_TLS_KEY_FILE`),
   Quarkus serves HTTPS on 8443, TLS 1.3 and 1.2 only, with forward-secret AEAD cipher suites
   only (no CBC, SHA-1 MACs or finite-field Diffie-Hellman, which a TLS scan marks weak). Plain
   HTTP on 8780 from other machines is served by default, or redirected to HTTPS (308) or refused
   (403) with `KILNMARK_HTTP_PLAIN`; plain HTTP from the server's own loopback is always served, for
   the container's health check. In a container that is the only local traffic: a browser or proxy
   on the host reaches a published port through the container network's gateway, so it counts as
   another machine. Kilnmark's filter decides this on the TCP connection itself, never on
   `X-Forwarded-*`, and not Quarkus's own setting, which would also turn away the health check. A
   redirect keeps the host the browser asked for and goes to `KILNMARK_HTTP_HTTPS_PORT` (the
   published port). `KILNMARK_HTTP_PLAIN` and `KILNMARK_HTTP_HSTS` take only their listed values;
   any other stops the server at start, so a typing mistake never leaves plain HTTP served. PEM
   because that is what a bank's certificate authority issues; a PKCS12 store works through
   Quarkus's own settings too.

## Consequences

- A header scan of Kilnmark finds every header it looks for, with a policy that allows no inline
  script on Kilnmark's own pages.
- Nothing from another origin can be loaded by the UI: a feature that needs one (a map tile
  server, say) must be added to the policy deliberately.
- Behind a proxy, the operator sets `KILNMARK_HTTP_HSTS=always`; INSTALL.md says so.
- A few responses never reach the filter and carry no headers: requests Vert.x refuses before
  routing (a malformed request line, a URI or headers too long) and bodies over the size limit.
  They carry no page or data.
- Next in the compliance order: TLS required to Kilnmark's own database and a setting to refuse
  sources without TLS, then the supply chain (SBOM, signed releases, scanning, `SECURITY.md`).
