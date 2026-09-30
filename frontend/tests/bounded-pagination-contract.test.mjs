import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";

const api = readApiSource();
const ui = readFrontendSource();
const employeeController = fs.readFileSync(
  "../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeController.java", "utf8");
const auditController = fs.readFileSync(
  "../backend/src/main/java/com/brainserve/appointment/audit/api/AuditController.java", "utf8");
const logsController = fs.readFileSync(
  "../backend/src/main/java/com/brainserve/appointment/essentiallog/api/EssentialLogController.java", "utf8");
const lifecycleController = fs.readFileSync(
  "../backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountLifecycleController.java", "utf8");
const migration = fs.readFileSync(
  "../backend/src/main/resources/db/migration/V33__bounded_admin_query_indexes.sql", "utf8");
const lifecycleMigration = fs.readFileSync(
  "../backend/src/main/resources/db/migration/V35__bounded_account_lifecycle_indexes.sql", "utf8");

test("large administrative screens never recursively download all pages", () => {
  assert.doesNotMatch(api, /employees\(departmentId\?[\s\S]{0,250}allSpringPageContent/);
  assert.doesNotMatch(api, /auditEvents\([^)]*\)[\s\S]{0,350}allSpringPageContent/);
  assert.doesNotMatch(api, /essentialLogs\([^)]*\)[\s\S]{0,350}allSpringPageContent/);
});

test("employees use bounded numbered pages and cursor registers use load-more", () => {
  assert.match(api, /employeePage\(filters/);
  assert.match(api, /accountLifecycleAccountPage\(filters/);
  assert.match(api, /archivedAccountPage\(filters/);
  assert.match(ui, /Page \{page \+ 1\} of \{pageCount\}/);
  assert.match(ui, /Page \{accountPage \+ 1\} of \{accountPageCount\}/);
  assert.match(ui, /Load 50 more/);
  assert.match(employeeController, /Page size must be between 25 and 100/);
  assert.match(lifecycleController, /Page<AccountClosureService\.AccountView>/);
  assert.match(lifecycleController, /Page size must be between 25 and 100/);
  assert.match(auditController, /CursorResponse/);
  assert.match(logsController, /CursorResponse/);
});

test("database indexes support stable occurred-at and id cursor scans", () => {
  assert.match(migration, /audit_event \(occurred_at desc, id desc\)/);
  assert.match(migration, /essential_log_record \(occurred_at desc, id desc\)/);
  assert.match(migration, /employee \(department_id, status, lower\(display_name\), id\)/);
  assert.match(lifecycleMigration, /iam_user_account \(account_status, enabled, archived, full_name, id\)/);
  assert.match(lifecycleMigration, /lower\(full_name\) gin_trgm_ops/);
});

