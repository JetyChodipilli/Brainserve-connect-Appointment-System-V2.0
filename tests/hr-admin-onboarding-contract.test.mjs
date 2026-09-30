import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const onboarding = read("backend/src/main/java/com/brainserve/appointment/iam/application/PrivilegedHrOnboardingService.java");
const profile = read("backend/src/main/java/com/brainserve/appointment/employee/application/HrEmployeeProfileProvisioningService.java");
const ceoController = read("backend/src/main/java/com/brainserve/appointment/iam/api/CeoAccountApprovalController.java");
const adminController = read("backend/src/main/java/com/brainserve/appointment/iam/api/SystemAdminAccountController.java");
const frontend = readFrontendSource();
const api = read("app/lib/api.ts");

test("CEO HR approval atomically creates the employee profile and department assignment", () => {
  assert.ok(sourceIncludes(onboarding, "employeeProfiles.createAndLink(approved.getId()"));
  assert.ok(sourceIncludes(onboarding, "accounts.approveByCeo(actorId, targetId)"));
  assert.ok(sourceIncludes(
      onboarding,
      "departmentHrs.assignForOnboarding(actorId, command.departmentId(), approved.getId())",
  ));
  assert.ok(onboarding.includes("DEPARTMENT_HR_ALREADY_ASSIGNED"));
  assert.ok(profile.includes("employee.transitionTo(EmployeeStatus.ACTIVE)"));
  assert.ok(sourceIncludes(profile, "appointmentHosts.linkEmployee(userAccountId, employee.getId())"));
});

test("CEO alone activates HR department onboarding while System Admin governs only the CEO", () => {
  assert.ok(ceoController.includes("PrivilegedApprovalRequest request"));
  assert.ok(sourceIncludes(ceoController, "hrOnboarding.approveByCeo(actorId(jwt), id, request.command())"));
  assert.ok(onboarding.includes("CEO_APPROVAL_REQUIRED"));
  assert.ok(onboarding.includes("HR Admin and Manager accounts must be approved by the single company CEO"));
  assert.ok(adminController.includes('GetMapping("/ceo-slot")'));
  assert.ok(api.includes("onboarding?: HrAccountApprovalInput"));
});

test("approval UI requires an unassigned department and HR employee details", () => {
  assert.ok(frontend.includes("Assign department before activation"));
  assert.ok(frontend.includes("Select an unassigned department"));
  assert.ok(frontend.includes("Approve, create ID & assign"));
  assert.ok(frontend.includes("Every active department already has an HR Admin"));
});

