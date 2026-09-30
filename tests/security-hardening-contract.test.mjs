import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("rejected authentication attempts persist defensive state outside rolled-back requests", () => {
    const authentication = read("backend/src/main/java/com/brainserve/appointment/iam/application/AuthenticationService.java");
    const writer = read("backend/src/main/java/com/brainserve/appointment/iam/application/AuthenticationSecurityStateWriter.java");
    assert.match(writer, /@Transactional\(propagation = Propagation\.REQUIRES_NEW\)/);
    assert.match(writer, /findByIdForUpdate\(userId\)\.ifPresent\(UserAccount::recordFailedLogin\)/);
    assert.match(writer, /sessions\.revokeFamily\(familyId, revokedAt\)/);
    assert.match(writer, /rotateRefreshToken\(String currentHash, String nextHash,/);
    assert.match(writer, /findByTokenHashForUpdate\(currentHash\)/);
    assert.match(writer, /revokePresentedRefreshToken\(String tokenHash, Instant revokedAt\)/);
    assert.match(authentication, /securityState\.recordFailedLogin\(user\.getId\(\)\)/);
    assert.match(authentication, /securityState\.revokeRefreshTokenFamily\(current\.getFamilyId\(\), Instant\.now\(\)\)/);
    assert.match(authentication, /securityState\.rotateRefreshToken\(currentHash, nextHash, user\.getId\(\),/);
    assert.match(authentication, /securityState\.revokePresentedRefreshToken\(hash\(refreshToken\), Instant\.now\(\)\)/);
});

test("private documents enforce actor and department ownership and validate file signatures", () => {
    const controller = read("backend/src/main/java/com/brainserve/appointment/document/api/DocumentController.java");
    const service = read("backend/src/main/java/com/brainserve/appointment/document/application/DocumentService.java");
    const visitorApi = read("backend/src/main/java/com/brainserve/appointment/visitor/api/package-info.java");
    assert.match(controller, /@AuthenticationPrincipal Jwt jwt/);
    assert.match(controller, /service\.upload\(actorId\(jwt\), ownerType, ownerId, category, file\)/);
    assert.match(service, /requireOwnerAccess\(actorUserId, ownerType, ownerId, true\)/);
    assert.match(service, /departmentHrs\.activeForUser\(actor\.userId\(\)\)/);
    assert.match(service, /assignment\.departmentId\(\)\.equals\(departmentId\)/);
    assert.match(service, /DOCUMENT_ACCESS_REJECTED/);
    assert.match(service, /DOCUMENT_CONTENT_MISMATCH/);
    assert.match(service, /case "application\/pdf" -> startsWith/);
    assert.match(service, /return !write && actor\.roles\(\)\.contains\(CEO\)/);
    assert.match(service, /Math\.max\(1, Math\.min\(urlMinutes, 15\)\)/);
    assert.match(visitorApi, /@org\.springframework\.modulith\.NamedInterface\("api"\)/);
});

test("expired token secrets have bounded retention without weakening replay detection", () => {
    const retention = read("backend/src/main/java/com/brainserve/appointment/iam/application/AuthenticationSessionRetentionService.java");
    const repository = read("backend/src/main/java/com/brainserve/appointment/iam/infrastructure/RefreshTokenSessionRepository.java");
    const migration = read("backend/src/main/resources/db/migration/V49__authentication_session_retention_index.sql");
    assert.match(retention, /expired-session-retention-days:30/);
    assert.match(retention, /Instant\.now\(\)\.minus\(expiredSessionRetentionDays, ChronoUnit\.DAYS\)/);
    assert.match(repository, /delete from RefreshTokenSession s where s\.expiresAt < :cutoff/);
    assert.match(migration, /ix_refresh_expires_at/);
});

test("production browser and local containers apply defense-in-depth controls", () => {
    const worker = read("worker/index.ts");
    const compose = read("docker-compose.yml");
    const dockerfile = read("Dockerfile.frontend");
    assert.match(worker, /Content-Security-Policy/);
    assert.match(worker, /Strict-Transport-Security/);
    assert.match(worker, /X-Content-Type-Options/);
    assert.match(worker, /Permissions-Policy/);
    assert.match(worker, /requestUrl\.protocol === "http:"/);
    assert.match(worker, /http:\/\/localhost:8080 http:\/\/127\.0\.0\.1:8080/);
    assert.match(dockerfile, /USER brainserve/);
    assert.match(dockerfile, /FROM dependencies AS production-dependencies/);
    assert.match(dockerfile, /npm prune --omit=dev/);
    assert.match(dockerfile, /--from=production-dependencies \/app\/node_modules/);
    for (const port of ["5433", "9092", "9094", "9096", "9000", "9001", "1025", "8025", "8080", "3000"]) {
        assert.match(compose, new RegExp(`127\\.0\\.0\\.1:${port}:`));
    }
    assert.match(compose, /127\.0\.0\.1:\$\{REDIS_HOST_PORT:-6380\}:6379/);
});

test("the verified dependency graph is vulnerability-gated", () => {
    const packageJson = JSON.parse(read("package.json"));
    const workflow = read(".github/workflows/ci.yml");
    assert.equal(packageJson.overrides.postcss, "8.5.23");
    assert.equal(packageJson.overrides.browserslist, "4.28.7");
    assert.equal(packageJson.overrides["fast-uri"], "3.1.6");
    assert.equal(packageJson.overrides.fflate, "0.7.5");
    assert.match(workflow, /npm audit --audit-level=high/);
});

test("backend CI supplies isolated cryptographic configuration", () => {
    const workflow = read(".github/workflows/ci.yml");
    assert.match(workflow, /ARCHIVE_ENCRYPTION_KEYS:\s*v1=[A-Za-z0-9+/]+=*/);
    assert.match(workflow, /PII_ENCRYPTION_KEY:\s*[A-Za-z0-9+/]+=*/);
    assert.match(workflow, /QR_PASS_SIGNING_SECRET:\s*ci-only-/);
    assert.doesNotMatch(workflow, /actions\/(?:checkout|setup-node|setup-java)@v4/);
});
