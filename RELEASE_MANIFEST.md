# BrainServe Connect — Debugged Full-Stack Release

This package contains the complete frontend and Java 21 Spring Boot application,
including PostgreSQL Flyway migrations V1–V46 and local orchestration for Redis,
Kafka, Mailpit, MinIO and ClamAV.

## Start the complete local stack

```bash
npm run env:init
docker compose up --build -d
npm run verify:stack
```

Open:

- Frontend: `http://localhost:3000`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Development email inbox: `http://localhost:8025`
- MinIO console: `http://localhost:9001`

The initial System Admin password is generated locally and printed once by
`npm run env:init`. No application password, OTP, signing key or database secret
is embedded in this source package.

## Final integration scope

- 152 production frontend API methods, all referenced by a frontend workflow.
- Durable PostgreSQL-backed internal calls published through Kafka after commit,
  acknowledged by the consumer and retried from queued/failed records.
- Public booking writes both the consented visitor identity and appointment.
- Security and Reception can search and verify visitor identity records.
- HR can manage effective-dated compensation and scanned private employee files;
  CEO receives read-only access where authorized.
- CEO pending termination approvals and Manager ownership APIs are connected.
- System Admin can inspect PostgreSQL, Redis, Kafka, SMTP, object storage and
  malware-scanner readiness from Settings.
- Docker Compose includes the frontend, backend, PostgreSQL with backups, Redis,
  Kafka, Mailpit, MinIO and ClamAV.

## Validation completed in the packaging environment

- TypeScript strict type checking and the production frontend build passed.
- ESLint passed.
- 220 automated contracts passed.
- 294 Java source files passed Java grammar parsing.
- All 45 Flyway migration files, V1 through V46, executed in order against an
  isolated PostgreSQL-compatible audit database.
- The production frontend server returned HTTP 200 in a local startup smoke test.
- Generated environment and Docker Compose configuration checks passed.
- Production dependency audit reported zero known vulnerabilities.

The packaging environment did not contain Java 21, Maven or Docker, so Maven,
Testcontainers and the live multi-container verification must be run on the
target machine with the commands above.

See `DEBUG_REPORT.md` for the repaired startup defects and the exact verification
boundary.
