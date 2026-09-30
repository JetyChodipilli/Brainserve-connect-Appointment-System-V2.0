# Sprint 1 — deployment and KPI baseline

Scope follows the BrainServe Connect V2 PRD: reproducible staging installation,
restore/rollback preparation, and KPI query reconciliation fixtures. The Admin
dashboard contract and cards belong to Sprints 3–4; the Workboard list, board,
drawer, and quick filters belong to Sprint 5.

## Delivered

| Outcome | Implementation | Acceptance evidence |
| --- | --- | --- |
| Reproducible staging | Existing stack plus `ops/staging/compose.yml`, commit-tagged application images, same-origin API, TLS proxy | `Staging & recovery` Actions job installs the complete stack on a disposable runner |
| Recovery preparation | Private custom-format dump, checksum validation, separate restore database, no overwrite or live target | Operational rejection tests and a real PostgreSQL restore/application readiness check in CI |
| Rollback preparation | Pinned image reapply and recorded schema checksums; additive migration | CI rehearses pinned reapply; the runbook specifies choosing a previously verified release and preserving volumes |
| KPI reconciliation | Real V37 → V38 upgrade plus synthetic source fixtures | `KpiReconciliationIntegrationTest`, required without skips in CI |
| Correct baseline | Sample-weighted wait mean including zero; unavailable denominators return null; latest workforce snapshot; department-scoped inside counts | PostgreSQL reconciliation fixtures and existing cache/scope regressions |
| Targeted UI accuracy | In-workflow card preserves a reported zero | Browser fixture contains a pending appointment while the metric stays zero |
| Dependency CI | Group coupled React packages; test built Workers in their actual runtime | Existing regression suite and rendered production HTML test |

No existing Flyway migration is edited. V38 adds nullable sample totals and counts,
preserves legacy metric columns, and wraps the existing refresh functions. Older
aggregates retain an unknown denominator rather than receiving invented coverage.
The live day and month are refreshed at installation. The dashboard cache version
changes so old summaries cannot conceal the correction.

## Verification

```sh
cd frontend
npm ci
npm run typecheck
npm run lint
npm run build
npm run test:regression
npx playwright install --with-deps chromium firefox webkit
npm run test:e2e
cd ../backend
mvn --batch-mode --no-transfer-progress clean verify
cd ..
node scripts/assert-database-tests.mjs
node --test --test-isolation=none scripts/tests/*.test.mjs
```

Java 21, Maven 3.9+, Node 24, and Docker Compose v2 are the CI toolchain.
Docker is required for database reconciliation. Local skips do not satisfy the
database acceptance criteria: the separate CI check rejects missing, skipped,
failed, or empty PostgreSQL suites. The optional large-data performance suite
remains outside Sprint 1; no production throughput or recovery-time claim is made.

CI artifacts contain test reports, schema checksums, release ID, and staging
results. Secrets, database dumps, rendered application data, and TLS private keys
are excluded. See [staging runbook](../ops/staging/README.md) and
[metric definitions](KPI_BASELINE.md).

## Release boundary

The CI environment is an ephemeral staging rehearsal. A customer's persistent
staging host, DNS, externally trusted certificate, SMTP provider, encrypted
off-site backups, recovery owners, and measured RPO/RTO must be configured before
production rollout. The three Kafka containers share one host; this deployment
does not establish host-level availability.

This release establishes the first verified container fallback. Reapplying it is
tested; rolling back to an older unverified image is not represented as a completed
drill. Future releases must retain and test the previously verified images.
