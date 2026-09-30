# BrainServe product roadmap and sprint plan

Planning baseline: 30 September 2026. Status: researched proposal for implementation; only the audit fixes described in [V2_AUDIT.md](V2_AUDIT.md) are implemented in this change. Requirements and testable release gates are in [V2_REQUIREMENTS.md](V2_REQUIREMENTS.md).

## Release decision

**V2.0 should be the repeatable, single-company SME product.** Offer a lean pilot first, finish the commercial package, then undertake enterprise work for committed pilot customers. True shared multi-tenant operation should be a separate V3.0 program with an isolation migration and security gates.

The existing role, department, company configuration, approval, retention, audit, reporting and notification capabilities are useful foundations. Extend them rather than create competing implementations. Keep the current interface and feature folder boundaries; add focused feature screens and adapters without recreating `brainserve-app.tsx`.

| Release | Customer outcome | Scope boundary | Planning feasibility* | Go/no-go trigger |
| --- | --- | --- | --- | --- |
| V2.0 / Phase 1 | Repeatedly deploy, configure, demonstrate and support one 50–500-employee organization | One organization per deployment; one initial calendar and one messaging provider | 4/5 | Two design partners, repeatable install and measured reliability |
| V2.1–V2.2 / Phase 2 | Enterprise identity and controlled multi-site pilots | Selected IdP and hardware/signing adapters; documented operational assurance | 3/5 | Customer supplies IdP test tenant, site policies and integration access |
| V3.0 / Phase 3 | Serve unrelated organizations safely on a platform | Explicit tenant isolation and lifecycle before billing or broad self-service | 2/5 | Repeated single-company sales, support capacity and isolation evidence |

*Judgment from the code and integration dependencies, not a probability of success. 5 means a bounded extension; 3 means significant external dependencies; 1 means a major platform redesign. Enterprise and SaaS work remain possible, but their cost and verification burden are materially higher.

## Reduce or postpone scope

| Requested item | Decision | Reason / replacement |
| --- | --- | --- |
| Company setup, branding, role/department onboarding | Keep and extend existing modules | Add a resumable wizard and validation, not a parallel identity/organization system. Avoid calling a single-company wizard tenancy. |
| Microsoft **and** Google calendar together | First provider chosen by the first two pilots; second after adoption | Start with approved appointment create/update/cancel and calendar-file fallback. Two-way sync, free/busy and room booking are later increments. |
| Teams **and** Slack together | First provider chosen by pilots | Start with arrivals and a link into the authorized app. Avoid sensitive visitor documents/OTP in messages. |
| Teams legacy connectors | Exclude from new implementation | Microsoft recommends Workflows or a supported bot approach; Workflows need owner/co-owner continuity. [R1] |
| Kiosk/tablet and badges | Keep a bounded browser mode | Automatic reset, restricted permissions and a tested print template. Native mobile apps, offline write queues and vendor printer SDKs wait for demand. |
| Subscription-ready packaging | Manual licensing and a documented support/renewal process first | No payment gateway or complicated usage billing in single-company V2.0. Enforcement must never block emergency/security access. |
| OIDC and SAML simultaneously | OIDC first; SAML only for a contractual requirement | Entra first, generic OIDC configuration for a validated second provider. Do not promise every IdP on day one. |
| SCIM | After SSO and access-lifecycle policy | Reuse account status/roles; deprovision must revoke sessions and dependent access. |
| Doors, signatures and watchlists | Pilot-specific adapters and policy approval | Hardware/vendor dependencies and operational false positives require explicit scope. No generic door platform or automatic denial based solely on fuzzy matching. |
| Enterprise emergency mode | Keep after live-occupancy accuracy is measured | Current visitor presence is not proof of employee attendance. Define the roster, stale-data warning and manual fallback before claiming full-building coverage. |
| Marketplace, custom regions, per-tenant keys, broad AI | Defer | Begin with an internal adapter registry; enable regional isolation/key boundaries only when architecture and contracts justify them. Governed reporting assistance is later, after access and evidence controls. |
| Rebuilding reports/retention/audit/notifications | Exclude duplicate implementations | Improve measured freshness, exports, dashboards and adapters on existing foundations. |
| Microservices/Kubernetes or removing Kafka just to simplify | Exclude from this program's initial scope | Keep a modular monolith and current durable delivery; change transport/topology only after measurements and delivery semantics are preserved. |

