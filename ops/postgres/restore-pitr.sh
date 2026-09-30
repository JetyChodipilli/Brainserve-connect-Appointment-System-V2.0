#!/bin/sh
set -eu

if [ "$#" -ne 3 ]; then
  echo "Usage: $0 <base-backup-directory> <empty-target-directory> <UTC-target-time>" >&2
  echo "Example target time: 2026-07-21 10:30:00+00" >&2
  exit 64
fi

base_backup="$1"
target="$2"
target_time="$3"

case "${target}" in
  /|/home|/root|"${HOME:-/nonexistent}")
    echo "Refusing unsafe target directory: ${target}" >&2
    exit 64
    ;;
esac

[ -f "${base_backup}/base.tar.gz" ] || { echo "base.tar.gz is missing" >&2; exit 66; }
[ -f "${base_backup}/SHA256SUMS" ] || { echo "SHA256SUMS is missing" >&2; exit 66; }
[ -d "${target}" ] || mkdir -p "${target}"
[ -z "$(find "${target}" -mindepth 1 -maxdepth 1 -print -quit)" ] || {
  echo "Target directory must be empty" >&2
  exit 73
}

(cd "${base_backup}" && sha256sum -c SHA256SUMS)
tar -xzf "${base_backup}/base.tar.gz" -C "${target}"
mkdir -p "${target}/pg_wal"
if [ -f "${base_backup}/pg_wal.tar.gz" ]; then
  tar -xzf "${base_backup}/pg_wal.tar.gz" -C "${target}/pg_wal"
fi

cat >> "${target}/postgresql.auto.conf" <<EOF
restore_command = 'cp /var/lib/postgresql/wal-archive/%f %p'
recovery_target_time = '${target_time}'
recovery_target_action = 'promote'
EOF
touch "${target}/recovery.signal"

echo "Recovery directory prepared. Start PostgreSQL with this directory and the WAL archive mounted read-only."
