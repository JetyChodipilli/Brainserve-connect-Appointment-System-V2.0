# Sprint 7: activity, discussion, drafts and search

Sprint 7 implements UX01, UX02, UX03 and WB06 from the expanded V2 PRD. Sprint 8
templates and recurrence remain a separate deliverable.

## Product behavior

- Workspace search groups appointments, visitors, employees and worksheets, with
  independent bounded pagination. Counts and details use the current account,
  role, department and assignment. Opening a result checks access again. CEO
  worksheet results come from retained oversight records and open read-only
  activity; operational worksheets open the existing Workboard drawer.
- Appointment history opens from each appointment and from search. Worksheet
  history and discussion appear in the existing task drawer. Timelines combine
  retained audit, checkpoint, worksheet and notification records with stable event
  IDs and deterministic time/ID ordering. Historical identity is shown only when
  a snapshot exists. Notification requests remain distinct from delivery receipts.
- Current worksheet participants can add plain-text comments, select scoped
  mentions and attach existing scanned private evidence. Authors can edit or
  remove their own comments with revision checks. Removed content is hidden from
  discussion, while its revision trail remains retained. Comments do not change
  official approval remarks or business workflow state.
- Task creation and update notes, visitor intake and company profile forms save
  encrypted, account-owned, allowlisted drafts. Users explicitly restore, discard
  or submit. Conflicting tabs cannot silently overwrite; unavailable saves remain
  only in the open form's memory. Account/session changes invalidate pending work.
  Final submission and its receipt share one transaction, so a lost response can
  be checked again without creating another business record.

## Operational policy

V57 adds discussion revisions and activity identity snapshots. V58 adds owned
drafts and submission receipts. V59 adds scoped search indexes. All earlier
migration checksums remain unchanged. Deploy the additive backend migrations
before the frontend; rollback means reapplying a compatible pinned application,
without deleting the new data or running a schema down-migration.

Draft expiry defaults to seven days. Set `brainserve.drafts.expiry-days` between
1 and 30; invalid configuration fails startup. Cleanup runs hourly by default
(`brainserve.drafts.cleanup-ms=3600000`) in bounded batches. Passwords, OTPs,
tokens, document bytes and visitor identity documents are excluded from draft
storage. Submission receipts contain only the resulting record reference.

Discussion revisions follow the worksheet's retention lifecycle. Removing a
comment hides its content and attachment links from the participant read model;
it does not erase the retained revision/audit trail. Evidence remains in the
existing private scanned store, with current task authorization at download.
Search results and details use no-store responses and avoid document identifiers,
private contact details and unrelated department snippets.

## Verification and release gates

Local validation covers TypeScript, lint, production build, regression checks,
operational rejection tests and Chromium browser workflows. The CI backend gate
requires three new PostgreSQL integration suites for activity, drafts and search,
in addition to all earlier PostgreSQL/Redis suites; skipped suites cannot satisfy
the gate. CI also runs the full existing cross-browser suite.

The disposable TLS staging rehearsal uses the real scanner and private object
store, then exercises comments with evidence, search authorization, draft
revision conflicts and repeated final submission. Backup/restore compares all
migration checksums through V59 and complete synthetic draft/comment/revision
data digests before checking restored readiness and pinned release reapplication.
No customer data or credentials are printed into the evidence artifacts.

Release requires Frontend, Backend and Staging & recovery to pass on the exact PR
head before merge. Automated checks establish implementation evidence; deployment
to a persistent customer environment and customer UAT remain separate activities.
