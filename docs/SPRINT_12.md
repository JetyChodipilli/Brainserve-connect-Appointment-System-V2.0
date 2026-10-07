# Sprint 12: Google Calendar

Sprint 12 implements the first provider in P1-08 / PRD FC04. Google Calendar is selected for one-way approved appointment delivery, recovery, and an authenticated calendar-file fallback. The roadmap exit requires real provider consent and create/update/cancel/reconciliation UAT. Automated transport fixtures and the disposable staging drill are separate evidence; live Google UAT is still pending.

## Operator workflow

The System Admin Integrations workspace starts consent in a separate browser tab. The operator grants the fixed permission, returns to the original BrainServe tab, reloads consent metadata if necessary, and explicitly finishes the connection. Browser callback completion alone cannot activate it. Completion requires the same BrainServe refresh-session family that started consent, current owner permissions, and recent MFA.

The backend uses a one-use authorization ticket, hashed state, PKCE, and a Secure HttpOnly SameSite=Lax browser-binding cookie. The two public routes are GET `/api/v1/integrations/google-calendar/authorize` and GET `/api/v1/integrations/google-calendar/callback`; connection and consent management remain authenticated. Tokens, codes, verifiers, and retained tickets are encrypted on the backend. Only the temporary authorize link reaches the initiating UI; metadata reloads return no link or provider credential. No provider credential is persisted in browser storage.

Unknown token exchanges consume the original code and require fresh consent. Before initial calendar creation, encrypted credentials and a provisioning uncertainty marker are committed durably. An uncertain calendar insert is not automatically repeated. Recovery accepts an operator-supplied calendar ID and verifies access and the connection-specific app marker. Reconsent checks the retained calendar rather than silently switching to another calendar. Local disconnect stops later delivery immediately and retains a bounded encrypted remote-revocation job; provider failure remains visible for explicit retry.

## Calendar ownership and privacy

The only Google scope is `https://www.googleapis.com/auth/calendar.app.created`. BrainServe creates a dedicated secondary calendar in the consenting Google account. It does not choose an existing personal calendar. Calendar-list discovery needs broader permissions, so unknown provisioning uses explicit verified ID recovery. The deployment's registered callback and Google's API destinations are fixed; operators cannot provide a destination URL.

Events contain a generic “BrainServe appointment” summary, UTC start/end instants, a deterministic opaque event ID, and private connection/revision markers. They contain no visitor or employee name, contact details, purpose, OTP, document, description, location, attendee, or invitation. Event visibility is private and provider reminders are disabled. Event writes use `sendUpdates=none`. UTC instants preserve the office booking's actual time through daylight-saving changes; the adapter does not reinterpret local strings.

| Committed appointment state | Provider behavior |
| --- | --- |
| APPROVED, CHECKED_IN, IN_MEETING | Create or update the managed event |
| CANCELLED, REJECTED, NO_SHOW, EXPIRED | Delete the managed event; absence is successful |
| Pending and reschedule awaiting reapproval | Keep the last approved external time |
| CHECKED_OUT, COMPLETED | Retain historical events |

## Delivery and recovery limits

Appointment transactions commit minimized outbox records before provider work starts. Worker transactions lock the owner, connection, and delivery in that order. Deterministic event IDs, retained mappings, private revisions, and provider ETag preconditions protect retry and ordering. Missing events returning 404 can be recreated; drifted managed events can be repaired. A cancelled event can be restored with a provider ETag when its app marker or retained mapping proves its identity. An unidentifiable 410 response or tombstone without an ETag fails safely and needs operator recovery; arbitrary deleted-event restoration is not claimed. Unmanaged events and higher remote revisions are not overwritten. Remote writes and database commits are not atomic; a timeout or failed commit can require later repair.

The native HTTP transport refuses redirects, limits request bodies to 16 KiB and responses to 64 KiB, and enforces a five-second deadline for a complete request including its body. A delivery makes at most three requests, including token refresh. The worker requires at least 35 seconds remaining on its 60-second lease and uses a 45-second transaction timeout. Holding these locks serializes provider ordering and local revocation, but can delay commands for that owner during a provider call. No exactly-once or cross-system transaction guarantee is claimed.

