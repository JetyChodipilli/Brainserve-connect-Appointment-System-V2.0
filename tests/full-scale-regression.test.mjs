import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(path, "utf8");
const api = read("app/lib/api.ts");
const appointments = read("app/lib/appointments.ts");
const notifications = read(
    "backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
);
const insights = read(
    "backend/src/main/java/com/brainserve/appointment/workinsight/application/WorkInsightService.java",
);
const history = read(
    "backend/src/main/java/com/brainserve/appointment/reporting/application/RoleAwareHistoryQueryService.java",
);

test("large administrative lists use bounded database-backed pages", () => {
  assert.match(api, /employeePage\(filters/);
  assert.match(api, /auditEvents\(filters/);
  assert.match(api, /essentialLogs\(filters/);
  assert.match(api, /accountLifecycleAccountPage\(filters/);
  assert.match(api, /archivedAccountPage\(filters/);
  assert.doesNotMatch(api, /employees\(departmentId\?[\s\S]{0,250}allSpringPageContent/);
  assert.doesNotMatch(api, /auditEvents\(filters[\s\S]{0,500}allSpringPageContent/);
  assert.doesNotMatch(api, /essentialLogs\(filters[\s\S]{0,500}allSpringPageContent/);
});

test("office-day behavior is configurable across frontend and backend services", () => {
  assert.match(appointments, /NEXT_PUBLIC_OFFICE_TIME_ZONE/);
  assert.match(notifications, /brainserve\.appointment\.office-zone/);
  assert.match(notifications, /ZonedDateTime\.now\(officeZone\)/);
  assert.match(insights, /brainserve\.appointment\.office-zone/);
  assert.match(insights, /LocalDate\.now\(officeZone\)/);
  assert.ok(sourceIncludes(history, 'addValue("officeZone", officeZone, Types.VARCHAR)'));
  assert.match(history, /AT TIME ZONE :officeZone/);
  assert.doesNotMatch(notifications, /static final ZoneId OFFICE_ZONE/);
  assert.doesNotMatch(insights, /static final ZoneId OFFICE_ZONE/);
  assert.doesNotMatch(history, /AT TIME ZONE 'Asia\/Kolkata'/);
});
