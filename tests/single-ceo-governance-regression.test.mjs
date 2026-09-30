import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const read = (path) => readFileSync(path, "utf8");
const provisioning = read(
  "backend/src/main/java/com/brainserve/appointment/iam/application/AccountProvisioningService.java",
);
const migration = read(
  "backend/src/main/resources/db/migration/V41__single_company_ceo_governance.sql",
);
const closure = read(
  "backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java",
);
const communicationDirectory = read(
  "backend/src/main/java/com/brainserve/appointment/iam/application/StaffCommunicationDirectoryService.java",
);
const internalCalls = read(
  "backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
);
const workInsights = read(
  "backend/src/main/java/com/brainserve/appointment/workinsight/application/WorkInsightService.java",
);
const terminations = read(
  "backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeTerminationService.java",
);
const roleChanges = read(
  "backend/src/main/java/com/brainserve/appointment/rolechange/application/RoleDepartmentChangeService.java",
);
const frontend = readFrontendSource();

test("PostgreSQL repairs legacy duplicates and enforces one governing CEO at commit", () => {
  assert.match(migration, /ranked_governing_ceos/);
  assert.match(migration, /account_status IN \('ACTIVE', 'PENDING_APPROVAL'\)/);
  assert.match(migration, /pg_advisory_xact_lock/);
  assert.match(migration, /governing_ceo_count > 1/);
  assert.match(migration, /uq_single_governing_ceo/);
  assert.match(migration, /DEFERRABLE INITIALLY DEFERRED/);
});

test("CEO creation is System Admin-only and HR or Manager activation is CEO-only", () => {
  assert.match(provisioning, /SYSTEM_ADMIN_APPROVED_ROLES\s*=\s*\n\s*EnumSet\.of\(SystemRole\.ROLE_CEO\)/);
  assert.match(provisioning, /requireCeoSlotAvailable\(null\)/);
  assert.match(provisioning, /CEO is a singleton company role created only by System Admin/);
  assert.match(provisioning, /requireSingleActiveCeo\(\)/);
  assert.match(provisioning, /CEO_ACCOUNT_ALREADY_EXISTS/);
  assert.match(frontend, /CEO · already assigned/);
  assert.doesNotMatch(frontend, /<option value="ROLE_CEO">CEO<\/option><option value="ROLE_MANAGER">Manager<\/option><option value="ROLE_HR_ADMIN">HR Admin<\/option><option value="ROLE_EMPLOYEE">/);
});

test("company-wide CEO routes resolve one exact executive without department filtering", () => {
  assert.match(communicationDirectory, /requireChiefExecutive\(\)/);
  assert.match(communicationDirectory, /chiefExecutives\.size\(\) != 1/);
  assert.match(internalCalls, /roles\.equals\(Set\.of\(CEO\)\)/);
  assert.match(internalCalls, /staff\.requireChiefExecutive\(\)/);
  assert.doesNotMatch(internalCalls, /sender\.roles\(\)\.contains\(HR\).*recipient\.roles\(\)\.contains\(CEO\).*departmentBound/s);
  assert.match(workInsights, /roles\.contains\(CEO\) \|\| roles\.contains\(SYSTEM_ADMIN\)/);
  assert.match(terminations, /findAllByStatusOrderByRequestedAtAsc/);
  assert.match(roleChanges, /return approver\.roles\(\)\.contains\(CEO\)/);
  assert.match(frontend, /Your department is a work assignment only; CEO governance remains company-wide/);
});

test("archive verification rejects random System Admin passwords before OTP issuance", () => {
  assert.match(closure, /passwordEncoder\.matches\(currentPassword, admin\.getPasswordHash\(\)\)/);
  assert.match(closure, /verifySystemAdminPassword\(admin, currentPassword\);\s*UserAccount target/s);
  assert.match(frontend, /verifyPreviewSystemAdminPassword\(userEmail, currentPassword\)/);
  assert.match(frontend, /constantTimeHexEqual\(admin\.passwordHash/);
  assert.match(closure, /CEO_SUCCESSION_REQUIRED/);
  assert.match(closure, /role == SystemRole\.ROLE_SYSTEM_ADMIN \|\| role == SystemRole\.ROLE_CEO/);
});

