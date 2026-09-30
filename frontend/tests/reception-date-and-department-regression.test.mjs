import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const frontend = readFrontendSource();
const service = read("../backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const job = read("../backend/src/main/java/com/brainserve/appointment/appointment/application/PastAppointmentCancellationJob.java");
const repository = read("../backend/src/main/java/com/brainserve/appointment/appointment/infrastructure/AppointmentRepository.java");
const migration = read("../backend/src/main/resources/db/migration/V32__past_appointment_cancellation_index.sql");

test("Reception verification is limited to the current office date", () => {
  assert.ok(frontend.includes('item.status === "Awaiting Reception"'));
  assert.ok(frontend.includes('officeToday(new Date(item.slotStart)) === officeToday()'));
  assert.ok(service.includes("requireCurrentOrFutureVisit(appointment)"));
  assert.ok(service.includes("PAST_VISIT_NOT_ACTIONABLE"));
});

test("past unfinished visits are cancelled at startup and every office midnight", () => {
  assert.ok(job.includes("ApplicationReadyEvent"));
  assert.ok(job.includes("@Scheduled"));
  assert.ok(job.includes("brainserve.appointment.office-zone"));
  assert.ok(service.includes("cancelPastUnfinishedVisits"));
  assert.ok(service.includes("PAST_VISITOR_APPOINTMENT_CANCELLED"));
  assert.ok(repository.includes("findAllBySlotEndLessThanAndStatusIn"));
  assert.ok(migration.includes("idx_appointment_past_cancellation"));
});

test("Reception appointment departments come from the CEO-managed directory", () => {
  assert.ok(frontend.includes("Reception already loads the authoritative department directory"));
  assert.ok(frontend.includes('if (role === "Security")'));
  assert.ok(frontend.includes("departments.filter((department) => department.active)"));
  assert.ok(!frontend.includes("hrDepartmentIds.has(department.id)"));
  assert.match(frontend, /"Checked in",\s*"Cancelled"/);
});

