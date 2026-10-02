# Sprint 3: dashboard contracts and first role cards

The System Admin and CEO overviews use versioned measurement cards with explicit
definitions, company scope, office dates or a live as-of instant, source checks,
coverage, and authorized record drill-downs. The CEO's existing visitor context,
schedule and approval actions remain available. Other role overviews and the
existing reporting/history endpoints retain their contracts.

## Read contracts

`GET /api/v1/dashboard/cards` accepts `period` (`TODAY`, `YESTERDAY`,
`LAST_7_DAYS`, `THIS_MONTH`, `PREVIOUS_MONTH`, `CUSTOM`), and custom `from`/`to`
office dates. The range is inclusive in the UI and next-day-exclusive in SQL,
bounded to 366 days. The response declares `metricVersion: sprint3.v1`, role,
scope, office zone, source metadata, first cards, supplementary cards and source
coverage. A selected historical range changes period measurements; it does not
turn visitors inside now or a pending approval queue into a historical sum.

`GET /api/v1/dashboard/cards/{metricId}/records` accepts the same range and a
bounded page/size (default 50, maximum 100). It uses the card's population and
current authority, including denied permission overrides. Stable ordering has an
ID tiebreaker. The wait distribution opens its valid observations; a delivery
cohort opens its eligible denominator, including late and unfinished work.
Metric IDs do not grant access to another role's technical or business records.

| Role | First six cards | Additional context |
|---|---|---|
| System Admin | Workflow availability OPS01; critical dependencies OPS04; pending account approvals IAM03; overdue visitor stages VIS08; dead-letter jobs NTF03; latest backup age OPS07 | Restore evidence OPS08 and measurement freshness/coverage OPS09 |
| CEO | Period arrivals VIS02; live visitors inside VIS05; pending CEO stages VIS07; visitor wait p95 VIS09; overdue delivery WORK03; on-time accepted delivery WORK07 | Retained cumulative visit records VIS14 and today's existing visitor/workforce context |

An available numeric zero is a confirmed empty population. Missing source facts
show **Unavailable** with a reason. A denied permission shows **Restricted**
and rejects the record endpoint. A zero denominator shows **Not
applicable**. Temporary failures offer retry; retained values are labeled stale.
Source age expires while the page is open. Live occupancy uses a 15-second budget,
business measurements 60 seconds, operational checks 30 seconds and retained
volume five minutes. Freshness describes the source observation, not request time.

Wait p95 uses raw valid intake-to-check-in seconds, including zero. Sample size,
coverage and missing/negative exclusions are visible; small samples below 20 are
flagged. A task submission is separate from delivery acceptance and final CEO
governance. Original delivery commitments remain unchanged by extensions.
Missing historical commitments or acceptance coverage cannot become an invented
on-time rate. Counts do not measure employee productivity.

WORK07 includes the full original due-date cohort, including unfinished work.
Final acceptance uses successful CEO decision audit history; later delivery
rework invalidates that acceptance. A period that has not fully elapsed is marked
preliminary. For 20 due tasks with 15 accepted on time, two late and three
unfinished, the rate is 75%; a later due-date extension does not change it.

## Source coverage

V53 adds measurement coverage/facts while preserving V1–V52 checksums and existing
business state machines. New facts share the business transaction; rollback cannot
leave a successful measurement behind. Existing governed history is reused where
it supports the definition. Legacy gaps remain visible rather than manufacturing
original deadlines or stage timestamps.

Workflow availability needs valid journey-probe attempts and outcomes; process
readiness alone is insufficient. Backup age and restore evidence require
independently recorded, deployment-specific successful completion. An available
CI restore artifact is evidence for that disposable CI stack and is not inserted
as a customer deployment's latest restore. Visitor stage breaches need governed
stage deadlines/policy history. Until those sources exist, their cards show
unavailable reasons. Dependencies reuse bounded current probes and expose safe
names/status rather than raw configuration or exception text.
An incomplete dependency aggregate remains unavailable; its records response may
include the sanitized observations that exist, with an explicit coverage reason.

## Rollout and rollback

Deploy frontend and backend from the same verified commit. Preserve encryption
keys, credentials, storage and database volumes. Flyway applies V53 additively;
validate a separate restored database before a persistent upgrade. No schema
down-migration or `flyway repair` is required.

`NEXT_PUBLIC_DASHBOARD_CARDS_ENABLED` defaults to `true`. To restore the previous
overview layout, set it to `false` at frontend build time and rebuild/reapply the
frontend image. Compose forwards the build argument; a runtime-only change cannot
replace a compiled public variable. Existing APIs and workflow routes remain
available. Preserve the new additive schema during a screen rollback.

The staging recovery rehearsal requires V53 and compares all restored Flyway
checksums before readiness and pinned-image reapply. CI requires the real
`AdministrationDashboardIntegrationTest` alongside the existing PostgreSQL/Redis
suites with zero skips. Local absence of Docker is not a database pass. Browser
checks cover role cards, period changes, drill-down paging, keyboard focus,
mobile overflow and the preserved visitor context.
