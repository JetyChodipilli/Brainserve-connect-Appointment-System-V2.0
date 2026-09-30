import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(path, "utf8");
const migration = read("backend/src/main/resources/db/migration/V29__scalable_history_reporting.sql");
const history = read("backend/src/main/java/com/brainserve/appointment/reporting/application/RoleAwareHistoryQueryService.java");
const dashboard = read("backend/src/main/java/com/brainserve/appointment/reporting/application/RoleDashboardQueryService.java");
const exports = read("backend/src/main/java/com/brainserve/appointment/reporting/application/ReportExportService.java");
const exportRepository = read("backend/src/main/java/com/brainserve/appointment/reporting/infrastructure/ReportExportJobRepository.java");
const reportsUi = readFrontendSource();
const compose = read("docker-compose.yml");
const postgresEntrypoint = read("ops/postgres/postgres-entrypoint.sh");

test("high-volume history is monthly partitioned and summarized", () => {
  assert.match(migration, /PARTITION BY RANGE \(occurred_at\)/);
  assert.match(migration, /audit_event_history/);
  assert.match(migration, /visitor_checkpoint_event/);
  assert.match(migration, /workboard_activity_event/);
  assert.match(migration, /daily_operational_summary/);
  assert.match(migration, /monthly_operational_summary/);
});

test("history is role scoped, cursor paginated and bounded", () => {
  assert.match(history, /RoleDataScopeService/);
  assert.match(history, /cursorTime/);
  assert.match(history, /LIMIT :limit/);
  assert.match(history, /HISTORY_SCOPE_DENIED|requireDataset/);
});

test("dashboard cache fails open and exports are asynchronous", () => {
  assert.match(dashboard, /dashboard-cache-seconds/);
  assert.match(dashboard, /catch \(DataAccessException/);
  assert.match(exports, /report-exports\//);
  assert.match(exports, /SXSSFWorkbook/);
  assert.match(exports, /maximumRows/);
});

test("export jobs are recoverable, ownership checked and worker locked", () => {
  assert.match(exports, /REPORT_EXPORT_ACCESS_DENIED/);
  assert.match(exports, /recoverInterruptedExports/);
  assert.match(exportRepository, /PESSIMISTIC_WRITE/);
  assert.match(reportsUi, /retryReportExport/);
  assert.match(reportsUi, /dataset: applied\.dataset/);
});

test("Reports exposes all role-authorized datasets and refreshes preview records", () => {
  assert.match(reportsUi, /report-workspace-nav/);
  assert.match(reportsUi, /<strong>Explore Records<\/strong>/);
  assert.match(reportsUi, /LegacyExploreRecordsView/);
  assert.match(reportsUi, /HistoryDatasetOptions/);
  assert.match(reportsUi, /<optgroup label="Other records">/);
  assert.match(reportsUi, /VISITS: "Visits & appointments"/);
  assert.match(reportsUi, /EMPLOYEES: "Employee records"/);
  assert.doesNotMatch(reportsUi, /<select disabled value="VISITS">/);
  assert.match(reportsUi, /readPreviewWorkspaceAppointments/);
  assert.match(reportsUi, /brainserve:demo-appointments-updated/);
  assert.match(reportsUi, /Refresh records/);
});

test("PostgreSQL has WAL archiving and physical base backups", () => {
  assert.match(postgresEntrypoint, /archive_mode=on/);
  assert.match(compose, /postgres-backup:/);
  assert.match(compose, /postgres_wal_archive/);
});

