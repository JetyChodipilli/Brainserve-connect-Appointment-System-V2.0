import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
// These suites require PostgreSQL/Docker. Local skips remain explicit; CI must
// prove they ran, rather than returning green with no database coverage.
for (const name of [
  "com.brainserve.appointment.DatabaseMigrationIntegrationTest",
  "com.brainserve.appointment.reporting.application.KpiReconciliationIntegrationTest",
  "com.brainserve.appointment.reporting.application.AdministrationDashboardIntegrationTest",
  "com.brainserve.appointment.bulkimport.application.Sprint4PostgresIntegrationTest",
  "com.brainserve.appointment.workinsight.application.Sprint5PostgresIntegrationTest",
  "com.brainserve.appointment.iam.AccountProvisioningIntegrationTest",
  "com.brainserve.appointment.iam.SystemAdminPasswordChangeOtpIntegrationTest",
  "com.brainserve.appointment.iam.PrivilegedSecurityIntegrationTest",
  "com.brainserve.appointment.realtime.application.RedisRatePolicyIntegrationTest",
]) {
  const xml = readFileSync(`backend/target/surefire-reports/TEST-${name}.xml`, "utf8");
  const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
  assert.ok(suite, `${name}: missing test suite`);
  for (const attribute of ["skipped", "failures", "errors"]) {
    assert.match(suite, new RegExp(`\\b${attribute}="0"`), `${name}: ${attribute} must be zero`);
  }
  const count = Number(suite.match(/\btests="(\d+)"/)?.[1]);
  assert.ok(count > 0, `${name}: no tests executed`);
  console.log(`${name}: ${count} database tests executed`);
}
console.log("POSTGRESQL_AND_REDIS_COVERAGE_VERIFIED");
