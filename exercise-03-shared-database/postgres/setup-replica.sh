#!/bin/bash
# =============================================================
# PostgreSQL Replica Setup Script
# =============================================================
# This script initializes a PostgreSQL replica using streaming
# replication from the primary server.
#
# How PostgreSQL streaming replication works:
# 1. pg_basebackup copies ALL data from the primary (a full clone)
# 2. It creates a standby.signal file (tells PG to start in recovery mode)
# 3. It writes primary_conninfo to postgresql.auto.conf (connection details)
# 4. PostgreSQL starts and continuously streams WAL (write-ahead log)
#    changes from the primary, applying them locally
# 5. The replica is read-only — any write attempt returns an error
#
# The -R flag on pg_basebackup does steps 2 and 3 automatically.
# =============================================================

set -e

PRIMARY_HOST="${1:-postgres-primary}"
PGDATA="/var/lib/postgresql/data"

# If data directory already has data, skip backup and just start
if [ -f "$PGDATA/PG_VERSION" ]; then
    echo "=== Data directory already initialized, starting replica ==="
    exec su-exec postgres postgres \
        -c hot_standby=on \
        -c max_connections=50 \
        -c shared_buffers=128MB
fi

echo "=== Waiting for primary ($PRIMARY_HOST) to be ready ==="
# Use -d to specify database explicitly (otherwise pg_isready tries
# to connect to a database named after PGUSER, which doesn't exist)
until pg_isready -h "$PRIMARY_HOST" -p 5432 -d productdb; do
    echo "Primary not ready yet, waiting..."
    sleep 2
done

echo "=== Primary is ready, starting base backup ==="

# pg_basebackup: clone the primary's data directory
# -h: primary hostname
# -U: replication user (created by init-primary.sh)
# -D: destination directory
# -Fp: plain format (copy files directly)
# -Xs: stream WAL during backup (ensures consistency)
# -P: show progress
# -R: write recovery config (standby.signal + primary_conninfo)
PGPASSWORD=repl_password pg_basebackup \
    -h "$PRIMARY_HOST" \
    -U replicator \
    -D "$PGDATA" \
    -Fp -Xs -P -R

# Fix ownership — pg_basebackup ran as root, but PostgreSQL
# refuses to start as root. Change ownership to the 'postgres'
# user (UID 70 in Alpine) and start as that user.
chown -R postgres:postgres "$PGDATA"
chmod 700 "$PGDATA"

echo "=== Base backup complete, starting replica ==="

# Start PostgreSQL as the postgres user (not root)
# su-exec is Alpine's lightweight equivalent of gosu/sudo
exec su-exec postgres postgres \
    -c hot_standby=on \
    -c max_connections=50 \
    -c shared_buffers=128MB
