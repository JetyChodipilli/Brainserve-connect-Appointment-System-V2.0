import { draftFixture } from "./helpers/draft-fixture";
import { expect, test, type Page, type Route } from "@playwright/test";
import type { WorkboardItem, WorkboardPreferences, WorkPlanning } from "../features/workboard/types/workboard";

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
    const planning: WorkPlanning = { taskId: 'task-000', taskVersion: 2, priority: 'HIGH', originalDueDate: '2099-12-31', originalDueDateKnown: false, dueDate: '2099-12-31', estimateMinutes: 60, evidenceRequired: true, checklist: [{ id: 'requirement-1', title: 'Verify reconciliation', position: 0, required: true, completed: false }], blockers: [], evidence: [], submissions: [{ version: 1, submittedAt: '2026-10-01T00:00:00Z', acceptedAt: '2026-10-02T00:00:00Z', acceptedByRole: 'TEAM_LEAD', checklist: [{ id: 'requirement-old', title: 'Original accepted requirement', position: 0, required: true, completed: true }], evidence: [{ id: 'frozen-evidence', documentId: 'private-doc', filename: 'accepted.pdf', contentType: 'application/pdf', sizeBytes: 42, sha256: 'a'.repeat(64), createdAt: '2026-10-01T00:00:00Z' }] }], contactOptions: [{ id: 'lead-user', name: 'Assigned Lead' }], permissions: { manage: role !== 'EMPLOYEE', progress: role === 'EMPLOYEE', blocker: true, resolveBlocker: role !== 'EMPLOYEE', upload: role === 'EMPLOYEE' } };
    let uploads = 0;

    const profile = { userId: "employee-user", employeeId, email: "employee@brainserve.in", roles: [`ROLE_${role}`], permissions: [], forcePasswordChange: false };
    const state = { profile, items: Array.from({ length: 43 }, (_, index) => task(index)), reads: [] as URLSearchParams[], actions: [] as { endpoint: string; body: Record<string, unknown> }[],
        preferences: { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] } as WorkboardPreferences, preferenceWrites: [] as Record<string, unknown>[], prefConflict: false,
        planning, uploads: () => uploads, uploadFailure: 'SCANNER_UNAVAILABLE', planningConflict: false, planningDenied: false, planningStatus: 200, downloadStatus: 200, mutationStatus: 200, conflict: false, denyPage: false, workspaceStatus: 200, detailStatus: 200, deferredPage: null as Route | null, deferredDetail: null as Route | null, lateDetailId: "", lateQuery: "", serial: 0 };
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
        if (endpoint.startsWith('/work-tasks/task-000/')) {
            if (state.planningStatus !== 200) return route.fulfill({ status: state.planningStatus, json: { detail: 'Task scope removed' } });
            if (endpoint.endsWith('/download') && state.downloadStatus !== 200) return route.fulfill({ status: state.downloadStatus, json: { detail: 'Evidence scope removed' } });
            if (method !== 'GET' && state.mutationStatus !== 200) return route.fulfill({ status: state.mutationStatus, json: { detail: 'Task scope removed' } });
            if (state.planningDenied) return route.fulfill({ status: 403, json: { detail: 'Planning permission removed' } });
            if (endpoint.endsWith('/download')) return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.7 synthetic private evidence' });
            if (method !== 'GET') {
                if (endpoint.endsWith('/evidence') && method === 'POST') { uploads++; if (state.uploadFailure) return route.fulfill({ status: 503, json: { errorCode: state.uploadFailure, detail: state.uploadFailure === 'MALWARE_DETECTED' ? 'File rejected by scanner' : 'File scanner unavailable. Retry explicitly.' } }); }
                if (state.planningConflict) return route.fulfill({ status: 409, json: { errorCode: 'WORK_TASK_VERSION_CONFLICT', detail: 'Planning changed. Reload before retry.' } });
                const body = endpoint.endsWith('/evidence') ? {} : route.request().postDataJSON(); planning.taskVersion++;
                if (endpoint.endsWith('/planning')) { Object.assign(planning, { priority: body.priority, dueDate: body.dueDate, checklist: body.checklist.map((item: Record<string, unknown>, position: number) => ({ ...item, position, completed: false })) }); }
                if (endpoint.endsWith('/checklist')) planning.checklist.forEach(item => item.completed = body.completedIds.includes(item.id));
                if (endpoint.endsWith('/blockers')) planning.blockers.push({ id: 'blocker-1', reason: body.reason, contactUserId: body.contactUserId || 'lead-user', raisedAt: new Date().toISOString(), resolvedAt: null, raisedBy: 'employee-user', resolvedBy: null });
                if (endpoint.endsWith('/resolve')) planning.blockers[0].resolvedAt = new Date().toISOString();
                if (endpoint.endsWith('/contact')) planning.blockers[0].contactUserId = body.contactUserId;
                if (endpoint.endsWith('/evidence')) planning.evidence.push({ id: 'draft-evidence', documentId: 'draft-doc', filename: 'synthetic.pdf', contentType: 'application/pdf', sizeBytes: 42, sha256: 'b'.repeat(64), createdAt: new Date().toISOString() });
                if (method === 'DELETE') planning.evidence = [];
            }
            return route.fulfill({ json: planning });
        }
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

