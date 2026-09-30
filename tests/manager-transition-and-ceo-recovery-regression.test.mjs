import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const frontend = readFrontendSource();
const transition = read(
    "backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java",
);
const employeeRepository = read(
    "backend/src/main/java/com/brainserve/appointment/employee/infrastructure/EmployeeRepository.java",
);
const employeeService = read(
    "backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java",
);
const singleRoleMigration = read(
    "backend/src/main/resources/db/migration/V39__safe_operational_role_replacement.sql",
);
const exactRoleMigration = read(
    "backend/src/main/resources/db/migration/V40__close_role_and_manager_routing_gaps.sql",
);
const managerMigration = read(
    "backend/src/main/resources/db/migration/V38__single_role_manager_ceo_visit_routing.sql",
);

test("Team Lead or HR Admin to Manager is one atomic role and department transition", () => {
  assert.match(transition, /@Transactional\s+public Result transition/);
  assert.match(transition, /users\.findByIdForUpdate\(targetUserId\)/);
  assert.match(transition, /organization\.lockActiveDepartment\(departmentId\)/);
  assert.match(transition, /endPreviousAssignment\(actorUserId, target, previousRole\)/);
  assert.match(transition, /requireTargetAssignmentAvailable\(targetUserId, targetRole, departmentId\)/);
  assert.match(transition, /target\.replaceOperationalRole\(targetRole\)/);
  assert.match(transition, /sessions\.revokeAllForUser\(targetUserId, changedAt\)/);
  assert.match(transition, /ROLE_TRANSITION_SAME_ROLE/);
  assert.match(employeeRepository, /findByIdForUpdate/);
  assert.ok(sourceIncludes(employeeService, "employees.findByIdForUpdate(employeeId)"));
  assert.match(singleRoleMigration, /UNIQUE \(user_id\)\s+DEFERRABLE INITIALLY DEFERRED/);
  assert.match(exactRoleMigration, /ck_iam_user_exactly_one_role/);
});

test("only one active Manager can own a department", () => {
  assert.match(managerMigration, /uq_active_manager_per_department/);
  assert.match(managerMigration, /ON department_manager_assignment\(department_id\) WHERE active/);
  assert.ok(sourceIncludes(transition, "case ROLE_MANAGER -> managers.activeForDepartment(departmentId)"));
  assert.ok(sourceIncludes(transition, "!value.managerUserId().equals(targetUserId)"));
  assert.match(frontend, /currentManagers\.some\(\(item\) => item\.active && item\.departmentId === departmentId/);
  assert.match(frontend, /The selected department already has an active/);
});

test("browser-hosted transitions replace the role, transfer the employee and revoke the old session", () => {
  for (const expected of [
    "writeDemoTeamLeadAssignments(nextTeamLeads)",
    "writeDemoDepartmentHrAssignments(nextDepartmentHrs)",
    "writeDemoManagerAssignments(nextManagers)",
    "writeDemoEmployees(nextEmployees)",
    "writeDemoAccounts(nextAccounts)",
    'role: targetRole',
    "brainserve:demo-accounts-updated",
    "writePreviewWorkspaceSession(null)",
  ]) {
    assert.ok(frontend.includes(expected), `missing preview transition safeguard: ${expected}`);
  }
});

test("the canonical CEO recovery request reaches the System Admin preview queue", () => {
  assert.match(frontend, /email: "althuf@brainserve\.in"/);
  assert.match(frontend, /role: "ROLE_CEO"/);
  assert.match(frontend, /persistedCompanyCeo \?\? persistedSeedCeo/);
  assert.match(frontend, /persistedCeoAccounts\.length === 0 \? DEMO_CEO_ACCOUNT : null/);
  assert.match(frontend, /account\.email\.toLowerCase\(\) === normalizedIdentifier/);
  assert.match(frontend, /brainserve:demo-recovery-updated/);
  assert.match(frontend, /Refresh requests/);
});

test("a real active preview CEO remains visible in Account lifecycle and stale sessions are rejected", () => {
  assert.match(frontend, /The seed is only a fresh-browser fallback/);
  assert.match(frontend, /readDemoAccounts\(\)\s*\.filter\(\(item\) => item\.status === "ACTIVE"\)/);
  assert.match(frontend, /brainserve:demo-accounts-updated/);
  assert.match(frontend, /window\.addEventListener\("storage", refreshFromStorage\)/);
  assert.match(frontend, /roleFromAuthority\(account\.role\) === previewSession\.role/);
  assert.match(frontend, /This account is no longer active or its role changed/);
  assert.doesNotMatch(frontend, /const canonicalCeo = persistedCeo \?\? DEMO_CEO_ACCOUNT/);
});

