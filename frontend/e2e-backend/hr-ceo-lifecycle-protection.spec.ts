import { expect, test } from "@playwright/test";

const departmentId = "11111111-1111-4111-8111-111111111111";
const hrUserId = "22222222-2222-4222-8222-222222222222";
const hrEmployeeId = "33333333-3333-4333-8333-333333333333";
const ceoEmployeeId = "44444444-4444-4444-8444-444444444444";
const employeeId = "55555555-5555-4555-8555-555555555555";

const employees = [
  {
    id: ceoEmployeeId,
    employeeNumber: "BSPL-TECH-0001",
    displayName: "Jety CEO",
    officialEmail: "jety@brainserve.in",
    phoneNumber: null,
    departmentId,
    designation: "Chief Executive Officer",
    joiningDate: "2026-01-01",
    relievingDate: null,
    status: "ACTIVE",
    lifecycleProtected: true,
    version: 1,
  },
  {
    id: employeeId,
    employeeNumber: "BSPL-TECH-0002",
    displayName: "Asha Employee",
    officialEmail: "asha@brainserve.in",
    phoneNumber: null,
    departmentId,
    designation: "Software Engineer",
    joiningDate: "2026-02-01",
    relievingDate: null,
    status: "ACTIVE",
    lifecycleProtected: false,
    version: 1,
  },
];

const pageOf = <T,>(content: T[]) => ({
  content,
  number: 0,
  size: 100,
  totalElements: content.length,
  totalPages: 1,
  first: true,
  last: true,
  empty: content.length === 0,
});

test("department HR sees the CEO but cannot change status or request termination", async ({ page }) => {
  let ceoLifecycleWriteAttempted = false;

  await page.route("http://backend.invalid/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;

    if (path === "/api/v1/public/company-profile") {
      await route.fulfill({ json: {
          name: "BrainServe Private Limited",
          emailDomain: "brainserve.in",
          hqAddress: "Hyderabad, Telangana, India",
          supportEmail: "support@brainserve.in",
        } });
      return;
    }
    if (path === "/api/v1/public/hosts") {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === "/api/v1/auth/login" && request.method() === "POST") {
      await route.fulfill({ json: {
          accessToken: "hr-access-token",
          refreshToken: "hr-refresh-token",
          forcePasswordChange: false,
        } });
      return;
    }
    if (path === "/api/v1/auth/me") {
      await route.fulfill({ json: {
          userId: hrUserId,
          employeeId: hrEmployeeId,
          email: "hr.tech@brainserve.in",
          roles: ["ROLE_HR_ADMIN"],
          permissions: ["EMPLOYEE_READ", "EMPLOYEE_STATUS_CHANGE"],
          forcePasswordChange: false,
        } });
      return;
    }
    if (path === "/api/v1/profile/me") {
      await route.fulfill({ json: {
          userId: hrUserId,
          employeeId: hrEmployeeId,
          fullName: "Technology HR",
          email: "hr.tech@brainserve.in",
          roles: ["ROLE_HR_ADMIN"],
          employeeNumber: "BSPL-TECH-HR01",
          designation: "HR Admin",
          employeeStatus: "ACTIVE",
          departmentId,
          departmentCode: "TECH",
          departmentName: "Technology",
          departmentActive: true,
          photoDocumentId: null,
          photoUrl: null,
          photoUrlExpiresAt: null,
        } });
      return;
    }
    if (path === "/api/v1/employees") {
      await route.fulfill({ json: pageOf(employees) });
      return;
    }
    if (path === "/api/v1/departments/visible") {
      await route.fulfill({ json: [
          { id: departmentId, code: "TECH", name: "Technology", active: true, version: 1 },
        ] });
      return;
    }
    if (path === "/api/v1/employees/department-summary") {
      await route.fulfill({ json: [
          { departmentId, totalEmployees: 2, activeEmployees: 2, onLeaveEmployees: 0, onboardingEmployees: 0 },
        ] });
      return;
    }
    if (path === "/api/v1/team-leads/assignments"
        || path === "/api/v1/department-hrs/assignments"
        || path === "/api/v1/hr/users") {
      await route.fulfill({ json: [] });
      return;
    }
    if (path === "/api/v1/admin/staff-accounts") {
      // This endpoint intentionally excludes CEO accounts. The employee API's
      // lifecycleProtected flag must still protect Jety's row.
      await route.fulfill({ json: pageOf([{
          userId: "66666666-6666-4666-8666-666666666666",
          employeeId,
          fullName: "Asha Employee",
          email: "asha@brainserve.in",
          roles: ["ROLE_EMPLOYEE"],
          enabled: true,
          forcePasswordChange: false,
          status: "ACTIVE",
          grantedPermissions: [],
          deniedPermissions: [],
          effectivePermissions: ["EMPLOYEE_READ"],
        }]) });
      return;
    }
    if (path === "/api/v1/appointments") {
      await route.fulfill({ json: pageOf([]) });
      return;
    }
    if (path === "/api/v1/dashboard/summary") {
      await route.fulfill({ json: {
          awaitingApproval: 0,
          activeVisits: 0,
          visitorsInside: 0,
          totalEmployees: 2,
          activeEmployees: 2,
        } });
      return;
    }
    if (path === "/api/v1/realtime/stream") {
      await route.fulfill({ status: 204 });
      return;
    }
    if ((path === `/api/v1/employees/${ceoEmployeeId}/status`
            || path === "/api/v1/employee-terminations")
        && request.method() !== "GET") {
      ceoLifecycleWriteAttempted = true;
      await route.fulfill({ status: 403, json: {
          errorCode: "CEO_LIFECYCLE_PROTECTED",
          detail: "Department HR cannot change the CEO lifecycle.",
        } });
      return;
    }
    await route.fulfill({ status: 404, json: { detail: `Unhandled test request: ${path}` } });
  });

  await page.goto("/");
  await page.getByRole("button", { name: "Staff login" }).click();
  await page.getByLabel("Login email").fill("hr.tech@brainserve.in");
  await page.getByLabel("Password").fill("StrongPass!2026");
  const loginResponse = page.waitForResponse((response) =>
      response.url() === "http://backend.invalid/api/v1/auth/login"
      && response.request().method() === "POST");
  const profileResponse = page.waitForResponse((response) =>
      response.url() === "http://backend.invalid/api/v1/auth/me");
  const [login, profile] = await Promise.all([
    loginResponse,
    profileResponse,
    page.getByRole("button", { name: "Sign in securely" }).click(),
  ]);
  expect(login.status()).toBe(200);
  expect(profile.status()).toBe(200);
  await page.getByRole("button", { name: "Employees" }).click();

  const ceoRow = page.locator(".employee-table.table-row").filter({ hasText: "Jety CEO" });
  await expect(ceoRow).toBeVisible();
  await expect(ceoRow.getByText("Company-wide authority · System Admin managed")).toBeVisible();
  await expect(ceoRow.getByText("Protected", { exact: true })).toBeVisible();
  await expect(ceoRow.getByRole("combobox")).toHaveCount(0);

  const employeeRow = page.locator(".employee-table.table-row").filter({ hasText: "Asha Employee" });
  await expect(employeeRow.getByRole("combobox", { name: "Change Asha Employee status" })).toBeVisible();
  expect(ceoLifecycleWriteAttempted).toBe(false);
});
