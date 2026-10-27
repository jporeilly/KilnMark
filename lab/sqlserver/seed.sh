#!/bin/bash
# One-shot seed for the lab's SQL Server (service lab-sqlserver-init in lab/compose.yaml).
# Waits for the server, then loads labdb from labdb.sql once. If labdb is already loaded it does
# nothing; if an earlier load stopped half way, it drops labdb and loads it again.
# The sa password comes from SQLCMDPASSWORD, which sqlcmd reads itself.
set -euo pipefail

SQLCMD=/opt/mssql-tools18/bin/sqlcmd
SERVER="${LAB_SQLSERVER_HOST:-lab-sqlserver},1433"

# -C trusts the lab server's self-signed certificate; -b stops at the first error.
q() { "$SQLCMD" -S "$SERVER" -U sa -C -b -l 5 -h -1 -W "$@"; }

echo "Waiting for SQL Server at $SERVER ..."
ready=0
for _ in $(seq 1 90); do
    if q -Q "SET NOCOUNT ON; IF DATABASEPROPERTYEX(N'model', 'Status') <> 'ONLINE' RAISERROR('starting', 16, 1);" \
        >/dev/null 2>&1; then
        ready=1
        break
    fi
    sleep 5
done
if [ "$ready" -ne 1 ]; then
    echo "SQL Server did not answer within about 8 minutes. Check its log: lab/lab.sh logs sqlserver" >&2
    exit 1
fi

exists=$(q -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM sys.databases WHERE name = N'labdb';" | tr -d '[:space:]')
if [ "$exists" = "1" ]; then
    seeded=$(q -d labdb -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM sys.extended_properties WHERE class = 0 AND name = N'kilnmark_lab_seed';" | tr -d '[:space:]')
    if [ "$seeded" = "1" ]; then
        echo "labdb is already loaded: nothing to do."
        exit 0
    fi
    echo "labdb exists but an earlier load did not finish: dropping it and loading it again."
    q -Q "ALTER DATABASE labdb SET SINGLE_USER WITH ROLLBACK IMMEDIATE; DROP DATABASE labdb;"
fi

echo "Loading labdb ..."
# -I: QUOTED_IDENTIFIER ON, which filtered indexes, persisted computed columns and xml need.
"$SQLCMD" -S "$SERVER" -U sa -C -b -I -i /seed/labdb.sql
echo "labdb is ready: connect as kiln_reader on localhost:14330."
