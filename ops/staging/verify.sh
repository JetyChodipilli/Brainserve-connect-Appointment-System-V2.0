#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after generating backend/.env. The project must
# be disposable: this drills a restore by moving only this stack's backend.
: "${RELEASE_ID:?Set RELEASE_ID to the checked-out commit}"
: "${STAGING_DOMAIN:=localhost}"
export STAGING_DOMAIN
compose=(docker compose --env-file backend/.env -f docker-compose.yml -f ops/staging/compose.yml --profile full-stack)
evidence="${STAGING_EVIDENCE_DIR:-/tmp/brainserve-staging-evidence}"
mkdir -p "${evidence}"
chmod 700 "${evidence}"
"${compose[@]}" config --quiet
"${compose[@]}" build backend frontend minio minio-init
"${compose[@]}" up -d --wait --wait-timeout 900
"${compose[@]}" cp proxy:/data/caddy/pki/authorities/local/root.crt "${evidence}/staging-ca.crt"
smoke() {
    local status
    curl --fail --silent --show-error --retry 20 --retry-connrefused --retry-delay 2 \
        --cacert "${evidence}/staging-ca.crt" "https://${STAGING_DOMAIN}:8443/" > "${evidence}/home.html"
    grep -qi 'BrainServe Connect' "${evidence}/home.html"
    status="$(curl --silent --show-error --cacert "${evidence}/staging-ca.crt" \
        --output /dev/null --write-out '%{http_code}' "https://${STAGING_DOMAIN}:8443/api/v1/dashboard/summary")"
    [ "${status}" = 401 ]
    status="$(curl --silent --show-error --cacert "${evidence}/staging-ca.crt" \
        --output /dev/null --write-out '%{http_code}' "https://${STAGING_DOMAIN}:8443/actuator/health")"
    [ "${status}" = 404 ]
}
smoke
# Stop writes before measuring/dumping. Recovery happens in a separate database.
"${compose[@]}" stop backend
"${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d brainserve -v ON_ERROR_STOP=1 -c "create table sprint1_recovery_probe(id integer primary key, evidence text not null); insert into sprint1_recovery_probe values (1, '\''zero-wait'\''), (2, '\''scoped-access'\'');"'
"${compose[@]}" cp ops/postgres/backup-logical.sh postgres:/tmp/backup-logical.sh
"${compose[@]}" cp ops/postgres/restore-logical.sh postgres:/tmp/restore-logical.sh
"${compose[@]}" exec -T postgres sh -c 'PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD" PGDATABASE=brainserve sh /tmp/backup-logical.sh /tmp/sprint1-backup'
"${compose[@]}" exec -T postgres sh -c 'PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD" sh /tmp/restore-logical.sh /tmp/sprint1-backup brainserve_restore_sprint1'
for database in brainserve brainserve_restore_sprint1; do
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atc "select version || chr(58) || checksum from flyway_schema_history where success order by installed_rank"' sh "${database}" > "${evidence}/${database}-schema.txt"
done
cmp "${evidence}/brainserve-schema.txt" "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^50:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^51:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^52:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^53:' "${evidence}/brainserve_restore_sprint1-schema.txt"
restored="$("${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d brainserve_restore_sprint1 -v ON_ERROR_STOP=1 -Atc "select string_agg(id || chr(58) || evidence, chr(44) order by id) from sprint1_recovery_probe"')"
[ "${restored}" = '1:zero-wait,2:scoped-access' ]
cat > "${evidence}/restore-compose.yml" <<'YAML'
services:
  backend:
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/brainserve_restore_sprint1
YAML
"${compose[@]}" -f "${evidence}/restore-compose.yml" up -d --no-build --wait --wait-timeout 180 backend
smoke
# Reapply the pinned release to the original staging database. No schema down-
# migration or volume deletion; this establishes the first verified fallback.
"${compose[@]}" up -d --no-build --force-recreate --wait --wait-timeout 180 backend frontend
smoke
printf 'Release: %s\nTLS and API authorization: passed\nV53 restore and application readiness: passed\nPinned release reapply: passed\nSTAGING_RECOVERY_VERIFIED\n' "${RELEASE_ID}" > "${evidence}/result.txt"
cat "${evidence}/result.txt"
