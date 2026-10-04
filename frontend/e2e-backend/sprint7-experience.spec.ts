import { expect, test, type Page } from "@playwright/test";
import { draftFixture } from "./helpers/draft-fixture";
import type { WorkboardItem } from "../features/workboard/types/workboard";
import type { TaskComment } from "../features/activity/types";

const departmentId = "11111111-1111-4111-8111-111111111111";
const employeeId = "22222222-2222-4222-8222-222222222222";
const taskId = "33333333-3333-4333-8333-333333333333";
const appointmentId = "44444444-4444-4444-8444-444444444444";
const leadId = "55555555-5555-4555-8555-555555555555";
const now = "2026-10-04T04:00:00Z";

async function fixture(page: Page) {
    const profile = { userId: leadId, employeeId, email: "sprint7@example.invalid", roles: ["ROLE_TEAM_LEAD"], permissions: [], forcePasswordChange: false };
    const department = { id: departmentId, code: "ENG", name: "Engineering", active: true, version: 1 };
    const task: WorkboardItem = { id: taskId, departmentId, employeeId, teamLeadUserId: leadId, assignedByUserId: leadId, assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE", title: "Review scoped delivery", description: "Retain the recorded evidence and current scope.", departmentBranch: "Engineering", dueDate: "2099-12-31", status: "ASSIGNED", employeeUpdate: null, teamLeadReview: null, startedAt: null, completedAt: null, approvedAt: null, acknowledgedAt: null, createdAt: now, version: 2, assigneeName: "Scoped Worker", auditStatus: "NOT_AUDITED", auditRecordId: null, auditVersion: null, updatedAt: now, submissionVersion: null, priority: "NORMAL", blocked: false, allowedActions: [], nextActor: "EMPLOYEE", lane: "DELIVERY" };
    const state = { items: [task], comment: null as TaskComment | null, postBodies: [] as Record<string, unknown>[], creations: 0, draftOffline: false, openDenied: false, lateSearch: false };
    const drafts = draftFixture();
    const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, last: true });
    const actor = { id: leadId, name: "Recorded Lead", role: "ROLE_TEAM_LEAD", snapshotRecorded: true };
    const activity = { events: [{ id: "audit:event-1", occurredAt: now, eventType: "WORK_TASK_COMMENT_CREATED", title: "Comment added", actor, cycle: 1, evidenceVersion: 2, departmentId, correlationId: "recorded-correlation", deliveryStatus: "REQUESTED", note: "Recorded <script>window.attack=true</script>" }], page: 0, size: 50, hasMore: false };
    await page.addInitScript(() => { sessionStorage.setItem("brainserve.connect.access-token", "sprint7-access"); sessionStorage.setItem("brainserve.connect.refresh-token", "sprint7-refresh"); });
    await page.route("http://backend.invalid/api/v1/**", async route => {
        const url = new URL(route.request().url()), path = url.pathname.replace("/api/v1", ""), method = route.request().method();
        if (path.startsWith("/drafts/") && state.draftOffline && method === "PUT") return route.fulfill({ status: 503, json: { detail: "Draft service unavailable" } });
        if (await drafts(route, (_form, _context, fields) => { state.creations++; const created = { ...task, id: crypto.randomUUID(), title: fields.title, description: fields.description, employeeId: fields.employeeId, dueDate: fields.dueDate, version: 0 }; state.items.push(created); return { json: created }; })) return;
        if (path === "/auth/me" || path === "/profile/me") return route.fulfill({ json: { ...profile, fullName: "Scoped Lead", departmentId, photoUrl: null } });
        if (path === "/employees") return route.fulfill({ json: paged([{ id: employeeId, employeeNumber: "EMP-7", departmentId, displayName: "Scoped Worker", officialEmail: profile.email, designation: "Engineer", status: "ACTIVE" }]) });
        if (["/departments", "/departments/visible"].includes(path)) return route.fulfill({ json: [department] });
        if (["/appointments", "/admin/staff-accounts"].includes(path)) return route.fulfill({ json: paged([]) });
        if (path === "/work-tasks") return route.fulfill({ json: state.items });
        if (path === "/work-tasks/workspace") return route.fulfill({ json: { departmentId, departmentName: "Engineering", departmentCode: "ENG", eligibleAssignees: [{ employeeId, displayName: "Scoped Worker", designation: "Engineer", role: "EMPLOYEE" }] } });
        if (path === "/workboard/preferences") return route.fulfill({ json: { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] } });
        if (path === "/workboard") return route.fulfill({ json: { policyVersion: "workboard.v1", generatedAt: new Date().toISOString(), officeZone: "Asia/Kolkata", officeDate: "2026-10-04", scope: "DEPARTMENT", departmentId, number: 0, size: 20, totalElements: state.items.length, totalPages: 1, counts: { scopes: { TODAY: state.items.length, CARRY_FORWARD: 0, HISTORY: 0, ALL: state.items.length }, quickFilters: { ALL: state.items.length, MY_ACTIONS: 0, DUE_TODAY: 0, OVERDUE_DELIVERY: 0, AWAITING_MY_REVIEW: 0, RETURNED_FOR_REWORK: 0 } }, laneCounts: { DELIVERY: state.items.length, REVIEW: 0, REWORK: 0, CLOSED: 0 }, items: state.items } });
        if (path.startsWith("/workboard/")) return route.fulfill({ json: { item: task, history: [], historyTruncated: false } });
        if (path === "/search") {
            const query = url.searchParams.get("q");
            if (state.lateSearch) await new Promise(resolve => setTimeout(resolve, 600));
            return route.fulfill({ json: { query, groups: [
                { type: "appointments", label: "Appointments", available: true, coverage: "Current authorized appointments", number: 0, size: 5, totalElements: query === "missing" ? 0 : 1, totalPages: 1, items: query === "missing" ? [] : [{ id: appointmentId, title: "Authorized Visitor", subtitle: "REF-S7", status: "APPROVED" }] },
                { type: "worksheets", label: "Worksheets", available: true, coverage: "Current department worksheets", number: 0, size: 5, totalElements: 1, totalPages: 1, items: [{ id: taskId, title: task.title, subtitle: "Engineering", status: "ASSIGNED" }] },
            ] } });
        }
        if (path.startsWith("/search/")) {
            if (state.openDenied) return route.fulfill({ status: 404, json: { detail: "Record unavailable" } });
            const isTask = path.includes("/worksheets/");
            return route.fulfill({ json: { type: isTask ? "worksheets" : "appointments", id: isTask ? taskId : appointmentId, title: isTask ? task.title : "Authorized Visitor", subtitle: isTask ? "Engineering" : "REF-S7", status: "APPROVED", route: isTask ? "work" : "appointments", detail: { Purpose: "Authorized record detail" } } });
        }
        if (path.endsWith("/activity")) return route.fulfill({ json: activity });
        if (path.includes("/comments")) {
            if (method === "GET") return route.fulfill({ json: { comments: state.comment ? [state.comment] : [], page: 0, size: 50, hasMore: false, canComment: true, participants: [{ id: "worker-user", name: "Scoped Worker" }], evidence: [] } });
            if (method === "POST") { const body = route.request().postDataJSON(); state.postBodies.push(body); state.comment = { id: crypto.randomUUID(), author: actor, body: body.body, mentions: [], attachments: [], version: 0, createdAt: now, editedAt: null, deletedAt: null, canEdit: true }; }
            if (method === "PUT") { const body = route.request().postDataJSON(); expect(body.expectedVersion).toBe(state.comment?.version); state.comment = { ...state.comment!, body: body.body, version: state.comment!.version + 1, editedAt: now }; }
            if (method === "DELETE") state.comment = { ...state.comment!, body: null, deletedAt: now, version: state.comment!.version + 1 };
            return route.fulfill({ json: state.comment });
        }
        if (path === "/realtime/stream") return route.fulfill({ status: 204 });
        if (path.includes("unread")) return route.fulfill({ json: { unreadCount: 0 } });
        return route.fulfill({ json: [] });
    });
    await page.goto("/"); await expect(page.getByLabel("Workspace search", { exact: true })).toBeVisible();
    return { state, drafts };
}
async function workboard(page: Page) {
    const menu = page.getByRole("button", { name: "Open navigation" }); if (await menu.isVisible()) await menu.click();
    await page.getByRole("navigation", { name: "Role workspace" }).getByRole("button", { name: "Work board", exact: true }).click();
    await expect(page.getByRole("button", { name: "Create task sheet", exact: true })).toBeEnabled();
}
async function createForm(page: Page) { await workboard(page); await page.getByRole("button", { name: "Create task sheet", exact: true }).click(); return page.getByRole("dialog", { name: "Create a department worksheet" }); }

