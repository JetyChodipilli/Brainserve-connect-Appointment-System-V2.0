import { readFrontendSource, readApiSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const ui = readFrontendSource();
const api = readApiSource();
const service = readFileSync("../backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java", "utf8");
const emailDispatcher = readFileSync("../backend/src/main/java/com/brainserve/appointment/notification/application/NotificationDispatcher.java", "utf8");
const outbox = readFileSync("../backend/src/main/java/com/brainserve/appointment/notification/domain/OutboxMessage.java", "utf8");
const scheduler = readFileSync("../backend/src/main/java/com/brainserve/appointment/notification/application/NotificationAsyncConfiguration.java", "utf8");
const page = readFileSync("app/page.tsx", "utf8");
const errorBoundary = readFileSync("components/shared/app-error-boundary.tsx", "utf8");
const controller = readFileSync("../backend/src/main/java/com/brainserve/appointment/notification/api/InternalCallNotificationController.java", "utf8");
const migration = readFileSync("../backend/src/main/resources/db/migration/V31__notification_archive_and_deletion_audit.sql", "utf8");

test("notification workspace keeps today operational and exposes a separate archive", () => {
  assert.match(ui, /Today’s conversations/);
  assert.match(ui, /Sent today/);
  assert.match(ui, /Messages move here automatically after the office day ends/);
  assert.match(ui, /Delete old message/);
  assert.match(ui, /className="archive-shortcut"/);
  assert.match(api, /internalNotificationArchive/);
  assert.match(controller, /@GetMapping\("\/archive"\)/);
  assert.match(service, /findInboxForDay/);
});

test("today message deletion is blocked by the backend and every deletion is logged", () => {
  assert.match(service, /TODAY_MESSAGE_DELETE_FORBIDDEN/);
  assert.match(service, /You can delete only your own archived messages/);
  assert.match(service, /ARCHIVED_MESSAGE_DELETED/);
  assert.match(service, /Message snapshot:/);
  assert.match(controller, /@DeleteMapping\("\/\{notificationId\}"\)/);
  assert.match(migration, /deleted_by_user_id/);
  assert.match(migration, /WHERE deleted_at IS NULL/);
});

test("notification sections fail independently and background polling cannot overlap", () => {
  assert.match(ui, /notificationLoadInFlightRef\.current/);
  assert.match(ui, /notificationsLoadedRef\.current/);
  assert.match(ui, /const \[recipientResult\] = await Promise\.allSettled/);
  assert.match(ui, /const \[inboxResult, sentResult, archiveResult\] = await Promise\.allSettled/);
  assert.match(ui, /recipientLoadFailed/);
  assert.match(ui, /Recipient directory is reconnecting/);
  assert.match(ui, /Other message data remains available/);
  assert.match(ui, /Some message data is temporarily stale/);
  assert.match(ui,
      /window\.setInterval\(\(\) => \{[\s\S]*?isWorkspaceUpdateLeader\(\)[\s\S]*?refresh\(\)[\s\S]*?\}, 30000\)/);
});

test("notification delivery does not hold database transactions during network calls", () => {
  assert.match(emailDispatcher, /TransactionOperations transactions/);
  assert.match(emailDispatcher, /private List<ClaimedEmail> claimReady\(\)/);
  assert.match(emailDispatcher, /private void complete\(UUID messageId, String failure\)/);
  assert.doesNotMatch(emailDispatcher, /@Transactional\s+public void dispatch\(\)/);
  assert.match(service, /private List<ClaimedInternalCall> claimReadyForDelivery\(\)/);
  assert.match(service, /private\s+void\s+publishClaimed\s*\(\s*ClaimedInternalCall\s+notification\s*\)/,);assert.doesNotMatch(service, /@Transactional\s+public void dispatchPending\(\)/);
});

test("notification workers recover stale claims and scheduled jobs use a bounded pool", () => {
  assert.match(outbox, /nextAttemptAt = Instant\.now\(\)\.plusSeconds\(300\)/);
  assert.match(emailDispatcher, /OutboxMessage\.Status\.PROCESSING/);
  assert.match(scheduler, /setPoolSize\(4\)/);
  assert.match(scheduler, /setThreadNamePrefix\("brainserve-scheduled-"\)/);
});

test("a render exception offers workspace recovery instead of a blank application", () => {
  assert.match(page, /<AppErrorBoundary>/);
  assert.match(errorBoundary, /getDerivedStateFromError/);
  assert.match(errorBoundary, /Reload workspace/);
  assert.match(errorBoundary, /Your saved records were not changed/);
});

