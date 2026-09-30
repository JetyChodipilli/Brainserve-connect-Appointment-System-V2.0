# Dashboard source definitions and reconciliation baseline

Owner: application reporting module. Reporting zone: Asia/Kolkata. Database
timestamps are UTC instants. Inclusive office dates become a half-open interval
from midnight of the first date through midnight after the last date. V29 summary
functions explicitly use Asia/Kolkata; changing `OFFICE_TIME_ZONE` alone does not
change their SQL. Keep staging configured to that zone until a timezone-aware
read-model migration is delivered.

These definitions describe the **existing API**, not all future PRD metrics. They
are the reconciliation baseline for Sprint 3's metric contract. Existing visitor
totals remain available. Do not relabel a scheduled cohort as an event-arrival flow.

| Existing `/dashboard/summary` field | Source and meaning | Temporal type / limitation |
| --- | --- | --- |
| `scheduledVisits` | Appointment count by `slot_start` in the selected office-date interval | Scheduled cohort; rescheduling changes membership |
| `arrivedVisits` | Scheduled cohort with a recorded `security_intake_at` | Cohort coverage, not first-intake events occurring in the period |
| `awaitingApproval` | Scheduled cohort currently in `PENDING_%` statuses | Current state of selected cohort; not a global live waiting stock |
| `activeVisits` | Scheduled cohort currently approved or checked in | Current state of selected cohort |
| `completedVisits`, `cancelledVisits`, `rejectedVisits` | Scheduled cohort in those current statuses | Cohort outcomes; not transition events in the period |
| `visitorsInside` | Open access records joined to appointment routing department | Current instant; company-wide only for organization-wide roles; historical monthly response retains its existing zero placeholder pending Sprint 3 availability contract |
| `totalEmployees`, `activeEmployees` | Latest available daily snapshot within the interval | Snapshot, never sum or peak. Historical refresh currently uses present employee state; authoritative end-of-period reconstruction needs employment/department history |
| Monthly workforce | Employees joined by month end and not relieved by that date | Employment snapshot using current department mapping; past department transfers cannot be reconstructed from this query |
| `assignedWork` | Tasks created in the interval | Assignment flow; lacks future recurrence/handover semantics |
| `inProgressWork` | Daily summary: tasks started in the interval; personal response: current in-progress tasks | Legacy inconsistency; normalize in Sprint 3 before using as a capacity KPI |
| `completedWork`, `approvedWork` | Daily/monthly task completion and approval timestamps; personal response uses current statuses | Separate submission and acceptance; rework-cycle history is required for final PRD throughput definitions |
| `averageWaitSeconds` | Sum of eligible check-in minus intake durations divided by the eligible sample count | Check-in event cohort; zero durations included; negative durations excluded; null when no samples or any historical denominator is unavailable |
| `generatedAt` | Time this response was produced, retained when cached | Not source freshness. Source refresh timestamp and coverage belong to Sprint 3's contract |

The mean is rounded once, after combining unrounded numeric duration totals. Never
average daily or department means or weight them by arrivals without check-ins.
V38 stores `wait_seconds_total` and `wait_sample_count` for daily and monthly rows;
old rows default to null. A mixed known/unknown daily range returns null. Percentiles
cannot be obtained by combining those totals or averaging daily percentiles.

## Role scope

Scope is resolved from the current active account before reading the cache. Cache
keys include actor, current role, department, employee, and range. System Admin and
CEO have company reporting; HR, Manager, and Team Lead have their assigned
department; Employee uses personal queries. Reception and Security have visitor
and checkpoint access. Workforce and work values are suppressed in the legacy response. Their future
dashboard contract must omit these unauthorized metrics rather than treating suppression as a genuine metric zero.

## Required next metric contracts

| PRD metric | Canonical definition | Instrumentation / coverage prerequisite |
| --- | --- | --- |
| VIS01 scheduled | Distinct scheduled appointment cohort | Stable scheduling and cancellation rules |
| VIS02 arrivals | Distinct first intake events in period | First-intake event time, deduplication, stage coverage |
| VIS03 check-ins / VIS04 check-outs | Distinct first check-ins and completed check-outs in period | Access lifecycle and duplicate-scan policy |
| VIS05 inside / VIS06 waiting | Authorized current nonterminal stock | As-of instant and explicit unavailable state |
| VIS09 waiting p95 | p95 of valid visitor waiting durations | Complete event cohort; sample size and coverage, not daily percentiles |
| WORK01 outstanding | Distinct unfinished current task plus audit-stage work | Canonical acceptance and rework state contract |
| WORK02 due today / WORK03 overdue | Office-date deadline cohorts split into execution and review | Original and current deadlines, accepted evidence validity |
| WORK07 on-time accepted | Accepted by original deadline / eligible original-deadline cohort | Versioned original deadline and current valid acceptance, exclusions documented |
| IAM01 active employment | Distinct active employment as of interval end | Employment and department histories |
| IAM02 enabled accounts | Distinct enabled login accounts | IAM lifecycle, separately from employees |
| OPS01 workflow availability | Successful eligible workflow checks / eligible checks | Governed probes and maintenance/exclusion policy |

Other PRD measures (approval turnaround, no-shows, reviewer waiting, blocked time,
rework frequency, delivery attempts, and department trends) require the specified
events before publishing values. A missing source is **unavailable**, not zero.

## Reproducible reconciliation fixture

`backend/src/test/resources/reporting/kpi-reconciliation.sql` is synthetic data,
used only in isolated test databases. The integration test migrates V37 with an
existing historical aggregate to V38, then verifies:

- Department A durations 0, 60, and 180 produce 80 seconds over unequal days.
- Department B contributes one 900-second sample and ten arrivals without
  check-ins; the company mean is 285 seconds, using four eligible samples.
- A next-day-midnight sample is excluded from the two-day range but included in
  its month; the monthly company mean is 252 seconds.
- A negative duration is excluded, an authentic zero remains zero, and unavailable
  historical denominators return null.
- One department sees one open access record while the authorized company sees two.
- Workforce snapshots of 10 then 4 produce 4, not 10 or 14.

Fixtures and expected values are reconciled against the production query service,
not a duplicate calculator. CI rejects skipped database fixtures.
