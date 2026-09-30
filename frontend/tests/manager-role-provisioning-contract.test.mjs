import { readFrontendModule, readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const frontend = readFrontendSource();
const provisioning = read("../backend/src/main/java/com/brainserve/appointment/iam/application/AccountProvisioningService.java");
const registration = read("../backend/src/main/java/com/brainserve/appointment/iam/api/RegistrationController.java");
const onboarding = read("../backend/src/main/java/com/brainserve/appointment/iam/application/PrivilegedHrOnboardingService.java");
const transition = read("../backend/src/main/java/com/brainserve/appointment/iam/application/OperationalRoleTransitionService.java");
const identity = read("../backend/src/main/java/com/brainserve/appointment/iam/application/TeamLeadIdentityServiceImpl.java");
const migration = read("../backend/src/main/resources/db/migration/V39__safe_operational_role_replacement.sql");

test("Manager is visible and accepted through both account creation paths", () => {
  const registrationView = readFrontendModule("features/auth/account-registration.tsx");
  const provisioningView = readFrontendModule("features/accounts/components/account-provisioning-panel.tsx");
  assert.ok(registrationView.includes('<option value="ROLE_MANAGER">Manager</option>'));
  assert.ok(provisioning.includes("SystemRole.ROLE_HR_ADMIN, SystemRole.ROLE_MANAGER"));
  assert.ok(provisioning.includes("CEO, HR Admin or Manager accounts can be created here"));
  assert.ok(registration.includes("case ROLE_HR_ADMIN, ROLE_MANAGER"));
  assert.ok(frontend.includes("<h2>CEO, HR Admin or Manager</h2>"));
  assert.ok(provisioningView.includes('role === "System Admin" && <article'));
  assert.equal(provisioningView.includes('role === "System Admin" && isBackendConfigured && <article'), false);
  assert.ok(provisioningView.includes("newDemoTemporaryPassword()"));
  assert.ok(provisioningView.includes("writeDemoAccounts([...existingAccounts, previewAccount])"));
  assert.ok(provisioningView.includes("PREVIEW PASSWORD · SHOWN FOR THIS SESSION"));
});

test("Manager approval creates its employee profile and department assignment atomically", () => {
  assert.ok(onboarding.includes("requireOnboardedRole"));
  assert.ok(onboarding.includes("organization.lockActiveDepartment"));
  assert.ok(onboarding.includes("managers.assignForOnboarding"));
  assert.ok(onboarding.includes("MANAGER_ACCOUNT_ONBOARDING_COMPLETED"));
  assert.ok(frontend.includes('["ROLE_HR_ADMIN", "ROLE_MANAGER"].includes(account.role)'));
  assert.ok(frontend.includes("assignedManagerDepartmentIds"));
  assert.ok(frontend.includes("writeDemoManagerAssignments"));
});

test("role replacements keep one committed role without transient database conflicts", () => {
  assert.ok(migration.includes("DEFERRABLE INITIALLY DEFERRED"));
  assert.ok(migration.includes("UNIQUE (user_id)"));
  assert.ok(identity.includes("findByEmployeeIdForUpdate"));
  assert.ok(identity.includes("users.saveAndFlush(user)"));
  assert.ok(transition.includes("findByIdForUpdate"));
  assert.ok(transition.includes("organization.lockActiveDepartment"));
  assert.ok(transition.includes("target.replaceOperationalRole(targetRole)"));
});
