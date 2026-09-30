import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("account closure uses retained snapshots instead of destructive deletion", () => {
  const migration = read("backend/src/main/resources/db/migration/V28__account_closure_and_archival.sql");
  assert.match(migration, /CREATE TABLE account_closure_request/i);
  assert.match(migration, /CREATE TABLE archived_account/i);
  assert.match(migration, /CREATE TABLE account_lifecycle_record/i);
  assert.match(migration, /archived_at/);
  assert.doesNotMatch(migration, /password_hash.*archived_account/i);
  assert.doesNotMatch(migration, /profile.*binary/i);
});

test("permanent System Admin and employee termination routes cannot be bypassed", () => {
  const service = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
  const termination = read("backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeTerminationService.java");
  const activeFilter = read("backend/src/main/java/com/brainserve/appointment/iam/config/ActiveAccountFilter.java");
  const security = read("backend/src/main/java/com/brainserve/appointment/iam/config/SecurityConfiguration.java");
  assert.match(service, /SYSTEM_ADMIN_ACCOUNT_PROTECTED/);
  assert.match(service, /EMPLOYEE_TERMINATION_WORKFLOW_REQUIRED/);
  assert.match(termination, /archiveAfterEmployeeTermination/);
  assert.match(service, /sessions\.revokeAllForUser/);
  assert.match(service, /target\.archive/);
  assert.match(activeFilter, /account\.isEnabled\(\) && !account\.isArchived\(\)/);
  assert.match(security, /addFilterAfter\(activeAccountFilter, BearerTokenAuthenticationFilter\.class\)/);
});

test("approval routes and direct archival are server-side role locked", () => {
  const selfController = read("backend/src/main/java/com/brainserve/appointment/iam/api/AccountClosureController.java");
  const adminController = read("backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountLifecycleController.java");
  assert.match(selfController, /hasAnyRole\('CEO','MANAGER','HR_ADMIN','TEAM_LEAD','RECEPTIONIST','SECURITY'\)/);
  assert.match(selfController, /hasAnyRole\('CEO','HR_ADMIN'\)/);
  assert.match(adminController, /hasRole\('SYSTEM_ADMIN'\)/);
  assert.match(adminController, /direct-archive\/request-otp/);
  assert.match(adminController, /direct-archive\/challenge/);
  assert.match(adminController, /direct-archive/);
});

