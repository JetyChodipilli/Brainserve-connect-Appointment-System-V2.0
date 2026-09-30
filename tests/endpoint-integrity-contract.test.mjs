import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

const visitor = read("backend/src/main/java/com/brainserve/appointment/visitor/api/VisitorController.java");
const settings = read("backend/src/main/java/com/brainserve/appointment/configuration/api/SystemConfigurationController.java");
const settingsService = read("backend/src/main/java/com/brainserve/appointment/configuration/application/WorkspaceSettingsService.java");
const hrLifecycle = read("backend/src/main/java/com/brainserve/appointment/iam/application/HrAccountLifecycleService.java");
const hrLifecycleController = read("backend/src/main/java/com/brainserve/appointment/iam/api/HrAccountLifecycleController.java");
const appointments = read("backend/src/main/java/com/brainserve/appointment/appointment/application/AppointmentService.java");
const security = read("backend/src/main/java/com/brainserve/appointment/iam/config/SecurityConfiguration.java");

test("visitor registration and identity verification persist and emit audit refreshes", () => {
  assert.match(visitor, /@PostMapping\("\/public\/visitors"\)\s+@Transactional/);
  assert.match(visitor, /@PostMapping\("\/visitors\/\{id\}\/verify"\)[\s\S]*?@Transactional/);
  assert.ok(visitor.includes('audit.record("VISITOR_REGISTERED"'));
  assert.ok(visitor.includes('audit.record("VISITOR_VERIFY"'));
});

test("system settings controller delegates writes to the transactional policy service", () => {
  assert.ok(settings.includes("WorkspaceSettingsService"));
  assert.ok(settings.includes("settings.update(key, request.value())"));
  assert.equal(settings.includes("SystemSettingRepository"), false);
  assert.match(settingsService, /@Transactional\s+public SystemSetting update/);
});

test("legacy HR deactivation cannot bypass Account lifecycle", () => {
  assert.equal(hrLifecycle.includes("target.disable()"), false);
  assert.equal(hrLifecycleController.includes("/{id}/deactivate"), false);
  assert.match(hrLifecycleController, /@GetMapping List<Response>/);
});

test("all appointment decision paths create audit-backed realtime refreshes", () => {
  for (const event of [
    "APPOINTMENT_REQUESTED",
    "VISITOR_RECEPTION_REGISTERED",
    "APPOINTMENT_CONTACT_VERIFIED",
    "APPOINTMENT_APPROVED",
    "APPOINTMENT_REJECTED",
    "VISITOR_HR_APPROVED",
    "VISITOR_HR_REJECTED",
    "VISITOR_CEO_APPROVED",
    "VISITOR_CEO_REJECTED",
    "APPOINTMENT_CANCELLED",
  ]) assert.ok(appointments.includes(`audit.record("${event}"`), `${event} must be audited`);
});

test("API documentation assets are permitted by the security policy", () => {
  assert.ok(security.includes('default-src \'self\''));
  assert.ok(security.includes("script-src 'self'"));
  assert.ok(security.includes("style-src 'self' 'unsafe-inline'"));
  assert.ok(security.includes("frame-ancestors 'none'"));
});
