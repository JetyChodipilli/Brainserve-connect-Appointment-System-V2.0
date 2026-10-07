# Sprint 11: integration foundations and support diagnostics

Implements the foundation of PRD FC04 and the redacted support package in UX06. Real calendar consent/reconciliation and messaging-provider UAT remain Sprint 12 and Sprint 13.

## Administrator connections

The connected System Admin workspace has Integrations and Support diagnostics screens. Connections record their owner, provider, fixed minimum scopes, credential expiry, current status, health-check result and version. Sprint 11 exposes only the local calendar and messaging simulators. It accepts no arbitrary destination URL and performs no provider network requests.

Connection credentials are write-only, encrypted with the existing AES-GCM sensitive-value converter and the externally supplied `PII_ENCRYPTION_KEY`. Responses, attempts, audit details and support exports exclude them. Secrets must be injected through the deployment secret mechanism, retained for restore and rotated under the existing encryption runbook. Reconnect replaces the credential and increments its generation; revoke erases its active ciphertext. The simulator verifies envelope/retry semantics, not real provider credentials or OAuth consent.

Every endpoint requires an active current System Admin with `SYSTEM_CONFIGURE`. Connections belong to their creator; another administrator cannot inspect or mutate those connection details. Credential writes and support endpoints additionally require the existing recent MFA proof. Creation/test/retry request IDs and observed versions prevent duplicate submissions and stale updates. Writes do not automatically replay after an uncertain response. The interface requires an explicit reload and clears credential fields after submission and account changes.

## Committed delivery and recovery

Appointment and check-in writers capture minimized events and delivery jobs in the same database transaction. Provider work begins after commit. Payloads retain only the appointment identifier, status/type, slot instants and event timing; they exclude visitor names, contact details, purpose, OTPs, documents and message bodies. The foundation records source workflow states; the first real calendar adapter must apply the approved-appointment mapping and cancellation rules during Sprint 12 UAT.

Each resource has a monotonically allocated revision. A source event UUID deduplicates repeated listener delivery. Newer revisions supersede older queued effects. Each connection/business-event pair has one durable delivery record; the simulator stores persistent receipts and external-ID mappings. Leases and credential generations fence expired workers, reconnects and revocations. Attempt evidence is append-only. Success, outage, rate limit, reauthentication and permanent failure can be exercised from the simulator test control. Connections are capped at 100 per owner and 1,000 in total.

Automatic delivery is bounded to five attempts per cycle and twenty total attempts. Authorized manual retry is limited to three cycles and requires current connection eligibility and the observed delivery version. A failed provider test remains visible. Connection revocation stops pending work, and failed provider delivery does not reverse a committed appointment. The scheduler processes bounded batches; provider-specific consent, subscription renewal, request timeouts, `Retry-After` parsing and callback verification belong to the real adapters.

## Support diagnostic package

Support shows the included-field preview before generation. The package has a schema version, a generated UUID support reference, a one-to-twenty-four-hour window, vetted environment/version/build metadata, database migration/read status and bounded integration connection/delivery counts. Free-form correlation headers, environment dumps, URLs, connection labels, usernames, emails, business IDs, logs, stack traces and raw business JSON are excluded.

Generation and retained downloads pass a strict field/type/value allowlist. An unknown field, malformed count, invalid snapshot or excessive size blocks export. Packages are at most 64 KiB, expire after twenty-four hours and can be downloaded only by their creator while still an eligible administrator. Generation and successful download are audited. Preview and download responses use `no-store`; downloads use an authenticated JSON attachment. Expired payloads are removed in bounded cleanup batches. Metadata-only request receipts remain so retrying the same request UUID cannot renew an expired package. Each owner can retain at most twenty live packages.

Safe metadata is configured through `SUPPORT_ENVIRONMENT` (`UNKNOWN`, `DEVELOPMENT`, `TEST`, `STAGING`, `PRODUCTION`), `SUPPORT_RELEASE_VERSION` (numeric semantic version) and `SUPPORT_BUILD_REVISION` (full hexadecimal Git SHA). Invalid configuration becomes `UNKNOWN` and is never exported verbatim. Missing measurements are not described as healthy provider connectivity; simulator health and database readability have their own meanings.

## Verification and deployment

CI requires `Sprint11IntegrationPostgresIntegrationTest` and `Sprint11SupportPostgresIntegrationTest` to execute against migrated PostgreSQL, alongside all existing PostgreSQL/Redis suites. Browser tests cover 360, 768 and 1440-pixel layouts, credential clearing, explicit reload after conflicts or uncertain writes, session cancellation, retry/revoke controls and preview/download behavior. Existing typecheck, lint, build, regression, dependency audit and module-boundary checks remain required.

The disposable TLS staging drill exercises simulator success/failure/retry, credential revocation, authorized support download and expiry. It compares every Sprint 11 connection, event, revision, delivery, receipt, attempt, mapping and diagnostic table across isolated backup restoration and pinned-release reapplication. Release counts and commit-specific CI evidence belong on the sprint PR.

Deploy additive Flyway migrations V65–V66 before the updated application. Keep integration event/receipt/mapping/history tables and the encryption key during application rollback. Use the separate-database restore procedure; do not reverse these migrations or remove evidence tables. An older application may ignore the new tables but cannot process integration jobs. During rollback, stop the new scheduler and accept an explicit integration delivery pause until compatible code is restored.
