import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(path, "utf8");
const app = readFrontendSource();
const api = read("app/lib/api.ts");
const controller = read(
  "backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountLifecycleController.java");
const service = read(
  "backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
const users = read(
  "backend/src/main/java/com/brainserve/appointment/iam/infrastructure/UserAccountRepository.java");
const migration = read(
  "backend/src/main/resources/db/migration/V36__account_lifecycle_employee_department_filter.sql");

test("employee lifecycle directory requires a department before loading", () => {
  assert.match(app, /accountRole === "ROLE_EMPLOYEE" && !accountDepartmentId/);
  assert.match(app, /Select employee department/);
  assert.match(app, /Filter employees by department/);
  assert.match(app, /Employees are loaded only after a department is selected/);
  assert.match(app, /account\.departmentId === accountDepartmentId/);
});

test("selected employee department reaches a bounded PostgreSQL query", () => {
  assert.match(api, /params\.set\("departmentId", filters\.departmentId\)/);
  assert.match(controller, /@RequestParam\(required = false\) UUID departmentId/);
  assert.match(service, /EMPLOYEE_DEPARTMENT_REQUIRED/);
  assert.match(service, /organization\.requireActiveDepartment\(departmentId\)/);
  assert.match(users, /employee\.departmentId = :departmentId/);
  assert.match(migration, /employee \(department_id, id\)/);
});

