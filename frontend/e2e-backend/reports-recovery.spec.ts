import { expect, test, type Page } from "@playwright/test";

const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, first: true, last: true, empty: !content.length });
async function fixture(page: Page, role = "ROLE_CEO") {
    const state = { authStatus: 200, refreshStatus: 200, authDelay: 0, coreStatus: 200, reportStatus: 200, mixStatus: 200, exportBody: null as Record<string, unknown> | null, requests: [] as string[] };
    const profile = { userId: "22222222-2222-4222-8222-222222222222", employeeId: "44444444-4444-4444-8444-444444444444", email: "review@brainserve.in", roles: [role], permissions: ["REPORT_VIEW", "EMPLOYEE_READ", "APPOINTMENT_APPROVE"], forcePasswordChange: false };
    await page.addInitScript(() => {
        if (sessionStorage.getItem("test-seeded")) return;
        sessionStorage.setItem("test-seeded", "1");
        sessionStorage.setItem("brainserve.connect.access-token", "review-access");
        sessionStorage.setItem("brainserve.connect.refresh-token", "review-refresh");
    });
    await page.route("http://backend.invalid/api/v1/**", async route => {
        const url = new URL(route.request().url()); const path = url.pathname;
        state.requests.push(path);
        if (path.endsWith("/auth/refresh")) {
            if (state.refreshStatus === 200) state.authStatus = 200;
            return route.fulfill({ status: state.refreshStatus, json: state.refreshStatus === 200 ? { accessToken: "renewed-access", refreshToken: "renewed-refresh" } : { detail: "Refresh unavailable" } });
        }
        if (path.endsWith("/auth/me")) {
            const status = state.authStatus;
            if (state.authDelay) await new Promise(resolve => setTimeout(resolve, state.authDelay));
            return route.fulfill({ status, json: status === 200 ? profile : { detail: "Session verification failed" } });
        }
        if (path.endsWith("/profile/me")) return route.fulfill({ json: { ...profile, fullName: "Review User", departmentId: "11111111-1111-4111-8111-111111111111", photoUrl: null } });
        if (path.endsWith("/dashboard/summary")) {
            const status = url.searchParams.has("period") ? state.reportStatus : state.coreStatus;
            return route.fulfill({ status, json: status === 200 ? { awaitingApproval: 3, activeVisits: 7, visitorsInside: 4, totalEmployees: 30, activeEmployees: 28, arrivedVisits: 12, scheduledVisits: 18, completedVisits: 8, cancelledVisits: 1, rejectedVisits: 2, scope: role === "ROLE_HR_ADMIN" ? "DEPARTMENT" : "COMPANY",
                sourceGeneration: 1, sourceRefreshedAt: new Date().toISOString(), freshness: "FRESH", freshUntil: new Date(Date.now() + 60_000).toISOString(), sourceType: "SUMMARY" } : { detail: "Temporary outage" } });
        }
        if (path.endsWith("/dashboard/visit-types")) return route.fulfill({ status: state.mixStatus, json: state.mixStatus === 200 ? [{ type: "EMPLOYEE_VISIT", total: 12 }, { type: "HR_INTERVIEW", total: 6 }] : { detail: "Temporary outage" } });
        if (path.endsWith("/public/company-profile")) return route.fulfill({ json: { name: "BrainServe Private Limited", emailDomain: "brainserve.in", hqAddress: "Hyderabad", supportEmail: "support@brainserve.in" } });
        if (path.endsWith("/employees") || path.endsWith("/appointments") || path.endsWith("/admin/staff-accounts")) return route.fulfill({ status: path.endsWith("/appointments") ? state.coreStatus : 200, json: paged([]) });
        if (path.endsWith("/realtime/stream")) return route.fulfill({ status: 204 });
        if (path.endsWith("/reception/visitors-inside")) return route.fulfill({ json: [{ id: "visit-1", appointmentId: "appointment-1", visitorName: "Review Visitor", badgeNumber: "R-001", checkedInAt: new Date().toISOString(), checkedOutAt: null }] });
        if (path.includes("/history")) return route.fulfill({ json: { items: [], hasMore: false, nextCursor: null } });
        if (path.endsWith("/report-exports")) {
            if (route.request().method() === "POST") state.exportBody = route.request().postDataJSON();
            return route.fulfill({ json: route.request().method() === "POST" ? { id: "export-1" } : [] });
        }
        if (path.includes("unread")) return route.fulfill({ json: { unreadCount: 0, count: 0 } });
        return route.fulfill({ json: [] });
    });
    return state;
}
const nav = (page: Page) => page.getByRole("navigation", { name: "Role workspace" });

