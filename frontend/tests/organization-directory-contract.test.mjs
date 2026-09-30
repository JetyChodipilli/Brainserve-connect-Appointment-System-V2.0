import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const controller = read("../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeController.java");
const repository = read("../backend/src/main/java/com/brainserve/appointment/employee/infrastructure/EmployeeRepository.java");
const departmentController = read("../backend/src/main/java/com/brainserve/appointment/organization/api/DepartmentController.java");
const organizationScopeController = read("../backend/src/main/java/com/brainserve/appointment/reporting/api/OrganizationScopeController.java");
const staffAccountController = read("../backend/src/main/java/com/brainserve/appointment/iam/api/StaffAccountAdministrationController.java");
const identityProvisioning = read("../backend/src/main/java/com/brainserve/appointment/iam/application/IdentityProvisioningServiceImpl.java");
const api = readApiSource();
const app = readFrontendSource();

test("department workforce counts are calculated by PostgreSQL", () => {
  assert.ok(repository.includes("summarizeByDepartment"));
  assert.ok(repository.includes("group by department_id"));
  assert.ok(sourceIncludes(repository, "count(*) filter (where status = 'ACTIVE')"));
  assert.ok(controller.includes('@GetMapping("/department-summary")'));
});

test("department rosters use a server-side department filter", () => {
  assert.ok(controller.includes("@RequestParam(required = false) UUID departmentId"));
  assert.ok(repository.includes("findAllByDepartmentId"));
  assert.ok(api.includes('params.set("departmentId", filters.departmentId)'));
});

test("organization actions remain permission controlled and transactional", () => {
  assert.ok(departmentController.includes("'DEPARTMENT_MANAGE'"));
  assert.ok(departmentController.includes("@Transactional"));
  assert.ok(departmentController.includes("DEPARTMENT_ACTIVATED"));
  assert.ok(departmentController.includes("DEPARTMENT_DEACTIVATED"));
});

test("System Admin account lifecycle filters use database departments instead of preview fixtures", () => {
  assert.ok(departmentController.includes("hasAnyRole('SYSTEM_ADMIN','CEO')"));
  assert.ok(sourceIncludes(app, "isBackendConfigured ? [] : readDemoDepartments()"));
  assert.ok(app.includes('if (role === "System Admin")'));
  assert.ok(sourceIncludes(
      app,
      "const [departmentList, managerAssignmentList] = await Promise.all([brainServeApi.departments(), brainServeApi.managerAssignments()])",
  ));
  assert.ok(app.includes("setDepartments(departmentList)"));
});

test("CEO sees all departments while HR, Manager, Team Lead and Employee receive only their assigned department", () => {
  assert.ok(organizationScopeController.includes('@GetMapping("/visible")'));
  assert.ok(organizationScopeController.includes("hasAnyRole('CEO','HR_ADMIN','MANAGER','TEAM_LEAD','EMPLOYEE')"));
  assert.ok(organizationScopeController.includes("departmentHrs.requireForUser(userId).departmentId()"));
  assert.ok(organizationScopeController.includes("teamLeads.requireForUser(userId).departmentId()"));
  assert.ok(organizationScopeController.includes("managers.requireForUser(userId).departmentId()"));
  assert.ok(organizationScopeController.includes('jwt.getClaimAsString("employeeId")'));
  assert.ok(organizationScopeController.includes("employees.departmentIdForEmployee(UUID.fromString(employeeId))"));
  assert.ok(organizationScopeController.includes("organization.allDepartments()"));
  assert.ok(api.includes("visibleDepartments()"));
  assert.ok(api.includes('"/departments/visible"'));
  assert.ok(app.includes("visibleDepartments.map((department)"));
  assert.ok(app.includes('role === "CEO" ? "Organization-wide access" : "Department-scoped access"'));
});

