# V2.0 reliability and architecture audit

Reviewed 30 September 2026 against V2.0 commit `261a62412602b866643c6d8b89bb59943ddaf758`. This report describes source review and automated checks, not a penetration test, live production capacity certification, or a completed SaaS implementation.

## Confirmed issues fixed

| Finding | Reproduction / impact | Change | Evidence |
| --- | --- | --- | --- |
| Pending GETs were keyed only by URL | Start `/profile/me` for account A, log out, sign in as B before it completes, request the same URL. B could share A's pending result. | Added a session generation to coalescing keys; login/logout invalidates pending work; stale responses are rejected, including responses whose JSON body finishes later. | Executable tests against the transpiled real API client. |
| Late token refresh could replace or revoke a newer login | An A refresh completes with tokens, or with 401, after B signs in. | Refresh checks the originating session before changing tokens or expiring the session. Normal token renewal preserves the generation and still coalesces concurrent refreshes. A stale completion cannot clear a newer refresh promise. Requests cannot retry under a different session. | Delayed success/rejection tests; concurrent expired-request test. |
| Limiter did not handle every Redis data-access failure consistently | A timeout could bypass the intended controlled security-state error. A missing script result could admit the request. | Catch Redis `DataAccessException` around the counter operation; return 503 for failure or null counter, without invoking business code. | JUnit tests for connection errors, timeouts, missing counters and denied downstream execution. |
| Browser JavaScript could not read the cross-origin retry hint | Server returned `Retry-After`, but CORS did not expose it. | Exposed `Retry-After` alongside the existing correlation/ETag headers. Limits and allowed origins are unchanged. | Security configuration coverage and code review. |

The request coalescer still merges only simultaneous GETs. It does not persist business data, merge mutations, or replay 429 responses. Requests already processed by the server cannot be undone by rejecting a stale browser response.

## Protections reviewed

| Area | Actual behavior | Assessment / limit |
| --- | --- | --- |
| Frontend feature boundaries | Workboard owns its page, hook, API, contracts, utilities and registration. Shared transport remains in `frontend/lib/api-client.ts`. | Structure remains intact; new transport changes apply consistently across features. |
| API and workflows | Feature API methods, role/department checks, appointment transitions, Workboard actions and after-commit notifications remain connected. | Existing contract and browser fixtures cover representative workflows; fixtures are not a live integration environment. |
| Rate limits | Redis Lua atomically increments a per-IP/per-operation counter and sets expiry. Login: 10 per 900 seconds; refresh: 120 per minute; public appointment creation: 20 per hour. Other OTP/recovery/cancellation budgets remain unchanged. | Security state now fails closed for Redis data-access failures. Office NAT and trusted proxies need a separate policy revision before commercial rollout. |
| Dashboard cache | Current actor scope resolves before lookup. Key includes actor, role, department, employee and date range. TTL defaults to 180 seconds, clamped to 60–300 seconds. | Cached content is scoped. Missing department scope is rejected before cache/SQL. Cache read/write failures and corrupt JSON fall back to database results. |
| Cache freshness | Existing TTL policy is retained; no general after-commit invalidation was added here. | Dashboard totals can remain stale after a mutation. Live security/emergency occupancy must use an uncached authoritative source and show its timestamp. |
| Browser tabs | Web Locks elect one SSE leader; BroadcastChannel distributes generic refresh signals. A storage lease supports browsers without Web Locks. | Three-tab runtime tests cover one stream, refresh propagation, leader closure/handover and cleanup for both paths. No business payload or token is broadcast. |
| Tab capability fallback | If locking/storage capabilities cannot coordinate, each tab can open its own stream. | One stream per browser origin is a supported-capability optimization, not a universal security guarantee. Backend quotas remain necessary. |
| Sessions | Access/refresh tokens remain in sessionStorage. Backend checks roles and account eligibility. | Existing model retained. XSS/CSP review and an explicit session design decision are still required; tab coordination is not protection against XSS. |
| Failure recovery | Browser request deadline 20 seconds; refresh once; SSE reconnect uses backoff/jitter; transient restoration retains credentials for retry. | Regression coverage remains. No automatic mutation replay was added. |
| Durable notifications | Existing database outbox, after-commit listeners, Kafka delivery and internal inbox remain. | Keep idempotency and replay coverage when adding external adapters. Three brokers on one host do not provide host-failure resilience. |
| Backend boundaries | Spring Modulith test checks public module access. | Existing test explicitly filters dependency-cycle violations. A passing check does not certify an acyclic backend. |
| CI/CD | Frontend working directory/cache/build/tests use `frontend/`; Java 21/Maven verification runs in `backend/`. | Automated validation exists. Production promotion, approvals, backups and rollback still need the deployment sprint; CI success is not proof of deployment readiness. |

## Open backlog, ordered by commercial impact

| ID | Priority | Required change / acceptance | Planned sprint |
| --- | --- | --- | --- |
| AUD-01 | High before pilot | Replace login's shared office-IP-only budget with tested per-account and broader per-IP controls. Define trusted proxies; reject spoofed forwarding headers. Demonstrate legitimate users behind one NAT and distributed attacks. Do not simply trust `X-Forwarded-For` or remove rate limits. | S2 |
| AUD-02 | High before pilot | Define freshness by dataset. Invalidate/version affected dashboard scopes after commit; retain TTL as a fallback. Verify role/department changes and cancelled/checked-in visits. | S2 |
| AUD-03 | High before production | Exercise TLS, secret injection/rotation, restore, service restarts, and deployment rollback on the chosen cloud stack. Include Redis security-state outages and queue backlog alerts. | S1, S9 |
| AUD-04 | Medium | Inventory backend cycles and remove one bounded group at a time through public interfaces/events. Replace the blanket cycle filter with an explicit baseline, then fail on new cycles. | S2, E8 |
| AUD-05 | Medium | Measure large frontend chunks and expensive queries before optimizing. Feature ownership is fixed; it does not itself reduce bundle size. Add route/feature loading boundaries only where measured and visually verified. | S2, S9 |
| AUD-06 | Medium before enterprise | Review browser token exposure, CSP, session revocation and SSO callback behavior with security tests. Any cookie migration needs CSRF/CORS and reconnect regression coverage. | S2, E1–E2 |
| AUD-07 | Required before SaaS | Tenant-scope SQL, cache, files, exports, sessions, async jobs, channels and integration credentials. Current code is single-company and must not be sold as tenant-isolated. | T1–T8 |

## Verification

- Frontend build includes TypeScript verification; lint has no errors and two existing image warnings.
- 276 frontend regression checks pass, including nine new executable transport/session checks.
- 28 browser checks pass: existing locked/recovery flows, backend-response fixtures and Workboard, plus two new three-tab coordination cases. The rejected-refresh login behavior is explicitly verified.
- Full local Maven verification has no failures/errors; five checks skip because no Docker runtime is available locally. Limiter, scoped-cache and CORS tests run locally. CI must run the Docker-backed checks before release.
- Cache tests verify current-scope lookup order, department-change separation, timeout fallback and corrupt-entry replacement with the configured TTL.
- This change edits shared transport and two backend security configuration files. It does not change feature JSX, style files, API endpoint bodies, database schema or business workflow state transitions.

## Remaining proof needed

Real OAuth/IdP consent, printer hardware, production proxy addressing, representative 50–500-employee load, restore timing, browser background throttling over long periods, and independent security review require a staging/pilot environment. Automated checks here establish regression evidence for the reviewed code paths; they do not eliminate these release requirements.

See [product roadmap](PRODUCT_ROADMAP.md) and [requirements and release gates](V2_REQUIREMENTS.md).
