import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const role = read("../backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java");
const assignment = read("../backend/src/main/java/com/brainserve/appointment/teamlead/application/TeamLeadAssignmentService.java");
const assignmentController = read("../backend/src/main/java/com/brainserve/appointment/teamlead/api/TeamLeadController.java");
const appointment = read("../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const appointmentController = read("../backend/src/main/java/com/brainserve/appointment/appointment/api/AppointmentController.java");
const migration = read("../backend/src/main/resources/db/migration/V16__team_lead_assignments.sql");
const frontend = readFrontendSource();
const api = readApiSource();
const employeeDirectory = read("../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeDirectory.java");
const identity = read("../backend/src/main/java/com/brainserve/appointment/iam/application/TeamLeadIdentityServiceImpl.java");

test("Team Lead is a department-scoped role promoted from an active employee", () => {
  assert.ok(role.includes("ROLE_TEAM_LEAD"));
  assert.ok(role.includes("TEAM_LEAD_VISIT_APPROVE"));
  assert.ok(assignment.includes("promoteActiveEmployee"));
  assert.ok(assignment.includes("TEAM_LEAD_DEPARTMENT_MISMATCH"));
  assert.ok(assignment.includes("departmentIdForEmployee"));
});

test("PostgreSQL enforces one active Team Lead per department and per employee", () => {
  assert.ok(migration.includes("uq_active_team_lead_per_department"));
  assert.ok(migration.includes("uq_active_department_per_team_lead_employee"));
  assert.ok(migration.includes("WHERE active"));
});

test("HR manages assignments while CEO has read-only visibility", () => {
  assert.ok(assignmentController.includes("TEAM_LEAD_ASSIGNMENT_MANAGE"));
  assert.ok(assignmentController.includes("hasAnyRole('HR_ADMIN','CEO')"));
  assert.ok(api.includes("assignTeamLead"));
  assert.ok(api.includes("endTeamLeadAssignment"));
  assert.ok(frontend.includes("Assign Team Lead"));
  assert.ok(frontend.includes("Replace Team Lead"));
  assert.ok(frontend.includes("canManageLead && department.active"));
  assert.ok(frontend.includes("eligibleLeadEmployees"));
  assert.ok(assignment.includes("TEAM_LEAD_ASSIGNMENT_DEPARTMENT_SCOPE_DENIED"));
  assert.ok(identity.includes("promoteActiveEmployee"));
});

test("Team Lead access is managed once in Organization, not duplicated in Settings or registration", () => {
  assert.ok(!frontend.includes("Promote an approved employee"));
  assert.ok(!frontend.includes("CREATE TEAM LEAD ACCESS"));
  assert.ok(frontend.includes("Their existing login credentials remain unchanged"));
  assert.ok(sourceIncludes(frontend, 'const allowedRoles = [["ROLE_RECEPTIONIST", "Receptionist"], ["ROLE_SECURITY", "Security"]]'));
  assert.ok(!sourceIncludes(frontend, 'const allowedRoles = [["ROLE_EMPLOYEE"'));
  assert.ok(!sourceIncludes(frontend, 'const allowedRoles = [["ROLE_TEAM_LEAD"'));
  assert.ok(frontend.includes('["ROLE_TEAM_LEAD", "ROLE_EMPLOYEE", "ROLE_RECEPTIONIST", "ROLE_SECURITY"]'));
  assert.ok(frontend.includes("Receptionist and Security accounts are never eligible"));
  assert.ok(frontend.includes("Only enabled, approved Employee accounts are eligible"));
  assert.ok(frontend.includes('account.roles[0] === "ROLE_EMPLOYEE"'));
  assert.ok(!frontend.includes("canManageLead && !routingDepartment"));
  assert.ok(assignment.includes("requireActiveEmployee"));
  assert.ok(employeeDirectory.includes("requireActiveEmployee"));
  assert.ok(identity.includes("revokeAllForUser"));
});

test("a promoted employee uses the normal login without a Team Lead access banner", () => {
  assert.ok(!frontend.includes("Team Lead portal access"));
  assert.ok(!frontend.includes("team-lead-login-note"));
  assert.ok(frontend.includes("Their existing login credentials remain unchanged"));
  assert.ok(frontend.includes('role: "ROLE_TEAM_LEAD"'));
  assert.ok(frontend.includes('ROLE_TEAM_LEAD: "Team Lead"'));
  assert.ok(frontend.includes('"Team Lead": ["overview", "work", "employees", "notifications", "organization", "reports", "profile"]'));
});

test("employee visits route from HR to the assigned Team Lead", () => {
  assert.ok(appointment.includes("PENDING_TEAM_LEAD_APPROVAL"));
  assert.ok(appointment.includes("activeForHost"));
  assert.ok(appointmentController.includes("/{id}/team-lead-approve"));
  assert.ok(appointmentController.includes("/{id}/team-lead-reject"));
  assert.ok(frontend.includes("Awaiting Team Lead"));
  assert.ok(api.includes('stage: "hr" | "team-lead" | "manager" | "ceo"'));
});

test("Team Lead queues and rosters are limited to the assigned department", () => {
  assert.ok(appointment.includes("findByRoutingDepartmentIdAndSlotStartGreaterThanEqualAndSlotStartLessThan("));
  assert.ok(appointment.includes("assignment.departmentId(), from, to, pageable"));
  assert.ok(assignmentController.includes('@GetMapping("/me/team")'));
  assert.ok(api.includes("myTeamLeadAssignment"));
  assert.ok(api.includes("myTeam()"));
  assert.ok(frontend.includes("Today’s department calendar"));
  assert.ok(frontend.includes("Your department team"));
});

