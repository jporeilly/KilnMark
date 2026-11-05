Kilnmark's own HTTPS certificate goes here, when Kilnmark ends TLS itself (INSTALL.md, "HTTPS"):

  server.crt   the certificate, with its chain, as PEM
  server.key   its private key, as PEM

compose.yaml mounts this folder read-only at /etc/kilnmark/tls, or the folder KILNMARK_CERTS_DIR
names (an install made by kilnmark.sh or kilnmark.cmd uses certs/https in its install root). Then
set KILNMARK_TLS_CERT_FILE=/etc/kilnmark/tls/server.crt and
KILNMARK_TLS_KEY_FILE=/etc/kilnmark/tls/server.key in .env. With no certificate, Kilnmark serves
plain HTTP for a proxy in front to end TLS.
