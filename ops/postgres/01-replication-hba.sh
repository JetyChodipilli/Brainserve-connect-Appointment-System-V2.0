#!/bin/sh
set -eu

# pg_basebackup uses PostgreSQL's replication protocol from the isolated backup
# container. Password authentication is still required through Docker secrets/.env.
echo "host replication all all scram-sha-256" >> "${PGDATA}/pg_hba.conf"
