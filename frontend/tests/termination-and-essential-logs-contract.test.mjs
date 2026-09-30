import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("employee termination is a persisted HR to CEO workflow", () => {
  const migration = read("../backend/src/main/resources/db/migration/V27__employee_termination_and_essential_logs.sql");
  const controller = read("../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeTerminationController.java");
  assert.match(migration, /CREATE TABLE employee_termination_request/);
  assert.match(migration, /WHERE status = 'PENDING_CEO_APPROVAL'/);
  assert.match(controller, /@PreAuthorize\("hasRole\('HR_ADMIN'\)"\)/);
  assert.match(controller, /@PreAuthorize\("hasRole\('CEO'\)"\)/);
  assert.match(controller, /@PostMapping\("\/\{id\}\/approve"\)/);
  assert.match(controller, /@PostMapping\("\/\{id\}\/reject"\)/);
});

test("direct employee status changes cannot bypass CEO termination approval", () => {
  const service = read("../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java");
  const workflow = read("../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeTerminationService.java");
  assert.match(service, /TERMINATION_APPROVAL_REQUIRED/);
  assert.match(service, /terminateAfterApproval/);
  assert.match(workflow, /departmentHrs\.requireAssignedReviewer/);
  assert.match(workflow, /teamLeads\.endForEmployeeIfAssigned/);
  assert.match(workflow, /employees\.terminateAfterApproval/);
});

test("termination decisions create audit, essential log and internal notification records", () => {
  const workflow = read("../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeTerminationService.java");
  const notifications = read("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
  assert.match(workflow, /EMPLOYEE_TERMINATION_REQUESTED/);
  assert.match(workflow, /TERMINATION_APPROVED/);
  assert.match(workflow, /TERMINATION_REJECTED/);
  assert.match(workflow, /logs\.record/);
  assert.match(notifications, /notifyCeoOfTerminationRequest/);
  assert.match(notifications, /notifyHrOfTerminationDecision/);
});

test("System Admin Logs is a database-backed table and profile photo reaches the account icon", () => {
  const app = readFrontendSource();
  const api = readApiSource();
  const controller = read("../backend/src/main/java/com/brainserve/appointment/essentiallog/api/EssentialLogController.java");
  assert.match(app, /label: "Logs"/);
  assert.match(app, /function EssentialLogsView/);
  assert.match(app, /essential-logs-table/);
  assert.match(app, /account-avatar\$\{profilePhotoUrl/);
  assert.match(app, /onProfileUpdated/);
  assert.match(api, /essentialLogs\(filters/);
  assert.match(controller, /hasRole\('SYSTEM_ADMIN'\)/);
});

test("HR and CEO have role-specific termination interfaces", () => {
  const app = readFrontendSource();
  const api = readApiSource();
  assert.match(app, /Request termination…/);
  assert.match(app, /Request CEO approval/);
  assert.match(app, /Approve & disable access/);
  assert.match(api, /requestEmployeeTermination/);
  assert.match(api, /decideEmployeeTermination/);
});

