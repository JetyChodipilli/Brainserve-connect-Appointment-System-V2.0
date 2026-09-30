import { readFrontendSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { sourceIncludes } from "./source-contract-utils.mjs";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const employeeController = read("../backend/src/main/java/com/brainserve/appointment/employee/api/EmployeeController.java");
const employeeService = read("../backend/src/main/java/com/brainserve/appointment/employee/application/EmployeeService.java");
const notification = read("../backend/src/main/java/com/brainserve/appointment/notification/domain/InternalCallNotification.java");
const notificationService = read("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java");
const notificationController = read("../backend/src/main/java/com/brainserve/appointment/notification/api/InternalCallNotificationController.java");
const staffDirectory = read("../backend/src/main/java/com/brainserve/appointment/iam/application/StaffCommunicationDirectoryService.java");
const migration = read("../backend/src/main/resources/db/migration/V25__prioritized_internal_messages.sql");
const app = readFrontendSource();
const css = read("app/globals.css");

test("CEO can register or move their own profile to any active department", () => {
  assert.ok(employeeController.includes('@PutMapping("/me/executive-profile")'));
  assert.ok(employeeController.includes("hasRole('CEO')"));
  assert.ok(employeeService.includes("upsertExecutiveProfile"));
  assert.ok(sourceIncludes(employeeService, "appointmentHosts.linkEmployee(actorUserId, employee.getId())"));
  assert.ok(sourceIncludes(employeeService, "employee.transferDepartment(department.id())"));
  assert.ok(app.includes("MY EXECUTIVE DEPARTMENT"));
  assert.ok(app.includes("Create and register this as my CEO department"));
});

test("HR and Team Lead receive a full-width single-department workspace", () => {
  assert.ok(app.includes('org-grid-focused'));
  assert.ok(app.includes('visibleDepartments.length === 1'));
  assert.ok(css.includes('.org-grid-focused { grid-template-columns: minmax(0, 1fr); }'));
});

test("internal delivery persists priority, purpose and a stable conversation key", () => {
  assert.ok(notification.includes("enum MessagePriority { NORMAL, HIGH, URGENT }"));
  assert.ok(notification.includes("enum MessageCategory { GENERAL, ACTION_REQUIRED, VISITOR, WORK, INSIGHT, LEAVE }"));
  assert.ok(notificationController.includes("request.priority()"));
  assert.ok(notificationController.includes("conversationKey"));
  assert.ok(notificationService.includes("priorityRank"));
  assert.ok(migration.includes("ix_internal_call_priority_inbox"));
  assert.ok(migration.includes("ix_internal_call_conversation"));
});

test("internal delivery UI separates priority inbox, conversations and sent history", () => {
  assert.ok(app.includes("Priority inbox"));
  assert.ok(app.includes("Conversations"));
  assert.ok(app.includes("Urgent messages are placed first"));
  assert.ok(app.includes("Visitor coordination"));
  assert.ok(css.includes(".conversation-layout"));
  assert.ok(css.includes(".priority-badge-urgent"));
});

test("internal delivery resolves current participant roles instead of stale message snapshots", () => {
  assert.ok(staffDirectory.includes("findByUserIds"));
  assert.ok(staffDirectory.includes("users.findAllById(userIds)"));
  assert.ok(notificationController.includes("Set<String> senderRoles"));
  assert.ok(notificationController.includes("Set<String> recipientRoles"));
  assert.ok(notificationController.includes("currentMembers.get(value.getSenderUserId())"));
  assert.ok(app.includes("senderRoles: sender ? [sender.role]"));
  assert.ok(app.includes("participantRoles.map(readableNotificationRole)"));
  assert.ok(app.includes("brainserve:demo-accounts-updated"));
});

