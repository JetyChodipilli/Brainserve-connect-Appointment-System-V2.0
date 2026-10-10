#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after generating backend/.env. The project must
# be disposable: this drills a restore by moving only this stack's backend.
: "${RELEASE_ID:?Set RELEASE_ID to the checked-out commit}"
: "${STAGING_DOMAIN:=localhost}"
[ "${BRAINSERVE_DISPOSABLE_STACK:-}" = 1 ] || { echo "Explicit disposable-stack opt-in required" >&2; exit 1; }
[ "${STAGING_DOMAIN}" = localhost ] || { echo "Disposable rehearsal requires localhost" >&2; exit 1; }
export STAGING_DOMAIN
[[ "${RELEASE_ID}" =~ ^[a-f0-9]{40}$ ]] || { echo "Immutable release SHA required" >&2; exit 1; }
[ "${RELEASE_ID}" = "$(git rev-parse HEAD)" ] || { echo "Release must match the checked-out source" >&2; exit 1; }
tick() { node -p 'Number(process.hrtime.bigint() / 1000000n)'; }
install_begin="$(tick)"
rollback_release=3d51d65af1b7a026c1fd7fa9f55aa52063d6234e
[ "${RELEASE_ID}" != "${rollback_release}" ] || { echo "A distinct rollback release is required" >&2; exit 1; }
rollback_source="$(mktemp -d)"
trap 'rm -rf "${rollback_source}"' EXIT
git archive "${rollback_release}" | tar -x -C "${rollback_source}"
docker build -t "brainserve-backend:${rollback_release}" "${rollback_source}/backend"
docker build --build-arg NEXT_PUBLIC_API_BASE_URL=/api/v1 --build-arg NEXT_PUBLIC_DASHBOARD_CARDS_ENABLED=true -t "brainserve-frontend:${rollback_release}" "${rollback_source}/frontend"
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
install_ready="$(tick)"
# Work evidence exercises the actual scanner and private object store, with
# synthetic principals/data only. This script already requires a disposable stack.
node scripts/verify-work-evidence-staging.mjs
# Stop writes before measuring/dumping. Recovery happens in a separate database.
recovery_begin="$(tick)"
"${compose[@]}" stop backend
"${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d brainserve -v ON_ERROR_STOP=1 -c "create table sprint1_recovery_probe(id integer primary key, evidence text not null); insert into sprint1_recovery_probe values (1, '\''zero-wait'\''), (2, '\''scoped-access'\'');"'
"${compose[@]}" cp ops/postgres/backup-logical.sh postgres:/tmp/backup-logical.sh
"${compose[@]}" cp ops/postgres/restore-logical.sh postgres:/tmp/restore-logical.sh
backup_begin="$(tick)"
"${compose[@]}" exec -T postgres sh -c 'PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD" PGDATABASE=brainserve sh /tmp/backup-logical.sh /tmp/sprint1-backup'
backup_end="$(tick)"
"${compose[@]}" exec -T postgres sh -c 'PGUSER="$POSTGRES_USER" PGPASSWORD="$POSTGRES_PASSWORD" sh /tmp/restore-logical.sh /tmp/sprint1-backup brainserve_restore_sprint1'
restore_end="$(tick)"
for database in brainserve brainserve_restore_sprint1; do
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atc "select count(*), md5(string_agg(row_to_json(t)::text, chr(124) order by id)) from release_profile t"' sh "${database}" > "${evidence}/${database}-sprint16-data.txt"
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atc "select version || chr(58) || checksum from flyway_schema_history where success order by installed_rank"' sh "${database}" > "${evidence}/${database}-schema.txt"
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint7-data.txt" <<'SQL'
select 'drafts',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by owner_id,form_type,context_key),'')) from owned_form_draft t
union all select 'comments',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from task_comment t
union all select 'revisions',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from task_comment_revision t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint8-data.txt" <<'SQL'
select 'templates',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from work_routine_template t
union all select 'versions',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by template_id,version),'')) from work_routine_template_version t
union all select 'schedules',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from work_routine_schedule t
union all select 'occurrences',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by schedule_id,occurrence_date),'')) from work_routine_occurrence t
union all select 'notice_receipts',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by event_key),'')) from work_routine_notice_receipt t
union all select 'scheduled_tasks',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from department_work_task t where id in(select task_id from work_routine_occurrence where task_id is not null);
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint9-data.txt" <<'SQL'
select 'handovers',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from work_task_handover t
union all select 'handover_notices',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by event_key),'')) from work_handover_notice_receipt t
union all select 'handed_tasks',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from department_work_task t where id in(select task_id from work_task_handover)
union all select 'original_commitments',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by work_task_id),'')) from work_original_commitment t
union all select 'review_history',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from audit_event_history t where event_type like 'WORK_INSIGHT_%';
select 'review_stages',count(*),md5(coalesce(string_agg(row_to_json(t)::text, '|' order by id),'')) from work_review_stage_event t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint10-data.txt" <<'SQL'
select 'preferences',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by user_id),'')) from notification_preference t
union all select 'preference_history',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from notification_preference_history t
union all select 'email_receipts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by notification_id),'')) from notification_email_receipt t
union all select 'policies',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from approval_policy t
union all select 'stages',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from approval_stage t
union all select 'delegations',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from approval_delegation t
union all select 'reminder_receipts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by stage_id,window_number,recipient_id),'')) from approval_reminder_receipt t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint11-data.txt" <<'SQL'
select 'connections',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_connection t
union all select 'deliveries',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_delivery t
union all select 'attempts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_delivery_attempt t
union all select 'mappings',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by connection_id,resource_id),'')) from integration_external_mapping t
union all select 'receipts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by connection_id,business_event_id),'')) from integration_simulator_receipt t
union all select 'resource_revisions',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by resource_id),'')) from integration_resource_revision t
union all select 'business_events',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_business_event t
union all select 'command_receipts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by request_id),'')) from integration_command_receipt t
union all select 'diagnostic_requests',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by request_id),'')) from support_diagnostic_request t
union all select 'diagnostics',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from support_diagnostic_package t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint12-data.txt" <<'SQL'
select 'google_calendars',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by connection_id),'')) from integration_google_calendar t
union all select 'google_consents',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_google_consent t
union all select 'google_revocations',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_google_revocation t
union all select 'calendar_reconciliations',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_calendar_reconciliation t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint13-data.txt" <<'SQL'
select 'slack_destinations',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by connection_id),'')) from integration_slack_destination t
union all select 'slack_rate_limits',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by workspace_id,channel_id),'')) from integration_slack_rate_limit t
union all select 'slack_revocations',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from integration_slack_revocation t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint14-data.txt" <<'SQL'
select 'visit_groups',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from appointment_visit_group t
union all select 'group_members',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from appointment t where visit_group_id is not null
union all select 'kiosk_devices',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from kiosk_device t
union all select 'kiosk_intakes',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from kiosk_arrival_intake t;
SQL
    "${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At' sh "${database}" > "${evidence}/${database}-sprint15-data.txt" <<'SQL'
