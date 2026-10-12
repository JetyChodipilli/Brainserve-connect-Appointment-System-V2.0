# BrainServe Connect V2.0 release candidate

This candidate serves one company per deployment. Identify every installation by its tested immutable Git SHA and image tags. Commercial approval remains pending until the private acceptance record and original reviewer/customer evidence are complete. The repository's empty template is not an approved customer release.

## Delivered implementation

Sprints 1–4 provide staged install/recovery, governed sessions/rate policy, scoped KPI cards and company setup/imports. Sprints 5–10 add the Workboard, private scanned evidence, timelines/drafts/search, recurrence, controlled handover and notification/reminder/delegation policies. Sprints 11–14 add restricted support packages, Google Calendar and Slack delivery/recovery, group visits, restricted visitor devices and browser badges. Sprint 15 adds keyboard/accessibility checks and measured monitoring/load/recovery drills. Sprint 16 adds descriptive commercial/support records and the release handoff package. The individual sprint documents define behavior and limits; automated implementation evidence does not certify provider, hardware or customer UAT.

## Installation and second-company rehearsal

Use [the installation instructions](../README.md), the [staging runbook](../ops/staging/README.md), and the existing secret generator. Pin tested backend/frontend images, inject secrets outside images and commit history, configure the company email domain/branding/office clock and establish the single approved System Admin/CEO lifecycle. Run Company setup and reviewed imports through authorized workflows. Use a separate database, storage, keys, domains and service configuration for another company. Synthetic demo/CI fixtures remain in their disposable environment.

For the second install, record the candidate SHA, clean host/topology, start/end times, deployment changes, health/TLS checks, Company setup elapsed time, each role's core UAT and named operator. Target installation within one working day and administrator setup within 30 minutes as proposed acceptance; actual measurements are still required. Record every source edit as a failure of repeatable installation. Keep credentials and personal data out of the public report.

## Compatibility matrix

| Surface | Automated evidence / boundary | Acceptance still needed |
| --- | --- | --- |
| Server runtime | Java 21 and repository-pinned Maven dependencies in CI | Chosen host/operations environment |
| Frontend runtime | Node.js 24, locked dependencies, type/lint/build/regressions in CI | Customer deployment and reconnect/session renewal |
| Database | Additive V71 private profile; existing migration checksums retained | Customer backup/restore and private storage/key recovery |
| Browser | Playwright locked-runtime cases on Chromium/Firefox/WebKit; authenticated fixture cases on Chromium | Populated Chrome/Firefox/Safari role workflows, screen reader, zoom and high contrast |
| Phone/tablet/desktop | Automated narrow/tablet/desktop feature checks using existing responsive tokens | Chosen devices, text scaling and assistive technology |
| Previous application | Independently built Sprint 15 commit `3d51d65af1b7a026c1fd7fa9f55aa52063d6234e` exercised against isolated V71 restore | Secured-host rollback procedure and customer data loss/recovery targets |
| Google / Slack | Scoped consent, transport, delivery and failure fixtures | Real provider ownership, consent, renewal/revocation, retries and delivery UAT |
| Visitor kiosk / badge | Device/session/QR/browser-print tests; disabled by default | Selected scanner/tablet/printer and badge stock |

Deploy database/backend before the new frontend. A new frontend against an older API reports the unavailable source and blocks commercial editing. Application rollback retains V71 and the private profile table; older application code does not own its editing API. The disposable drill uses quiesced writes and checks retained business/profile fingerprints. Preserve backups, keys, private objects and audit evidence before a real rollout. Do not down-migrate, erase migrations or assume every historical app release is compatible.

## Capacity and recovery limits

Sprint 15's short single-runner smoke used 500 synthetic accounts and 50 sessions with direct fixture JWTs. It did not exercise real login/refresh/MFA or a three-tab, 60-minute customer workload and lacked adequate mutation samples for target passage. Its database-only quiesced recovery omitted off-host retrieval, WAL/PITR, object/archive/key recovery, DNS/host replacement and provider state. These results are implementation rehearsal evidence, not a supported capacity, SLA, customer RPO/RTO or full disaster-recovery promise. Use [Sprint 15's acceptance protocol](SPRINT_15.md) for representative measurements.

Accept service budgets, retention, integration/device exclusions, outage fallback and the support owner with each customer. Keep the chosen metrics private and route tested alerts to named recipients. Delivery uncertainty can produce provider duplicates; use the documented recovery acknowledgement instead of blind replay.

## Release decision

Collect the [pilot records](PILOT_UAT.md) and [commercial/support handoff](COMMERCIAL_HANDOFF.md). Copy `docs/release/acceptance.example.json` to secured customer evidence storage, bind its `releaseSha` to the installed candidate, and have reviewers authenticate each reference. Mark real outcomes and record optional feature exclusions only after confirming their service configuration is disabled. Run the checker with `--release-sha` and `--require-accepted`. A valid pending record is a visible handoff, never permission to advertise an accepted release.

After the three product-verification jobs succeed, CI publishes `v2-release-acceptance-packet` with a candidate-bound pending record, engineering job evidence, [acceptance runbook](release/ACCEPTANCE_RUN.md) and [native review record](release/ENGINEERING_REVIEW.md). It closes only the CI gate; the fourteen customer/operator gates, owners and release decision remain pending. Use the main-run packet for the merged release, copy it to private evidence storage and follow its procedures. The preparation command also creates a new local packet without overwriting an existing operator record.
