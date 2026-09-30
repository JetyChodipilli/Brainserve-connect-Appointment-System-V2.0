import { readFrontendSource } from "./frontend-source.mjs";
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const app = readFrontendSource();
const api = readFileSync("app/lib/api.ts", "utf8");
const properties = readFileSync("backend/src/main/resources/application.properties", "utf8");
const errors = readFileSync(
    "backend/src/main/java/com/brainserve/appointment/shared/api/GlobalExceptionHandler.java",
    "utf8",
);
const staffDirectory = readFileSync(
    "backend/src/main/java/com/brainserve/appointment/iam/application/StaffCommunicationDirectoryService.java",
    "utf8",
);
const staffRepository = readFileSync(
    "backend/src/main/java/com/brainserve/appointment/iam/infrastructure/UserAccountRepository.java",
    "utf8",
);
const workTasks = readFileSync(
    "backend/src/main/java/com/brainserve/appointment/worktask/application/DepartmentWorkTaskService.java",
    "utf8",
);
const notifications = readFileSync(
    "backend/src/main/java/com/brainserve/appointment/notification/application/InternalCallNotificationService.java",
    "utf8",
);

test("database connection acquisition fails before the browser deadline", () => {
    assert.match(properties, /connection-timeout=\$\{DB_CONNECTION_TIMEOUT_MS:5000\}/);
    assert.match(properties, /validation-timeout=\$\{DB_VALIDATION_TIMEOUT_MS:3000\}/);
    assert.match(properties, /keepalive-time=\$\{DB_KEEPALIVE_MS:30000\}/);
    assert.match(properties, /data-source-properties\.tcpKeepAlive=true/);
    assert.match(errors, /DATABASE_UNAVAILABLE/);
    assert.match(errors, /HttpStatus\.SERVICE_UNAVAILABLE/);
});

test("directory and work-board reads use bulk database queries", () => {
    assert.match(staffDirectory, /findActiveWithAnyRole\(resolvedRoles, AccountStatus\.ACTIVE\)/);
    assert.match(staffDirectory, /findActiveWithAnyRoleInDepartment\(/);
    assert.match(staffRepository, /where role in :roles/);
    assert.match(workTasks, /employees\.employeeSummaries\(employeeIds\)/);
    assert.match(notifications, /recipientDepartmentScope\(sender, candidates\)/);
    assert.match(notifications, /employees\.employeeSummaries\(employeeIds\)/);
});

test("frontend coalesces duplicate GETs and avoids data-heavy remount storms", () => {
    assert.match(api, /const inFlightGetRequests = new Map/);
    assert.match(api, /method !== "GET" \|\| init\.signal/);
    assert.match(api, /inFlightGetRequests\.get\(path\)/);
    assert.match(app, /const minimumRefreshInterval = 15_000/);
    assert.doesNotMatch(app, /key=\{`work:\$\{workspaceRevision\}`\}/);
    assert.doesNotMatch(app, /key=\{`notifications:\$\{workspaceRevision\}`\}/);
});