for (const width of [360, 768, 1440]) test(`scoped search opens history and a worksheet by keyboard at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 }); await fixture(page);
    const input = page.getByLabel("Workspace search", { exact: true }); await input.fill("Authorized");
    const result = page.getByRole("button", { name: "Open appointment: Authorized Visitor" }); await expect(result).toBeVisible();
    await input.press("ArrowDown"); await expect(result).toBeFocused(); await result.press("Enter");
    const dialog = page.getByRole("dialog", { name: "Authorized Visitor" }); await expect(dialog.getByRole("region", { name: "Visit activity timeline" })).toContainText("Recorded Lead");
    await expect(dialog).toContainText("Recorded <script>window.attack=true</script>"); expect(await page.evaluate(() => Reflect.get(window, "attack"))).toBeUndefined();
    await page.screenshot({ path: `test-results/sprint7-history-${width}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.keyboard.press("Escape"); await expect(input).toBeFocused();
    await input.fill("delivery"); await page.getByRole("button", { name: "Open worksheet: Review scoped delivery" }).click();
    await expect(page.getByRole("dialog", { name: "Review scoped delivery" })).toBeVisible();
    await page.evaluate(() => window.dispatchEvent(new Event("brainserve:auth-session-changed"))); await expect(page.getByRole("dialog")).toHaveCount(0);
});

