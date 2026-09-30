import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const appointment = read("backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const hrService = read("backend/src/main/java/com/brainserve/appointment/departmenthr/application/DepartmentHrAssignmentService.java");
const migration = read("backend/src/main/resources/db/migration/V24__department_hr_routing.sql");
const frontend = readFrontendSource();
const workTask = read("backend/src/main/java/com/brainserve/appointment/worktask/application/DepartmentWorkTaskService.java");
const insight = read("backend/src/main/java/com/brainserve/appointment/workinsight/application/WorkInsightService.java");

test("PostgreSQL enforces one active HR per department while CEO transfers an existing HR safely", () => {
  assert.ok(migration.includes("uq_active_hr_per_department"));
  assert.ok(migration.includes("uq_active_department_per_hr"));
  assert.ok(hrService.includes("currentForHr.ifPresent"));
  assert.ok(hrService.includes("employees.transferDepartment(hr.employeeId(), departmentId)"));
  assert.ok(frontend.includes("Assigned HRs remain selectable"));
});

test("visitor host rules are enforced by backend and reflected in the form", () => {
  assert.ok(appointment.includes("Client meetings must select the active Team Lead"));
  assert.ok(appointment.includes("Emergency meetings must select an active CEO or department HR host"));
  assert.ok(appointment.includes("Employee meetings must select the HR assigned to the employee department"));
  assert.ok(frontend.includes("Routing department"));
  assert.ok(frontend.includes('ceoApprovalRoute ? "Department Manager" : "Department HR"'));
  assert.ok(frontend.includes("CEO final approval"));
  assert.ok(frontend.includes("Employee to meet"));
  assert.ok(frontend.includes("routingDepartmentId: routingDepartmentId || selectedHost.departmentId"));
  assert.ok(!frontend.includes('selectedHost.category === "CEO"\n          ? null : routingDepartmentId'));
});

test("department HR owns appointment, task and insight queues", () => {
  assert.ok(appointment.includes("departmentHrs.requireForUser(userId).departmentId()"));
  assert.ok(workTask.includes("departmentHrs.requireForUser(userId).departmentId()"));
  assert.ok(sourceIncludes(insight, "departmentHrs.requireAssignedReviewer(task.departmentId(), hrUserId)"));
});