select 'load_accounts',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from iam_user_account t
union all select 'load_employees',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from employee t
union all select 'load_work_tasks',count(*),md5(coalesce(string_agg(row_to_json(t)::text,'|' order by id),'')) from department_work_task t;
SQL
done
cmp "${evidence}/brainserve-sprint16-data.txt" "${evidence}/brainserve_restore_sprint1-sprint16-data.txt"
grep -Eq '^1\|' "${evidence}/brainserve_restore_sprint1-sprint16-data.txt"
cmp "${evidence}/brainserve-sprint15-data.txt" "${evidence}/brainserve_restore_sprint1-sprint15-data.txt"
grep -Eq '^load_accounts\|500\|' "${evidence}/brainserve_restore_sprint1-sprint15-data.txt"
cmp "${evidence}/brainserve-sprint7-data.txt" "${evidence}/brainserve_restore_sprint1-sprint7-data.txt"
cmp "${evidence}/brainserve-sprint8-data.txt" "${evidence}/brainserve_restore_sprint1-sprint8-data.txt"
cmp "${evidence}/brainserve-sprint9-data.txt" "${evidence}/brainserve_restore_sprint1-sprint9-data.txt"
cmp "${evidence}/brainserve-sprint10-data.txt" "${evidence}/brainserve_restore_sprint1-sprint10-data.txt"
cmp "${evidence}/brainserve-sprint11-data.txt" "${evidence}/brainserve_restore_sprint1-sprint11-data.txt"
cmp "${evidence}/brainserve-sprint12-data.txt" "${evidence}/brainserve_restore_sprint1-sprint12-data.txt"
cmp "${evidence}/brainserve-sprint13-data.txt" "${evidence}/brainserve_restore_sprint1-sprint13-data.txt"
cmp "${evidence}/brainserve-sprint14-data.txt" "${evidence}/brainserve_restore_sprint1-sprint14-data.txt"
for fixture in visit_groups group_members kiosk_devices kiosk_intakes; do
    grep -Eq "^${fixture}\|[1-9][0-9]*\|" "${evidence}/brainserve_restore_sprint1-sprint14-data.txt"
