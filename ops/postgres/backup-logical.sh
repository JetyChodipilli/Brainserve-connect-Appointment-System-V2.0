#!/bin/sh
set -eu
umask 077
if [ "$#" -ne 1 ]; then echo "Usage: $0 <new-backup-directory>" >&2; exit 64; fi
destination="$1"
mkdir "${destination}" # Refuse overwriting any existing backup.
trap 'rm -f "${destination}/database.dump.partial"' EXIT HUP INT TERM
pg_dump --format=custom --no-owner --no-acl --dbname="${PGDATABASE:-brainserve}" > "${destination}/database.dump.partial"
pg_restore --list "${destination}/database.dump.partial" >/dev/null
mv "${destination}/database.dump.partial" "${destination}/database.dump"
(cd "${destination}" && sha256sum database.dump > SHA256SUMS)
echo "LOGICAL_BACKUP_VERIFIED"