test("Overview preserves a reported zero while a pending request remains visible", async ({ page }) => {
    await fixture(page, "ROLE_HR_ADMIN");
    await page.route("http://backend.invalid/api/v1/dashboard/summary*", route => route.fulfill({ json: {
        awaitingApproval: 0, activeVisits: 0, visitorsInside: 0, totalEmployees: 1, activeEmployees: 1, arrivedVisits: 0,
    } }));
    await page.route("http://backend.invalid/api/v1/appointments?*", route => route.fulfill({ json: paged([{
        id: "pending-zero-fixture", referenceNumber: "KPI-0", visitorName: "Pending Fixture",
        visitorEmail: "visitor@example.invalid", visitorPhone: "0000000000", purpose: "KPI fixture",
        type: "EMPLOYEE_VISIT", status: "PENDING_HR_APPROVAL", hostEmployeeId: "44444444-4444-4444-8444-444444444444",
        slotStart: new Date().toISOString(), slotEnd: new Date(Date.now() + 3_600_000).toISOString(),
    }]) }));
    await page.goto("/");
    await expect(page.getByText("Pending Fixture").first()).toBeVisible();
    const workflow = page.locator(".metric-card").filter({ hasText: "In workflow" });
    await expect(workflow.locator("strong")).toHaveText("0");
    await expect(workflow.locator("small")).toHaveText("1 require your action");
});

for (const role of ["ROLE_CEO", "ROLE_HR_ADMIN", "ROLE_SYSTEM_ADMIN"]) {
    test(`${role}: Reports totals and chart coexist with history and CSV export`, async ({ page }) => {
        const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
        const state = await fixture(page, role);
        await page.goto("/");
        await nav(page).getByRole("button", { name: "Reports", exact: true }).click();
        const overview = page.getByRole("region", { name: "Reports overview", exact: true });
        await expect(overview.getByRole("img", { name: "Visit mix: 18 scheduled visits" })).toBeVisible();
        await expect(overview.getByText("66.7%", { exact: false })).toBeVisible();
        await expect(overview.locator("article").filter({ hasText: /^Arrived12$/ }).locator("strong")).toHaveText("12");
        state.mixStatus = 503;
        await overview.getByRole("button", { name: "Refresh report" }).click();
        await expect(overview.getByRole("alert")).toContainText("Last loaded values are retained");
        await expect(overview.getByRole("img")).toBeVisible();
        await overview.getByLabel("Report from").fill("2026-01-01");
        state.reportStatus = 503;
        await overview.getByRole("button", { name: "Apply report range" }).click();
        await expect(overview.getByRole("alert")).toContainText("Retry to load your report");
        await expect(overview.getByRole("img")).toHaveCount(0);
        await page.getByRole("navigation", { name: "Reports navigation" }).getByRole("button", { name: /Explore Records/ }).click();
        await expect(page.getByRole("heading", { name: "Role-authorized history" })).toBeVisible();
        await page.getByRole("button", { name: "Export CSV", exact: true }).click();
        await expect(page.getByRole("heading", { name: "Export centre" })).toBeVisible();
        expect(state.exportBody?.format).toBe("CSV");
        expect(state.exportBody?.dataset).toBe("VISITS");
        expect(errors).toEqual([]);
    });
}

test("temporary session outage retains credentials, retry remains guarded, then redirects", async ({ page }) => {
    const state = await fixture(page); state.authStatus = 503;
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.access-token"))).toBe("review-access");
    state.authDelay = 800;
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(page.locator('[data-action="retry"]')).toBeDisabled();
    await expect(nav(page)).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Retry connection" })).toBeEnabled();
    state.authStatus = 200;
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(page.getByRole("heading", { name: "Opening your workspace…" })).toBeVisible();
    await expect(nav(page)).toBeVisible();
    await expect(page.getByRole("region", { name: "Visitors", exact: true }).locator("dl div").filter({ hasText: "Arrived today" }).locator("dd")).toHaveText("12");
    await nav(page).getByRole("button", { name: "Visitors", exact: true }).click();
    await expect(page.locator("#live-occupancy").getByText("Review Visitor", { exact: true })).toBeVisible();
    await expect(page.locator("#live-occupancy").getByRole("button", { name: /Check out/ })).toHaveCount(0);
});

test("revoked session clears credentials and returns to login", async ({ page }) => {
    const state = await fixture(page); state.authStatus = 403;
    await page.goto("/");
    await expect(page.getByRole("button", { name: "Sign in securely" })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.access-token"))).toBeNull();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.refresh-token"))).toBeNull();
    await expect(nav(page)).toHaveCount(0);
});

test("initial core API outage stays on recovery until retry loads the workspace", async ({ page }) => {
    const state = await fixture(page); state.coreStatus = 503;
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(page.getByRole("button", { name: "Retry connection" })).toBeEnabled();
    state.coreStatus = 200;
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(nav(page)).toBeVisible();
});


test("expired access token plus transient refresh outage preserves the renewable session", async ({ page }) => {
    const state = await fixture(page); state.authStatus = 401; state.refreshStatus = 503;
    await page.goto("/");
    await expect(page.getByRole("heading", { name: "Reconnecting to your workspace" })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.refresh-token"))).toBe("review-refresh");
    state.refreshStatus = 200;
    await page.getByRole("button", { name: "Retry connection" }).click();
    await expect(nav(page)).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.access-token"))).toBe("renewed-access");
});

test("rejected refresh credential still fails closed", async ({ page }) => {
    const state = await fixture(page); state.authStatus = 401; state.refreshStatus = 403;
    await page.goto("/");
    await expect(page.getByRole("button", { name: "Sign in securely" })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.refresh-token"))).toBeNull();
    await expect(nav(page)).toHaveCount(0);
});
