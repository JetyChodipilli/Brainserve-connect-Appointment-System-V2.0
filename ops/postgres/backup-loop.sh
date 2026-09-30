#!/bin/sh
set -eu

: "${PGHOST:=postgres}"
: "${PGPORT:=5432}"
: "${PGUSER:=brainserve}"
: "${POSTGRES_BACKUP_INTERVAL_SECONDS:=86400}"
: "${POSTGRES_BACKUP_RETENTION_DAYS:=14}"
: "${POSTGRES_WAL_RETENTION_DAYS:=35}"

if [ "${POSTGRES_WAL_RETENTION_DAYS}" -lt "${POSTGRES_BACKUP_RETENTION_DAYS}" ]; then
  echo "POSTGRES_WAL_RETENTION_DAYS must be at least POSTGRES_BACKUP_RETENTION_DAYS" >&2
  exit 1
fi

while true; do
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  destination="/backups/base-${stamp}"
  mkdir -p "${destination}"
  if pg_basebackup --host="${PGHOST}" --port="${PGPORT}" --username="${PGUSER}" \
      --pgdata="${destination}" --format=tar --gzip --wal-method=stream --checkpoint=fast; then
    sha256sum "${destination}"/*.tar.gz > "${destination}/SHA256SUMS"
    find /backups -mindepth 1 -maxdepth 1 -type d -name 'base-*' \
      -mtime "+${POSTGRES_BACKUP_RETENTION_DAYS}" -exec rm -rf -- {} +
    find /var/lib/postgresql/wal-archive -maxdepth 1 -type f \
      \( -name '[0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F]' \
         -o -name '*.backup' -o -name '*.history' \) \
      -mtime "+${POSTGRES_WAL_RETENTION_DAYS}" -delete
  else
    rm -rf -- "${destination}"
  fi
  sleep "${POSTGRES_BACKUP_INTERVAL_SECONDS}"
done