done
cmp "${evidence}/brainserve-schema.txt" "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^50:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^51:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^52:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^53:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^54:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^55:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^56:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^57:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^58:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^59:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^60:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^61:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^62:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^63:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^64:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^65:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^66:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^67:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^68:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^70:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^71:' "${evidence}/brainserve_restore_sprint1-schema.txt"
grep -q '^69:' "${evidence}/brainserve_restore_sprint1-schema.txt"
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
node scripts/verify-restored-read-staging.mjs brainserve_restore_sprint1 restored
restored_ready="$(tick)"
cat > "${evidence}/rollback-compose.yml" <<YAML
services:
  backend:
    image: brainserve-backend:${rollback_release}
  frontend:
    image: brainserve-frontend:${rollback_release}
YAML
rollback_begin="$(tick)"
"${compose[@]}" -f "${evidence}/restore-compose.yml" -f "${evidence}/rollback-compose.yml" up -d --no-build --wait --wait-timeout 180 backend frontend
smoke
node scripts/verify-restored-read-staging.mjs brainserve_restore_sprint1 rollback
"${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d brainserve_restore_sprint1 -v ON_ERROR_STOP=1 -Atc "select count(*), md5(string_agg(row_to_json(t)::text, chr(124) order by id)) from release_profile t"' > "${evidence}/rollback-sprint16-data.txt"
cmp "${evidence}/brainserve-sprint16-data.txt" "${evidence}/rollback-sprint16-data.txt"
rollback_end="$(tick)"
"${compose[@]}" exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d brainserve_restore_sprint1 -v ON_ERROR_STOP=1 -Atc "select version || chr(58) || checksum from flyway_schema_history where success order by installed_rank"' > "${evidence}/rollback-schema.txt"
cmp "${evidence}/brainserve-schema.txt" "${evidence}/rollback-schema.txt"
# Reapply the pinned release to the original staging database. No schema down-
# migration or volume deletion. Both restored and original database schemas remain intact.
reapply_begin="$(tick)"
"${compose[@]}" up -d --no-build --force-recreate --wait --wait-timeout 180 backend frontend
smoke
node scripts/verify-restored-read-staging.mjs brainserve reapply
reapply_end="$(tick)"
node scripts/write-recovery-report.mjs "${evidence}/recovery-report.json" "${RELEASE_ID}" "${rollback_release}" "${install_begin}" "${install_ready}" "${recovery_begin}" "${backup_begin}" "${backup_end}" "${restore_end}" "${restored_ready}" "${rollback_begin}" "${rollback_end}" "${reapply_begin}" "${reapply_end}"
printf 'Release: %s\nTLS and API authorization: passed\nScanned private work evidence: passed\nScoped search, comments, draft receipts and restored data: passed\nRecurrence snapshots, notifications and retained evidence: passed\nHandover authorship, workload and original-deadline analytics: passed\nNotification preferences, reminders and delegation revocation: passed\nIntegration receipts, retry, revocation and diagnostic expiry: passed\nGoogle Calendar API boundaries and retained restore fixtures: passed\nSlack API boundaries and retained restore fixtures: passed\nGroup and kiosk nonempty restore fixtures: passed\nV71 restore and application readiness: passed\nPrivate release record, staff access and prior-release privacy: passed\nSynthetic load and Redis readiness drill: passed\nAuthenticated restored and prior-release business reads: passed\nTimed compatible-release rollback and reapply: passed\nSTAGING_RECOVERY_VERIFIED\n' "${RELEASE_ID}" > "${evidence}/result.txt"
cat "${evidence}/result.txt"
