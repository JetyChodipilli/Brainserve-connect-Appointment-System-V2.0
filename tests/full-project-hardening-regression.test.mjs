import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const employeeRepository = read("backend/src/main/java/com/brainserve/appointment/employee/infrastructure/EmployeeRepository.java");
const employeeService = read("backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java");
const employeeDirectory = read("backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeDirectory.java");
const publicDirectory = read("backend/src/main/java/com/brainserve/appointment/availability/api/PublicDirectoryController.java");
const hostDirectory = read("backend/src/main/java/com/brainserve/appointment/iam/application/AppointmentHostDirectoryService.java");
const teamLeadController = read("backend/src/main/java/com/brainserve/appointment/teamlead/api/TeamLeadController.java");
const teamLeadService = read("backend/src/main/java/com/brainserve/appointment/teamlead/application/TeamLeadAssignmentService.java");
const staffAdministration = read("backend/src/main/java/com/brainserve/appointment/iam/application/StaffAccountAdministrationService.java");
const permissionAdministration = read("backend/src/main/java/com/brainserve/appointment/iam/application/PermissionAdministrationService.java");
const recovery = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountRecoveryService.java");
const recoveryWriter = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountRecoveryRequestWriter.java");
const accountClosure = read("backend/src/main/java/com/brainserve/appointment/iam/application/AccountClosureService.java");
const api = read("app/lib/api.ts");
const appointments = read("app/lib/appointments.ts");
const ui = readFrontendSource();

test("employee search is performed in PostgreSQL across name, employee ID and email", () => {
  assert.match(employeeRepository, /lower\(employee\.displayName\).*like/s);
  assert.match(employeeRepository, /lower\(employee\.employeeNumber\).*concat\(:query, '%'\)/s);
  assert.match(employeeRepository, /lower\(employee\.officialEmail\).*concat\(:query, '%'\)/s);
  assert.ok(sourceIncludes(
      employeeService,
      "return employees.search(departmentId, status, hasQuery, normalizedQuery, pageable)",
  ));
});

test("department and Team Lead directories use bounded pages", () => {
  assert.match(employeeDirectory, /Page<DepartmentMember> departmentMembers\(UUID departmentId, Pageable pageable\)/);
  assert.match(teamLeadController, /Page<EmployeeDirectory\.DepartmentMember> myTeam/);
  assert.match(teamLeadController, /pageable\.getPageSize\(\) < 25 \|\| pageable\.getPageSize\(\) > 100/);
  assert.match(ui, /totalElements: page\.totalElements/);
  assert.match(ui, /Page \{loadedRoster\.page \+ 1\} of \{loadedRoster\.totalPages\}/);
  assert.match(ui, /Name, employee ID or company email/);
});

test("public booking does not download the complete employee directory", () => {
  assert.match(publicDirectory, /@GetMapping\("\/employees"\)/);
  assert.match(publicDirectory, /pageable\.getPageSize\(\) < 10 \|\| pageable\.getPageSize\(\) > 50/);
  assert.match(employeeDirectory, /Page<PublicEmployee> publicActiveEmployees/);
  assert.match(hostDirectory, /findDistinctByRolesContainingAndStatusAndEnabledTrueAndArchivedFalse/);
  assert.match(api, /publicEmployees\(departmentId: string, query = ""\)/);
  assert.match(ui, /Name or employee ID/);
});

test("HR account and permission actions fail closed outside the assigned department", () => {
  assert.ok(sourceIncludes(staffAdministration,
      "Page<UserAccount> list(UUID actorId, String query, Pageable pageable)"));
  assert.ok(sourceIncludes(staffAdministration,
      "findHrManagedAccounts(actorId, departmentId, normalizedQuery, pageable)"));
  assert.ok(sourceIncludes(staffAdministration,
      "departmentHrs.requireForUser(actor.getId()).departmentId()"));
  assert.match(staffAdministration, /STAFF_ACCOUNT_DEPARTMENT_SCOPE_DENIED/);
  assert.match(permissionAdministration, /PERMISSION_TARGET_DEPARTMENT_SCOPE_DENIED/);
  assert.match(permissionAdministration, /target\.getRoles\(\)\.isEmpty\(\) \|\| !lowerRoles\.containsAll/);
  assert.match(teamLeadService, /TEAM_LEAD_ASSIGNMENT_DEPARTMENT_SCOPE_DENIED/);
  assert.ok(sourceIncludes(
      teamLeadService,
      "departmentHrs.requireForUser(actorUserId).departmentId().equals(departmentId)",
  ));
  assert.match(api, /staffAccountPage\(filters:/);
  assert.match(ui, /managedAccountTotalPages/);
});

test("public recovery persistence is committed before secondary audit work", () => {
  assert.doesNotMatch(recovery, /@Transactional\s+public void request/);
  assert.match(recovery, /requestWriter\.createIfAbsent/);
  assert.match(recovery, /catch \(RuntimeException auditFailure\)/);
  assert.match(recoveryWriter,
      /@Transactional\(propagation = Propagation\.REQUIRES_NEW\)\s+public Optional<UUID> createIfAbsent/);
  assert.match(recoveryWriter, /saveAndFlush\(new AccountRecoveryRequest/);
});

test("API calls have a bounded timeout and office time conversion uses the configured IANA zone", () => {
  assert.match(api, /API_REQUEST_TIMEOUT_MS = 20_000/);
  assert.match(api, /controller\.abort\(\)/);
  assert.doesNotMatch(appointments, /OFFICE_OFFSET|05:30/);
  assert.match(appointments, /timeZone: OFFICE_TIME_ZONE/);
  assert.match(appointments, /for \(let pass = 0; pass < 3; pass \+= 1\)/);
});

test("reports and replacement lookup stay bounded for large organizations", () => {
  assert.match(ui, /<ScalableReportsView role=\{props\.role\} refreshKey=\{props\.refreshKey\} \/>/);
  assert.match(ui, /<ReportsOverview role=\{props\.role\} refreshKey=\{props\.refreshKey\} \/>/);
  assert.doesNotMatch(accountClosure, /users\.findAll\(/);
  assert.match(accountClosure, /PageRequest\.of\(0, 100/);
});

