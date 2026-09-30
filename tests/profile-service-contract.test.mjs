import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const controller = read("backend/src/main/java/com/brainserve/appointment/iam/api/MyProfileController.java");
const service = read("backend/src/main/java/com/brainserve/appointment/iam/application/MyProfileService.java");
const documents = read("backend/src/main/java/com/brainserve/appointment/document/application/DocumentService.java");
const roles = read("backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java");
const api = read("app/lib/api.ts");
const app = readFrontendSource();
const styles = read("app/globals.css");

test("every authenticated role receives a private profile endpoint", () => {
  assert.ok(controller.includes('@RequestMapping("/api/v1/profile")'));
  assert.ok(controller.includes('@PreAuthorize("isAuthenticated()")'));
  assert.ok(controller.includes('@GetMapping("/me")'));
  assert.ok(service.includes("staff.requireActive(userId)"));
  assert.ok(service.includes("employees.employeeSummary(account.employeeId())"));
  assert.ok(service.includes("organization.findDepartment(departmentId)"));
});

test("profile photo upload is image-only, private and short-lived", () => {
  assert.ok(controller.includes('consumes = MediaType.MULTIPART_FORM_DATA_VALUE'));
  assert.ok(documents.includes('upload("ACCOUNT", userId, "PROFILE_PHOTO", file)'));
  assert.ok(documents.includes('Set.of("image/jpeg", "image/png")'));
  assert.ok(documents.includes('responseContentDisposition("inline; filename='));
  assert.ok(documents.includes("signatureDuration(Duration.ofMinutes(urlMinutes))"));
});

test("the profile popover exposes My profile to all eight roles without a duplicate navigation item", () => {
  assert.ok(api.includes("export type MyProfile"));
  assert.ok(api.includes("uploadMyProfilePhoto(file: File)"));
  assert.ok(api.includes('body.append("file", file)'));
  assert.equal((app.match(/"profile"\]/g) ?? []).length, 8);
  assert.ok(app.includes('<strong>My profile</strong>'));
  assert.ok(!app.includes('{ id: "profile", label: "My profile"'));
  assert.ok(app.includes('aria-label="Profile menu"'));
  assert.ok(app.includes("Private profile storage"));
});

test("the profile popover uses the BrainServe glass theme instead of a black panel", () => {
  assert.match(styles, /\.profile-popover \{[^}]*linear-gradient[^}]*backdrop-filter: blur/s);
  assert.ok(!styles.includes("background: #2a1117"));
});

test("the complete role menu remains reachable in short viewports", () => {
  assert.match(styles, /\.sidebar \{[^}]*height: 100vh;[^}]*overflow-y: auto;/s,);
  assert.match(styles, /@supports \(height: 100dvh\) \{[\s\S]*?\.sidebar \{[^}]*height: 100dvh;[^}]*\}/s,);
  assert.match(styles, /\.sidebar \{[^}]*overscroll-behavior-y: contain;/s);
  assert.match(styles, /\.sidebar nav \{[^}]*flex-shrink: 0;/s);
  assert.match(styles, /\.sidebar-bottom \{[^}]*flex-shrink: 0;/s);
  assert.ok(styles.includes(".sidebar::-webkit-scrollbar-thumb"));
});

test("Reception can send internal calls after receiving leadership messages", () => {
  const receptionPermissions = roles.match(
      /ROLE_RECEPTIONIST\(EnumSet\.of\(([\s\S]*?)\)\),/,
  )?.[1] ?? "";
  assert.ok(receptionPermissions.includes("INTERNAL_NOTIFICATION_READ"));
  assert.ok(receptionPermissions.includes("INTERNAL_NOTIFICATION_SEND"));
  assert.ok(app.includes("Acknowledged. Reception will coordinate this."));
  assert.ok(!app.includes("Receptionist</strong><ChevronRight size={14} />Receive only"));
});

