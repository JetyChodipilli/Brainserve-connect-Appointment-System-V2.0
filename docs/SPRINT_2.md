# Sprint 2: security, request limits and data freshness

This release implements PRD FND01–FND04. Existing role workflows, CEO visitor cards, Workboard actions and recovery screens remain covered by regressions. Flyway V51 and V52 are additive; applied migrations are unchanged.

## Sign-in and recovery

System Admin, CEO, HR Admin, Manager and Team Lead require an authenticator by default. First sign-in enrolls TOTP after any required temporary-password change. All enrolled accounts require MFA on future logins. Ten single-use recovery codes are shown once; store them securely before proceeding. Authenticator replacement requires fresh authenticator/recovery verification, preserves the old authenticator until confirmation, then rotates recovery codes and revokes old sessions.

My profile → Account security shows paginated active session families, their creation/renewal times and the current session. Other-session revocation, privileged permission/recovery changes and report exports require recent verification (default five minutes). Sign out everywhere remains available without additional verification. Disabling/archiving an account, changing authorities or revoking its session rejects existing access tokens. Live connections recheck eligibility every 25 seconds and close on revocation, expiry or security-state failure. Existing JWTs without a session ID require renewal/sign-in.

TOTP secrets use the existing AES-GCM encryption key; recovery codes are stored as account-bound hashes. A proof cannot be reused. Failed MFA attempts are persisted and limited to five within fifteen minutes. No recovery-code or MFA-secret browser persistence is introduced. If all authenticators and recovery codes are lost, this release provides no automatic MFA reset or administrative bypass: keep a recovery code available before rollout.

## Deployment configuration

Use the updated `backend/.env.example`. Compose forwards MFA, account operation limits, live-connection limits and freshness settings. Preserve `PII_ENCRYPTION_KEY`, `JWT_SECRET`, archive keys and existing data volumes. Deploy frontend and backend from the same verified commit; first validate a restored database. No production database is modified by the development checks.

Forwarded-header rewriting is disabled. `TRUSTED_PROXY_CIDRS` identifies only the physical reverse-proxy peers; empty means direct connections. The resolver walks numeric X-Forwarded-For addresses from the trusted right edge and ignores client-supplied prefixes. Staging pins Caddy to `STAGING_PROXY_IP` (default `172.29.91.10`) on `STAGING_SUBNET` (default `172.29.91.0/24`) and trusts that one `/32`. Change both when the subnet conflicts with the host network.

| Budget | Default |
|---|---|
| Login / refresh / logout per office IP | 600 / 1200 / 600 per minute |
| Uploads / export jobs / download links per account | 20 / 5 / 30 per minute |
| History and keyword searches per account | 120 per minute |
| Live connection attempts / simultaneous connections per account | 12 per minute / 3 |
| Distributed connection lease | 90 seconds, renewed every 25 seconds |

Persisted account password lockout remains independent of the broad IP budget. Real limit exhaustion returns 429 and remaining Retry-After; unavailable Redis/security state returns 503. Redis failure denies new costly operations and closes live connections, while read-only endpoints retain their existing database behavior. Leases expire after an instance crash and cannot be resurrected by a late heartbeat.

The existing bearer-token transport uses per-tab sessionStorage, omits cookies and does not enable credentialed CORS. This avoids ambient cookie CSRF; same-origin script execution can still read these credentials. Staging CSP restricts connections/images to the same origin plus required data/blob images and blocks framing/objects; it retains inline script/style compatibility for the current renderer. A nonce-based CSP and HttpOnly-cookie migration are separate architectural work, not claimed by this release.

## Dashboard freshness and tabs

Dashboard scope is resolved before cache lookup and rechecked before returning data. Source writes advance a persisted revision in the business transaction; a rolled-back write leaves no revision. Summary refreshes stamp their generation and source refresh time in the same transaction, after successful calculations. Dirty, incomplete, expired or raced snapshots cannot populate the Redis cache. After-commit refresh failure leaves successful business work intact and exposes stale data until scheduled recovery.

Overview and Reports distinguish source refresh/check time from response generation, age out automatically and label loading, stale, unknown and failed refreshes. Real zeros remain zeros. Historical ranges remain conservatively stale/unknown until actually refreshed. Scope changes and authorization failures clear previous metrics; temporary same-scope network errors retain clearly labeled prior values.

The revision row serializes source mutations while SQL summaries refresh. This deliberately simple mechanism favors correctness; measure contention before replacing it with per-department revisions. Personal live queries use the PostgreSQL observation time rather than a Redis-write clock.

Tabs coordinate one live connection per API/account via Web Locks or a bounded storage lease. Hidden tabs release leadership; foreground tabs refresh and reacquire it. Session changes abort old connections and reject delayed API responses. Messages contain refresh/state signals only. When coordination/storage is unavailable, direct tab connections remain constrained by backend quotas.

## Verification

Frontend checks cover type checking, lint, production build, source/runtime regressions, MFA enrollment/recovery and multi-tab behavior. Backend checks cover replay, ownership, fresh proof, account/session eligibility, proxy spoofing, quotas and cache races. CI additionally requires real PostgreSQL and Redis integration suites with zero skips, then builds the entire Compose stack, restores the V52 database and rechecks application readiness. Local absence of Docker does not qualify those integration suites as passed.

The merged dependency update failed the critical Next.js audit. This release preserves vinext beta.13 and plugin-rsc 0.5.35, upgrades Next.js and eslint-config-next to 16.3.8 and retains the CI audit gate.