## Capacity and sequencing

One sprint is two weeks. Estimates assume one experienced full-stack engineer, about **60–70 focused engineering hours per sprint**, with part-time product/UAT, QA/security and operations input available. Engineering estimates include implementation, automated tests, migration/rollback work and documentation; customer approvals, OAuth verification, procurement and independent assessments can add calendar time.

A lean pilot is S1–S4 with a temporary manual/demo setup for integrations and printing: roughly 240–320 engineering hours, 8 weeks of work plus 1–2 weeks contingency. It is a supervised pilot, not the full Phase 1 claim. Full V2.0 plans ten sprints; unresolved acceptance gates delay launch rather than silently dropping protections.

### Phase 1: V2.0 commercial single-company release

| Sprint | Deliverable | Acceptance evidence | Dependencies / requirement IDs |
| --- | --- | --- | --- |
| S0 — completed audit increment | Session race and limiter fixes, cache/tab runtime coverage | CI green; reviewed audit backlog | AUD report; current change |
| S1 | Versioned production/staging deployment template, TLS, secret injection and encrypted backup/restore procedure | Install on a clean staging environment, rotate a secret, restore a backup and roll back an image | Chosen cloud/DNS/SMTP; P1-01, P1-02 |
| S2 | Office-NAT-safe rate policy, trusted proxies, dashboard freshness, metrics baseline and backend cycle inventory | Same-IP legitimate-user and spoofed-header tests; scope/freshness tests; recorded latency baseline | S1; P1-03, P1-04, CORE-01 |
| S3 | Resumable company setup and branding/policy configuration | Authorized admin completes/re-enters setup; invalid department/role combinations blocked; current theme preserved | Existing configuration/accounts modules; P1-05 |
| S4 | Guided admin/role onboarding, inline help, seeded demo, commercial pilot package | Fresh admin and receptionist finish scripted tasks; demo is separate from customer data; support/install checklist | S3; P1-06, P1-11 |
| S5 | Integration adapter foundation and connection/admin status screens | Encrypted credentials, disconnect/revoke, after-commit jobs, deduplication, bounded retries and failed-job replay | Provider choice + test accounts; P1-07 |
| S6 | First calendar provider: approved appointment create/update/cancel | Duplicate event delivery produces one event; cancellation clears it; renewal/disconnect and timezone tests | S5 + provider consent; P1-08 |
| S7 | First arrival messaging provider | Arrival event once per delivery key; 429/timeout retry with delay; permission-safe app links and owner runbook | S5; P1-09 |
| S8 | Restricted kiosk mode and badge printing | Auto-reset clears visitor data; staff admin inaccessible; print/QR tested on agreed tablet/printer | Agreed device matrix; P1-10 |
| S9 | Production dashboards/alerts, realistic load and restore drill | Agreed p95/p99 targets measured; Redis/queue/database failures alerted; RPO/RTO recorded | S1/S2 + representative data; P1-02, P1-12 |
| S10 | Pilot UAT, upgrade/rollback rehearsal, support and manual subscription packaging | All release gates signed off; second clean customer install demonstrated; known limits recorded | S1–S9; P1-11, P1-12 |

### Phase 2: V2.1–V2.2 enterprise pilots

Baseline fifteen sprints, taken only after the Phase 1 gate. Each row is one two-week sprint; a vendor delay can move a later row without changing its acceptance requirements.

