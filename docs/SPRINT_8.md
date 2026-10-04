# Sprint 8: templates and recurring worksheets

Sprint 8 implements WB07 and the recurrence scenarios QA09/QA11 from the expanded V2 PRD. HR and Team Leads use **Work routines** within their department Work Board. Existing worksheet delivery, approval, audit, private evidence and original-deadline rules still apply.

## Product behavior

Templates contain worksheet instructions, ordered checklist requirements, an eligible assignee role and a due-date offset in calendar days. Edits append a template version. The first attempt for a future occurrence uses the latest template; existing worksheets and blocked occurrence snapshots retain their original version. Team Leads can schedule Employees; HR can schedule Employees or their department's active Team Lead. Every read and write checks the current account, permissions and department assignment.

Schedules support daily, weekly and monthly frequency and intervals from 1 to 12. Weekly days use the Monday-based office week. Monthly dates clamp to the last day of shorter months without changing the next month's intended day. The configured office time zone defines local date and creation time. A daylight-saving gap advances by its duration; an overlap uses the earlier offset and still creates one local-date occurrence.

Weekend and holiday behavior are explicit **Include** or **Skip** settings. Holidays are dates maintained on the schedule by the department owner; no jurisdiction-specific holiday feed is assumed. The end date is inclusive. Preview shows the next ten accepted dates and due dates. Pausing stops new tasks and retries while preserving existing work and evidence. Resuming skips elapsed paused dates; an active schedule instead catches up missed occurrences after a restart in bounded batches.

Each occurrence has a unique schedule/date key and captures its template version. Task creation, checklist initialization, occurrence receipt and durable internal-message intent share one transaction. Database worker ownership and the unique receipt prevent concurrent workers or repeated retries from creating a second task or business notification. Existing notification dispatch runs after commit and retries queued delivery independently.

Materialization rechecks the creator's current role, permission and assignment, and the assignee's active account, role and department. Ineligible occurrences appear as blocked exceptions in schedule history. Correct the account or assignment, then explicitly retry the same occurrence. Infrastructure failures roll back for a later worker attempt. Reload version conflicts before retrying an edit or pause action. Account changes clear pending form/results; stale responses cannot restore the previous account's work.

## API and operations

`/api/v1/work-routines` provides context, paginated templates/schedules, preview, schedule state and occurrence history/retry. Actors come from the authenticated principal. Requests use revision checks and creation request IDs; foreign IDs do not disclose another department's routines. UI controls use the existing feature folders, transport, dialog/focus behavior and visual tokens.

V60 adds templates, retained versions, schedules, occurrence snapshots and notification receipts. Earlier Flyway migration checksums are unchanged. Deploy the additive backend migration before the frontend. Reapply a compatible pinned application for rollback; retain the new data and avoid schema down-migrations. `OFFICE_TIME_ZONE` remains the deployment-wide calendar source. Each schedule retains its creation zone; coordinate time-zone changes with department owners.

The scheduler polls every 15 seconds by default (`WORK_ROUTINES_POLL_MS`). Set `WORK_ROUTINES_ENABLED=false` to suspend automatic materialization during an operational maintenance window. This switch preserves schedules, receipts and existing work; re-enabling permits active schedules to catch up. Use the schedule's pause control when elapsed dates should be skipped on resume.

## Verification

Local checks cover the actual Java calendar, frontend behavior, production build, lint, operational rejection tests and Chromium workflows. Required CI runs Java 21 with real PostgreSQL/Redis and rejects skipped database suites, including `Sprint8RecurrencePostgresIntegrationTest`. Tests exercise concurrent workers, repeated/restarted work, current eligibility, immutable snapshots, pause and evidence retention, conflicts and transactional rollback.

The disposable TLS staging drill exercises the real scheduler, scanner, private object store and notification receipt. Backup/restore compares all migration checksums through V60 and full synthetic recurrence/task data digests, then checks restored readiness and pinned release reapplication. Required Frontend, Backend and Staging & recovery checks must pass on the reviewed PR head before merge. These checks establish implementation evidence; persistent customer rollout and customer UAT remain separate activities.