test("premium organization cards open live rosters and department-scoped onboarding", () => {
  assert.ok(app.includes("Your company, clearly connected"));
  assert.ok(app.includes("openDepartment(department)"));
  assert.ok(app.includes("brainServeApi.employeePage({"));
  assert.ok(app.includes("departmentId: department.id"));
  assert.ok(app.includes("size: 50"));
  assert.ok(app.includes("onAddEmployee(department.id)"));
  assert.ok(app.includes("initialDepartmentId={employeeDepartmentId}"));
});

test("HR lifecycle actions work in demo and backend modes", () => {
  assert.ok(app.includes("const changeEmployeeLifecycle"));
  assert.ok(app.includes("if (isBackendConfigured)"));
  assert.ok(app.includes("brainServeApi.changeEmployeeStatus(employee.uuid"));
  assert.ok(!app.includes("const changeEmployeeLifecycle = async (employee: Employee, nextStatus: Employee[\"status\"]) => {\n    if (!employee.uuid) return;"));
  assert.ok(app.includes('busy ? "Saving…" : "Choose action"'));
});

test("department members use stable department IDs and stay visible after updates", () => {
  assert.ok(app.includes("departmentId?: string"));
  assert.ok(app.includes("belongsToDepartment(employee, department)"));
  assert.ok(app.includes("const liveMemberById = new Map"));
  assert.ok(app.includes("loadedRoster.items.map"));
  assert.ok(app.includes("departmentId: item.departmentId"));
  assert.ok(app.includes("Department Team Lead"));
  assert.ok(app.includes("const activeLeadAssignment"));
  assert.ok(app.includes("teamLeadAssignments.find"));
  assert.ok(app.includes("No Team Lead assigned"));
  assert.ok(!app.includes("Reporting manager in department"));
  assert.ok(!app.includes("Select manager or leave unassigned"));
});

test("Team Lead candidates are searchable and backend validated after HR approval", () => {
  assert.ok(app.includes("onDecision={refreshStaffAccounts}"));
  assert.ok(app.includes("await onDecision?.()"));
  assert.ok(app.includes("setStaffAccounts(await brainServeApi.staffAccounts())"));
  assert.ok(app.includes('account.roles.length === 1 && account.roles[0] === "ROLE_EMPLOYEE"'));
  assert.ok(app.includes("eligibleLeadEmployees"));
  assert.ok(app.includes("canManageLead && department.active"));
  assert.ok(app.includes("Only enabled, approved Employee accounts are eligible"));
  assert.ok(!app.includes("canManageLead && !routingDepartment"));
  assert.ok(app.includes("Search by name, employee ID or email"));
});

test("approved Employee accounts can be assigned to a department before Team Lead promotion", () => {
  assert.ok(staffAccountController.includes("UUID employeeId"));
  assert.ok(staffAccountController.includes("user.getEmployeeId()"));
  assert.ok(identityProvisioning.includes("AccountStatus.ACTIVE"));
  assert.ok(identityProvisioning.includes("account.linkEmployee(employeeId)"));
  assert.ok(app.includes("unassignedEmployeeAccounts"));
  assert.ok(app.includes("Assign department and create employee ID"));
  assert.ok(app.includes("Complete approved employee profile"));
  assert.ok(app.includes("Assign department & create ID"));
  assert.ok(app.includes("employeeId: linkedEmployeeId"));
  assert.ok(app.includes("HR must assign your department and employee ID before you can sign in"));
});

test("department assignment accepts registered employees with a single name and surfaces modal errors", () => {
  assert.ok(controller.includes('@Size(max = 80) String lastName'));
  assert.ok(!controller.includes('@NotBlank @Size(max = 80) String lastName'));
  assert.ok(!app.includes("Enter the employee's first and last name."));
  assert.ok(app.includes('if (name.length < 2)'));
  assert.ok(app.includes('initialDepartmentId={employeeDepartmentId} error={operationError}'));
  assert.ok(app.includes('{error && <div className="login-error" role="alert">{error}</div>}'));
});

