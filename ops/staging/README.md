# Staging installation and recovery runbook

Use a dedicated Linux host with Docker Engine and Compose v2, Node 24 for secret
generation, Git, sufficient capacity for PostgreSQL, Redis, three Kafka brokers,
MinIO, ClamAV, Mailpit, and both application images. Allocate resources for all services, monitor memory and disk during the
rehearsal, and record measured sizing before a persistent deployment. Keep it
isolated from customer production data.

## Install a pinned release

```sh
git checkout <verified-commit>
node scripts/generate-local-env.mjs
export RELEASE_ID="$(git rev-parse HEAD)"
export STAGING_DOMAIN=staging.example.internal
docker compose --env-file backend/.env -f docker-compose.yml -f ops/staging/compose.yml --profile full-stack config --quiet
docker compose --env-file backend/.env -f docker-compose.yml -f ops/staging/compose.yml --profile full-stack build backend frontend minio minio-init
docker compose --env-file backend/.env -f docker-compose.yml -f ops/staging/compose.yml --profile full-stack up -d --wait --wait-timeout 900
```

Keep `backend/.env` mode 0600 and out of Git, logs, and artifacts. Retain a secured
copy of PII, QR signing, JWT, archive encryption keys (including retired key
versions), database credentials, and private-document credentials. Never generate
a replacement secret file during upgrade or rollback.

The staging override disables bootstrap accounts, uses same-origin `/api/v1`, and
adds HTTPS on loopback port 8443. Caddy uses an internal CA by default: trust its
root certificate on authorized clients. Infrastructure ports remain loopback.
The network defaults to `172.29.91.0/24`; automatic container addresses come from
`172.29.91.128/25`, leaving the proxy's fixed `172.29.91.10` outside that pool.
If those addresses conflict with the host network, set `STAGING_SUBNET`,
`STAGING_DYNAMIC_SUBNET`, and `STAGING_PROXY_IP` together. Keep the dynamic range
inside the subnet and the proxy address inside the subnet but outside the dynamic
range. The backend trusts only that exact proxy address for forwarded headers.
For remote staging, explicitly bind `STAGING_BIND_IP` to the intended interface,
configure firewall/access controls and DNS, and use an organization-approved
certificate. `STAGING_TLS` may name the certificate/key directive; mount the files
read-only with a local override. Mailpit is a test mailbox, not a customer SMTP
provider. Document the approved one-time account provisioning procedure before
staff use; do not leave bootstrap credentials enabled.

The proxy denies external actuator routes. Readiness stays accessible inside the
backend container. Health probes establish startup readiness, not full workflow
availability or authenticated business acceptance.

The historical MinIO and mc Docker Hub repositories are unavailable. The stack
builds the same release versions from exact upstream Git commits in
`ops/minio/Dockerfile`, retains their license notices, and enables private bucket
versioning. These community repositories are now archived/source-only; choose a
maintained S3 provider for production and test private-object access and migration
before customer rollout. Source builds restore staging reproducibility and do
not constitute a maintenance guarantee for those releases.

## Disposable acceptance rehearsal

```sh
# On an EMPTY, DISPOSABLE stack only. This creates a synthetic recovery probe,
# restores into a new database and temporarily connects the backend to it.
export RELEASE_ID="$(git rev-parse HEAD)"
export STAGING_DOMAIN=localhost
export BRAINSERVE_DISPOSABLE_STACK=1
bash ops/staging/verify.sh
```

The script validates Compose, builds and tags images, waits for the full stack,
checks HTTPS and product HTML, requires 401 for an anonymous dashboard request,
requires 404 for external actuator access, exercises scanned private work evidence,
scoped discussion/drafts, recurrence receipts, controlled handover and original-deadline
analytics, stops writes, creates a checksummed dump,
restores a separate database, compares every successful Flyway version/checksum,
compares full retained row digests including authored transfer snapshots, notice receipts,
review stages and original commitments, starts the application against the restore, and
reapplies the pinned images against the original staging database. It never deletes
or overwrites a database. `STAGING_RECOVERY_VERIFIED` appears only after every check.

