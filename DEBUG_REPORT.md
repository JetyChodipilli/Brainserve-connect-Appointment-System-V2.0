# BrainServe Connect — ZIP Debug Report

## Result

The original ZIP was structurally valid, but it was not safe to describe as a
verified working full-stack package. A fresh backend could stop during Flyway
migration V45, and several integration and build defects could surface after
startup. Those verified defects are repaired in this package.

## Repaired defects

1. **Fresh database startup failure**
   - Flyway V45 referenced a nonexistent `iam_user_account.rejection_reason`
     column.
   - The migration now clears the real rejection metadata and the complete
     V1–V46 migration chain executes in order.

2. **Hidden TypeScript failures**
   - The old production build did not run a standalone TypeScript check.
   - Four compile errors were corrected and `npm run build` now fails if
     `tsc --noEmit` fails.

3. **Broken document and report links**
   - Presigned MinIO URLs used the Docker-only hostname `minio`, which a user's
     browser cannot resolve.
   - The backend now uses a separate `S3_PUBLIC_ENDPOINT`, defaulting to
     `http://localhost:9000`, for browser-facing links.

4. **Frontend API configuration**
   - The Docker frontend API URL was fixed at one literal value.
   - It is now configurable through `NEXT_PUBLIC_API_BASE_URL`, with the local
     Docker URL retained as the default.

5. **Slow/unreliable ClamAV readiness**
   - The stack used the database-free `_base` image and did not persist virus
     signatures.
   - It now uses the preloaded `clamav/clamav:1.4` image and a persistent
     signature volume.

6. **Secret leakage into Docker build contexts**
   - Root `.env` files and backend local property overrides were not fully
     excluded.
   - Docker ignore rules now exclude generated secrets and local backend
     credentials while retaining `.env.example`.

## Verification completed

- Strict TypeScript check
- Production frontend build
- ESLint
- 220 Node regression and source-contract tests
- 294 Java source/test files parsed with a Java grammar
- All 45 Flyway migrations executed sequentially in an isolated
  PostgreSQL-compatible database
- Generated environment contract check
- Docker Compose YAML and dependency-condition validation
- Production frontend startup smoke test returning HTTP 200
- Production npm dependency audit with zero known vulnerabilities
- ZIP CRC/integrity test

## Verification boundary

The audit environment does not have Java 21, Maven or Docker. Therefore it could
not execute `mvn test`, Testcontainers, or the complete ten-container stack.
External conditions such as occupied ports, insufficient Docker memory, blocked
image downloads or incorrect custom environment values can still prevent a
service from starting.

## Run and diagnose

Requirements:

- Docker Desktop or Docker Engine with Compose v2
- Node.js 22 or later
- Free local ports: 3000, 5432, 6379, 8025, 8080, 9000, 9001 and 9092

Run:

```bash
npm run env:init
docker compose up --build -d
npm run verify:stack
```

Inspect service state:

```bash
docker compose ps
docker compose logs --tail=200 backend
docker compose logs --tail=200 kafka postgres redis minio clamav
```

Open:

- Application: `http://localhost:3000`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Mailpit: `http://localhost:8025`
- MinIO console: `http://localhost:9001`

The generated System Admin password is printed once by `npm run env:init`.
