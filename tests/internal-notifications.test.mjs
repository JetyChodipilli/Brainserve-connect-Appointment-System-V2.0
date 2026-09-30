import assert from "node:assert/strict";
import test from "node:test";

import {
  allowedRecipientAuthorities,
  canSendInternalNotification,
  currentNotificationRecipients,
  readableNotificationRole,
} from "../app/lib/internal-notifications.ts";

test("internal notification role routes match the BrainServe hierarchy", () => {
  assert.deepEqual(allowedRecipientAuthorities("CEO"), ["ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD", "ROLE_RECEPTIONIST"]);
  assert.deepEqual(allowedRecipientAuthorities("HR Admin"), ["ROLE_CEO", "ROLE_TEAM_LEAD", "ROLE_EMPLOYEE", "ROLE_RECEPTIONIST"]);
  assert.deepEqual(allowedRecipientAuthorities("Team Lead"), ["ROLE_HR_ADMIN", "ROLE_RECEPTIONIST"]);
  assert.deepEqual(allowedRecipientAuthorities("Employee"), ["ROLE_HR_ADMIN"]);
  assert.deepEqual(allowedRecipientAuthorities("Reception"), ["ROLE_CEO", "ROLE_MANAGER", "ROLE_HR_ADMIN", "ROLE_TEAM_LEAD"]);
});

test("disallowed cross-role calls stay blocked", () => {
  assert.equal(canSendInternalNotification("CEO", "ROLE_HR_ADMIN"), true);
  assert.equal(canSendInternalNotification("HR Admin", "ROLE_RECEPTIONIST"), true);
  assert.equal(canSendInternalNotification("HR Admin", "ROLE_CEO"), true);
  assert.equal(canSendInternalNotification("Team Lead", "ROLE_HR_ADMIN"), true);
  assert.equal(canSendInternalNotification("Employee", "ROLE_RECEPTIONIST"), false);
  assert.equal(canSendInternalNotification("Reception", "ROLE_HR_ADMIN"), true);
  assert.equal(canSendInternalNotification("Reception", "ROLE_EMPLOYEE"), false);
});

test("notification roles have readable labels", () => {
  assert.equal(readableNotificationRole("ROLE_HR_ADMIN"), "Hr Admin");
  assert.equal(readableNotificationRole("ROLE_RECEPTIONIST"), "Receptionist");
});

test("current account roles replace stale fallbacks and the sender is never selectable", () => {
  const accounts = [
    { userId: "althuf", fullName: "Althuf", email: "althuf@brainserve.in", roles: ["ROLE_MANAGER"] },
    { userId: "jety", fullName: "Jety", email: "jety@brainserve.in", roles: ["ROLE_CEO"] },
  ];
  const oldFallbacks = [
    { userId: "althuf", fullName: "Althuf", email: "althuf@brainserve.in", roles: ["ROLE_CEO"] },
    { userId: "preview-hr", fullName: "Preview HR", email: "hr@brainserve.in", roles: ["ROLE_HR_ADMIN"] },
  ];

  const recipients = currentNotificationRecipients(
    allowedRecipientAuthorities("Manager"), accounts, oldFallbacks, "althuf", "althuf@brainserve.in");

  assert.deepEqual(recipients.map((recipient) => [recipient.email, recipient.roles[0]]), [
    ["jety@brainserve.in", "ROLE_CEO"],
    ["hr@brainserve.in", "ROLE_HR_ADMIN"],
  ]);
});
