import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(path, "utf8");
const migration = [
  read("backend/src/main/resources/db/migration/V29__scalable_history_reporting.sql"),
  read("backend/src/main/resources/db/migration/V34__retention_legal_hold_and_verified_cold_archive.sql"),
].join("\n");
const governance = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/application/DataGovernanceService.java",
);
const archive = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/application/DataPartitionArchiveService.java",
);
const disposal = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/application/OperationalRetentionService.java",
);
const ledger = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/application/GovernanceLedgerService.java",
);
const objectDeletion = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/application/ObjectStorageDeletionService.java",
);
const controller = read(
  "backend/src/main/java/com/brainserve/appointment/reporting/api/DataGovernanceController.java",
);
const api = read("app/lib/api.ts");
const ui = readFrontendSource();
const compose = read("docker-compose.yml");
const backup = read("ops/postgres/backup-loop.sh");

test("employees, visitors, appointments, audit and essential logs have independent policies", () => {
  for (const dataset of ["EMPLOYEE", "VISITOR", "APPOINTMENT", "AUDIT", "ESSENTIAL_LOG"]) {
    assert.match(migration, new RegExp(`'${dataset}'`));
  }
  assert.match(migration, /disposal_action/);
  assert.match(governance, /disposalAction/);
  assert.match(ui, /At expiry/);
});

test("monthly history supports encrypted cold archival for all governed operational datasets", () => {
  for (const parent of [
    "employee_history_event",
    "visitor_history_event",
    "appointment_history_event",
    "essential_log_history",
  ]) {
    assert.match(migration, new RegExp(parent));
    assert.match(archive, new RegExp(parent));
  }
  assert.match(archive, /AES\/GCM\/NoPadding/);
  assert.match(archive, /ARCHIVE_MAGIC/);
  assert.match(archive, /archive-encryption-keys/);
  assert.match(compose, /ARCHIVE_ENCRYPTION_KEYS/);
});

test("restore verification is mandatory before database partition removal", () => {
  assert.match(archive, /readAndVerify/);
  assert.match(archive, /objectMapper\.readTree/);
  assert.match(archive, /Downloaded archive checksum does not match/);
  assert.match(archive, /Restored archive row count does not match/);
  assert.match(archive, /where status = 'VERIFIED' and restore_tested_at is not null/);
  assert.match(archive, /detach partition/);
});

test("legal holds and active investigations block archive removal and disposal", () => {
  assert.match(migration, /LEGAL_HOLD/);
  assert.match(migration, /ACTIVE_INVESTIGATION/);
  assert.match(governance, /hasActiveHold/);
  assert.match(archive, /data_legal_hold/);
  assert.match(disposal, /hold\.released_at is null/);
  assert.match(controller, /legal-holds/);
  assert.match(api, /createDataLegalHold/);
  assert.match(ui, /Preservation controls/);
});

test("expired eligible operational PII is deleted or anonymized only after archive verification", () => {
  assert.match(disposal, /anonymizeFormerEmployees/);
  assert.match(disposal, /anonymizeExpiredAppointments/);
  assert.match(disposal, /deleteExpiredVisitorProfiles/);
  assert.match(disposal, /restore_tested_at is not null/g);
  assert.match(disposal, /retention_anonymized_at/);
  assert.match(disposal, /delete from visitor source[\s\S]+hold\.released_at is null[\s\S]+returning source\.id/);
});

test("governance evidence is hash chained and database protected from mutation", () => {
  assert.match(migration, /data_governance_log/);
  assert.match(migration, /pg_advisory_xact_lock/);
  assert.match(migration, /digest\(NEW\.previous_hash/);
  assert.match(migration, /BEFORE UPDATE OR DELETE ON data_governance_log/);
  assert.match(ledger, /verifyIntegrity/);
  assert.match(ui, /Immutable governance ledger/);
});

test("archive disposal removes object versions and coordinates backup expiry", () => {
  assert.match(objectDeletion, /listObjectVersions/);
  assert.match(objectDeletion, /deleteObjects/);
  assert.match(archive, /backup_expiry_register/);
  assert.match(backup, /POSTGRES_WAL_RETENTION_DAYS/);
  assert.match(backup, /wal-archive/);
  assert.match(compose, /POSTGRES_WAL_RETENTION_DAYS/);
});