async function planningTab(page: Page) {
    await page.getByRole('button', { name: 'View details for Worksheet 000' }).click();
    await drawer(page).getByRole('tab', { name: 'Planning & evidence' }).click();
    await expect(drawer(page).getByText('Recorded deadline at Sprint 6 rollout')).toBeVisible();
}
for (const width of [360, 768, 1440]) test(`planning evidence keyboard and responsive at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 }); await fixture(page); await planningTab(page);
    await drawer(page).getByLabel('Verify reconciliation').check();
    await drawer(page).getByRole('button', { name: 'Save checklist progress' }).click();
    await expect(drawer(page).getByRole('heading', { name: 'Checklist · 1/1' })).toBeVisible();
    await drawer(page).getByText('Version 1 · original author not recorded · Historical acceptance retained', { exact: true }).click();
    await expect(drawer(page).getByText('Complete · Original accepted requirement (required)', { exact: true })).toBeVisible();
    const download = page.waitForEvent('download'); await drawer(page).getByRole('button', { name: 'Download accepted.pdf' }).click(); expect((await download).suggestedFilename()).toBe('accepted.pdf');
    await page.screenshot({ path: `test-results/sprint6-planning-${width}.png` }); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await drawer(page).getByRole('tab', { name: 'Planning & evidence' }).focus(); await page.keyboard.press('Home'); await expect(drawer(page).getByRole('tab', { name: 'Overview' })).toBeFocused();
});
test('scanner failures retain file and explicit retry, frozen files survive removal', async ({ page }) => {
    const { state } = await fixture(page); await planningTab(page);
    await drawer(page).getByLabel('Evidence file (JPEG, PNG or PDF)').setInputFiles({ name: 'synthetic.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.7 synthetic') });
    await drawer(page).getByRole('button', { name: 'Scan & upload evidence' }).click(); await expect(drawer(page).getByRole('alert')).toContainText('scanner unavailable'); expect(state.uploads()).toBe(1);
    await expect(drawer(page).getByText('Selected: synthetic.pdf · not uploaded')).toBeVisible();
    state.uploadFailure = 'MALWARE_DETECTED'; await drawer(page).getByRole('button', { name: 'Scan & upload evidence' }).click(); await expect(drawer(page).getByRole('alert')).toContainText('rejected by scanner'); expect(state.uploads()).toBe(2);
    state.uploadFailure = ''; await drawer(page).getByRole('button', { name: 'Scan & upload evidence' }).click(); await expect(drawer(page).getByRole('heading', { name: 'Draft evidence · 1/20' })).toBeVisible();
    await drawer(page).getByRole('button', { name: 'Remove draft synthetic.pdf' }).click(); await expect(drawer(page).getByRole('heading', { name: 'Draft evidence · 0/20' })).toBeVisible(); expect(state.planning.submissions[0].evidence).toHaveLength(1);
});
test('requirements conflict retains notes and immutable accepted snapshot', async ({ page }) => {
    const { state } = await fixture(page, 'TEAM_LEAD'); await planningTab(page); await drawer(page).getByRole('button', { name: 'Edit requirements & deadline' }).click();
    const form = page.getByRole('dialog', { name: 'Requirements & deadline' }); await form.getByRole('combobox', { name: 'Priority', exact: true }).selectOption('URGENT'); await form.getByRole('textbox', { name: 'Reason', exact: true }).fill('Explicit commitment change'); state.planningConflict = true;
    await form.getByRole('button', { name: 'Save planning change' }).click(); await expect(form.getByRole('alert')).toContainText('Planning changed'); await expect(form.getByRole('textbox', { name: 'Reason', exact: true })).toHaveValue('Explicit commitment change');
    state.planningConflict = false; state.planning.taskVersion = 7; await form.getByRole('button', { name: 'Reload planning and retain notes' }).click(); await expect(form.getByRole('textbox', { name: 'Reason', exact: true })).toHaveValue('Explicit commitment change');
    await form.getByRole('button', { name: 'Save planning change' }).click(); await expect(form).toHaveCount(0); expect(state.planning.priority).toBe('URGENT'); expect(state.planning.taskVersion).toBe(8); expect(state.planning.submissions[0].checklist[0].title).toBe('Original accepted requirement');
});
test('blockers preserve deadline and denied reload removes controls; close retains notes', async ({ page }) => {
    const { state } = await fixture(page, 'TEAM_LEAD'); await planningTab(page); await drawer(page).getByRole('button', { name: 'Raise blocker' }).click(); const form = page.getByRole('dialog', { name: 'Raise blocker', exact: true });
    await form.getByRole('textbox', { name: 'Reason', exact: true }).fill('Awaiting signed specification'); await form.getByRole('button', { name: 'Close planning form' }).click(); await page.getByRole('button', { name: 'Keep editing planning' }).click(); await expect(form.getByRole('textbox', { name: 'Reason', exact: true })).toHaveValue('Awaiting signed specification'); await form.getByRole('button', { name: 'Save planning change' }).click();
    await expect(drawer(page).getByText('Awaiting signed specification', { exact: true })).toBeVisible(); expect(state.planning.dueDate).toBe('2099-12-31'); state.planningDenied = true; await drawer(page).getByRole('button', { name: 'Reload planning' }).click(); await expect(drawer(page).getByRole('alert')).toContainText('permission removed'); await expect(drawer(page).getByRole('button', { name: 'Resolve blocker' })).toHaveCount(0);
});

for (const status of [403, 404]) test(`denied evidence download ${status} clears retained content and selected file`, async ({ page }) => {
    const { state } = await fixture(page); await planningTab(page);
    await drawer(page).getByText('Version 1 · original author not recorded · Historical acceptance retained', { exact: true }).click();
    await drawer(page).getByLabel('Evidence file (JPEG, PNG or PDF)').setInputFiles({ name: 'private-selected.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF synthetic') });
    state.downloadStatus = status;
    await drawer(page).getByRole('button', { name: 'Download accepted.pdf' }).click();
    await expect(drawer(page).getByRole('alert')).toContainText('scope removed');
    await expect(drawer(page).getByText('accepted.pdf', { exact: true })).toHaveCount(0);
    await expect(drawer(page).getByText('Selected: private-selected.pdf · not uploaded')).toHaveCount(0);
    await expect(drawer(page).getByRole('button', { name: 'Scan & upload evidence' })).toHaveCount(0);
});
for (const operation of ['mutation', 'reload']) test(`scope denial during requirements ${operation} clears form and notes`, async ({ page }) => {
    const { state } = await fixture(page, 'TEAM_LEAD'); await planningTab(page);
    await drawer(page).getByRole('button', { name: 'Edit requirements & deadline' }).click();
    const form = page.getByRole('dialog', { name: 'Requirements & deadline' });
    await form.getByRole('textbox', { name: 'Reason', exact: true }).fill('Private unsaved commitment reason');
    if (operation === 'mutation') { state.mutationStatus = 404; await form.getByRole('button', { name: 'Save planning change' }).click(); }
    else { state.planningConflict = true; await form.getByRole('button', { name: 'Save planning change' }).click(); await expect(form.getByRole('alert')).toContainText('Planning changed'); state.planningStatus = 404; await form.getByRole('button', { name: 'Reload planning and retain notes' }).click(); }
    await expect(form).toHaveCount(0); await expect(drawer(page).getByRole('alert')).toContainText('scope removed');
    await expect(drawer(page).getByRole('button', { name: 'Edit requirements & deadline' })).toHaveCount(0);
    state.mutationStatus = 200; state.planningStatus = 200; state.planningConflict = false;
    await drawer(page).getByRole('button', { name: 'Reload planning' }).click();
    await drawer(page).getByRole('button', { name: 'Edit requirements & deadline' }).click();
    await expect(form.getByRole('textbox', { name: 'Reason', exact: true })).toHaveValue('');
});
