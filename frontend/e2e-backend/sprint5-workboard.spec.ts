import { draftFixture } from "./helpers/draft-fixture";
import { expect, test, type Page, type Route } from "@playwright/test";
import type { WorkboardItem, WorkboardPreferences } from "../features/workboard/types/workboard";

const department = { id: "department-1", code: "ENG", name: "Engineering", active: true, version: 0 };
const employeeId = "44444444-4444-4444-8444-444444444444";
function task(index: number, patch: Partial<WorkboardItem> = {}): WorkboardItem {
    return { id: `task-${String(index).padStart(3, "0")}`, departmentId: department.id, employeeId, teamLeadUserId: "lead-user", assignedByUserId: "lead-user", assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE",
        title: `Worksheet ${String(index).padStart(3, "0")}`, description: "Complete this full instruction and preserve the evidence. ".repeat(12), departmentBranch: department.name, dueDate: "2099-12-31", status: "ASSIGNED",
        employeeUpdate: null, teamLeadReview: null, startedAt: null, completedAt: null, approvedAt: null, acknowledgedAt: null, createdAt: new Date().toISOString(), version: 2,
        assigneeName: "Scoped Employee", auditStatus: "NOT_AUDITED", auditRecordId: null, auditVersion: null, updatedAt: new Date().toISOString(), submissionVersion: null, priority: null, blocked: null,
        allowedActions: ["start", "complete"], nextActor: "EMPLOYEE", lane: "DELIVERY", ...patch };
}
async function fixture(page: Page, role = "EMPLOYEE") {
    const profile = { userId: "employee-user", employeeId, email: "employee@brainserve.in", roles: [`ROLE_${role}`], permissions: [], forcePasswordChange: false };
    const state = { profile, items: Array.from({ length: 43 }, (_, index) => task(index)), reads: [] as URLSearchParams[], actions: [] as { endpoint: string; body: Record<string, unknown> }[],
        preferences: { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] } as WorkboardPreferences, preferenceWrites: [] as Record<string, unknown>[], prefConflict: false,
        conflict: false, denyPage: false, workspaceStatus: 200, detailStatus: 200, deferredPage: null as Route | null, deferredDetail: null as Route | null, lateDetailId: "", lateQuery: "", serial: 0 };
    await page.addInitScript(() => { sessionStorage.setItem("brainserve.connect.access-token", "sprint5-access"); sessionStorage.setItem("brainserve.connect.refresh-token", "sprint5-refresh"); });
    const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, last: true });
    const pageData = (params: URLSearchParams) => {
        const filtered = state.items.filter((item) => (!params.get("query") || item.title.toLowerCase().includes(params.get("query")!.toLowerCase()))
            && (!params.get("branch") || item.departmentBranch === params.get("branch")) && (params.get("status") === "ALL" || item.status === params.get("status")));
        const scope = params.get("scope"), period = scope === "HISTORY" || scope === "CARRY_FORWARD" ? [] : filtered;
        const action = params.get("quickFilter"), items = action === "AWAITING_MY_REVIEW" ? period.filter((item) => item.allowedActions.some((value) => ["approve", "hr-audit", "insight-rework"].includes(value))) : action === "RETURNED_FOR_REWORK" ? period.filter((item) => item.lane === "REWORK") : period;
        const sorted = [...items].sort((a, b) => params.get("sort") === "TITLE" ? b.title.localeCompare(a.title) : a.id.localeCompare(b.id));
        const number = Number(params.get("page")), size = Number(params.get("size"));
        return { policyVersion: "workboard.v1", generatedAt: new Date(Date.now() + ++state.serial).toISOString(), officeZone: "Asia/Kolkata", officeDate: "2026-10-03", scope: role === "EMPLOYEE" ? "OWN" : "DEPARTMENT", departmentId: role === "EMPLOYEE" ? null : department.id,
            number, size, totalElements: items.length, totalPages: Math.ceil(items.length / size), counts: { scopes: { TODAY: filtered.length, CARRY_FORWARD: 0, HISTORY: 0, ALL: filtered.length }, quickFilters: { ALL: period.length, MY_ACTIONS: period.filter((item) => item.allowedActions.length).length, DUE_TODAY: 0, OVERDUE_DELIVERY: 0, AWAITING_MY_REVIEW: period.filter((item) => item.allowedActions.includes("approve")).length, RETURNED_FOR_REWORK: period.filter((item) => item.lane === "REWORK").length } },
            laneCounts: Object.fromEntries(["DELIVERY", "REVIEW", "REWORK", "CLOSED"].map((lane) => [lane, items.filter((item) => item.lane === lane).length])), items: sorted.slice(number * size, (number + 1) * size) };
    };
    const handleDraft = draftFixture();
    await page.route("http://backend.invalid/api/v1/**", async (route) => {
        const url = new URL(route.request().url()), endpoint = url.pathname.replace("/api/v1", ""), method = route.request().method();
        if (await handleDraft(route, (form, context, fields) => {
            if (form === "TASK_CREATE") {
                const body = { ...fields }; state.actions.push({ endpoint: "/work-tasks", body });
                const created = task(43, { title: body.title, description: body.description, dueDate: body.dueDate, employeeId: body.employeeId, version: 0 });
                state.items.push(created); return { json: created };
            }
            const [id, action] = context.split("~");
            if (action === "insight-rework" || action === "hr-rework" || action === "revise-rework" && state.profile.roles.includes("ROLE_TEAM_LEAD")) {
                const endpoint = `/work-insights/tasks/${id}/${action === "insight-rework" ? "assign-rework" : action === "hr-rework" ? "request-rework" : "revise-rework"}`;
                const body = { [action === "insight-rework" ? "guidance" : action === "hr-rework" ? "reason" : "update"]: fields.note, expectedTaskVersion: Number(fields.taskVersion) };
                state.actions.push({ endpoint, body }); const item = state.items.find(value => value.id === id)!; item.version++; item.allowedActions = []; item.auditStatus = "PENDING_MANAGER_APPROVAL"; return { json: {} };
            }
            const body = { note: fields.note, expectedVersion: Number(fields.taskVersion) };
            state.actions.push({ endpoint: `/work-tasks/${id}/${action}`, body });
            const item = state.items.find(value => value.id === id)!;
            if (state.conflict) { item.version++; item.status = "IN_PROGRESS"; item.allowedActions = ["complete"]; return { status: 409, json: { errorCode: "WORK_TASK_VERSION_CONFLICT", detail: "This worksheet changed. Reload before retrying." } }; }
            item.version++; item.status = action === "complete" || action === "revise-rework" ? "COMPLETED" : action === "approve" ? "APPROVED" : action === "request-changes" ? "CHANGES_REQUESTED" : "IN_PROGRESS";
            item.allowedActions = action === "start" ? ["complete"] : []; return { json: item };
        })) return;
        if (endpoint === "/auth/me") return route.fulfill({ json: state.profile });
        if (endpoint === "/profile/me") return route.fulfill({ json: { ...state.profile, fullName: "Scoped Employee", departmentId: department.id, photoUrl: null } });
        if (endpoint === "/employees") return route.fulfill({ json: paged([{ id: employeeId, employeeNumber: "EMP-001", departmentId: department.id, displayName: "Scoped Employee", officialEmail: profile.email, designation: "Engineer", status: "ACTIVE" }]) });
        if (endpoint === "/departments" || endpoint === "/departments/visible") return route.fulfill({ json: [department] });
        if (endpoint === "/work-tasks/workspace") {
            if (state.workspaceStatus !== 200) return route.fulfill({ status: state.workspaceStatus, json: { detail: "CREATE denied" } });
            return route.fulfill({ json: { departmentId: department.id, departmentName: department.name, departmentCode: department.code, eligibleAssignees: [{ employeeId, displayName: "Scoped Employee", designation: "Engineer", role: "EMPLOYEE" }] } });
        }
        if (endpoint === "/appointments" || endpoint === "/admin/staff-accounts") return route.fulfill({ json: paged([]) });
        if (endpoint === "/workboard/preferences") {
            if (method === "PUT") {
                const body = route.request().postDataJSON(); state.preferenceWrites.push(body);
                if (state.prefConflict || body.expectedRevision !== state.preferences.revision) return route.fulfill({ status: 409, json: { detail: "Stale preference revision" } });
                state.preferences = { revision: state.preferences.revision + 1, layout: body.layout, density: body.density, savedFilters: body.savedFilters };
            }
            return route.fulfill({ json: state.preferences });
        }
        if (endpoint === "/workboard") {
            state.reads.push(url.searchParams);
            if (state.denyPage) return route.fulfill({ status: 403, json: { detail: "Scope removed" } });
            if (state.lateQuery && url.searchParams.get("query") === state.lateQuery) { state.deferredPage = route; return; }
            return route.fulfill({ json: pageData(url.searchParams) });
        }
        if (endpoint.startsWith("/workboard/task-")) {
            const id = endpoint.split("/").at(-1)!;
            if (state.detailStatus !== 200) return route.fulfill({ status: state.detailStatus, json: { detail: "Unavailable" } });
            if (id === state.lateDetailId) { state.deferredDetail = route; return; }
            return route.fulfill({ json: { item: state.items.find((item) => item.id === id), history: [{ id: "stored-event", title: "Worksheet assigned", occurredAt: "2026-10-01T00:00:00Z", actorRole: "TEAM_LEAD", note: "Actual recorded milestone" }], historyTruncated: true } });
        }
        if (method === "POST" && endpoint.startsWith("/work-tasks/task-")) {
            state.actions.push({ endpoint, body: route.request().postDataJSON() });
            const item = state.items.find((value) => value.id === endpoint.split("/")[2])!;
            if (state.conflict) { item.version++; item.status = "IN_PROGRESS"; item.allowedActions = ["complete"]; return route.fulfill({ status: 409, json: { detail: "This worksheet changed. Reload before retrying." } }); }
            const action = endpoint.split("/").at(-1);
            item.version++; item.status = action === "complete" || action === "revise-rework" ? "COMPLETED" : action === "approve" ? "APPROVED" : action === "request-changes" ? "CHANGES_REQUESTED" : action === "acknowledge" ? "ACKNOWLEDGED" : "IN_PROGRESS";
            item.allowedActions = action === "start" ? ["complete"] : []; return route.fulfill({ json: item });
        }
        if (method === "POST" && endpoint.startsWith("/work-insights/tasks/task-")) {
            state.actions.push({ endpoint, body: route.request().postDataJSON() });
            const item = state.items.find((value) => value.id === endpoint.split("/")[3])!;
            item.version++; item.allowedActions = []; item.auditStatus = "PENDING_MANAGER_APPROVAL"; return route.fulfill({ json: {} });
        }
        if (method === "POST" && endpoint === "/work-tasks") {
            const body = route.request().postDataJSON(); state.actions.push({ endpoint, body });
            const created = task(43, { title: body.title, description: body.description, dueDate: body.dueDate, employeeId: body.employeeId, version: 0 });
            state.items.push(created); return route.fulfill({ json: created });
        }
        if (endpoint === "/dashboard/summary") return route.fulfill({ json: { awaitingApproval: 0, activeVisits: 0, totalEmployees: 1, activeEmployees: 1, scope: "DEPARTMENT", departmentId: department.id } });
        if (endpoint === "/realtime/stream") return route.fulfill({ status: 204 });
        if (endpoint.includes("unread")) return route.fulfill({ json: { unreadCount: 0 } });
        return route.fulfill({ json: [] });
    });
    await page.goto("/"); await openWorkboard(page); return { state, pageData };
}
async function openWorkboard(page: Page) {
    await expect(page.getByRole("navigation", { name: "Role workspace" })).toBeAttached();
    const menu = page.getByRole("button", { name: "Open navigation" }); if (await menu.isVisible()) await menu.click();
    await page.getByRole("navigation", { name: "Role workspace" }).getByRole("button", { name: "Work board", exact: true }).click();
    await expect(page.getByRole("heading", { name: "43 matching worksheets" })).toBeVisible();
}
const drawer = (page: Page) => page.getByRole("dialog").filter({ has: page.getByRole("button", { name: "Close worksheet details" }) });

