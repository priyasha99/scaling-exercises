#!/bin/bash
# =============================================================
# PostgreSQL Primary Initialization Script
# =============================================================
# Runs once when the primary database is first created.
# Sets up the replication user and permissions needed for
# streaming replication to replicas.
#
# This script is mounted into /docker-entrypoint-initdb.d/
# which PostgreSQL's Docker image runs automatically on first start.
# =============================================================

set -e

echo "=== Setting up replication user and permissions ==="

# Create replication user
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    -- Create a dedicated replication user
    -- REPLICATION privilege allows streaming WAL data
    -- LOGIN allows connecting (vs roles that can't login)
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'repl_password';

    -- Grant connect permission on the database
    GRANT CONNECT ON DATABASE productdb TO replicator;
EOSQL

# Add replication entry to pg_hba.conf
# This allows the replicator user to connect for streaming replication
# from any Docker network IP (0.0.0.0/0 is fine within Docker's isolated network)
echo "host replication replicator 0.0.0.0/0 md5" >> "$PGDATA/pg_hba.conf"
echo "host all app 0.0.0.0/0 md5" >> "$PGDATA/pg_hba.conf"

echo "=== Replication setup complete ==="
