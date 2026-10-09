# Pilot UAT and feedback protocol

Run two distinct supervised customer pilots against the candidate SHA. No pilot feedback has been supplied for Sprint 16; the empty register means input is missing, not that customers found no issues. Keep identifiable data, original signoffs and detailed incident evidence in secured customer storage. Use redacted summaries and stable packet references in the acceptance record.

## Pilot record

For each pilot, record a private pilot identifier, candidate/image SHA, company configuration, host/topology, browser/device versions, data distribution, test dates, participating roles, operations/support owners, included optional features and accepted limits. Record expected versus actual outcomes, defect IDs, screenshots or measurements and reviewer/customer signoff. Never use another pilot's approval as evidence for this one.

| Workflow | Expected outcome and evidence |
| --- | --- |
| System Admin | Install/setup, approved CEO lifecycle, scoped settings and release/support record; verify MFA and a stale-tab conflict |
| CEO | Company scope, account/leadership approvals, visitor counts and report reconciliation; private admin records remain inaccessible |
| HR Admin | Employee onboarding, department/account approvals, leave/termination and lifecycle recovery within assigned scope |
| Manager / Team Lead | Scoped assignments, approvals, Workboard updates, handover, recurrence and original-deadline analytics |
| Employee | Own tasks/comments/evidence, notification preferences, saved draft recovery and denied other-person records |
| Reception | Booking/group visit, arrival/approval follow-up, cancellation and agreed badge printing |
| Security | Signed pass checks, authorized check-in/out, cancellation/expiry rejection and manual outage fallback |
| Shared failure paths | Login/refresh/MFA, three-tab ownership, Redis/provider outage, reconnect, lost write response, export recovery and account switch |
| Accessibility | Populated role flows by keyboard/screen reader in agreed real browsers; zoom, reduced motion, contrast and visible focus |
| Optional integrations/devices | Real Google/Slack lifecycle/delivery UAT and selected kiosk/scanner/printer checks when included |

A paused/cancelled agreement must leave normal authorized operations available. Expired/revoked account permissions must still fail. Check optional service configuration separately; no browser flag or commercial record can grant an integration or staff permission.

## Feedback register

Use `pilotFeedback` in the acceptance record with a unique `id`, redacted `summary`, `priority`, `status`, named `owner`, optional `followUpBy` and an evidence object. A fixed item needs candidate-bound reproduction/retest evidence. Critical/high items cannot use risk acceptance. An accepted medium/low item needs authenticated acceptance evidence and a current UTC follow-up date; an open item prevents approval. Preserve the original report and before/after result in private evidence rather than deleting unresolved feedback.

Evidence objects contain `reference`, `releaseSha`, `reviewedBy` and `reviewedAt` (UTC ISO time). Use a stable secured packet identifier or HTTPS reference without credentials/query tokens. The checker verifies declared completeness and consistency; reviewers must verify the actual result and signoff. Any candidate change requires reviewing which prior evidence still applies and updating the candidate-bound references.

## Acceptance run

Measure second install/setup timing, the representative 60-minute workload, full recovery/data loss, alert delivery/escalation and offboarding separately. Apply the targets and exclusions in [V2 requirements](V2_REQUIREMENTS.md) and [Sprint 15](SPRINT_15.md). Record the actual supported envelope, accepted limits and responsible owners. The release owner approves only after both pilots and every included feature's acceptance are complete. Optional providers/devices can be excluded with a reviewed reason and verified disabled server configuration; core release gates cannot be waived by an exclusion.
