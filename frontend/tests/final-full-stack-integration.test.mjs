import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const api = readApiSource();
const health = read("../backend/src/main/java/com/brainserve/appointment/operations/application/IntegrationHealthService.java");
const controller = read("../backend/src/main/java/com/brainserve/appointment/operations/api/IntegrationHealthController.java");
const compose = read("../docker-compose.yml");
const verifier = read("../scripts/verify-stack.mjs");

test("previously unexposed employee and visitor services are connected to the frontend", () => {
  for (const method of [
    "registerVisitor", "searchVisitors", "verifyVisitor", "currentCompensation",
    "compensationHistory", "createCompensation", "employeeDocuments",
    "uploadEmployeeDocument", "employeeDocumentDownload", "deleteEmployeeDocument",
    "pendingEmployeeTerminations",
  ]) {
    assert.match(frontend, new RegExp(`brainServeApi\\.${method}\\(`), `${method} must be used by the UI`);
    assert.match(api, new RegExp(`${method}\\(`), `${method} must be defined by the production API client`);
  }
  assert.match(frontend, /function EmployeeServicePanel/);
  assert.match(frontend, /function VisitorIdentityRegistry/);
});

test("System Admin can inspect every required backend dependency", () => {
  assert.match(controller, /@RequestMapping\("\/api\/v1\/admin\/integrations"\)/);
  assert.match(controller, /hasRole\('SYSTEM_ADMIN'\)/);
  for (const dependency of [
    "PostgreSQL", "Redis", "Kafka", "SMTP", "Object storage", "ClamAV",
  ]) assert.ok(health.includes(`"${dependency}"`), `${dependency} must be checked`);
  assert.match(frontend, /function IntegrationStatusPanel/);
  assert.match(frontend, /brainServeApi\.integrationHealth\(\)/);
});

test("Docker orchestration and executable verification cover the complete service topology", () => {
  for (const service of [
    "postgres:", "postgres-backup:", "redis:", "kafka:", "minio:",
    "clamav:", "mailpit:", "backend:", "frontend:",
  ]) assert.ok(compose.includes(`  ${service}`), `${service} must exist in Docker Compose`);
  for (const probe of [
    "pg_isready", "redis-cli", "kafka-topics.sh", "clamAvPing",
    "actuator/health/readiness", "minio/health/ready", "mailpit",
  ]) assert.ok(verifier.includes(probe), `${probe} must be part of stack verification`);
});

test("System Admin settings use the dedicated protected system configuration endpoints", () => {
  assert.match(frontend, /role === "System Admin"\s+\? brainServeApi\.systemSettings\(\)/);
  assert.match(frontend, /role === "System Admin"[\s\S]*brainServeApi\.updateSystemSetting/);
});

