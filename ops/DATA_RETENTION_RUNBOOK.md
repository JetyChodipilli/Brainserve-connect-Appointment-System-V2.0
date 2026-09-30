# BrainServe data retention and legal-hold runbook

## Lifecycle

1. PostgreSQL keeps operational rows and monthly history partitions.
2. A dataset becomes archive eligible after its configured warm-storage period.
3. Active dataset, partition, subject, legal-hold, or investigation records block
   every destructive lifecycle step.
4. The archive worker writes GZIP JSON Lines through AES-256-GCM encryption to
   the private S3-compatible bucket.
5. The worker downloads the object again, checks SHA-256, decrypts it, validates
   every JSON record, and confirms the restored row count.
6. Only a `VERIFIED` archive with a completed restore test can be detached and
   removed from PostgreSQL.
7. At policy expiry, every object version and delete marker is removed. Former
   employee and appointment rows are anonymized; eligible visitor profiles are
   deleted.
8. Base backups and WAL expire through their independent backup lifecycle.
9. Every policy, hold, archive, restore, removal, anonymization, deletion, and
   backup-expiry action is appended to `data_governance_log`.

## Required production configuration

Generate an independent 256-bit archive key:

```bash
openssl rand -base64 32
```

Configure it without committing it:

```text
ARCHIVE_ENCRYPTION_KEYS=v1=<generated-base64-key>
ARCHIVE_ENCRYPTION_ACTIVE_KEY_VERSION=v1
```

For rotation, keep both keys configured until all `v1` objects have expired:

```text
ARCHIVE_ENCRYPTION_KEYS=v1=<old-key>,v2=<new-key>
ARCHIVE_ENCRYPTION_ACTIVE_KEY_VERSION=v2
```

Losing an old key makes its cold archives unrecoverable.

## Legal holds and investigations

- Only System Admin can place or release a hold.
- `DATASET` blocks the complete dataset.
- `PARTITION` blocks one monthly archive partition.
- `SUBJECT` blocks one record or person. For archive safety, any active subject
  hold conservatively prevents disposal of that dataset's mixed archive.
- A review date is a reminder, not an automatic release.
- Releasing a hold requires a reason and creates another immutable ledger entry.
- Never release a hold without written authority tied to its case reference.

## Restore test before database removal

The automated restore test is successful only when all three checks pass:

- downloaded encrypted bytes match the manifest SHA-256;
- AES-GCM authentication and GZIP decompression finish without error;
- every JSON row parses and the restored count equals the database count.

If any check fails, the object is removed, the manifest becomes `FAILED`, and
the PostgreSQL partition remains attached.

## Backup lifecycle

- `POSTGRES_BACKUP_RETENTION_DAYS` controls physical base backups.
- `POSTGRES_WAL_RETENTION_DAYS` controls archived WAL and must be at least the
  base-backup window.
- `BACKUP_LIFECYCLE_RETENTION_DAYS` records when disposed data is expected to
  have aged out of every recoverable backup.
- Physical backups cannot remove one person's rows selectively. Keep expired
  restores isolated and rerun retention before permitting application access.

Run a monthly restore drill using `ops/postgres/PITR_RUNBOOK.md` and keep the
result with the governance evidence for that month.


# Security hardening notes

This repository applies layered controls at the browser, API, database and
container boundaries. Security-sensitive changes must preserve these rules:

- Failed login counters and refresh-token family revocations commit in an
  independent transaction even when the rejected request rolls back.
- Refresh rotation and logout take a database write lock on the presented
  token. Concurrent replay revokes the complete token family instead of
  producing two successor sessions or leaving a raced successor active.
- Private employee documents are authorized against the authenticated actor's
  current role and department. Client-supplied owner identifiers are never an
  authorization decision.
- Uploaded JPEG, PNG and PDF files must pass size, declared-type, file-signature
  and ClamAV checks before private object storage is updated.
- Employee document access is department scoped. Visitor document mutation is
  denied until visitor identities have a trustworthy department-owner link;
  only the CEO may read already-stored visitor evidence.
- Raw refresh tokens are never persisted by the backend. Expired token hashes
  remain available for the configured investigation window and are then
  removed by the session-retention worker.
- Local infrastructure ports bind to loopback. Production deployments should
  expose only a TLS reverse proxy, the frontend and explicitly required health
  endpoints.
- `FORWARD_HEADERS_STRATEGY` defaults to `none`. Set it to `framework` only when
  the backend is behind a trusted proxy that removes untrusted forwarded
  headers before adding its own.
- The production frontend sends CSP, anti-framing, MIME-sniffing, referrer,
  browser-capability and HSTS headers. Extend CSP allowlists narrowly when a
  new trusted API or object-storage origin is introduced.
- The runtime frontend image is built from pruned production dependencies.
  CI rejects high/critical advisories in the complete npm graph. Dependabot
  opens bounded weekly npm, Maven and GitHub Actions updates for review.

## Re-audit status

The second pass found and corrected a refresh-token race, an unscoped visitor
document path, and a container boundary that copied development dependencies
into the runtime image. `npm audit --omit=dev` reports zero vulnerabilities.
The full graph has four moderate advisories under the development-only
`drizzle-kit` chain; npm's proposed remediation is a breaking downgrade, and
those packages are not copied into the production image.

No source review or dependency scanner can prove that an application has no
security defects. The remaining architectural risks requiring separate,
coordinated migrations are: bearer tokens remain readable by same-origin
JavaScript in per-tab `sessionStorage`, and the production CSP still needs
`'unsafe-inline'` for the current Vinext bootstrap. Moving refresh tokens to
SameSite HttpOnly cookies and adopting nonce/hash-based scripts must include
CSRF, CORS, deployment and browser regression work.

## Retention settings

`EXPIRED_SESSION_RETENTION_DAYS` defaults to 30 and is clamped to 1–365 days.
It applies only to expired refresh-token hashes; it does not delete audit,
appointment, work-board or legal-hold evidence. Existing governed dataset
retention remains managed through the Data Governance workspace and Flyway
policies.

## Browser session boundary

Access and refresh tokens remain in per-tab `sessionStorage` for compatibility
with the current stateless bearer-token API. Logout clears them before the
network request and then attempts backend revocation. A future move to
same-site HttpOnly refresh cookies requires to be coordinated CSRF, CORS and API
deployment changes and should be delivered as a separate migration rather than
mixed into a maintenance patch.
