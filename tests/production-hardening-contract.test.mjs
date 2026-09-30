import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";

const read = (path) => fs.readFileSync(path, "utf8");

test("authenticated requests reject stale roles and temporary-password workspace access", () => {
  const filter = read("backend/src/main/java/com/brainserve/appointment/iam/config/ActiveAccountFilter.java");
  assert.match(filter, /currentAuthorities\.equals\(tokenAuthorities\)/);
  assert.match(filter, /ACCOUNT_AUTHORITY_CHANGED/);
  assert.match(filter, /isForcePasswordChange\(\)/);
  assert.match(filter, /PASSWORD_CHANGE_REQUIRED/);
});

test("CEO visits notify the assigned Manager while leave workflow stays department scoped", () => {
  const notifications = read("backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
  const leave = read("backend/src/main/java/com/brainserve/appointment/employee/application/LeaveRequestService.java");
  assert.match(notifications, /"CEO_VISIT"\.equals\(appointmentType\)/);
  assert.match(notifications, /managers\.requireForDepartment\(routingDepartmentId\)/);
  assert.match(notifications, /departmentHrs\.requireForDepartment\(departmentId\)/);
  assert.match(leave, /departmentHrs\.requireAssignedReviewer\(employeeDepartmentId, actor\)/);
  assert.match(leave, /findPendingForDepartment/);
});

test("browser refresh restores the BrainServe Connect session and forces password replacement", () => {
  const api = read("app/lib/api.ts");
  const frontend = readFrontendSource();
  assert.match(api, /window\.sessionStorage\.setItem\(REFRESH_TOKEN_KEY, refresh\)/);
  assert.match(api, /export function hasAuthSession/);
  assert.match(frontend, /function ForcedPasswordChange/);
  assert.match(frontend, /Restoring your session/);
  assert.match(frontend, /tokens\.forcePasswordChange \|\| profile\.forcePasswordChange/);
});

test("public product branding is consistently BrainServe Connect", () => {
  const layout = read("app/layout.tsx");
  const frontend = readFrontendSource();
  const apiDocs = read("backend/src/main/java/com/brainserve/appointment/shared/config/OpenApiConfiguration.java");
  assert.match(layout, /BrainServe Connect/);
  assert.match(frontend, /productName = "BrainServe Connect"/);
  assert.match(apiDocs, /BrainServe Connect API/);
});

