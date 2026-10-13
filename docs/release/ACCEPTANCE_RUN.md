## Start the acceptance run

This packet prepares a single-company V2.0 release. It records no customer approval. Keep the populated packet and original sign-offs in secured customer storage; never commit them or upload them as public CI artifacts. CI publishes only the blank preparation packet and non-sensitive engineering results.

1. Pin the installed Git SHA and backend/frontend image digests. Confirm that `acceptance.json`, engineering evidence and the deployment all identify that candidate. For a PR artifact, the SHA is GitHub's temporary merge revision; obtain a fresh main artifact after the actual merge for the installed release.
2. Appoint the release approver, operations owner and backup, support owner/contact and participating customer representatives. Fill the existing owner fields only after appointment. Store the backup/escalation roster and customer identities privately with the support-handoff evidence.
3. Record the organization, environment/topology, data distribution, dates, browsers/devices, included Google/Slack/kiosk features and exclusions in private evidence. Unknown optional-feature values must stay unknown until authorized server configuration is inspected.
4. Run two distinct pilots. Follow the role/failure/accessibility matrix in `docs/PILOT_UAT.md`; keep separate pilot identifiers, participants, expected/actual outcomes, feedback, evidence and original customer signatures. An empty feedback list means feedback has not been supplied.
5. Record each gate's actual outcome in `acceptance.json`. A passed gate requires a stable evidence reference, the same release SHA, reviewer and UTC review time. Preserve failures and their reasons. Critical/high feedback requires a fix and candidate-bound retest; an open item prevents approval.

## Gate execution and evidence

| Gate | Responsible reviewer | Required procedure and evidence |
| --- | --- | --- |
| `ci` | Engineering reviewer | Inspect the referenced Actions run, its exact candidate SHA and all predecessor jobs. CI preparation may record this gate from successful job results; authenticate the actual run before approval. |
| `compatibility` | QA and operator | Populate real Chrome/Firefox/Safari and selected phone/tablet/desktop role flows, reconnect/session renewal and deployment compatibility; include the native review receipt. Automated fixture/screenshot checks are supporting evidence. |
| `second-install` | Operations owner | Install the pinned candidate on another clean organization environment with isolated DB/storage/keys/domain/configuration. Record host/topology, start/end, health/TLS, image digests and any source edits. Proposed target: within one working day. |
| `setup-timing` | System Admin and customer | Run company setup, approved CEO lifecycle and reviewed imports. Record elapsed time and failed/recovered steps; proposed setup target is 30 minutes. |
| `pilot-one` | First customer and QA | Execute every included role/failure workflow; record expected/actual behavior, defects, feedback and the first customer's original sign-off. |
| `pilot-two` | Second customer and QA | Repeat independently for the second customer; do not reuse the first customer's approval. |
| `capacity-soak` | Performance and operations reviewers | Run representative 500-account data, at least 50 active sessions, three-tab behavior and a 60-minute mixed workload with real login/refresh/MFA. Record workload/samples, p95/p99, errors, CPU/memory/pool/locks and induced-outage recovery. Proposed read p95 ≤500 ms, mutation p95 ≤1 s, API p99 ≤2 s and non-induced errors <1%; accept the measured envelope explicitly. |
| `manual-accessibility` | Accessibility reviewer and users | Populate keyboard, screen-reader, zoom, contrast and reduced-motion checks in agreed browsers and role flows. Record assistive technology versions and failures; automated rules are not screen-reader acceptance. |
| `full-recovery` | Operations and security reviewers | On an isolated secured host, retrieve off-host backups and restore DB/WAL, private objects, archive/key versions and configuration; rehearse host/DNS replacement and compatible rollback. Record actual lost-data window, elapsed recovery and data validation. Agree measured RPO/RTO; proposed ≤15 min/≤4 h targets are not promises before acceptance. |
| `alert-routing` | Operations owner and named recipients | Test backup-age/failure, readiness/dependency and delivery/escalation alerts. Record actual named recipient acknowledgement, elapsed escalation, backup recipient and out-of-hours path. Obtain authorization for real recipient tests before sending. |
| `support-handoff` | Customer and support owner | Agree severity definitions, hours/time zone, acknowledgement/escalation targets, maintenance path, incident intake, backup operator and rollback authority. Rehearse a redacted support-diagnostics incident and record operator/customer acknowledgement. |
| `offboarding` | Customer-authorized operator | On a separate secured environment, rehearse scoped readable exports/checksums/receipt, pending job reconciliation, connection/device/session revocation, transfer of ownership and retention/hold/backup handling. No production revocation or deletion follows from generating this packet. |
| `google-live` | Connection owner and QA | If included, verify real consent and approved create/update/cancel, reconciliation, credential renewal/revocation and retry recovery. Otherwise verify disabled server configuration and record a reviewed exclusion. |
| `slack-live` | Connection owner and QA | If included, verify real arrivals, credential lifecycle, bounded retry and uncertain-delivery recovery. Otherwise verify disabled server configuration and record a reviewed exclusion. |
| `kiosk-hardware` | Reception/security and device owner | If included, validate the selected scanner/tablet/printer, badge stock, reset, cancellation/expiry and outage fallback. Otherwise verify the disabled server flag and record a reviewed exclusion. |

