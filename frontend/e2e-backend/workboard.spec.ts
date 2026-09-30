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
    const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, last: true });
    await page.addInitScript(() => {
        sessionStorage.setItem("brainserve.connect.access-token", "workboard-access");
        sessionStorage.setItem("brainserve.connect.refresh-token", "workboard-refresh");
    });
    await page.route("http://backend.invalid/api/v1/**", async (route) => {
        const endpoint = new URL(route.request().url()).pathname.replace("/api/v1", "");
        if (endpoint === "/auth/me") return route.fulfill({ json: profile });
        if (endpoint === "/profile/me") return route.fulfill({ json: { ...profile, fullName: "Employee Reviewer", departmentId: department.id, photoUrl: null } });
        if (endpoint === "/employees") return route.fulfill({ json: paged([{ id: employeeId, employeeNumber: "EMP-001", departmentId: department.id,
            displayName: "Employee Reviewer", officialEmail: profile.email, designation: "Engineer", status: "ACTIVE" }]) });
        if (endpoint === "/departments" || endpoint === "/departments/visible") return route.fulfill({ json: [department] });
        if (endpoint === "/appointments" || endpoint === "/admin/staff-accounts") return route.fulfill({ json: paged([]) });
        if (endpoint === "/work-tasks") return route.fulfill({ json: [task, { ...task, id: "other-task", employeeId: "other-employee", title: "Other employee worksheet" }] });
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
    await worksheet.getByRole("button", { name: "View details", exact: true }).click();
    await worksheet.getByRole("button", { name: "Start", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Start this worksheet" });
    await dialog.getByLabel("Starting note").fill("Checking the isolated feature module");
    await dialog.getByRole("button", { name: "Start work", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(worksheet.getByText("In progress", { exact: true }).first()).toBeVisible();
    expect(actions).toEqual([{ method: "POST", body: { note: "Checking the isolated feature module" } }]);
    expect(errors).toEqual([]);
});
