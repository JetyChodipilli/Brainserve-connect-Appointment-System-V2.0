import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const frontendApi = readApiSource();
const appointmentController = read("../backend/src/main/java/com/brainserve/appointment/appointment/api/AppointmentController.java");
const appointmentDomain = read("../backend/src/main/java/com/brainserve/appointment/appointment/domain/Appointment.java");
const notificationListener = read("../backend/src/main/java/com/brainserve/appointment/notification/application/SecurityArrivalNotificationListener.java");
const employeeNotificationListener = read("../backend/src/main/java/com/brainserve/appointment/notification/application/EmployeeVisitNotificationListener.java");
const appointmentService = read("../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const internalNotifications = read("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
const monthlyController = read("../backend/src/main/java/com/brainserve/appointment/reporting/api/MonthlyRecordsController.java");
const frontend = readFrontendSource();

test("frontend calls every staged visitor workflow endpoint", () => {
  for (const endpoint of [
    "/appointments/security-walk-ins",
    "/appointments/${id}/security-intake",
    "/appointments/${id}/reception-${decision}",
    "/appointments/${id}/${stage}-${decision}",
    "/appointments/${id}/reception-forward",
    "/admin/records/monthly?year=${year}&month=${month}",
  ]) assert.ok(frontendApi.includes(endpoint), `missing frontend call ${endpoint}`);
});

test("backend locks each visitor action to its assigned service permission", () => {
  for (const authority of [
    "SECURITY_VISITOR_INTAKE", "RECEPTION_VISIT_VERIFY", "HR_VISIT_APPROVE",
    "TEAM_LEAD_VISIT_APPROVE", "MANAGER_VISIT_APPROVE", "CEO_VISIT_APPROVE",
  ]) assert.ok(appointmentController.includes(`hasAuthority('${authority}')`), `missing ${authority}`);
});

test("appointment state machine preserves department routes and CEO final approval after Manager", () => {
  assert.ok(appointmentDomain.includes("transitionTo(AppointmentStatus.PENDING_RECEPTION_VERIFICATION)"));
  assert.ok(appointmentDomain.includes(": AppointmentStatus.PENDING_HR_APPROVAL"));
  assert.ok(appointmentDomain.includes("transitionTo(AppointmentStatus.PENDING_TEAM_LEAD_APPROVAL)"));
  assert.ok(appointmentDomain.includes("AppointmentStatus.PENDING_MANAGER_APPROVAL"));
  assert.ok(appointmentDomain.includes("transitionTo(AppointmentStatus.PENDING_CEO_APPROVAL)"));
  assert.ok(appointmentDomain.includes("status != AppointmentStatus.PENDING_CEO_APPROVAL"));
  assert.ok(appointmentDomain.includes("transitionTo(AppointmentStatus.APPROVED)"));
});

test("Kafka listeners cover Security arrival, Reception verification and cabin forwarding", () => {
  assert.ok(notificationListener.includes("sendSecurityArrival"));
  assert.ok(notificationListener.includes("sendReceptionVerification"));
  assert.ok(notificationListener.includes("sendReceptionForward"));
});

test("HR forwards an employee visitor card while Team Lead retains final approval", () => {
  assert.ok(appointmentService.includes("publishEmployeeVisitCard(appointment, actorUserId)"));
  assert.ok(appointmentService.includes("PENDING_TEAM_LEAD_APPROVAL"));
  assert.ok(employeeNotificationListener.includes("EmployeeVisitCardUpdated"));
  assert.ok(employeeNotificationListener.includes("TransactionPhase.AFTER_COMMIT"));
  assert.ok(internalNotifications.includes("activeByEmployeeId(hostEmployeeId)"));
  assert.ok(internalNotifications.includes("notifyEmployeeOfVisitorCard"));
  assert.ok(internalNotifications.includes("sender.userId().equals(recipient.userId())"));
  assert.ok(frontend.includes("VISITOR COMING TO MEET YOU"));
  assert.ok(frontend.includes("Forwarded by HR"));
});

test("employee appointment rendering remains scoped to the authenticated employee profile", () => {
  assert.ok(appointmentService.includes("findByHostEmployeeIdAndSlotStartGreaterThanEqualAndSlotStartLessThan("));
  assert.ok(appointmentService.includes("employeeId, from, to, pageable"));
  assert.ok(frontend.includes("employee.email.toLowerCase() === userEmail.toLowerCase()"));
  assert.equal(frontend.includes('(role === "Employee" && appointment.status === "Pending")'), false);
});

test("appointment operations default to the current office day while history stays in Reports", () => {
  assert.ok(appointmentController.includes("LocalDate.now(officeZone)"));
  assert.ok(appointmentController.includes("effectiveDate.atStartOfDay(officeZone)"));
  assert.ok(appointmentService.includes("findBySlotStartGreaterThanEqualAndSlotStartLessThan(from, to, pageable)"));
  assert.ok(frontend.includes("TODAY'S APPOINTMENTS"));
  assert.ok(frontend.includes("Reports → Explore Records for previous dates, monthly history, custom ranges and exports."));
});

test("System Admin register is based on actual Reception processing, not scheduled appointments", () => {
  assert.ok(monthlyController.includes("@PreAuthorize(\"hasAuthority('WORKFORCE_RECORD_VIEW')\")"));
  assert.ok(monthlyController.includes("receptionVisitsBetween(from, to)"));
  assert.ok(monthlyController.includes("receptionVerifiedAt"));
  assert.ok(monthlyController.includes("checkedInAt"));
  assert.equal(monthlyController.includes("visitsBetween(from, to)"), false);
  assert.ok(frontend.includes('className="records-table visitor-records-table"'));
  assert.ok(frontend.includes("EMPLOYEE LIFECYCLE REGISTER"));
  assert.ok(frontend.includes("LEAVE REQUEST REGISTER"));
});

