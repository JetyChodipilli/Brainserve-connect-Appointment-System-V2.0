import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const service = readFileSync(
  new URL("../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java", import.meta.url),
  "utf8",
);
const ui = readFrontendSource();

test("HR department scope takes precedence over company-wide operational permissions", () => {
  const hrScope = service.indexOf("if (hrView) return appointments.findByRoutingDepartmentId");
  const teamLeadScope = service.indexOf("if (teamLeadView)");
  const viewAll = service.indexOf("if (viewAll) return appointments.findBySlotStartGreaterThanEqual");

  assert.ok(hrScope >= 0, "HR appointments must be queried by routing department");
  assert.ok(teamLeadScope > hrScope, "Team Lead scope must remain present");
  assert.ok(viewAll > teamLeadScope,
    "company-wide access must be evaluated only after HR and Team Lead department scope");
  assert.match(service, /departmentHrs\.requireForUser\(userId\)\.departmentId\(\)/);
});

test("HR appointment UI rejects rows outside the signed-in HR department", () => {
  assert.match(ui,
    /item\.assignedToCurrentActor !== false\s*&&\s*\(!currentEmployee\?\.departmentId\s*\|\|\s*item\.routingDepartmentId === currentEmployee\.departmentId\)/);
});

