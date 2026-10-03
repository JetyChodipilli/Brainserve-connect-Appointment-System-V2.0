# Sprint 4: company setup and safe imports

Sprint 4 implements PRD FC03 and UX05: a resumable company setup checklist and
reviewed CSV imports. Each deployment still serves one company. Setup completion
does not create a tenant or bypass account approvals.

## Company setup

System Admin opens company setup from Settings. Configure the company profile,
branding and deployment office time zone, active departments and leadership,
appointment policy, notifications and privacy/retention choices through the
existing settings and governed account flows. Progress is saved on the server
with a revision. A stale tab receives a conflict and must reload.

Readiness is computed from current settings and assignments. A missing active
CEO, department leadership or invalid setting blocks completion. The runtime
office time zone is displayed separately from any requested preference; changing
a setting cannot silently change the deployment's appointment clock. Completion
is audited and later prerequisite changes invalidate current readiness.

## Import workflow

1. Open Safe imports in the authorized workspace. Available types come from the
   server's current permissions. System Admin can manage department imports;
   employee and visitor writes require their existing business permissions.
2. Download the selected template. Save UTF-8 CSV with its exact headers. Files
   are limited to **1,000 data rows and 2 MiB** on both client and server.
3. Choose the duplicate policy, upload and preview. Review valid, skipped and
   failed rows. Fix errors before uploading a replacement preview.
4. Confirm the reviewed job. Execution binds its checksum and revalidates current
   authority and reference state. A department or role change can fail affected
   rows after a successful preview.
5. Review each applied, skipped or failed result. Save the job ID and use its
   status/retry controls after a lost response. Reuse the original idempotency
   key; do not upload another file to recover an interrupted execution.

Imports create records. `SKIP` leaves matching existing identities or department
codes unchanged; `FAIL` reports the duplicate row. Employee imports create
profiles without accounts, passwords or invitations. Visitor imports create
individual visits through the existing registration and approval flow; they do
not approve or check visitors in. Quoted commas, multiline cells, escaped quotes
and a UTF-8 BOM are supported. Unsupported columns and malformed CSV are rejected.

Execution processes rows synchronously through durable, independent transactions.
A client timeout can lose the response while the service continues processing.
Use the saved job ID to reload status or safely resume the same job.

CSV record numbers start at 2, with the header as record 1; a quoted multiline
cell remains part of one record. Each applied row and its outcome commit together. A job can have partial results;
the whole file is **not atomic**. Retrying a job resumes unfinished rows without
duplicating committed effects. Authorization also applies to status and error
downloads; job IDs do not grant access to another account's upload.

Previews expire after 24 hours. Expired-job access clears uploaded row values and
reference snapshots. A scheduled cleanup also clears abandoned uploads (default:
every hour, up to 100 jobs per batch); monitor throughput and adjust the cleanup
cadence if batches accumulate. Durable outcomes and replay bindings remain. Expired unfinished
jobs require a fresh reviewed preview. Check committed outcomes first and retain
the duplicate policy when preparing the replacement.

Error CSV downloads neutralize spreadsheet formulas. Audit records contain job
identifiers and counts rather than uploaded names, addresses or file contents.

## APIs

| Path under `/api/v1` | Purpose |
|---|---|
| `GET /company-setup` | Current readiness, issues, office zone and saved progress |
| `PUT /company-setup/progress` | Revision-checked step progress |
| `POST /company-setup/complete` | Revalidate and audit completion |
| `GET /bulk-imports/options` | Current allowed types, duplicate policies and limits |
| `GET /bulk-imports/templates/{kind}` | CSV template and column definitions |
| `POST /bulk-imports/preview` | Validate bounded CSV and persist a reviewed job |
| `POST /bulk-imports/{id}/execute` | Execute/resume the exact checksum and key |
| `GET /bulk-imports/{id}` | Authorized durable row outcomes |
| `GET /bulk-imports/{id}/errors` | Formula-safe error CSV |

## Deployment, recovery and rollback

Deploy backend/database before the new frontend. V54 is additive and preserves
V1–V53 checksums. CI requires real PostgreSQL coverage and a staging restore with
V54 installed. Preserve job records when recovering an interrupted upload; they
bind committed effects to their results and replay keys.

Screen/application rollback does not undo imported employees, departments or
visits. Use normal governed corrections and cancellation where allowed. Sent
notifications, audit evidence and downstream effects are not reversible through
a bulk delete. Do not run a schema down-migration or erase jobs to retry them.
Rehearse the previous compatible application against an isolated restore before
using it on a persistent host; the staging pinned-image reapply is not evidence
that every older application version is compatible.

## Demo and pilot boundaries

Synthetic fixtures remain in the designated preview deployment and existing
`frontend/preview` modules. The preview does not execute production imports.
Do not seed synthetic employees or visits into a customer database to populate
KPIs. Local preview and automated staging UAT do not constitute customer pilot
approval. A supervised pilot requires an operations/support owner, accepted
limits and customer-role UAT; integrations, kiosk hardware and later Workboard
features remain in their scheduled sprints.

The new screens use existing fonts, tokens and feature boundaries. Kiranism's
dashboard page/form/table organization is adapted to this application's stack;
no parallel identity provider, fetching framework or theme is introduced.

CI also detected CVE-2026-93687 in the existing transitive `braces` dependency.
Until an upstream fixed release is available, a private MIT source copy applies
the reviewed depth guard, with installation-resolution and exploit regressions.
See [the patch provenance and removal criteria](../frontend/vendor/braces/README.md).
The normal npm audit threshold remains in force.