| Sprint | Deliverable | Acceptance focus | Requirement IDs |
| --- | --- | --- | --- |
| E1 | Entra OIDC callback/session foundation | Issuer/audience/state/nonce, PKCE, key rotation and failed login | P2-01 |
| E2 | SSO role mapping, local break-glass process; validated second OIDC provider if required | No unapproved role grants; recovery/revocation; customer IdP UAT | P2-01 |
| E3 | SCIM users/groups and lifecycle adapter | Idempotent updates, inactive accounts, revoked sessions, least-privilege provisioning token | P2-02 |
| E4 | Location hierarchy and single-site data migration | Site context required, default site backfill and rollback rehearsed | P2-03 |
| E5 | Site policies and scoped operational UI | Cross-site authorization and approved global views | P2-03 |
| E6 | Watch/blocklists and security review workflow | Match review, expiry, correction, restricted evidence and audited overrides | P2-04 |
| E7 | NDA/safety/consent signing adapter | Versioned document/evidence, decline path, verified callback and retention | P2-05 |
| E8 | Live emergency visitor snapshot and roster model | Freshness/coverage indicators, restricted actions, no cached dashboard totals | P2-06 |
| E9 | Assembly roll-call and supervised operational drill | Offline/manual export, duplicate actions, recovery and drill evidence | P2-06 |
| E10 | First door/access vendor adapter with simulator | Issue/revoke contract, allowlisted destinations, idempotency and timeouts | P2-07 |
| E11 | Access vendor staging/pilot validation | Cancel/expiry/termination revocation, outage behavior, operator escalation | P2-07 |
| E12 | Central logs/tracing, operational alerts and module-cycle reduction | PII redaction, correlation, exporter failures, no new cycles | P2-08, CORE-01 |
| E13 | Enterprise load and disaster-recovery exercise | Published capacity envelope and measured RPO/RTO | P2-09 |
| E14 | Independent penetration test and prioritized remediation | Findings triaged with a named owner and dates; reruns for fixed findings | P2-10 |
| E15 | Remediation retest, enterprise UAT and support handoff | No unresolved critical/high findings; customer-approved site/vendor limits | P2-10 |

If SAML or a second door/signature provider is required, estimate it as an explicit extension after a discovery spike; do not substitute it into an already full sprint. Pen-test booking begins before E14.

### Phase 3: V3.0 multi-tenant SaaS

Baseline twenty-two sprints. The first eight build and prove isolation before unrelated companies share a production deployment.

| Sprint | Deliverable | Acceptance focus | Requirement IDs |
| --- | --- | --- | --- |
| T1 | Tenant threat model, isolation choice and migration inventory | Account/tenant relationship, administrator boundary and recovery design reviewed | P3-01 |
| T2 | Tenant catalog and trusted request context | Client-supplied tenant identifier never grants access | P3-01 |
| T3 | Database tenant schema/default-organization migration | Composite constraints and staged backfill rehearsed | P3-01 |
| T4 | Repository/query scope and database isolation enforcement | Guessed identifiers/joins/batch actions cannot cross tenants | P3-01 |
| T5 | Cache, files, search, exports and archive boundaries | Tenant keys/paths/access and retention proven | P3-02 |
| T6 | Jobs, outbox, webhooks and adapters carry trusted tenant context | Replay/retry routes data and secrets to the correct tenant | P3-02 |
| T7 | Session/role/support boundaries and tenant-scoped browser channels | Account switching and malicious channel/cache cases | P3-02, P3-07 |
| T8 | Adversarial isolation suite and migration rehearsal | Independent review and isolated restore; no mixed-company production yet | P3-01, P3-02 |
| T9 | Idempotent tenant provisioning and setup | Interrupted creation resumes safely; default roles/policies scoped | P3-03 |
| T10 | Suspend/export/delete lifecycle | Quiesce jobs, revoke sessions, retention/legal-hold/backup handling | P3-03 |
| T11 | Tenant branding/configuration | No global settings leakage; defaults/versioned changes | P3-04 |
| T12 | Custom-domain ownership/TLS lifecycle | Verified domain, safe host resolution, renewal and domain removal | P3-04 |
| T13 | Plans, entitlements and feature flags | Server authorization plus auditable plan transitions | P3-05 |
| T14 | Quotas, noisy-neighbor controls and usage ledger | Per-tenant budgets and immutable deduplicated usage events | P3-05 |
| T15 | Billing-provider sandbox and webhook ledger | Signed callbacks, duplicate/out-of-order events, reconciliation | P3-06 |
| T16 | Subscription lifecycle and invoices | Upgrade/downgrade/grace/refund policy and customer support workflow | P3-06 |
| T17 | Platform admin and controlled support access | Time-limited grants, customer visibility, complete audit | P3-07 |
| T18 | Region/residency deployment policy | Data, replicas, logs, backups and vendor routing match chosen region | P3-08 |
| T19 | Internal integration adapter registry | Version/permissions/secret ownership and safe rollback | P3-09 |
| T20 | Tenant analytics, audit exports and customer health | Scoped aggregates, minimized PII and access controls | P3-10 |
| T21 | Tenant restore/failure/load/billing exercise | Isolation under failures and load; usage/payment reconciliation | P3-11 |
| T22 | Paid SaaS pilot and independent gate review | Support readiness, exit/export process and no isolation defects | P3-11 |