for (const width of [360, 768, 1440]) test(`list and native detail drawer reflow, focus and restore at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 }); await fixture(page);
    const results = page.getByRole("region", { name: "Worksheet results" }); await expect(results.getByRole("article")).toHaveCount(20);
    await expect(page.getByRole("button", { name: "List", exact: true })).toHaveAttribute("aria-pressed", "true");
    await page.screenshot({ path: `test-results/sprint5-list-${width}.png`, fullPage: true });
    await page.screenshot({ path: `test-results/sprint5-list-viewport-${width}.png`, fullPage: false });
    const trigger = results.getByRole("button", { name: "View details for Worksheet 000" }); await trigger.click();
    await expect(drawer(page).getByText("Not recorded for this worksheet")).toBeVisible();
    await expect(drawer(page).getByRole("button", { name: "Close worksheet details" })).toBeFocused();
    await drawer(page).getByRole("tab", { name: "Overview" }).focus(); await page.keyboard.press("ArrowRight"); await expect(drawer(page).getByRole("tab", { name: "Updates" })).toBeFocused();
    await page.keyboard.press("ArrowRight"); await expect(drawer(page).getByText("Actual recorded milestone")).toBeVisible(); await expect(drawer(page).getByText("Showing the latest 200 retained events.")).toBeVisible();
    await drawer(page).getByRole("button", { name: "Complete", exact: true }).focus(); await page.keyboard.press("Tab"); await expect(drawer(page).getByRole("button", { name: "Close worksheet details" })).toBeFocused();
    await drawer(page).getByRole("tab", { name: "Overview" }).click(); await page.screenshot({ path: `test-results/sprint5-drawer-${width}.png`, fullPage: false });
    const body = drawer(page).getByRole("tabpanel"); const scroll = await body.evaluate((element) => { element.scrollTop = 120; return element.scrollTop; });
    const detailRefresh = page.waitForResponse((response) => response.url().endsWith("/workboard/task-000"));
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await detailRefresh;
    expect(await body.evaluate((element) => element.scrollTop)).toBe(scroll);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 360) expect((await drawer(page).boundingBox())!.width).toBe(360);
    await page.keyboard.press("Escape"); await expect(drawer(page)).toHaveCount(0); await expect(trigger).toBeFocused(); expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe("hidden");
});

test("server pagination, combined filters, exact zero and list/board equivalence survive durable owner preferences and CAS", async ({ page }) => {
    const { state } = await fixture(page);
    const results = page.getByRole("region", { name: "Worksheet results" });
    await page.getByRole("button", { name: "Next page", exact: true }).click(); await expect(results.getByRole("article").first()).toHaveAccessibleName("Worksheet 020");
    const ids = await results.getByRole("article").evaluateAll((nodes) => nodes.map((node) => node.getAttribute("aria-labelledby")));
    await page.getByRole("button", { name: "Board", exact: true }).click(); await expect(page.getByRole("button", { name: "Board", exact: true })).toHaveAttribute("aria-pressed", "true");
    expect(await results.getByRole("article").evaluateAll((nodes) => nodes.map((node) => node.getAttribute("aria-labelledby")))).toEqual(ids);
    await expect(page.getByRole("region", { name: "Delivery stage", exact: true }).getByRole("heading", { name: /Delivery\s*43/ })).toBeVisible();
    await page.getByRole("combobox", { name: "Density", exact: true }).selectOption("COMFORTABLE"); await expect(page.locator(".density-comfortable")).toBeVisible();
    await page.getByLabel("Search worksheets", { exact: true }).fill("Worksheet 042"); await expect(page.getByRole("heading", { name: "1 matching worksheets" })).toBeVisible();
    await page.getByLabel("Department / branch filter").fill("Engineering"); await page.getByRole("combobox", { name: "Worksheet status", exact: true }).selectOption("ASSIGNED"); await page.getByRole("combobox", { name: "Sort worksheets" }).selectOption("TITLE");
    await page.locator(".workboard-saved-options > summary").click();
    await page.getByLabel("Filter name").fill("Scoped delivery"); await page.getByRole("button", { name: "Save current filters" }).click(); await expect(page.getByRole("button", { name: "Delete saved filter Scoped delivery" })).toBeVisible();
    expect(state.reads.at(-1)?.get("page")).toBe("0"); expect(state.reads.at(-1)?.get("query")).toBe("Worksheet 042"); expect(state.reads.at(-1)?.get("branch")).toBe("Engineering");
    expect(state.preferenceWrites.at(-1)).toMatchObject({ expectedRevision: 2, layout: "BOARD", density: "COMFORTABLE" }); expect(JSON.stringify(state.preferences)).not.toContain("employeeId");
    await page.reload(); await openWorkboard(page); await expect(page.getByRole("button", { name: "Board", exact: true })).toHaveAttribute("aria-pressed", "true"); await expect(page.locator(".density-comfortable")).toBeVisible();
    await page.locator(".workboard-saved-options > summary").click(); await page.getByRole("combobox", { name: "Saved filters", exact: true }).selectOption(state.preferences.savedFilters[0].id); await expect(page.getByLabel("Search worksheets", { exact: true })).toHaveValue("Worksheet 042");
    await page.getByLabel("Search worksheets", { exact: true }).fill("no matches"); await expect(page.getByRole("heading", { name: "0 matching worksheets" })).toBeVisible(); await expect(page.getByRole("button", { name: /My actions\s*0/, exact: true })).toBeVisible();
    state.prefConflict = true; await page.getByRole("button", { name: "List", exact: true }).click(); await expect(page.getByText("Preferences changed in another window.", { exact: false })).toBeVisible(); await expect(page.getByRole("button", { name: "Board", exact: true })).toHaveAttribute("aria-pressed", "true");
    state.prefConflict = false; state.preferences = { ...state.preferences, revision: state.preferences.revision + 1 }; await page.getByRole("button", { name: "Reload preferences" }).click(); await page.getByRole("button", { name: "List", exact: true }).click(); await expect(page.getByRole("button", { name: "List", exact: true })).toHaveAttribute("aria-pressed", "true");
    state.profile.email = "second@brainserve.in"; state.preferences = { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] }; await page.reload(); await openWorkboard(page); await expect(page.locator(".density-compact")).toBeVisible(); await expect(page.getByRole("button", { name: "Delete saved filter Scoped delivery" })).toHaveCount(0);
});

test("nested action focus, unsaved warning and observed version conflict preserve notes and disable obsolete action", async ({ page }) => {
    const { state } = await fixture(page); await page.getByRole("button", { name: "View details for Worksheet 000" }).click();
    const start = drawer(page).getByRole("button", { name: "Start", exact: true }); await start.click(); const action = page.getByRole("dialog", { name: "Start this worksheet" });
    await expect(action.getByLabel("Starting note")).toBeFocused(); await action.getByLabel("Starting note").fill("Keep this draft evidence");
    const dismissed = page.waitForEvent("dialog").then((dialog) => dialog.dismiss()); await page.keyboard.press("Escape"); await dismissed; await expect(action).toBeVisible(); await expect(action.getByLabel("Starting note")).toHaveValue("Keep this draft evidence");
    state.conflict = true; await action.getByRole("button", { name: "Start work", exact: true }).click(); await expect(action.getByRole("button", { name: "Reload current worksheet" })).toBeVisible();
    expect(state.actions[0].body).toEqual({ note: "Keep this draft evidence", expectedVersion: 2 }); await action.getByRole("button", { name: "Reload current worksheet" }).click();
    await expect(action.getByLabel("Starting note")).toHaveValue("Keep this draft evidence"); await expect(action.getByRole("button", { name: "Start work", exact: true })).toBeDisabled();
    const accepted = page.waitForEvent("dialog").then((dialog) => dialog.accept()); await action.getByRole("button", { name: "Cancel", exact: true }).click(); await accepted; await expect(action).toHaveCount(0);
    await expect(drawer(page).getByRole("button", { name: "Complete", exact: true })).toBeVisible();
    await drawer(page).getByRole("button", { name: "Complete", exact: true }).click(); const complete = page.getByRole("dialog", { name: "Submit completed work" });
    await complete.getByRole("button", { name: "Cancel", exact: true }).click(); await expect(drawer(page).getByRole("button", { name: "Complete", exact: true })).toBeFocused();
});

test("late queries/details and current-session/403 teardown clear scoped content and stacked scroll locks", async ({ page }) => {
    const { state, pageData } = await fixture(page); state.lateQuery = "late";
    await page.getByLabel("Search worksheets", { exact: true }).fill("late"); await expect.poll(() => Boolean(state.deferredPage)).toBe(true);
    await page.getByLabel("Search worksheets", { exact: true }).fill("Worksheet 042"); await expect(page.getByRole("heading", { name: "1 matching worksheets" })).toBeVisible();
    await state.deferredPage!.fulfill({ json: pageData(new URLSearchParams({ scope: "TODAY", quickFilter: "ALL", query: "", status: "ALL", branch: "", sort: "DUE_DATE", page: "0", size: "20" })) }).catch(() => {});
    await expect(page.getByRole("heading", { name: "1 matching worksheets" })).toBeVisible(); await expect(page.getByRole("article", { name: "Worksheet 000", exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Clear filters" }).click(); state.lateDetailId = "task-000"; await page.getByRole("button", { name: "View details for Worksheet 000" }).click(); await expect.poll(() => Boolean(state.deferredDetail)).toBe(true);
    await drawer(page).getByRole("button", { name: "Close worksheet details" }).click(); await page.getByRole("button", { name: "View details for Worksheet 001" }).click(); await expect(drawer(page).getByRole("heading", { name: "Worksheet 001", exact: true })).toBeVisible();
    await state.deferredDetail!.fulfill({ json: { item: state.items[0], history: [], historyTruncated: false } }).catch(() => {}); await expect(drawer(page).getByRole("heading", { name: "Worksheet 001", exact: true })).toBeVisible();
    await drawer(page).getByRole("button", { name: "Start", exact: true }).click(); await page.getByRole("dialog", { name: "Start this worksheet" }).getByLabel("Starting note").fill("Prior scoped note"); state.denyPage = true;
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await expect(page.getByRole("dialog")).toHaveCount(0); await expect(page.getByRole("article", { name: "Worksheet 001", exact: true })).toHaveCount(0);
    expect(await page.evaluate(() => getComputedStyle(document.body).overflow)).not.toBe("hidden"); await expect(page.getByText("Your current account no longer has access to this work scope.")).toBeVisible();
    state.denyPage = false; await page.evaluate(() => window.dispatchEvent(new Event("brainserve:auth-session-changed"))); await expect(page.getByRole("heading", { name: "43 matching worksheets" })).toBeVisible();
    state.lateDetailId = ""; await page.getByRole("button", { name: "View details for Worksheet 002" }).click(); await expect(drawer(page).getByRole("heading", { name: "Worksheet 002", exact: true })).toBeVisible();
    state.detailStatus = 404; await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await expect(page.getByRole("dialog")).toHaveCount(0); await expect(page.getByText("This worksheet is no longer available in your current scope.")).toBeVisible();
});

test("Team Lead retains create form warnings, visitor approvals and never receives self-approval", async ({ page }) => {
    const { state } = await fixture(page, "TEAM_LEAD"); state.items[0] = task(0, { assigneeRole: "TEAM_LEAD", status: "COMPLETED", submissionVersion: 4, nextActor: "HR_ADMIN", allowedActions: [], lane: "REVIEW" });
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await page.getByRole("button", { name: "View details for Worksheet 000" }).click();
    await expect(drawer(page).getByText("Version 4", { exact: true })).toBeVisible(); await expect(drawer(page).getByRole("button", { name: "Approve delivery" })).toHaveCount(0); await expect(drawer(page).getByText("HR Admin", { exact: true })).toBeVisible(); await page.keyboard.press("Escape");
    await expect(page.getByRole("heading", { name: "Department visitor approvals" })).toBeVisible(); await page.getByRole("button", { name: "Create task sheet", exact: true }).click(); const create = page.getByRole("dialog", { name: "Create a department worksheet" });
    await create.getByLabel("Task", { exact: true }).fill("Unsaved task"); const dismiss = page.waitForEvent("dialog").then((dialog) => dialog.dismiss()); await page.keyboard.press("Escape"); await dismiss; await expect(create).toBeVisible();
    const accept = page.waitForEvent("dialog").then((dialog) => dialog.accept()); await create.getByRole("button", { name: "Cancel", exact: true }).click(); await accept; await expect(create).toHaveCount(0); await expect(page.getByRole("button", { name: "Create task sheet", exact: true })).toBeFocused();
});

test("READ and review remain available after CREATE permission is denied, with old create scope cleared", async ({ page }) => {
    const { state } = await fixture(page, "TEAM_LEAD"); state.workspaceStatus = 403;
    state.items[0] = task(0, { status: "COMPLETED", allowedActions: ["approve", "request-changes"], lane: "REVIEW" });
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await expect(page.getByRole("button", { name: "Create task sheet", exact: true })).toBeDisabled();
    await expect(page.getByRole("heading", { name: "43 matching worksheets" })).toBeVisible(); await page.getByRole("button", { name: "View details for Worksheet 000" }).click();
    await expect(drawer(page).getByRole("button", { name: "Approve delivery", exact: true })).toBeVisible();
});

const journeys = [
    { role: "EMPLOYEE", action: "complete", button: "Complete", dialog: "Submit completed work", field: "Completion update", submit: "Submit for review", endpoint: "/work-tasks/task-000/complete", body: { note: "Recorded corrected delivery", expectedVersion: 2 } },
    { role: "EMPLOYEE", action: "acknowledge", button: "Acknowledge", endpoint: "/work-tasks/task-000/acknowledge", body: { expectedVersion: 2 } },
    { role: "EMPLOYEE", action: "revise-rework", button: "Update & resubmit", dialog: "Update the rework submission", field: "Corrected completion update", submit: "Update & resubmit", endpoint: "/work-tasks/task-000/revise-rework", body: { note: "Recorded corrected delivery", expectedVersion: 2 } },
    { role: "TEAM_LEAD", action: "approve", button: "Approve delivery", dialog: "Approve employee delivery", field: "Approval note", submit: "Approve worksheet", endpoint: "/work-tasks/task-000/approve", body: { note: "Recorded corrected delivery", expectedVersion: 2 } },
    { role: "TEAM_LEAD", action: "request-changes", button: "Request changes", dialog: "Return for employee changes", field: "Required changes", submit: "Send changes", endpoint: "/work-tasks/task-000/request-changes", body: { note: "Recorded corrected delivery", expectedVersion: 2 } },
    { role: "TEAM_LEAD", action: "insight-rework", button: "Create rework plan", dialog: "Create an Insights rework plan", field: "Rework guidance", submit: "Assign rework", endpoint: "/work-insights/tasks/task-000/assign-rework", body: { guidance: "Recorded corrected delivery", expectedTaskVersion: 2 } },
    { role: "TEAM_LEAD", action: "revise-rework", button: "Update & resubmit", dialog: "Update the rework submission", field: "Corrected completion update", submit: "Update & resubmit", endpoint: "/work-insights/tasks/task-000/revise-rework", body: { update: "Recorded corrected delivery", expectedTaskVersion: 2 } },
    { role: "HR_ADMIN", action: "hr-rework", button: "Return for rework", dialog: "Return worksheet for rework", field: "HR audit findings", submit: "Return to Team Lead", endpoint: "/work-insights/tasks/task-000/request-rework", body: { reason: "Recorded corrected delivery", expectedTaskVersion: 2 } },
    { role: "HR_ADMIN", action: "hr-audit", button: "Audit & send to Manager", endpoint: "/work-insights/tasks/task-000/audit", body: { expectedTaskVersion: 2 } },
] as const;
for (const journey of journeys) test(`${journey.role} ${journey.action} keeps its existing endpoint and observed task version`, async ({ page }) => {
    const { state } = await fixture(page, journey.role);
    state.items[0] = task(0, { status: journey.action === "complete" ? "ASSIGNED" : journey.action === "insight-rework" ? "INSIGHT_REWORK_REQUESTED" : journey.action === "acknowledge" || journey.role === "HR_ADMIN" ? "APPROVED" : "COMPLETED",
        assigneeRole: journey.role === "TEAM_LEAD" && journey.action === "revise-rework" ? "TEAM_LEAD" : "EMPLOYEE", allowedActions: [journey.action], lane: "REVIEW", teamLeadReview: journey.action === "revise-rework" ? "Correct the result" : null });
    await page.evaluate(() => document.dispatchEvent(new Event("visibilitychange"))); await page.getByRole("button", { name: "View details for Worksheet 000" }).click();
    await drawer(page).getByRole("button", { name: journey.button, exact: true }).click();
    if ("dialog" in journey) { const action = page.getByRole("dialog", { name: journey.dialog }); await action.getByLabel(journey.field).fill("Recorded corrected delivery"); await action.getByRole("button", { name: journey.submit, exact: true }).click(); await expect(action).toHaveCount(0); }
    await expect.poll(() => state.actions.length).toBe(1); expect(state.actions[0]).toEqual({ endpoint: journey.endpoint, body: journey.body });
    await expect(drawer(page).getByRole("button", { name: journey.button, exact: true })).toHaveCount(0);
});

test("create form sends the existing department assignment payload and reloads authoritative counts", async ({ page }) => {
    const { state } = await fixture(page, "HR_ADMIN"); await page.getByRole("button", { name: "Create task sheet", exact: true }).click();
    const create = page.getByRole("dialog", { name: "Create a department worksheet" }); await create.getByRole("combobox", { name: "Department assignee" }).selectOption(employeeId);
    await create.getByLabel("Task", { exact: true }).fill("New department worksheet"); await create.getByLabel("Worksheet instructions").fill("Concrete department work and acceptance criteria"); await create.getByLabel("Due date", { exact: true }).fill("2099-12-31");
    await create.getByRole("button", { name: "Create sheet & notify", exact: true }).click(); await expect(create).toHaveCount(0); await expect(page.getByRole("heading", { name: "44 matching worksheets" })).toBeVisible();
    expect(state.actions[0]).toEqual({ endpoint: "/work-tasks", body: { employeeId, title: "New department worksheet", description: "Concrete department work and acceptance criteria", dueDate: "2099-12-31" } });
});

test("compact list quick action opens the existing note dialog and reloads authoritative detail", async ({ page }) => {
    const { state } = await fixture(page); await page.getByRole("article", { name: "Worksheet 000", exact: true }).getByRole("button", { name: "Start Worksheet 000", exact: true }).click();
    const action = page.getByRole("dialog", { name: "Start this worksheet" }); await expect(action.getByLabel("Starting note")).toBeFocused();
    await action.getByLabel("Starting note").fill("Starting from the compact queue"); await action.getByRole("button", { name: "Start work", exact: true }).click(); await expect(action).toHaveCount(0);
    expect(state.actions[0]).toEqual({ endpoint: "/work-tasks/task-000/start", body: { note: "Starting from the compact queue", expectedVersion: 2 } });
    await expect(drawer(page).getByText("In progress", { exact: true }).first()).toBeVisible();
});
