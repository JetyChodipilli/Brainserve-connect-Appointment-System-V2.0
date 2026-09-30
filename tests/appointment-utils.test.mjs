import assert from "node:assert/strict";
import test from "node:test";

import {
  appointmentTypeCode,
  appointmentDates,
  fallbackSlots,
  hostCategoryForVisit,
  hostCategoriesForVisit,
  nextBusinessDays,
  officeDateTimeToIso,
  officeYearMonth,
} from "../app/lib/appointments.ts";

test("booking dates advance from a weekend to the next business day", () => {
  assert.deepEqual(nextBusinessDays(3, new Date("2026-07-11T04:00:00Z")), [
    "2026-07-13", "2026-07-14", "2026-07-15",
  ]);
});

test("emergency booking includes today even on a weekend", () => {
  assert.deepEqual(appointmentDates(3, true, new Date("2026-07-12T06:30:00Z")), [
    "2026-07-12", "2026-07-13", "2026-07-14",
  ]);
  assert.deepEqual(appointmentDates(2, false, new Date("2026-07-12T06:30:00Z")), [
    "2026-07-13", "2026-07-14",
  ]);
});

test("office date and time are converted using India Standard Time", () => {
  assert.equal(officeDateTimeToIso("2026-07-13", "09:30"), "2026-07-13T04:00:00.000Z");
  assert.equal(officeYearMonth("2026-06-30T19:00:00.000Z"), "2026-07");
});

test("fallback availability excludes slots that have already passed", () => {
  const slots = fallbackSlots("2026-07-13", new Date("2026-07-13T06:00:00Z"));
  assert.ok(slots.length > 0);
  assert.ok(slots.every((slot) => new Date(slot.start) > new Date("2026-07-13T06:10:00Z")));
  assert.ok(slots.every((slot) => new Date(slot.end).getTime() - new Date(slot.start).getTime() === 30 * 60 * 1000));
});

test("frontend visit labels map to backend appointment types", () => {
  assert.equal(appointmentTypeCode("CEO visit"), "CEO_VISIT");
  assert.equal(appointmentTypeCode("Interview"), "INTERVIEW");
  assert.equal(appointmentTypeCode("Employee meeting"), "EMPLOYEE_VISIT");
  assert.equal(appointmentTypeCode("Emergency visit"), "EMERGENCY");
});

test("visit types select only matching host categories", () => {
  assert.equal(hostCategoryForVisit("CEO visit"), "CEO");
  assert.equal(hostCategoryForVisit("HR visit"), "HR");
  assert.equal(hostCategoryForVisit("Interview"), "HR");
  assert.equal(hostCategoryForVisit("Employee visit"), "HR");
  assert.equal(hostCategoryForVisit("Client meeting"), "TEAM_LEAD");
  assert.equal(hostCategoryForVisit("Emergency visit"), null);
  assert.deepEqual(hostCategoriesForVisit("Emergency visit"), ["CEO", "HR"]);
});
