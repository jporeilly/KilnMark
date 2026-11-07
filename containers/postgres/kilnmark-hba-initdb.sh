#!/bin/sh
# A new data folder: keep the Starter Pack's login to its own database from the first start.
/usr/local/bin/kilnmark-hba.sh "$PGDATA/pg_hba.conf"
