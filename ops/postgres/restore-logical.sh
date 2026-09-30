#!/bin/sh
set -eu
umask 077
if [ "$#" -ne 2 ]; then echo "Usage: $0 <backup-directory> <new-brainserve_restore_database>" >&2; exit 64; fi
backup="$1"
target="$2"
case "${target}" in
  brainserve_restore_?*) ;;
  *) echo "Restore target must start with brainserve_restore_" >&2; exit 64 ;;
esac
case "${target}" in *[!a-z0-9_]*) echo "Unsafe restore database name" >&2; exit 64 ;; esac
[ "${#target}" -le 63 ] || { echo "Restore name exceeds PostgreSQL limit" >&2; exit 64; }
[ -f "${backup}/database.dump" ] && [ -f "${backup}/SHA256SUMS" ] || { echo "Backup files missing" >&2; exit 66; }
# Verify exactly our one dump, never filenames supplied by an untrusted manifest.
expected="$(cat "${backup}/SHA256SUMS")"
actual="$(cd "${backup}" && sha256sum database.dump)"
[ "${expected}" = "${actual}" ] || { echo "Backup checksum mismatch" >&2; exit 65; }
pg_restore --list "${backup}/database.dump" >/dev/null
createdb "${target}" # Refuse an existing target; never delete or overwrite it.
pg_restore --exit-on-error --no-owner --no-acl --dbname="${target}" "${backup}/database.dump"
echo "ISOLATED_RESTORE_COMPLETED"
