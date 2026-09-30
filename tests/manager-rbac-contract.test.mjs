import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const role = read("backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java");
const account = read("backend/src/main/java/com/brainserve/appointment/iam/domain/UserAccount.java");
const appointment = read("backend/src/main/java/com/brainserve/appointment/appointment/domain/Appointment.java");
const service = read("backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const controller = read("backend/src/main/java/com/brainserve/appointment/appointment/api/AppointmentController.java");
const transition = read("backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java");
const manager = read("backend/src/main/java/com/brainserve/appointment/manager/application/ManagerAssignmentService.java");
const migration = read("backend/src/main/resources/db/migration/V38__single_role_manager_ceo_visit_routing.sql");
const safeRoleMigration = read("backend/src/main/resources/db/migration/V39__safe_operational_role_replacement.sql");
const ceoHandoffMigration = read("backend/src/main/resources/db/migration/V42__ceo_visit_manager_to_ceo_handoff.sql");
const frontend = readFrontendSource();

test("Manager is a least-privilege role rather than a copy of CEO authority", () => {
  assert.ok(role.includes("ROLE_MANAGER(EnumSet.of"));
  assert.ok(role.includes("MANAGER_VISIT_APPROVE"));
  const managerDefinition = role.slice(role.indexOf("\n    ROLE_MANAGER(EnumSet.of"),
    role.indexOf("\n    ROLE_TEAM_LEAD(EnumSet.of"));
  for (const forbidden of ["SALARY_READ", "SALARY_APPROVE", "ROLE_MANAGE", "HR_ACCOUNT_DEACTIVATE"]) {
    assert.equal(new RegExp(`\\b${forbidden}\\b`).test(managerDefinition), false,
      `Manager must not inherit ${forbidden}`);
  }
});

test("one effective role is enforced and transitions clear stale permission overrides", () => {
  assert.ok(migration.includes("uq_iam_user_single_effective_role"));
  assert.ok(safeRoleMigration.includes("DEFERRABLE INITIALLY DEFERRED"));
  assert.ok(migration.includes("PARTITION BY user_id"));
  assert.ok(account.includes("A user account must have exactly one effective role"));
  assert.ok(account.includes("replaceOperationalRole"));
  assert.ok(account.includes("grantedPermissions.clear()"));
  assert.ok(account.includes("deniedPermissions.clear()"));
  assert.ok(transition.includes("sessions.revokeAllForUser"));
  assert.ok(transition.includes("endPreviousAssignment"));
});

test("CEO visits route through the assigned department Manager to the company CEO", () => {
  assert.ok(appointment.includes("PENDING_MANAGER_APPROVAL"));
  assert.ok(appointment.includes("PENDING_CEO_APPROVAL"));
  assert.ok(appointment.includes("approveByManager"));
  assert.ok(appointment.includes("approveByCeo"));
  assert.ok(service.includes("managers.requireForDepartment"));
  assert.ok(service.includes("managers.requireAssignedReviewer"));
  assert.ok(controller.includes("/manager-approve"));
  assert.ok(controller.includes("/manager-reject"));
  assert.ok(controller.includes("/ceo-approve"));
  assert.ok(manager.includes("MANAGER_DEPARTMENT_SCOPE_DENIED"));
  assert.ok(migration.includes("uq_active_manager_per_department"));
  assert.ok(ceoHandoffMigration.includes("PENDING_CEO_APPROVAL"));
});

test("frontend exposes Manager workspace, action queue and atomic role transition control", () => {
  assert.ok(frontend.includes('ROLE_MANAGER: "Manager"'));
  assert.ok(frontend.includes('"Awaiting Manager"'));
  assert.ok(frontend.includes("OperationalRoleTransitionPanel"));
  assert.ok(frontend.includes("One account · one role · one department assignment"));
  assert.ok(frontend.includes('role === "Manager" ? "ROLE_MANAGER"'));
  assert.ok(frontend.includes('<option value="ROLE_MANAGER">Manager</option>'));
});

