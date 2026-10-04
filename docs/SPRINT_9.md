# Sprint 9 — handover, workload and work analytics

This increment implements PRD WB08, WB09 and WB10 and extends DASH01 on the existing Workboard, evidence and reporting foundations.

## Assignment planning

HR and Team Lead see assignment counts in their current department: outstanding work, due today, upcoming deadlines, overdue delivery, pending review and blockers. Approved leave and account/employment lifecycle changes are checked on every read. Unavailable people with retained assignments remain visible with an explanation. A department filter cannot extend current authority.

Estimates are explicitly maintained task inputs. Missing estimates remain unknown; partial totals report how many assignments have estimates. Available capacity remains unknown because there is no maintained capacity input. Counts are planning information and do not rank staff or claim productivity.

## Controlled handover

Handover changes delivery responsibility within the department and exact assignee role. It requires current HR or assigned Team Lead authority, the observed task version, a target and a reason. The effective time records an immediate transaction; this increment does not schedule future transfers. Active account, employment, department and approved leave are rechecked under the transaction's locks. CEO-closed work cannot be transferred. Reviewer delegation is a separate workflow.

The original assignee and original deadline remain immutable. Prior submissions, private files, checklist snapshots, draft evidence and review decisions are retained with their original authorship. A transferred outstanding task begins a revised assignment and requires fresh delivery; old acceptance cannot become the new assignee's acceptance. Legacy author measurements are labelled unknown rather than invented. The removed Employee loses current worksheet, discussion, search and evidence access. The new assignee can inspect retained evidence through the existing authorized task download path. Old and new eligible participants receive durable notices.

A stale version conflicts instead of overwriting another transfer or review. The interface retains entered fields and requires explicit reload and review of the current assignment. An uncertain response must be reconciled against the current receipt before another action.

## Measurements

The selected period is an inclusive range of office dates, bounded to 366 days. Current stocks use the time of the read independently of the period. All responses declare scope, office zone, measurement version and generation time. Source gaps, excluded observations and small samples remain visible.

| ID | Meaning |
| --- | --- |
| WORK01 | Outstanding tasks, with delivery and review stages kept separate |
| WORK02 | Outstanding tasks due on office today |
| WORK03 | Overdue current delivery; waiting for review is shown separately |
| WORK04 | Unresolved blocker intervals |
| WORK05 | Actionable stages for the current eligible reviewer |
| WORK06 | Distinct submitted task/cycle/evidence versions; unique tasks and attempts differ |
| WORK07 | Current valid evidence accepted before the original commitment cutoff, divided by the entire original due cohort |
| WORK08 | Review turnaround median and p95 per Team Lead, HR, Manager and CEO stage, alongside unresolved age |
| WORK09 | Finally approved tasks with recorded rework divided by the closed cohort, alongside current rework backlog |
| WORK10 | Resolved blocker duration p95, with invalid intervals excluded and measured coverage |
| WORK11 | Outstanding assignments per member, with known estimates and unknown capacity |
| WORK12 | Open work created before office today, counted once |
| WORK13 | Distinct task/audit-cycle final CEO approval events |

WORK07 uses immutable `work_original_commitment` cutoffs. Employee evidence is accepted by the Team Lead; direct Team Lead evidence is accepted by HR. Final CEO closure is measured separately. Changing the current due date cannot improve the original commitment result. Late and unfinished due tasks remain in its denominator. Rework and handover invalidate current acceptance. Retained records without reliable original commitments are excluded with declared coverage.

Authorized daily and department trends, record pages and bounded CSV exports use the same scope, filters and metric version. CSV fields are escaped against spreadsheet formulas. A record-level action rechecks current worksheet policy. Responses and private exports are not cached by the browser. Role or account changes cancel pending requests and clear retained data.

## Verification and release

The acceptance suites cover concurrent handover, current eligibility, true authorship, evidence access, original deadline cohorts, stage distributions, workload lifecycle signals, scope boundaries and export reconciliation. Required CI rejects skipped PostgreSQL/Redis suites. Browser verification covers keyboard operation, conflicts, session changes and 360/768/1440 layouts. Staging exercises actual TLS, scanner/private storage, handover, analytics, backup restoration and pinned release reapply. Final measured results are recorded on the sprint PR.

Deploy the additive migrations before the application update. Keep old evidence and original commitment facts. Restore and rollback follow `ops/staging/README.md`; the drill restores into a separate database and compares retained data. Do not reverse migrations or remove receipt/history tables as a rollback shortcut.