Reconciliation reads the latest committed source snapshot instead of replaying old events. It allows one active job, at most three requests per rolling 24 hours per connection, a five-minute cooldown, 25 resources per durable batch, and 500 resources per run. The ceiling counts actionable latest source resources, including terminal history. A retained ledger with more than 500 such resources needs a later scoped or incremental repair design; this increment rejects it explicitly. Status and scanned-resource progress remain visible. Existing retry attempt ceilings continue to apply.

The calendar-file download exports approved upcoming appointments over 30 days with a 500-event ceiling. Overflow is rejected instead of returning a partial file. The UTF-8 file uses RFC 5545 escaping, 75-octet folding, CRLF, UTC dates, and generic summaries. The authenticated download requires fresh MFA, records an audit event, and returns no-store, nosniff, and attachment headers. It is a snapshot and needs manual reimport when bookings change.

This increment provides no bidirectional sync, provider watches, subscription renewal, free/busy, room/resource booking, or invitations. Renewal here means OAuth token refresh and reconsent. The displayed credential horizon is the application's 90-day reconsent policy, not a promise that Google access lasts 90 days. Provider policy, account revocation, or refresh failure can require reconsent sooner.

## Deployment and live acceptance

Create a Google OAuth web application, enable the Calendar API, configure its consent screen and permitted test account, and register the exact HTTPS callback ending `/api/v1/integrations/google-calendar/callback`. Configure all three backend variables together: `GOOGLE_CALENDAR_CLIENT_ID`, `GOOGLE_CALENDAR_CLIENT_SECRET`, and `GOOGLE_CALENDAR_REDIRECT_URI`. The secret belongs in deployment secret storage. Blank configuration leaves Google unavailable while the fallback and simulator behavior remain accessible. Partial configuration is rejected at startup.

Keep callback query strings, Cookie, Authorization, token responses, and provider request bodies out of access logs and tracing. The shipped Caddy configuration has access logging disabled; upstream infrastructure must preserve this redaction. Callback pages use no-store and no-referrer. Revocation polling defaults to 30 seconds and processes a bounded set of durable jobs. Retain the application encryption key and database backups together under the existing access policy; changing keys without a planned migration makes retained credentials unreadable.

The required live test uses a customer-approved Google test account and a reachable configured test deployment:

1. Start, deny, and restart consent; verify the fixed permission and that callback alone leaves the connection inactive. Complete from the original BrainServe session and observe one dedicated calendar.
2. Approve a synthetic appointment and observe one generic event at the exact UTC slot. Update and reapprove it, then cancel it and observe removal. Confirm no attendees or customer data were sent.
3. Repeat a delivery after an unknown outcome; verify one event. Remove or alter a managed event in Google and run reconciliation; verify the current approved state is restored without overwriting a higher managed revision.
4. Test expired access refresh and required reconsent against the retained calendar. Exercise uncertain calendar provisioning ID recovery without creating another calendar.
5. Disconnect with work queued; verify no later BrainServe writes and completed Google revocation. Observe and retry a provider-outage revocation failure.
6. Record the tested commit, deployment, account owner, results, and safe evidence without tokens, codes, cookies, or customer data.

V67 and V68 add Google consent/calendar/revocation records and durable reconciliation records. CI requires the real PostgreSQL Sprint 12 suites with no skips alongside all earlier required database suites. Frontend regression and browser coverage exercise consent, recovery, account cancellation, and responsive controls. The TLS staging drill checks authorization, Google-disabled metadata, minimized ICS download, and nonempty terminal restore fixtures. It compares all four new tables and V68 checksums across restore and pinned-release reapply. Commit-specific counts and results belong on the PR; live UAT remains an explicit release gate.

## Design and implementation references

The requested gstack review workflow, UI/UX Pro Max async-form and confirmation guidance, Ponytail dependency discipline, kiranism feature boundaries, Unlazy ownership/gates, and GPT taste hierarchy guidance are applied within the existing compact Newsreader/Manrope dashboard. No dependencies were added for the provider, and existing simulator controls remain provider-specific.

Primary provider references: [server-side OAuth](https://developers.google.com/identity/protocols/oauth2/web-server), [Calendar scopes](https://developers.google.com/workspace/calendar/api/auth), [creating events](https://developers.google.com/workspace/calendar/api/guides/create-events), [Calendar error recovery](https://developers.google.com/workspace/calendar/api/guides/errors), [calendar creation](https://developers.google.com/workspace/calendar/api/v3/reference/calendars/insert), and [calendar-list scopes](https://developers.google.com/workspace/calendar/api/v3/reference/calendarList/list).
