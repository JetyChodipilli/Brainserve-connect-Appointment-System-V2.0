import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("role department changes are persisted with one pending request per requester", () => {
  const migration = read("../backend/src/main/resources/db/migration/V26__role_department_change_requests.sql");
  assert.match(migration, /CREATE TABLE role_department_change_request/);
  assert.match(migration, /requester_role IN \('HR_ADMIN', 'TEAM_LEAD'\)/);
  assert.match(migration, /WHERE status = 'PENDING'/);
  assert.match(migration, /target_occupant_user_id uuid REFERENCES iam_user_account/);
});

test("request and decision endpoints are role locked", () => {
  const controller = read("../backend/src/main/java/com/brainserve/appointment/rolechange/api/RoleDepartmentChangeController.java");
  assert.match(controller, /hasAnyRole\('HR_ADMIN','TEAM_LEAD'\)/);
  assert.match(controller, /hasAnyRole\('CEO','HR_ADMIN'\)/);
  assert.match(controller, /@PostMapping\("\/\{id\}\/approve"\)/);
  assert.match(controller, /@PostMapping\("\/\{id\}\/reject"\)/);
  assert.match(controller, /@PostMapping\("\/\{id\}\/cancel"\)/);
});

test("HR changes route to CEO and Team Lead changes route to destination HR", () => {
  const service = read("../backend/src/main/java/com/brainserve/appointment/rolechange/application/RoleDepartmentChangeService.java");
  const notifications = read("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
  assert.match(service, /Only CEO can approve HR department changes/);
  assert.match(service, /Only the HR Admin assigned to the destination department/);
  assert.match(notifications, /broadcast\(sender, Set\.of\(CEO\)/);
  assert.match(notifications, /departmentHrs\.requireForDepartment\(targetDepartmentId\)/);
  assert.match(notifications, /MessageCategory\.ACTION_REQUIRED/);
});

test("occupied departments require an explicit replace or swap action", () => {
  const service = read("../backend/src/main/java/com/brainserve/appointment/rolechange/application/RoleDepartmentChangeService.java");
  const hrAssignments = read("../backend/src/main/java/com/brainserve/appointment/departmenthr/application/DepartmentHrAssignmentService.java");
  const leadAssignments = read("../backend/src/main/java/com/brainserve/appointment/teamlead/application/TeamLeadAssignmentService.java");
  assert.match(service, /Choose replace or swap/);
  assert.match(hrAssignments, /Resolution\.SWAP/);
  assert.match(hrAssignments, /Resolution\.REPLACE/);
  assert.match(leadAssignments, /Resolution\.SWAP/);
  assert.match(leadAssignments, /identities\.demote\(target\.getTeamLeadUserId\(\)\)/);
});

test("profile request and roles responsibilities ledger use the live API", () => {
  const app = readFrontendSource();
  const api = readApiSource();
  assert.match(app, /Request a department change/);
  assert.match(app, /ROLE ASSIGNMENT LEDGER/);
  assert.match(app, /Swap both department assignments/);
  assert.match(app, /Replace current role owner/);
  assert.match(api, /requestRoleDepartmentChange/);
  assert.match(api, /pendingRoleDepartmentChanges/);
  assert.match(api, /approveRoleDepartmentChange/);
});

