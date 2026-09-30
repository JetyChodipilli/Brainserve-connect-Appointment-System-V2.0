#!/bin/sh
set -eu

mkdir -p /var/lib/postgresql/wal-archive
chown -R postgres:postgres /var/lib/postgresql/wal-archive

exec docker-entrypoint.sh postgres \
  -c wal_level=replica \
  -c archive_mode=on \
  -c archive_timeout=60s \
  -c "archive_command=test ! -f /var/lib/postgresql/wal-archive/%f && cp %p /var/lib/postgresql/wal-archive/%f" \
  -c max_wal_senders=5 \
  -c wal_compression=on
