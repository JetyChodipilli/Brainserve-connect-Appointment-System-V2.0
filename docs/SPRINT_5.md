# Sprint 5: compact Workboard

Workboard provides a compact list and board lanes over the same scoped, paginated
records. A detail drawer keeps instructions, current updates, review milestones
and eligible actions together. Existing task creation, submission, review, rework
and oversight workflows remain the mutation authority.

## Read and preference contract

`GET /api/v1/workboard` returns policy `workboard.v1`, the runtime office timezone
and date, exact scoped counts, lane counts and one page. Employee reads are limited
to their own tasks; department roles use their current authorized department.
Every request rechecks the active account and effective permission. Unknown or
out-of-scope detail IDs return 404 without another department's content.

| Criterion | Values and limits |
|---|---|
| Period | `TODAY`, `CARRY_FORWARD`, `HISTORY`, `ALL` |
| Quick filter | `ALL`, `MY_ACTIONS`, `DUE_TODAY`, `OVERDUE_DELIVERY`, `AWAITING_MY_REVIEW`, `RETURNED_FOR_REWORK` |
| Search | Literal text, at most 120 characters; SQL wildcard characters are escaped |
| Status | `ALL` or an existing task status |
| Branch | Exact branch, at most 170 characters |
| Sort | `DUE_DATE`, `UPDATED_AT`, `TITLE`, `PRIORITY`; task ID breaks ties |
| Page | Zero-based, 0–10000; size 1–100 |

Period counts use search, status and branch. Quick-filter counts also use the
selected period. Total and lane counts apply every selected criterion. List and
board show the same page; board lane totals can exceed the number of visible cards.
Empty results remain an exact zero, rather than a missing-data placeholder.
Today and due dates follow the configured office timezone, not the browser clock.
Overdue delivery excludes a task waiting for review. Historical employee
acknowledgements can remain actionable after final CEO review.

`GET /api/v1/workboard/{taskId}` independently reads the selected task and at most
200 actual history events or milestones. It reports truncation. Current review
notes and mutable milestones do not imply a complete immutable audit trail or
archived evidence versions. A legacy submission counter can be unknown. Completed
means submitted; employee approval does not imply final CEO closure.
For a legacy task, the counter begins with its first recorded submission after
V55; earlier cycles are not reconstructed or included in that count.

`GET` and `PUT /api/v1/workboard/preferences` persist layout, density and up to ten
named filters for the signed-in owner. PUT includes `expectedRevision`; a stale
revision returns 409. Defaults are `LIST` and `COMPACT`. Saved filter IDs are safe
identifiers up to 64 characters; names contain 1–60 characters. Filters contain
criteria, not task evidence or a permission override. Preview preferences remain
isolated from production accounts.

## Actions, conflicts and accessibility

Available actions come from current backend eligibility. Board lanes do not grant
permission or bypass required review evidence. Team Leads cannot approve their
own work. Existing oversight screens continue to serve CEO review.

Task mutations accept optional `expectedVersion`; Work Insight mutations accept
optional `expectedTaskVersion`. The new UI supplies the observed version. The
check and existing business transition run in the same transaction. A mismatch
returns 409 and the UI retains the user's notes while refreshing current data.
Older clients can omit the optional version field during a coordinated rollout.

The drawer traps keyboard focus, closes with Escape, restores focus to the
opener and warns before discarding unsaved action notes. Selected task details
are loaded independently of the current page or filter. Unauthorized/session
changes clear previously scoped content; late responses cannot restore it.
Mobile layouts retain readable controls and task actions.

Priority/blocker editing and checklists belong to Sprint 6. Full comment timelines,
drafts and advanced search belong to Sprint 7. Sprint 5 does not invent priority,
blocker values or unavailable historical submission evidence.

## Rollout and recovery

Deploy the backend and additive V55 migration before the frontend. V55 adds
owner preferences, submission counters and read-supporting indexes. Preserve
V1–V54 checksums. Keep the previous verified images and a verified backup before
changing a persistent environment. Do not use Flyway repair to hide drift or run a
schema down-migration. An older application does not provide the new preference
or observed-version UI; assess its compatibility before choosing it as fallback.
Use a forward fix or an isolated pre-upgrade restore when compatibility fails.

CI requires the Sprint 5 PostgreSQL suite, together with the existing mandatory
database and Redis suites, to execute with zero skips, failures or errors. The
disposable staging recovery job checks V55, compares all migration checksums,
starts the application against the isolated restored database and reapplies the
pinned release. See [staging recovery](../ops/staging/README.md).

Browser API fixtures verify UI behavior and request contracts. PostgreSQL suites
verify actual database scope, counts, preference persistence and concurrency.
Disposable staging verifies install and recovery readiness. These checks do not
claim customer acceptance, representative production performance or a production
deployment; role-based pilot acceptance remains a separate product activity.