test("direct archive verification is resumable, bounded and never stores the System Admin password", () => {
  const service = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
  const controller = read("backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountLifecycleController.java");
  assert.match(service, /activeDirectArchiveChallenge/);
  assert.match(service, /resendDirectArchiveOtp/);
  assert.match(service, /otpMaxAttempts/);
  assert.match(service, /otpResendSeconds/);
  assert.match(service, /passwordFailureKey/);
  assert.match(service, /findByIdForUpdate/);
  assert.match(service, /TransactionSynchronizationManager\.registerSynchronization/);
  assert.match(service, /sessions\.revokeAllForUser/);
  assert.match(controller, /@DeleteMapping\("\/direct-archive\/challenge\/\{challengeId\}"\)/);
  assert.doesNotMatch(service, /Map\.entry\("currentPassword"/);
  assert.doesNotMatch(service, /Map\.entry\("otp"/);
});

test("every lifecycle transition creates audit, essential log and Kafka-backed notifications", () => {
  const service = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
  const notifications = read("backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
  assert.match(service, /lifecycle\.save/);
  assert.match(service, /audit\.record/);
  assert.match(service, /logs\.record/);
  assert.match(service, /notifyAccountClosureReview/);
  assert.match(service, /notifySystemAdminOfAccountClosure/);
  assert.match(service, /notifyAccountClosureDecision/);
  assert.match(notifications, /persistAndPublish/);
});

test("Team Lead closure transfers open work without rewriting approved history", () => {
  const assignment = read("backend/src/main/java/com/brainserve/appointment/teamlead/application/TeamLeadAssignmentService.java");
  const listener = read("backend/src/main/java/com/brainserve/appointment/worktask/application/TeamLeadClosureTaskReassignmentListener.java");
  const task = read("backend/src/main/java/com/brainserve/appointment/worktask/domain/DepartmentWorkTask.java");
  assert.match(assignment, /ReassignedForAccountClosure/);
  assert.match(listener, /findAllByTeamLeadUserIdAndDepartmentId/);
  assert.match(listener, /WorkTaskStatus\.APPROVED/);
  assert.match(listener, /WorkTaskStatus\.ACKNOWLEDGED/);
  assert.match(task, /reassignOpenTask/);
});

test("frontend exposes self-service closure and the System Admin lifecycle workspace", () => {
  const app = readFrontendSource();
  const api = read("app/lib/api.ts");
  assert.match(app, /label: "Account lifecycle"/);
  assert.match(app, /function AccountLifecycleView/);
  assert.match(app, /Deactivate &amp; archive/);
  assert.match(app, /Permanent protected account/);
  assert.match(app, /Pending closure requests/);
  assert.match(app, /Archived accounts/);
  assert.match(api, /requestMyAccountClosure/);
  assert.match(api, /activeDirectArchiveChallenge/);
  assert.match(api, /requestDirectArchiveOtp/);
  assert.match(api, /resendDirectArchiveOtp/);
  assert.match(api, /cancelDirectArchiveChallenge/);
  assert.match(api, /directArchiveAccount/);
});

test("archived recovery keeps one identity and one current role behind password and OTP verification", () => {
  const migration = read(
    "backend/src/main/resources/db/migration/V44__governed_archived_account_recovery.sql");
  const account = read(
    "backend/src/main/java/com/brainserve/appointment/iam/domain/UserAccount.java");
  const service = read(
    "backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
  const roles = read(
    "backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java");
  const controller = read(
    "backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountLifecycleController.java");

  assert.match(migration, /recovered_at/);
  assert.match(migration, /CREATE UNIQUE INDEX ux_archived_account_current/i);
  assert.match(migration, /WHERE recovered_at IS NULL/i);
  assert.match(account, /recoverArchivedWithRole/);
  assert.match(account, /roles\.clear\(\);\s*roles\.add\(nextRole\)/);
  assert.match(service, /verifySystemAdminPassword\(admin, currentPassword\)/);
  assert.match(service, /sendArchivedAccountRecoveryOtp/);
  assert.match(service, /verifyRecoveryChallengeOtp/);
  assert.match(service, /roleTransitions\.recoverArchived/);
  assert.match(roles, /sessions\.revokeAllForUser\(targetUserId/);
  assert.match(roles, /CEO_ALREADY_ASSIGNED/);
  assert.match(roles, /requireTargetAssignmentAvailable/);
  assert.match(service, /ACCOUNT_LIFECYCLE_VERIFICATION_PENDING/);
  assert.match(migration, /recovered_department_id uuid REFERENCES org_department/);
  assert.match(controller, /archived-recovery\/request-otp/);
  assert.match(controller, /archived-recovery\/challenge/);
  assert.doesNotMatch(service, /Map\.entry\("currentPassword"/);
});

test("recovery is a persistent in-workspace section and preserves only the frozen challenge", () => {
  const app = readFrontendSource();
  const api = read("app/lib/api.ts");
  assert.match(app, /Recover account/);
  assert.match(app, /GOVERNED RECOVERY/);
  assert.match(app, /Minimize recovery verification/);
  assert.match(app, /writePreviewArchivedRecoveryChallenge/);
  assert.match(app, /same user and employee IDs/);
  assert.match(app, /Previous role and department remain in immutable lifecycle history/);
  assert.match(api, /activeArchivedRecoveryChallenge/);
  assert.match(api, /requestArchivedRecoveryOtp/);
  assert.match(api, /resendArchivedRecoveryOtp/);
  assert.match(api, /cancelArchivedRecoveryChallenge/);
  assert.match(api, /recoverArchivedAccount/);
  assert.doesNotMatch(app, /PREVIEW_ARCHIVED_RECOVERY_PASSWORD_KEY/);
});

