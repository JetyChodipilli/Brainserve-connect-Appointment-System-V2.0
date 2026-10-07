# Sprint 10 — notification preferences and approval escalation

Implements PRD UX04 and FC01 on the existing notification delivery, Workboard, private evidence and visit approval workflows.

## Delivery preferences

Every active account can open Notifications → Delivery preferences, including System Admin. Saved account settings control routine inbox delivery, body-free email copies, browser sounds, immediate/hourly/daily cadence, IANA time zone and quiet hours. Daily delivery uses the next 09:00 boundary in the selected zone; hourly delivery uses the next hour. Overnight quiet windows and daylight-saving gaps/overlaps use real instants. Routine delivery resumes after the configured end.

Preferences are versioned, and changes retain an append-only snapshot. Stale writes return a conflict. Pending routine jobs use the current preference version; delivered messages and prior receipts remain intact. A disabled routine channel records suppression rather than deleting the notification. Mandatory security, approval/action, visitor, leave, escalation, urgent and high-priority review notices bypass routine channel/cadence/quiet choices. Existing mandatory account email flows remain required.

Digest email groups share a durable event key. Each source notification has an immutable receipt, so transport retries cannot create another email event. Each routine email claim rechecks current quiet hours, channel choice and account status, and uses the current email address. Email contains a sign-in prompt, not private message or evidence content. In-app messages remain individually acknowledgeable. Messages released from an earlier day appear in the current delivery-day inbox while remaining in retained history. Changes cannot recall a message already handed to its transport.

## Approval policies and queue

System Admin configures separate work and visit stage policies. Seeded policies are disabled. Enabling or changing a policy appends a version for newly entered stages; existing stages retain their captured policy, entry time and deadline. Deadlines use elapsed minutes, including weekends and holidays. The interface states this clock explicitly. Previously existing stages are marked with unknown original entry age rather than inventing historical timing.

The queue lists unresolved eligible stages and offers an overdue filter. Pages scan bounded source windows; an empty eligible page can still have another source page. System Admin sees stage identifiers and timing without private business content. Eligible reviewers can inspect retained work submissions and files through the existing current authorization checks.

The scheduled worker locks current policy and resource state in the established writer order. Stage/window/recipient receipts and mandatory notification inserts commit together. An advisory worker lock prevents competing workers from sending duplicate reminders. Notifications go to the current canonical reviewer, any currently valid delegate and the configured current escalation recipient. Missing eligible recipients cause a later retry. Resolution closes the stage and stops new reminders. Reminders cannot approve, admit a visitor, change the workflow or alter authored evidence.

## Scoped reviewer delegation

The canonical Team Lead, HR or Manager can delegate one current work or visit stage to an active alternate with the same role, department and base review permission. The grant records the owner, delegate, reason and expiry, bounded to 30 days. Approved leave, account lifecycle, role, employment, department, permission and canonical owner eligibility are checked again at action time. Owner leave can be covered without disabling their ability to delegate. The assignee cannot review their own work. Host identity and singleton final CEO authority remain unchanged and cannot be delegated.

The owner or current System Admin can revoke a grant; revoked or expired grants lose scope immediately for subsequent actions. Decisions use the existing domain transitions and observed resource version. The actual reviewer is retained in the audit; original submission authors and files remain intact. Delegation does not grant a role, permission, assignment-planning capability or permanent department access.

## Failure behavior and release checks

The preference, policy, delegation and decision forms block another write after an uncertain response or conflict until the user explicitly reloads current state. They do not automatically replay writes. Account/session changes cancel pending requests and clear private panels. Native labels, disclosures, visible focus and responsive fields support 360/768/1440 layouts.

`Sprint10NotificationPostgresIntegrationTest` exercises migrated production services with real PostgreSQL/Redis, including concurrent reminders/reviews, rollback, scope and current-eligibility changes, work/visit decisions and retained authorship. CI requires this suite to execute without skips and also runs all existing backend, frontend, browser and operational regressions. The disposable staging drill verifies TLS APIs, reminder delivery and revoked authority, then compares preferences, receipts, captured policies, stages and grants across backup restoration and pinned release reapply. Exact release counts and CI evidence are recorded on the sprint PR.

Deploy additive migrations V63–V64 before the application update. Keep policy, receipt and history tables during rollback; follow the existing separate-database restore runbook. This sprint adds internal delivery/email preferences and existing review escalation. External messaging integrations and configurable office-working-time clocks remain later roadmap work.
