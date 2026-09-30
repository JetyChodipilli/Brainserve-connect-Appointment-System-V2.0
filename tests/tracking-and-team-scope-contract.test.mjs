import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const appointmentService = read("backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");

test("legacy department data is normalized before Team Lead employee filtering", () => {
  assert.ok(frontend.includes("const departments = readDemoDepartments()"));
  assert.ok(frontend.includes("departmentId: department.id, department: department.name"));
  assert.ok(frontend.includes("activeTeamLeadAssignments.length === 1"));
  assert.ok(frontend.includes("currentTeamLeadAssignment.teamLeadEmployeeId"));
});

test("check-in by reference persists through the shared appointment updater", () => {
  assert.ok(frontend.includes("item.id !== id && item.referenceNumber !== id"));
  assert.ok(frontend.includes('updateAppointment(normalized, "Checked in")'));
});

test("public tracking refreshes active visits and presents checkout as completed", () => {
  assert.ok(frontend.includes("refreshStatus"));
  assert.ok(frontend.includes("window.setInterval(() => void refreshStatus(), 5000)"));
  assert.ok(frontend.includes("Visit completed"));
  assert.ok(frontend.includes('IN_MEETING: "Checked in"'));
  assert.ok(appointmentService.includes("appointment.checkOut()"));
  assert.ok(appointmentService.includes("appointment.complete()"));
});