Per-tenant encryption keys and additional regions need a separate threat model and operational estimate. They are not automatic consequences of adding `tenant_id`. Full legal entity, tax and invoicing requirements must be confirmed for the actual selling jurisdictions before P3-06.

## Engineering rates and budget

These are **planning assumptions, not researched contractor quotes or a sales valuation**. Replace the hourly rate with your actual team cost. A two-week sprint is not 80 fully available coding hours; the capacity assumption above includes review and coordination.

| Scope | Engineering effort | Base solo schedule | Budget at ₹750/hour | Budget at ₹1,250/hour | Budget at ₹2,000/hour |
| --- | --- | --- | --- | --- | --- |
| Lean supervised pilot | 240–320 h | 8 weeks + 1–2 buffer | ₹1.8–2.4 lakh | ₹3–4 lakh | ₹4.8–6.4 lakh |
| Full Phase 1 / V2.0, including pilot | 600–800 h | 20 weeks + 4 buffer | ₹4.5–6 lakh | ₹7.5–10 lakh | ₹12–16 lakh |
| Phase 2, additional to Phase 1 | 800–1,200 h | 30 weeks baseline; allow 24–36 before buffer | ₹6–9 lakh | ₹10–15 lakh | ₹16–24 lakh |
| Phase 3, additional to Phases 1–2 | 1,200–1,800 h | 44 weeks baseline; allow 36–56 before buffer | ₹9–13.5 lakh | ₹15–22.5 lakh | ₹24–36 lakh |

Carry approximately 20% contingency for Phases 1/2 and 30% for Phase 3. At the middle rate, full Phase 1 including contingency is ₹9–12 lakh; all three phases together are approximately ₹40.5–59.25 lakh including those contingencies. Treat Phase 2/3 estimates as early discovery ranges (roughly ±35–50% uncertainty), not fixed-price commitments. A dedicated QA/security/operations team, licensing, external assessments, hardware, hosting and applicable taxes are additional. Two engineers may shorten the schedule, but dependencies prevent simply halving it.

Do not choose a subscription selling price from build cost alone. Validate willingness to pay with the pilot organizations, then quote a separate setup/onboarding fee, recurring subscription, support tier and paid integration work. Procurement can require an annual contract and a service level even when billing is manual.

## Hosting and operating cost

Verified public pricing anchors: AWS Lightsail lists Linux public-IPv4 instances at $44/month for 8 GB and $84/month for 16 GB, and load balancing at $18/month. These are individual components, not a complete BrainServe production quote. [R9] Validate region, tax, transfer, storage, database, backups, SMTP, secrets and monitoring before ordering.