test("revoked search open removes names and late queries cannot repopulate cleared search", async ({ page }) => {
    const { state } = await fixture(page); const input = page.getByLabel("Workspace search", { exact: true }); await input.fill("Authorized");
    const result = page.getByRole("button", { name: "Open appointment: Authorized Visitor" }); await expect(result).toBeVisible(); state.openDenied = true; await result.click();
    await expect(page.getByRole("region", { name: "Workspace search results" }).getByRole("alert")).toContainText("unavailable in your current workspace"); await expect(result).toHaveCount(0);
    state.lateSearch = true; await input.fill("slow"); await page.waitForRequest(request => request.url().includes("/search?q=slow"));
    await page.getByRole("button", { name: "Clear workspace search" }).click(); await expect(input).toHaveValue(""); await page.waitForTimeout(750); await expect(result).toHaveCount(0);
});

test("participant discussion renders HTML inertly and retains removal feedback", async ({ page }) => {
    const { state } = await fixture(page); await workboard(page); await page.getByRole("button", { name: "View details for Review scoped delivery" }).click();
    await page.getByRole("tab", { name: "Review history" }).click();
    const discussion = page.getByRole("region", { name: "Worksheet comments" }); await discussion.getByLabel("Comment text").fill("<img src=x onerror=window.attack=true> plain discussion");
    await discussion.getByRole("button", { name: "Post comment" }).click(); await expect(discussion).toContainText("<img src=x onerror=window.attack=true> plain discussion");
    expect(state.postBodies).toHaveLength(1); expect(typeof state.postBodies[0].clientRequestId).toBe("string"); expect(await page.evaluate(() => Reflect.get(window, "attack"))).toBeUndefined();
    await discussion.getByRole("button", { name: "Edit comment" }).click(); await discussion.getByLabel("Edit comment text").fill("Reviewed discussion"); await discussion.getByRole("button", { name: "Save edited comment" }).click();
    await expect(discussion).toContainText("Reviewed discussion"); await discussion.getByRole("button", { name: "Remove comment", exact: true }).click(); await discussion.getByRole("button", { name: "Confirm removal" }).click();
    await expect(discussion).toContainText("Original revisions are retained"); await expect(discussion.getByText("Reviewed discussion", { exact: true })).toHaveCount(0);
});

test("server draft restores only on request and conflicting tabs cannot overwrite it", async ({ page }) => {
    const { drafts } = await fixture(page); const dialog = await createForm(page);
    await dialog.getByLabel("Task", { exact: true }).fill("Local safe draft"); await expect(dialog.getByRole("region", { name: "Saved draft" })).toContainText("Draft saved");
    const remote = drafts.drafts.get("TASK_CREATE/new")!; remote.revision++; remote.fields = { ...remote.fields, title: "Changed in another tab" };
    await dialog.getByLabel("Task", { exact: true }).fill("My concurrent text"); await expect(dialog).toContainText("Draft changed in another tab"); await expect(dialog.getByLabel("Task", { exact: true })).toHaveValue("My concurrent text");
    await expect(dialog.getByRole("button", { name: "Create sheet & notify" })).toBeDisabled(); await dialog.getByRole("button", { name: "Check current saved draft" }).click();
    await expect(dialog).toContainText("Saved draft available"); await expect(dialog.getByLabel("Task", { exact: true })).toHaveValue("My concurrent text");
    await dialog.getByRole("button", { name: "Restore draft" }).click(); await expect(dialog.getByLabel("Task", { exact: true })).toHaveValue("Changed in another tab");
});

test("offline draft stays in memory and a lost successful submission is confirmed once", async ({ page }) => {
    const { state, drafts } = await fixture(page); state.draftOffline = true; const dialog = await createForm(page);
    await dialog.getByLabel("Task", { exact: true }).fill("Safe memory only text"); await expect(dialog).toContainText("Unsaved · service unavailable");
    expect(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }))).not.toContain("Safe memory only text");
    state.draftOffline = false; await dialog.getByRole("button", { name: "Reconnect draft service" }).click();
    await dialog.getByLabel("Department assignee").selectOption(employeeId); await dialog.getByLabel("Worksheet instructions").fill("Verify confirmed delivery without duplicate creation");
    drafts.state.loseNextSubmission = true; await dialog.getByRole("button", { name: "Create sheet & notify" }).click(); await expect(dialog).toContainText("Submission needs confirmation");
    await expect(dialog.getByLabel("Task", { exact: true })).toBeDisabled(); await dialog.getByRole("button", { name: "Check current saved draft" }).click(); await expect(dialog).toContainText("Submission confirmed");
    await dialog.getByRole("button", { name: "Continue after confirmed submission" }).click(); await expect(dialog).toHaveCount(0); expect(state.creations).toBe(1);
    await expect(page.getByRole("article", { name: "Safe memory only text" })).toBeVisible();
});
