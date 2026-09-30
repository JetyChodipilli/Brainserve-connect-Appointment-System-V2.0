import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const account = read("backend/src/main/java/com/brainserve/appointment/iam/domain/UserAccount.java");
const transition = read(
    "backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java",
);
const employees = read(
    "backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java",
);
const migration = read(
    "backend/src/main/resources/db/migration/V43__reconcile_manager_identity_transitions.sql",
);

test("a former CEO keeps one identity while becoming an active Manager", () => {
  assert.match(account, /replaceFormerChiefExecutiveWithManager/);
  assert.match(account, /roles\.add\(SystemRole\.ROLE_MANAGER\)/);
  assert.match(account, /status = AccountStatus\.ACTIVE/);
  assert.match(transition, /formerChiefExecutive/);
  assert.match(transition, /requireAnotherActiveChiefExecutive\(targetUserId\)/);
  assert.match(transition, /Only System Admin can move a former CEO/);
  assert.match(transition, /sessions\.revokeAllForUser\(targetUserId, changedAt\)/);
  assert.match(transition, /target\.replaceFormerChiefExecutiveWithManager\(\)/);
});

test("the employee record and leadership ownership move in the same transaction", () => {
  assert.match(transition, /endConflictingAssignments\(actorUserId, target, targetRole, departmentId\)/);
  assert.match(transition, /employees\.transitionOperationalPosition\(employeeId, departmentId/);
  assert.ok(sourceIncludes(employees, "employees.findByIdForUpdate(employeeId)"));
  assert.ok(sourceIncludes(employees, "employee.transitionOperationalPosition(departmentId, designation)"));
  assert.match(transition, /case ROLE_MANAGER -> "Department Manager"/);
});

test("Flyway V43 repairs existing role-assignment conflicts and prevents recurrence", () => {
  for (const expected of [
    "v43_manager_identity_conflict",
    "v43_ceo_successor",
    "DELETE FROM iam_user_role",
    "'ROLE_MANAGER'",
    "designation = 'Department Manager'",
    "status = 'ACTIVE'",
    "iam_refresh_token_session",
    "ck_active_manager_identity_consistency",
    "DEFERRABLE INITIALLY DEFERRED",
  ]) {
    assert.ok(migration.includes(expected), `missing V43 safeguard: ${expected}`);
  }
});

test("Preview mode reconciles the same account and rejects stale CEO sessions", () => {
  for (const expected of [
    "managerIdentityConflictExists",
    'role: "ROLE_MANAGER", status: "ACTIVE"',
    "activeManagerUserIds.has(account.id)",
    "historicalCompanyCeo",
    "writeDemoAccounts(nextAccounts)",
    'role: targetRole, status: "ACTIVE"',
    'role: "Department Manager", status: "Active"',
    "This account is no longer active or its role changed",
  ]) {
    assert.ok(frontend.includes(expected), `missing Preview identity repair: ${expected}`);
  }
});

