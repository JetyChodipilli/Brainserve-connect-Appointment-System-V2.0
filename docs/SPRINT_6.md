# Sprint 6: planning and secure evidence

Workboard tasks now have Low, Normal, High or Urgent priority, an optional effort
estimate, ordered checklists and blockers independent of delivery status. Legacy
tasks default to Normal and unblocked. The original deadline remains separate
from the current commitment; legacy backfills explicitly report that the original
commitment is unknown. Changing a deadline requires an audited reason and never
silently pauses the delivery clock.

## Authority and revisions

The Planning and evidence tab uses the current task revision on every write.
Missing revisions are invalid; stale revisions return 409. Users retain entered
notes during refresh and must deliberately retry. Uploads never retry automatically.
Assignees update progress and raise blockers; authorized department leads manage
requirements, resolve blockers and change the follow-up contact. Contact choices
are bounded active department participants. Notifications reuse existing scoped
delivery. Blockers do not automatically change the delivery status or due date.

Every read, download and mutation rechecks current account permissions and task
scope. Unknown and foreign task IDs return the same 404. Administrative setup,
reception and security roles gain no task-evidence access. CEO access requires the
existing eligible oversight record. Accepted or finally closed stages suppress
ineligible progress and upload actions. A submitted draft can change for the next
version while the already submitted snapshot stays frozen. Checklist progress remains separate from
review acceptance.

| API relative to `/api/v1/work-tasks/{id}` | Behavior |
|---|---|
| `GET /planning` | Current revision, requirements, blockers, draft files, frozen submission versions and permissions |
| `PUT /planning` | Priority, estimate, deadline and requirement definitions with reason and expectedVersion |
| `PUT /checklist` | Assignee completed item IDs with expectedVersion |
| `POST /blockers` | Reason and optional authorized contact |
| `POST /blockers/{blockerId}/resolve` | Resolution reason and expectedVersion |
| `PUT /blockers/{blockerId}/contact` | Authorized contact, reason and expectedVersion |
| `POST /evidence` | Multipart file and expectedVersion |
| `DELETE /evidence/{evidenceId}` | Unlink a current draft reference; preserve retained versions |
| `GET /evidence/{evidenceId}/download` | Authenticated attachment bytes with no-store and nosniff |

Checklists are limited to 50 items and current draft files to 20. Estimates are
optional or 1–525600 minutes. Reads expose truncation for bounded blocker and
submission history. Workboard adds a Blocked filter and actual priority sorting.

## Evidence and submission versions

The existing private document store accepts JPEG, PNG or PDF evidence, checks
actual bytes and size (10 MiB default), scans with ClamAV and stores a SHA-256
digest. Scanner outages and malformed scanner responses fail closed. Downloads
require the task owner binding and CLEAN status, bound the stream, and verify its
length and digest. Raw object keys and public presigned URLs are never exposed.
An outer transaction rollback removes a newly uploaded object.

Required checklist items and evidence are checked by the existing submission and
revision paths, including older clients. Each submitted text version freezes its
checklist and evidence references. Acceptance records the reviewer role and timestamp; existing review audit events
retain the actor.
Rework creates another version; later requirement edits cannot rewrite a frozen
version. Draft unlinking does not erase prior reviewer evidence. This sprint adds
no automatic evidence purge; retained objects continue to require the existing
retention and legal-hold procedures.

## Rollout and verification

Deploy the backend and additive V56 migration before the frontend. Preserve all
V1–V55 checksums. Keep a verified database/object backup and previous images; use
a forward fix or an isolated restore if compatibility fails. Never down-migrate
or use Flyway repair to hide drift. See the [staging runbook](../ops/staging/README.md).

CI requires the new Sprint6PostgresIntegrationTest and all nine previous mandatory
PostgreSQL/Redis suites to execute without skips, failures or errors. Browser
fixtures exercise scoped UI journeys and conflicts; they do not substitute for
real database checks. The disposable localhost staging drill exercises HTTPS,
private MinIO storage and actual ClamAV, including malware, scanner outage,
authorized downloads, frozen acceptance and permission revocation. Recovery checks
V56, all migration checksums, restored application readiness and pinned reapply.
Seeded staging sessions do not establish interactive MFA enrollment acceptance.
Production deployment, customer UAT and representative performance remain separate
release activities. Comments, drafts and unified search belong to Sprint 7.