| Environment | Initial monthly budget allowance (USD) | Qualification |
| --- | --- | --- |
| Small supervised pilot | $60–150 | Non-HA; may be inadequate for the full current stack without measured sizing. Keep it separate from production. |
| Single-company SME production | $150–400 | Planning allowance for app/data/supporting services and backups; benchmark Kafka/ClamAV and explicitly choose resilience. Independent-host HA can exceed it. |
| Enterprise pilot / resilient deployment | $400–1,500+ | Dedicated resources, observability, redundancy and retention; capacity/SLA determines the actual design. |
| Multi-tenant SaaS | Capacity-based after a pilot | Model active users, peak visits, files, email/API usage, regions and per-tenant isolation. No defensible flat estimate yet. |

The budget allowances are engineering estimates, not provider offers. Existing Microsoft/Google/Slack/identity accounts, signing/door vendor fees, printers/tablets, independent penetration tests and support labor are separate. Google Calendar's documentation currently says standard API use has no additional cost and describes planned over-quota billing later in 2026; do not promise permanent free/unlimited usage. [R3]

## Integration research translated into requirements

- Teams: choose Workflows or a supported bot, register owner/co-owner and an operational handoff; do not build against retiring connectors. [R1]
- Calendar: persist external IDs, make updates/cancellations idempotent and renew subscriptions. A later Google two-way adapter must process deleted entries and recover from invalid sync tokens with a scoped full resync. [R2–R4]
- Messaging: respect provider quotas and `Retry-After`, retain failed delivery jobs, keep secrets out of frontend/logs, and expose a connection-health view. [R5]
- Identity: validate the OIDC protocol instead of trusting email strings. SCIM provisioning/deprovisioning must map to the existing account lifecycle and session/access revocation. [R6–R7]
- SaaS: authentication alone is not tenant isolation. Establish server-side tenant context and isolation across every storage and processing layer before multi-company launch. [R8]

## Later product opportunities

Prioritize mobile approvals/scanning through a responsive/PWA increment once kiosk patterns are proven. Add contractor/vendor passes when customers need recurring access windows and induction. Improve occupancy/wait-time/approval analytics from existing reports rather than creating another reporting engine. Open a public marketplace only after multiple stable internal adapters and support contracts exist. AI-assisted summaries require scoped inputs, evidence links, human review and no autonomous access/security decisions.

## Primary sources

Researched 30 September 2026; recheck pricing and provider policies at implementation.

- R1: [Microsoft Teams incoming webhooks, connector retirement and Workflows](https://learn.microsoft.com/en-us/microsoftteams/platform/webhooks-and-connectors/how-to/add-incoming-webhook).
- R2: [Microsoft Graph change notifications and subscription lifecycle](https://learn.microsoft.com/en-us/graph/change-notifications-overview).
- R3: [Google Calendar quotas and current/planned cost policy](https://developers.google.com/workspace/calendar/api/guides/quota).
- R4: [Google Calendar incremental synchronization and expired-token recovery](https://developers.google.com/workspace/calendar/api/guides/sync).
- R5: [Slack API rate limits](https://docs.slack.dev/apis/web-api/rate-limits/) and [incoming webhook installation/secrets](https://docs.slack.dev/messaging/sending-messages-using-incoming-webhooks/).
- R6: [Microsoft identity OIDC protocol](https://learn.microsoft.com/en-us/entra/identity-platform/v2-protocols-oidc).
- R7: [Microsoft Entra SCIM provisioning guidance](https://learn.microsoft.com/en-us/entra/identity/app-provisioning/use-scim-to-provision-users-and-groups).
- R8: [AWS SaaS tenant isolation strategies](https://docs.aws.amazon.com/whitepapers/latest/saas-tenant-isolation-strategies/saas-tenant-isolation-strategies.html).
- R9: [AWS Lightsail pricing](https://aws.amazon.com/lightsail/pricing/).
- R10: [OWASP API resource consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/), [OWASP browser storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/HTML5_Security_Cheat_Sheet.html), and [Spring Modulith verification](https://docs.spring.io/spring-modulith/reference/verification.html).
