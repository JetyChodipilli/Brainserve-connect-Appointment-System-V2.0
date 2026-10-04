import { draftFixture } from "./helpers/draft-fixture";
import { expect, test } from "@playwright/test";

test("Employee Workboard loads scoped tasks and sends worksheet actions through its feature API", async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    const employeeId = "44444444-4444-4444-8444-444444444444";
    const department = { id: "department-1", code: "ENG", name: "Engineering", active: true, version: 0 };
    const profile = { userId: "employee-user", employeeId, email: "employee@brainserve.in", roles: ["ROLE_EMPLOYEE"],
        permissions: [], forcePasswordChange: false };
    const task = { id: "task-1", departmentId: department.id, employeeId, teamLeadUserId: "team-lead",
        assignedByUserId: "team-lead", assignedByRole: "TEAM_LEAD", assigneeRole: "EMPLOYEE",
        title: "Verify Workboard extraction", description: "Review the feature module and record the delivery evidence.",
        departmentBranch: department.name, dueDate: "2099-12-31", status: "ASSIGNED",
        employeeUpdate: null, teamLeadReview: null, startedAt: null, completedAt: null, approvedAt: null,
        acknowledgedAt: null, createdAt: new Date().toISOString(), version: 0 };
    const actions: Array<{ method: string; body: unknown }> = [];
    const workboardItem = () => ({ ...task, assigneeName: "Employee Reviewer", auditStatus: "NOT_AUDITED", auditRecordId: null, auditVersion: null,
        updatedAt: task.createdAt, submissionVersion: null, priority: null, blocked: null, allowedActions: task.status === "ASSIGNED" ? ["start", "complete"] : ["complete"], nextActor: "Employee", lane: "DELIVERY" });
    const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, last: true });
    await page.addInitScript(() => {
        sessionStorage.setItem("brainserve.connect.access-token", "workboard-access");
        sessionStorage.setItem("brainserve.connect.refresh-token", "workboard-refresh");
    });
    const handleDraft = draftFixture();
    await page.route("http://backend.invalid/api/v1/**", async (route) => {
        const endpoint = new URL(route.request().url()).pathname.replace("/api/v1", "");
        if (await handleDraft(route, (_form, _context, fields) => { actions.push({ method: "POST", body: { note: fields.note, expectedVersion: Number(fields.taskVersion) } }); task.status = "IN_PROGRESS"; return { json: task }; })) return;
        if (endpoint === "/auth/me") return route.fulfill({ json: profile });
        if (endpoint === "/profile/me") return route.fulfill({ json: { ...profile, fullName: "Employee Reviewer", departmentId: department.id, photoUrl: null } });
        if (endpoint === "/employees") return route.fulfill({ json: paged([{ id: employeeId, employeeNumber: "EMP-001", departmentId: department.id,
            displayName: "Employee Reviewer", officialEmail: profile.email, designation: "Engineer", status: "ACTIVE" }]) });
        if (endpoint === "/departments" || endpoint === "/departments/visible") return route.fulfill({ json: [department] });
        if (endpoint === "/appointments" || endpoint === "/admin/staff-accounts") return route.fulfill({ json: paged([]) });
        if (endpoint === "/work-tasks") return route.fulfill({ json: [task, { ...task, id: "other-task", employeeId: "other-employee", title: "Other employee worksheet" }] });
        if (endpoint === "/workboard/preferences") return route.fulfill({ json: { revision: 0, layout: "LIST", density: "COMPACT", savedFilters: [] } });
        if (endpoint === "/workboard/task-1") return route.fulfill({ json: { item: workboardItem(), history: [], historyTruncated: false } });
        if (endpoint === "/workboard") return route.fulfill({ json: { policyVersion: "workboard.v1", generatedAt: new Date().toISOString(), officeZone: "Asia/Kolkata", officeDate: "2026-10-03", scope: "OWN", departmentId: null,
            number: 0, size: 20, totalElements: 1, totalPages: 1, counts: { scopes: { TODAY: 1, CARRY_FORWARD: 0, HISTORY: 0, ALL: 1 }, quickFilters: { ALL: 1, MY_ACTIONS: 1, DUE_TODAY: 0, OVERDUE_DELIVERY: 0, AWAITING_MY_REVIEW: 0, RETURNED_FOR_REWORK: 0 } }, laneCounts: { DELIVERY: 1, REVIEW: 0, REWORK: 0, CLOSED: 0 }, items: [workboardItem()] } });
        if (endpoint === "/work-tasks/task-1/start") {
            actions.push({ method: route.request().method(), body: route.request().postDataJSON() });
            task.status = "IN_PROGRESS";
            return route.fulfill({ json: task });
        }
        if (endpoint === "/dashboard/summary") return route.fulfill({ json: { awaitingApproval: 0, activeVisits: 0, totalEmployees: 1, activeEmployees: 1, scope: "DEPARTMENT" } });
        if (endpoint === "/realtime/stream") return route.fulfill({ status: 204 });
        if (endpoint.includes("unread")) return route.fulfill({ json: { unreadCount: 0 } });
        return route.fulfill({ json: [] });
    });

    await page.goto("/");
    await page.getByRole("navigation", { name: "Role workspace" }).getByRole("button", { name: "Work board", exact: true }).click();
    const worksheet = page.getByRole("article", { name: task.title });
    await expect(worksheet).toBeVisible();
    await expect(page.getByRole("heading", { name: "Other employee worksheet" })).toHaveCount(0);
    await worksheet.getByRole("button", { name: `View details for ${task.title}`, exact: true }).click();
    await page.getByRole("dialog", { name: task.title }).getByRole("button", { name: "Start", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Start this worksheet" });
    await dialog.getByLabel("Starting note").fill("Checking the isolated feature module");
    await dialog.getByRole("button", { name: "Start work", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(page.getByRole("dialog", { name: task.title }).getByText("In progress", { exact: true }).first()).toBeVisible();
    expect(actions).toEqual([{ method: "POST", body: { note: "Checking the isolated feature module", expectedVersion: 0 } }]);
    expect(errors).toEqual([]);
});
