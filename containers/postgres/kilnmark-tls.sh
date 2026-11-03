#!/bin/sh
# Starts PostgreSQL with TLS on (ADR 0032). The first start makes a self-signed certificate for this
# database, kept in the data volume beside the data, so Kilnmark's connection is encrypted from the
# first day; replace server.crt and server.key in /var/lib/postgresql/tls with your own to have them
# verified. Then hands over to the image's own entrypoint, adding the TLS settings to whatever
# command the container was given.
set -e
dir=/var/lib/postgresql/tls
if [ ! -s "$dir/server.key" ] || [ ! -s "$dir/server.crt" ]; then
  mkdir -p "$dir"
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -subj "/CN=kilnmark-postgres" -addext "subjectAltName=DNS:postgres,DNS:localhost,IP:127.0.0.1" \
    -keyout "$dir/server.key" -out "$dir/server.crt" 2>/dev/null
fi
# PostgreSQL refuses a key anyone but its owner can read.
chown -R postgres:postgres "$dir" 2>/dev/null || true
chmod 700 "$dir"
chmod 600 "$dir/server.key"
exec docker-entrypoint.sh "$@" -c ssl=on -c "ssl_cert_file=$dir/server.crt" -c "ssl_key_file=$dir/server.key" \
  -c ssl_min_protocol_version=TLSv1.2