The evidence probe refuses existing accounts and requires both explicit disposable
opt-in and localhost. It creates synthetic scoped principals, exercises real HTTPS,
PostgreSQL, MinIO and ClamAV, rejects invalid bytes and malware, verifies fail-closed
scanner outage, immutable submission acceptance, authorized byte downloads and
permission revocation. The handover probe removes the previous Employee's current
access, preserves scanned evidence and original authorship, requires a fresh submission
from the new assignee, and reconciles cohort records/CSV against the displayed metric.
Seeded sessions exercise request authorization; this probe
does not test interactive login or MFA enrollment. Never run it on a staff host.

The CI cleanup deletes **only the disposable runner's volumes**. Do not copy that
cleanup step to a persistent staging or production host.

## Backup and restore

Existing physical base backups/WAL remain in place; see
[PITR runbook](../postgres/PITR_RUNBOOK.md). Logical backups supplement them and do
not prove point-in-time recovery. Run with the PostgreSQL 17 client inside a
secured container or host, passing credentials via protected environment/pgpass:

```sh
PGDATABASE=brainserve sh ops/postgres/backup-logical.sh /secured/backups/release-<id>
sh ops/postgres/restore-logical.sh /secured/backups/release-<id> brainserve_restore_<id>
```

The destination must be new. A failed dump cannot publish a verified backup. A
restore rejects corrupt checksums, unsafe names, existing target databases, and
PostgreSQL errors. A failure after target creation leaves a separate partial
database for investigation; remove it only after confirming the target and cause.

Store encrypted off-host copies of database backups, retained WAL, the MinIO
private bucket and object versions, and encryption keys. Local Docker volumes do
not survive loss of the host. Logical dumps use `--no-owner --no-acl`: restore
required database roles/grants separately under the security owner's procedure.
Keep restored data isolated until retention/legal-hold rules have been reconciled.
Record backup time, start/end of recovery, data loss, schema checksums, row counts,
object availability, decryption checks, role access checks, and the approving owner.
RPO/RTO remain unmeasured until a representative full restore is timed.

## Upgrade and rollback

1. Record the running verified commit and image IDs/digests for both applications,
   environment configuration, Flyway versions/checksums, and backup identifiers.
2. Check migration compatibility. V51 adds MFA/session storage, V52 adds
   reporting revisions and wraps existing refresh functions, and V53 adds
   dashboard measurement facts/coverage. V54 adds versioned company setup and
   durable import previews/results. V55 adds Workboard preferences and submission
   counters. V56 adds versioned planning and immutable submission evidence. Preserve V1–V55
   checksums; do not use `flyway repair` to hide drift. Read `docs/SPRINT_2.md`
   for MFA enrollment, encryption-key continuity and the exact trusted proxy;
   `docs/SPRINT_3.md` describes metric definitions, source gaps and screen rollback.
   Read `docs/SPRINT_4.md` before resuming interrupted imports or rolling back setup.
   Read `docs/SPRINT_5.md` for Workboard scopes, version conflicts and backend-first
   rollout. Read `docs/SPRINT_6.md` for evidence retention and planning policy.
   Restore verification checks V56 and compares all migration checksums.
3. Verify the new release in an isolated restore before changing a persistent host.
4. Build the new commit-tagged images, preserve the previous verified images, and
   run the same Compose `up --no-build --wait` with the new `RELEASE_ID`.
5. On failure, select the previously **verified** commit/configuration and set its
   `RELEASE_ID`; reapply both retained images with `up --no-build --wait`. Preserve
   databases, Kafka, Redis, and private-document volumes. Verify readiness, login,
   role scope, visit workflow, and Workboard submission/review before reopening.
6. Do not run a schema down-migration. If the prior application is incompatible
   with the new schema, keep writes stopped and use a forward fix or restore the
   verified pre-upgrade backup to a separate database with an explicit data-loss
   decision. Never restore over the live directory.

Sprint 1's CI reapply establishes the first verified fallback image. It does not
claim a previous release has passed rollback acceptance. Later releases must test
the actual previous verified images against the upgraded schema and record results.
