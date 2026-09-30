import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("CEO visits follow Security to Reception to Manager to CEO in every runtime layer", () => {
  const domain = read("../backend/src/main/java/com/brainserve/appointment/appointment/domain/Appointment.java");
  const service = read("../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
  const migration = read("../backend/src/main/resources/db/migration/V42__ceo_visit_manager_to_ceo_handoff.sql");
  const frontend = readFrontendSource();

  assert.match(domain, /approveByManager[\s\S]*PENDING_CEO_APPROVAL/);
  assert.match(domain, /approveByCeo[\s\S]*transitionTo\(AppointmentStatus\.APPROVED\)/);
  assert.match(service, /ManagerApprovalRequested/);
  assert.match(service, /CeoVisitDecisionRecorded/);
  assert.match(migration, /Security -> Reception -> assigned department Manager -> company CEO/);
  assert.match(frontend, /stage === "manager" && isCeoApprovalRoute\(appointment\) \? "Awaiting CEO"/);
  assert.match(frontend, /CEO final approval/);
});

test("Manager-to-CEO handoff and CEO decision return are delivered through internal notifications", () => {
  const gateway = read("../backend/src/main/java/com/brainserve/appointment/notification/api/InternalNotificationGateway.java");
  const listener = read("../backend/src/main/java/com/brainserve/appointment/notification/application/CeoVisitNotificationListener.java");
  const notifications = read("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");

  assert.match(gateway, /notifyCeoOfManagerVisitApproval/);
  assert.match(gateway, /notifyManagerOfCeoVisitDecision/);
  assert.match(listener, /TransactionPhase\.AFTER_COMMIT/);
  assert.match(listener, /final decision is required/);
  assert.match(notifications, /staff\.requireChiefExecutive\(\)/);
  assert.match(notifications, /managers\.requireForDepartment\(departmentId\)/);
});

test("CEO and assigned Manager govern work audits without receiving HR authority", () => {
  const frontend = readFrontendSource();
  const service = read("../backend/src/main/java/com/brainserve/appointment/workinsight/application/WorkInsightService.java");
  const repository = read("../backend/src/main/java/com/brainserve/appointment/workinsight/infrastructure/WorkTaskAuditRecordRepository.java");

  assert.match(frontend, /Manager: \["overview", "appointments", "work", "insights"/);
  assert.match(frontend, /Open work oversight/);
  assert.match(frontend, /role === "Manager" \? "Work oversight"/);
  assert.match(frontend, /role === "Manager" \? "Department work oversight"/);
  assert.match(frontend, /MANAGER_APPROVE/);
  assert.match(frontend, /decideManagerWorkInsight/);
  assert.match(service, /roles\.contains\(MANAGER\)/);
  assert.match(service, /managers\.requireForUser\(actorUserId\)\.departmentId\(\)/);
  assert.match(service, /decideByManager/);
  assert.match(service, /managers\.requireAssignedReviewer/);
  assert.match(repository, /findTop1000ByWeekStartAndDepartmentIdOrderByHrAuditedAtDesc/);
});

test("CEO overview keeps both final visit decisions and company-wide account approvals visible", () => {
  const frontend = readFrontendSource();
  const provisioning = read("../backend/src/main/java/com/brainserve/appointment/iam/application/AccountProvisioningService.java");
  const ceoMigration = read("../backend/src/main/resources/db/migration/V41__single_company_ceo_governance.sql");

  assert.match(frontend, /\["CEO", "HR Admin"\]\.includes\(role\)[\s\S]*<Overview/);
  assert.match(frontend, /<AccountProvisioningPanel[\s\S]*?\bcompact\s+role=\{role\}/,);
  assert.match(frontend, /Company-wide HR and Manager approval/);
  assert.match(provisioning, /SystemRole\.ROLE_HR_ADMIN, SystemRole\.ROLE_MANAGER/);
  assert.match(ceoMigration, /uq_single_governing_ceo/);
});

test("visitor records retain Manager and CEO decision evidence", () => {
  const api = readApiSource();
  const controller = read("../backend/src/main/java/com/brainserve/appointment/reporting/api/MonthlyRecordsController.java");
  const frontend = readFrontendSource();

  assert.match(api, /managerActorId: string \| null/);
  assert.match(api, /ceoDecisionAt: string \| null/);
  assert.match(controller, /value\.managerApprovalActorId\(\), value\.managerDecisionAt\(\)/);
  assert.match(controller, /UUID managerActorId, Instant managerDecisionAt/);
  assert.match(frontend, /Manager \$\{formatOfficeTime\(item\.managerDecisionAt\)\}/);
  assert.match(frontend, /CEO \$\{formatOfficeTime\(item\.ceoDecisionAt\)\}/);
});

