import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const service = readFileSync(
    new URL("../../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java", import.meta.url),
    "utf8",
);
const controller = readFileSync(
    new URL("../../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeController.java", import.meta.url),
    "utf8",
);
const termination = readFileSync(
    new URL("../../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeTerminationService.java", import.meta.url),
    "utf8",
);
const migration = readFileSync(
    new URL("../../backend/src/main/resources/db/migration/V46__protect_active_ceo_employee_lifecycle.sql", import.meta.url),
    "utf8",
);
const ui = readFrontendSource();

test("HR, Manager, Team Lead and Employee lists are derived from their assigned department", () => {
    const hrScope = service.indexOf('actor.roles().contains("ROLE_HR_ADMIN")');
    const teamLeadScope = service.indexOf('actor.roles().contains("ROLE_TEAM_LEAD")');
    const managerScope = service.indexOf('actor.roles().contains("ROLE_MANAGER")');
    const employeeScope = service.indexOf('actor.roles().contains("ROLE_EMPLOYEE")');

    assert.ok(hrScope >= 0);
    assert.ok(teamLeadScope > hrScope);
    assert.ok(managerScope > teamLeadScope);
    assert.ok(employeeScope > managerScope);
    assert.match(service, /departmentHrs\s*\.requireForUser\(actorUserId\)\s*\.departmentId\(\)/);
    assert.match(service, /teamLeads\s*\.requireForUser\(actorUserId\)\s*\.departmentId\(\)/);
    assert.match(service, /managers\s*\.requireForUser\(actorUserId\)\s*\.departmentId\(\)/);
    assert.match(service, /actor\.employeeId\(\)/);
    assert.match(service, /departmentIdForEmployee\(actor\.employeeId\(\)\)/);
    assert.match(service, /EMPLOYEE_PROFILE_NOT_LINKED/);
    assert.match(service, /EMPLOYEE_DEPARTMENT_SCOPE_DENIED/);
});

test("employee read and lifecycle endpoints pass the authenticated actor into scope checks", () => {
    assert.match(controller, /service\.list\(actor\(jwt\), query, departmentId, status, pageable\)/);
    assert.match(controller, /service\.getVisible\(actor\(jwt\), id\)/);
    assert.match(controller, /service\.changeStatusScoped\(actor\(jwt\), id, request\.status\(\)\)/);
    assert.match(controller, /service\.departmentSummaries\(actor\(jwt\)\)/);
});

test("department-scoped employee UI removes the company-wide department selector", () => {
    assert.match(ui, /const departmentScoped = \["HR Admin", "Manager", "Team Lead", "Employee"\]\.includes\(role\)/);
    assert.match(ui, /loadedEmployees\.filter\(\s*\(employee\) => employee\.departmentId === scopedDepartmentId/);
    assert.match(ui, /departmentScoped\s*\?\s*scopedDepartmentId[\s\S]*?: \[\][\s\S]*?: loadedEmployees/);
    assert.match(ui, /if \(departmentScoped && !scopedDepartmentId\)[\s\S]*?setBackendPageEmployees\(\[\]\)[\s\S]*?setTotalElements\(0\)/);
    assert.match(ui, /Your employee department assignment could not be resolved/);
    assert.match(ui, /departmentId: selectedDepartment/);
    assert.match(ui, /departmentScoped \?[\s\S]*scoped-department-label[\s\S]*All departments/);
    assert.match(ui, /<small>\{role\} · \{userEmail\}<\/small>/);
});

test("a CEO department profile is visible to HR but protected from lifecycle actions", () => {
    assert.match(service, /requireLifecycleAuthority\(\s*actorUserId,\s*employee\s*\)/);
    assert.match(service, /public boolean isChiefExecutive\(UUID employeeId\)/);
    assert.match(service, /public Set<UUID> chiefExecutiveEmployeeIds\(\)/);
    assert.match(service, /staff\.activeWithAnyRole\(Set\.of\("ROLE_CEO"\)\)/);
    assert.match(service, /findByOfficialEmailIgnoreCase\(\s*account\.email\(\)\s*\)/);
    assert.match(service, /CEO_LIFECYCLE_PROTECTED/);
    assert.match(service, /rejectedSecurityAudit\.record\(\s*"CEO_LIFECYCLE_CHANGE_BLOCKED"/);
    assert.match(controller, /boolean lifecycleProtected/);
    assert.match(controller, /lifecycleProtectedEmployeeIds\.contains\(value\.getId\(\)\)/);
    assert.match(termination, /employees\.isChiefExecutive\(employeeId\)/);
    assert.match(ui, /item\.lifecycleProtected/);
    assert.match(ui, /!isCompanyExecutive && transitions\(item\.status\)\.length/);
    assert.match(ui, /Company-wide authority · System Admin managed/);
    assert.match(ui, /protected-lifecycle-label/);
    assert.match(migration, /protect_active_ceo_employee_lifecycle/);
    assert.match(migration, /BEFORE UPDATE OF status, relieving_date ON employee/);
    assert.match(migration, /BEFORE DELETE ON employee/);
    assert.match(migration, /lower\(account\.email\) = lower\(OLD\.official_email\)/);
    assert.match(migration, /CEO_LIFECYCLE_PROTECTED/);
});

