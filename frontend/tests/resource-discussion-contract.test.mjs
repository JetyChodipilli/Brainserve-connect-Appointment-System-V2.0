import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");
const controller = read("../backend/src/main/java/com/brainserve/appointment/resourcediscussion/api/ProjectResourceDiscussionController.java");
const service = read("../backend/src/main/java/com/brainserve/appointment/resourcediscussion/application/ProjectResourceDiscussionService.java");
const listener = read("../backend/src/main/java/com/brainserve/appointment/notification/application/ResourceDiscussionNotificationListener.java");
const migration = read("../backend/src/main/resources/db/migration/V18__project_resource_discussions.sql");
const api = readApiSource();
const app = readFrontendSource();

test("only Team Leads can create structured resource discussions", () => {
  assert.ok(controller.includes("hasRole('TEAM_LEAD')"));
  assert.ok(service.includes("teamLeads.requireForUser"));
  assert.ok(service.includes("HR_RECIPIENT_REQUIRED"));
});

test("HR actions are assigned and state controlled", () => {
  assert.ok(service.includes("RESOURCE_DISCUSSION_ASSIGNED_TO_ANOTHER_HR"));
  assert.ok(controller.includes("/{id}/hr-action"));
  for (const action of ["SCHEDULE", "REQUEST_INFORMATION", "DECLINE"]) {
    assert.ok(service.includes(action));
  }
});

test("Kafka notifications run only after the database transaction commits", () => {
  assert.ok(listener.includes("TransactionPhase.AFTER_COMMIT"));
  assert.ok(listener.includes('@Async("notificationExecutor")'));
  assert.ok(listener.includes("sendResourceDiscussionUpdate"));
});

test("PostgreSQL stores the complete resource request and indexed HR queue", () => {
  for (const field of ["project_name", "required_roles", "requested_headcount", "preferred_at", "hr_response", "scheduled_at"]) {
    assert.ok(migration.includes(field), `missing ${field}`);
  }
  assert.ok(migration.includes("ix_resource_discussion_hr_queue"));
});

test("frontend supports create, HR decision, revision and completion", () => {
  for (const call of ["createResourceDiscussion", "decideResourceDiscussion", "reviseResourceDiscussion", "completeResourceDiscussion"]) {
    assert.ok(api.includes(call), `missing ${call}`);
  }
  assert.ok(app.includes("Discuss project resources with HR"));
  assert.ok(app.includes("HR resource discussion queue"));
  assert.ok(app.includes("Resubmit to HR"));
});