These targets come from `docs/V2_REQUIREMENTS.md` and `docs/SPRINT_15.md`. Keep the full customer measurements private. A short CI fixture load or quiesced logical restore cannot replace soak/full recovery evidence. Only the three optional-feature gates can use `NOT_APPLICABLE`, with verified disabled configuration, reason and reviewer evidence.

## Record a supplied gate result

After the responsible reviewer has performed a gate and authenticated its original evidence, save a private JSON file with that gate's existing `id`, actual `status`, `evidence` and `reason`. Use the evidence format in `acceptance.json`; do not put credentials or temporary download links in it. `FAILED` requires a reason. An optional `NOT_APPLICABLE` result requires the corresponding feature to be explicitly verified disabled in the input record, a reason and reviewer evidence.

Record the supplied declaration into a new private file:

```bash
node scripts/record-release-evidence.mjs --record /secure/v2-release/acceptance.json --gate-file /secure/v2-release/reviewed-gate.json --output /secure/v2-release/acceptance-next.json --release-sha "$RELEASE_ID"
```

The command validates the existing and updated records against the installed SHA, changes only the selected gate and refuses existing output files. Inputs must be regular bounded JSON files: record ≤256 KiB, gate declaration ≤8 KiB. Output is mode 0600 on POSIX; use a private parent directory and appropriate Windows ACLs. Preserve prior versions and original signed evidence privately. A write failure may leave a private partial new file; inspect it before using or retrying.

This command records supplied attestations; it does not perform the check or authenticate a signature. It never appoints owners or changes release approval, and refuses approved records. Keep absent evidence pending. No customer/operator gate is closed merely by installing or merging this tool.

## Review and release decision

Use `ENGINEERING_REVIEW.md` and its linked PR receipt to check source review scope, final candidate, fixes and tooling limitations. Revalidate applicability whenever the candidate changes. The preparation command can record declared CI job results; it cannot authenticate original evidence, appoint owners or obtain customer consent.

From the checked-out candidate, validate the private record without approving it:

```bash
node scripts/verify-release-acceptance.mjs /secure/v2-release/acceptance.json --release-sha "$RELEASE_ID"
```

After all gates/feedback/owners/optional features are complete, the real release approver records `APPROVED`, their name and UTC time after the evidence reviews. Then run:

```bash
node scripts/verify-release-acceptance.mjs /secure/v2-release/acceptance.json --release-sha "$RELEASE_ID" --require-accepted
```

An approved marker cannot waive pending gates. Verify original signatures and evidence outside this format checker before any customer production decision. A SHA mismatch, open feedback or missing owner requires a pending decision and a new/reviewed packet.

## Prepare another candidate

```bash
node scripts/prepare-release-acceptance.mjs --release-sha "$RELEASE_ID" --output /secure/v2-release
```

The parent directory must already exist and the output directory must be new. Preparation refuses to overwrite prior operator records. Local preparation leaves the CI gate pending until verified evidence is supplied. POSIX output is restricted to directory mode 0700/file mode 0600; use a user-private directory and appropriate ACLs on Windows. Archive the original packet and sign-offs privately when a candidate changes.
