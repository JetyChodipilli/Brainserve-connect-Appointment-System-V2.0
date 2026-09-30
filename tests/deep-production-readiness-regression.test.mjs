import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("all generated temporary passwords require replacement before workspace access", () => {
  const privileged = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountProvisioningService.java");
  const staff = read("backend/src/main/java/com/brainserve/appointment/iam/application/StaffAccountAdministrationService.java");
  const frontend = readFrontendSource();
  assert.match(privileged, /encoder\.encode\(temporaryPassword\), true, AccountStatus\.PENDING_APPROVAL/);
  assert.match(staff,
      /encoder\.encode\(temporaryPassword\),\s*true,\s*AccountStatus\.PENDING_HR_APPROVAL/);
  assert.match(frontend, /forcePasswordChange: true/);
  assert.match(frontend, /Boolean\(account\.forcePasswordChange\)/);
  assert.match(frontend, /Preview verification code/);
});

test("Manager lifecycle and CEO visitor routes stay aligned across backend and preview", () => {
  const closure = read("backend/src/main/java/com/brainserve/appointment/iam/api/AccountClosureController.java");
  const appointment = read("backend/src/main/java/com/brainserve/appointment/appointment/domain/Appointment.java");
  const service = read("backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
  const frontend = readFrontendSource();
  assert.match(closure, /hasAnyRole\('CEO','MANAGER','HR_ADMIN','TEAM_LEAD','RECEPTIONIST','SECURITY'\)/);
  assert.match(appointment, /transitionTo\(AppointmentStatus\.PENDING_MANAGER_APPROVAL\)/);
  assert.match(appointment, /transitionTo\(AppointmentStatus\.PENDING_CEO_APPROVAL\)/);
  assert.match(service, /if \(requiresManager\) managers\.requireForDepartment/);
  assert.match(frontend, /stage === "manager" && isCeoApprovalRoute\(appointment\) \? "Awaiting CEO"/);
  assert.match(frontend, /Security and Reception verified · Manager approved · waiting for CEO final decision/);
  assert.match(frontend, /item\.role === "ROLE_MANAGER" \? departments\.find/);
});

test("role transitions are counted in PostgreSQL and exactly one committed role is enforced", () => {
  const repository = read("backend/src/main/java/com/brainserve/appointment/iam/infrastructure/UserAccountRepository.java");
  const transition = read("backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java");
  const migration = read("backend/src/main/resources/db/migration/V40__close_role_and_manager_routing_gaps.sql");
  assert.match(repository, /findOperationalRoleTransitionCandidates/);
  assert.match(repository, /user\.employeeId is not null/);
  assert.match(repository, /count\(distinct user\.id\)/);
  assert.match(transition, /users\.findOperationalRoleTransitionCandidates/);
  assert.match(migration, /CREATE CONSTRAINT TRIGGER ck_iam_user_exactly_one_role/);
  assert.match(migration, /DEFERRABLE INITIALLY DEFERRED/);
  assert.match(migration, /count\(\*\).*iam_user_role/s);
});

test("manual internal calls cannot cross department boundaries for department-owned roles", () => {
  const service = read("backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
  const frontend = readFrontendSource();
  assert.match(service, /isWithinManualMessageScope/);
  assert.match(service, /INTERNAL_CALL_DEPARTMENT_NOT_ALLOWED/);
  assert.match(service, /employees\.departmentIdForEmployee/);
  assert.match(frontend, /demoAccountDepartment/);
  assert.match(frontend, /same-department HR/);
});

test("API refresh and realtime recovery are bounded and revoke stale browser sessions", () => {
  const api = read("app/lib/api.ts");
  const frontend = readFrontendSource();
  assert.match(api, /AUTH_SESSION_EXPIRED_EVENT/);
  assert.match(api, /signal: controller\.signal/);
  assert.match(api, /Math\.min\(30_000, 3_000 \* \(2 \*\* reconnectAttempt\)\)/);
  assert.match(api, /onAuthSessionExpired/);
  assert.match(frontend, /setSessionMessage\(\s*"Your login changed, expired or was revoked/);
  assert.match(frontend, /queueSafeRefresh/);
  assert.match(frontend, /PREVIEW_WORKSPACE_SESSION_KEY/);
  assert.match(frontend, /readPreviewWorkspaceSession\(\)/);
});

test("production UI uses authoritative departments, consistent naming and accessible motion", () => {
  const frontend = readFrontendSource();
  const styles = read("app/globals.css");
  const layout = read("app/layout.tsx");
  assert.match(frontend, /brainServeApi\.publicDepartments\(\)/);
  assert.match(frontend, /name: "BrainServe Connect"/);
  assert.match(frontend, /useModalDialog\(onClose\)/);
  assert.match(styles, /prefers-reduced-motion: reduce/);
  assert.match(styles, /\.button:disabled, \.icon-button:disabled/);
  assert.doesNotMatch(layout, /codex-preview/);
  assert.match(frontend, /Standards-correct SHA-256/);
  assert.doesNotMatch(frontend, /const seeds = \[0x811c9dc5/);
});

test("manager approval mail and visitor-pass URLs use production-safe configuration", () => {
  assert.match(read("backend/src/main/java/com/brainserve/appointment/notification/application/NotificationDispatcher.java"),
      /case "MANAGER_VISIT_APPROVAL_REQUIRED"/);
  const visitorPass = read("backend/src/main/java/com/brainserve/appointment/appointment/application/VisitorPassService.java");
  assert.match(visitorPass, /brainserve\.frontend\.public-url/);
  assert.doesNotMatch(visitorPass, /https:\/\/brainserve\.in\/visitor-pass/);
  assert.match(read("backend/src/main/resources/application.properties"),
      /brainserve\.notification\.from=\$\{MAIL_FROM:noreply@brainserve\.in}/);
});

