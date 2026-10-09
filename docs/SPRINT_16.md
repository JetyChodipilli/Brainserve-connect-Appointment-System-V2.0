# Sprint 16 — release preparation and commercial handoff

Scope: the final Phase 1 implementation sprint, including P1-11 manual agreement/renewal metadata, support ownership, optional feature visibility, compatibility evidence, release documentation and a checked pilot acceptance record. Customer UAT and production release approval are separate decisions. No pilot feedback or customer signoff was supplied for this implementation.

## System Admin release workspace

Open **Release and support** from the System Admin workspace. The service requires an active System Admin with `SYSTEM_CONFIGURE` and fresh MFA proof for both reads and writes. Reads use `no-store`. A single private `release_profile` row holds a bounded typed JSON record; V71 is additive and the initial status is `UNCONFIGURED` with empty support fields and null dates. This table stays outside generic workspace/system settings, including when the previous application is running.

Record an agreement reference, start/renewal dates, support owner, support contact email and agreed hours/time zone. `PILOT` and `ACTIVE` require the complete term and support details. Dates must be a complete, ordered pair. The displayed renewal notice uses the running office time zone and changes no permissions or service behavior. `PAUSED` and `CANCELLED` are descriptive commercial records. No authorization, session, appointment, visitor, export or security writer reads this record to decide access.

`GET /api/v1/release-profile` returns the observed version, record, office date/zone and renewal notice. `PUT` accepts `{expectedVersion, profile}` and rejects unsupported fields, invalid types and stale versions. The row is locked and updated in one transaction with a minimized audit event. Reload after a conflict or an unconfirmed response; writes are not automatically replayed. Account changes cancel pending requests and clear record/form values. Demo mode does not fabricate or persist a commercial agreement.

Optional Google Calendar, Slack and visitor kiosk availability comes from existing provider configuration APIs and the protected `GET /api/v1/admin/kiosks/config` response. Failed reads display **Could not verify**. Agreement status cannot enable these features; service configuration, connection ownership, normal authorization and device restrictions remain authoritative. Configured availability is not provider/hardware UAT.

## Release and pilot records

The [release candidate guide](RELEASE_CANDIDATE.md), [pilot UAT and feedback register](PILOT_UAT.md), and [commercial/support handoff](COMMERCIAL_HANDOFF.md) cover installation, compatibility, role workflows, measured limits, support escalation and customer exit. The [acceptance template](release/acceptance.example.json) intentionally starts with a pending decision, unknown owners/features and fifteen pending evidence gates.

The portable checker validates the inventory, candidate SHA, evidence/reviewer/time references, feature exclusions and feedback dispositions. An approval marker cannot waive pending gates. Critical/high feedback needs a recorded fix and recheck; accepted medium/low feedback needs an owner, evidence and a current follow-up deadline. Only a verified disabled optional provider/device can have a reviewed exclusion. It does not authenticate referenced evidence or provide customer consent. Store populated records and original signoffs privately.

```bash
node scripts/verify-release-acceptance.mjs docs/release/acceptance.example.json
node scripts/verify-release-acceptance.mjs /secure/customer-release.json --release-sha "$RELEASE_ID" --require-accepted
```

The first command validates a pending template. The second exits nonzero until complete recorded approval matches the installed immutable release.

## Verification and compatibility

Focused browser coverage checks keyboard forms, native date/required-field constraints, mobile/tablet/desktop layout, unavailable and malformed sources, stale-version/lost-response recovery, optional feature uncertainty and account-change fences. Existing regression and browser suites remain required. A dedicated real PostgreSQL/Redis suite checks persistence, concurrent writers, minimized audit, role/MFA boundaries, generic-settings isolation and cancellation independent of sessions/configuration. CI rejects skipped database coverage.

The disposable staging drill writes a synthetic agreement, rejects invalid/stale/unauthorized requests, records cancellation and then runs real Workboard load while cancellation remains recorded. Restore fingerprints retain the private profile. Current restored/reapplied applications read the same record; the independently built Sprint 15 application reads retained business data against V71 while its generic settings API cannot return the new private metadata. Protected recovery reads renew the persisted synthetic administrator proof before issuing their fixture JWT: slow restarts keep the production five-minute MFA policy intact. This does not test genuine login, MFA or refresh. No schema down-migration is used. Published-head Frontend, Backend and Staging & recovery CI must pass before implementation acceptance; evidence is recorded on the Sprint 16 PR.

## Outstanding commercial acceptance

Two real pilot UATs and supplied feedback; second-organization installation/setup timing; representative capacity/session-renewal soak; populated manual browser/screen-reader/zoom checks; complete secured-host/object/archive/key recovery with approved RPO/RTO; alert recipient/escalation acceptance; named operations/support owners and offboarding rehearsal remain customer/operator gates. Google, Slack and selected hardware need live acceptance when included. Kiosk stays disabled by default. Sprint 16 implementation does not declare V2.0 commercially approved.
