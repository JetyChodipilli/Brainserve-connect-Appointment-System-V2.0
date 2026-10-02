import { expect, test, type Page } from "@playwright/test";

type Card = {
    id: string; title: string; definition: string; kind: string; clock: string; unit: string;
    state: string; value: number | null; displayValue: string | null; reason: string | null;
    sourceRefreshedAt: string | null; freshUntil: string | null; freshness: string;
    sampleSize: number | null; eligibleCount: number | null; coveragePercent: number | null;
    excludedCount: number | null; drillDownAvailable: boolean; comparison: null;
};
const officeDay = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Kolkata", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
function officeRange(period: string) {
    const today = officeDay();
    const shifted = (days: number) => new Date(Date.parse(today) + days * 86_400_000).toISOString().slice(0, 10);
    if (period === "YESTERDAY") return { from: shifted(-1), to: shifted(-1) };
    if (period === "LAST_7_DAYS") return { from: shifted(-6), to: today };
    if (period === "THIS_MONTH") return { from: today.slice(0, 8) + "01", to: today };
    if (period === "PREVIOUS_MONTH") {
        const last = new Date(Date.parse(today.slice(0, 8) + "01") - 86_400_000).toISOString().slice(0, 10);
        return { from: last.slice(0, 8) + "01", to: last };
    }
    return { from: today, to: today };
}
const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: content.length ? 1 : 0, last: true });
function card(id: string, title: string, value: number | null = 0, extra: Partial<Card> = {}): Card {
    return { id, title, definition: `Authorized definition for ${id}.`, kind: "STOCK", clock: "NOW", unit: "COUNT",
        state: "AVAILABLE", value, displayValue: null, reason: null, sourceRefreshedAt: new Date().toISOString(),
        freshUntil: new Date(Date.now() + 60_000).toISOString(), freshness: "FRESH", sampleSize: null,
        eligibleCount: null, coveragePercent: null, excludedCount: null, drillDownAvailable: true, comparison: null, ...extra };
}
async function fixture(page: Page, role = "ROLE_CEO") {
    const state = { status: 200, slowYesterday: false, requests: [] as string[], cardRequests: [] as string[],
        recordRequests: [] as string[], deniedRecords: false, expires: false };
    const profile = { userId: "22222222-2222-4222-8222-222222222222", employeeId: "44444444-4444-4444-8444-444444444444",
        email: "dashboard@brainserve.in", fullName: "Dashboard Reviewer", roles: [role],
        permissions: ["REPORT_VIEW", "SYSTEM_CONFIGURE", "ROLE_MANAGE", "CEO_VISIT_APPROVE", "VISITOR_OCCUPANCY_READ", "WORK_INSIGHT_READ"], forcePasswordChange: false };
    await page.addInitScript(() => {
        sessionStorage.setItem("brainserve.connect.access-token", "dashboard-access");
        sessionStorage.setItem("brainserve.connect.refresh-token", "dashboard-refresh");
    });
    await page.route("http://backend.invalid/api/v1/**", async route => {
        const url = new URL(route.request().url()); const path = url.pathname;
        state.requests.push(path);
        if (path.endsWith("/auth/me") || path.endsWith("/profile/me")) return route.fulfill({ json: profile });
        if (path.endsWith("/auth/security")) return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: true, mfaVerified: true, stepUpRequired: false } });
        if (path.endsWith("/dashboard/cards")) {
            state.cardRequests.push(url.search);
            const period = url.searchParams.get("period") ?? "TODAY";
            if (period === "YESTERDAY" && state.slowYesterday) await new Promise(resolve => setTimeout(resolve, 700));
            if (state.status !== 200) return route.fulfill({ status: state.status, json: { detail: "Measurement source temporarily unavailable" } });
            const admin = role === "ROLE_SYSTEM_ADMIN";
            const unavailable = { state: "UNAVAILABLE", value: null, reason: "No verified measurement evidence has been recorded.", freshness: "UNKNOWN", sourceRefreshedAt: null, freshUntil: null, drillDownAvailable: false };
            const cards = admin ? [
                card("OPS01", "Core workflow availability", null, { ...unavailable, kind: "RATE", unit: "PERCENT" }),
                card("OPS04", "Critical dependency state", null, { unit: "STATUS", displayValue: "READY" }),
                card("IAM03", "Pending account approvals"),
                card("VIS08", "Overdue approval stages", null, unavailable),
                card("NTF03", "Dead-letter jobs"),
                card("OPS07", "Latest backup age", null, { ...unavailable, unit: "SECONDS" }),
            ] : [
                card("VIS02", "Arrivals", period === "YESTERDAY" ? 99 : 0, { kind: "FLOW", clock: "PERIOD" }),
                card("VIS05", "Visitors inside now", 4, { freshUntil: new Date(Date.now() + (state.expires ? 3_000 : 15_000)).toISOString() }),
                card("VIS07", "Pending CEO stages"),
                card("VIS09", "Visitor wait p95", 0, { kind: "DISTRIBUTION", clock: "PERIOD", unit: "SECONDS", sampleSize: 10, eligibleCount: 12, coveragePercent: 83.3, excludedCount: 2 }),
                card("WORK03", "Overdue delivery", 2),
                card("WORK07", "On-time accepted delivery", null, { kind: "COHORT", clock: "PERIOD", unit: "PERCENT", state: "NOT_APPLICABLE", reason: "No eligible original commitments in this period.", eligibleCount: 0, drillDownAvailable: false }),
            ];
            return route.fulfill({ json: { metricVersion: "sprint3.v1", role, scope: "COMPANY", departmentId: null,
                from: url.searchParams.get("from") ?? officeRange(period).from, to: url.searchParams.get("to") ?? officeRange(period).to,
                officeZone: "Asia/Kolkata", asOf: new Date().toISOString(), sourceGeneration: 1, cards,
                supplementary: admin ? [card("OPS08", "Verified restore evidence", null, unavailable), card("OPS09", "Metric freshness and coverage", null, { unit: "STATUS", displayValue: "PARTIAL" })]
                    : [card("VIS14", "Cumulative visit records", 37, { kind: "CUMULATIVE", clock: "HISTORY", reason: "Retained history since 2025-01-01" })],
                coverage: [{ id: "original-commitments", title: "Original delivery commitments", state: "PARTIAL", since: "2026-10-02T00:00:00Z", reason: "Legacy dates remain unknown." }] } });
        }
        if (/\/dashboard\/cards\/[^/]+\/records$/.test(path)) {
            state.recordRequests.push(url.search);
            if (state.deniedRecords) return route.fulfill({ status: 403, json: { code: "DASHBOARD_SCOPE_DENIED", detail: "Access to these records is restricted." } });
            const pageNumber = Number(url.searchParams.get("page") ?? 0);
            return route.fulfill({ json: { metricId: path.split("/").at(-2), metricVersion: "sprint3.v1", state: "AVAILABLE", reason: null,
                asOf: new Date().toISOString(), from: url.searchParams.get("from") ?? officeDay(), to: url.searchParams.get("to") ?? officeDay(), page: pageNumber, size: 50,
                totalElements: 51, totalPages: 2, items: [{ id: `arrival-${pageNumber}`, label: pageNumber ? "Last arrival" : "First arrival", detail: "Matching arrival population", status: "APPROVED", occurredAt: new Date().toISOString(), kind: "VISIT" }] } });
        }
        if (path.endsWith("/dashboard/summary")) return route.fulfill({ json: { role, scope: "COMPANY", from: officeDay(), to: officeDay(), awaitingApproval: 0,
            activeVisits: 7, visitorsInside: 4, arrivedVisits: 12, totalEmployees: 30, activeEmployees: 28,
            generatedAt: new Date().toISOString(), sourceRefreshedAt: new Date().toISOString(), sourceGeneration: 1, freshness: "FRESH", freshUntil: new Date(Date.now() + 60_000).toISOString(), sourceType: "SUMMARY" } });
        if (path.endsWith("/public/company-profile")) return route.fulfill({ json: { name: "BrainServe", emailDomain: "brainserve.in" } });
        if (path.endsWith("/employees") || path.endsWith("/appointments") || path.endsWith("/admin/staff-accounts")) return route.fulfill({ json: paged([]) });
        if (path.endsWith("/realtime/stream") || path.endsWith("/auth/logout")) return route.fulfill({ status: 204 });
        if (path.includes("unread")) return route.fulfill({ json: { unreadCount: 0, count: 0 } });
        return route.fulfill({ json: [] });
    });
    return state;
}

test("CEO cards distinguish confirmed zero, live stock, missing cohorts and retained visitor context", async ({ page }) => {
    const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    await fixture(page); await page.goto("/");
    const dashboard = page.getByRole("region", { name: "Administration dashboard" });
    await expect(dashboard.getByRole("article", { name: "Arrivals", exact: true }).locator("strong")).toHaveText("0", { timeout: 15_000 });
    await expect(dashboard.getByRole("article", { name: "Visitors inside now" })).toContainText("4");
    await expect(dashboard.getByRole("article", { name: "On-time accepted delivery" })).toContainText(/not applicable/i);
    await expect(dashboard).toContainText("Asia/Kolkata");
    await expect(dashboard.getByRole("article", { name: "Visitor wait p95" })).toContainText("10");
    await expect(dashboard.getByRole("article", { name: "Cumulative visit records" })).toContainText("37");
    await expect(dashboard.getByText("Arrived today", { exact: true })).toBeVisible();
    expect(errors).toEqual([]);
});

test("System Admin unavailable probes/backups remain explicit and governed account panel remains", async ({ page }) => {
    await fixture(page, "ROLE_SYSTEM_ADMIN"); await page.goto("/");
    const dashboard = page.getByRole("region", { name: "Administration dashboard" });
    await expect(dashboard.getByRole("article", { name: "Core workflow availability" })).toContainText(/unavailable/i);
    await expect(dashboard.getByRole("article", { name: "Latest backup age" })).toContainText(/unavailable/i);
    await expect(dashboard.getByRole("article", { name: "Pending account approvals" })).toContainText("0");
    await expect(page.getByText(/account provisioning|account approvals/i).first()).toBeVisible();
});

test("drill-down keeps definition filters, pages records, traps focus and restores its opener", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    const arrivals = page.getByRole("article", { name: "Arrivals", exact: true });
    const opener = arrivals.getByRole("button").first(); await opener.click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("First arrival", { exact: true })).toBeVisible();
    await dialog.getByRole("button", { name: /next/i }).click();
    await expect(dialog.getByText("Last arrival", { exact: true })).toBeVisible();
    expect(state.recordRequests.some(search => new URLSearchParams(search).get("page") === "1")).toBe(true);
    for (let i = 0; i < 8; i++) await page.keyboard.press("Tab");
    expect(await page.evaluate(() => Boolean(document.activeElement?.closest("dialog")))).toBe(true);
    await page.keyboard.press("Escape"); await expect(dialog).not.toBeVisible();
    await expect(opener).toBeFocused();
});

test("obsolete period responses cannot replace the new selected range", async ({ page }) => {
    const state = await fixture(page); state.slowYesterday = true; await page.goto("/");
    const period = page.getByRole("combobox", { name: "Dashboard period", exact: true });
    await period.selectOption("YESTERDAY");
    await expect.poll(() => state.cardRequests.some(search => new URLSearchParams(search).get("period") === "YESTERDAY")).toBe(true);
    await period.selectOption("LAST_7_DAYS");
    await expect.poll(() => state.cardRequests.some(search => new URLSearchParams(search).get("period") === "LAST_7_DAYS")).toBe(true);
    await expect(page.getByRole("article", { name: "Arrivals", exact: true })).toContainText("0");
    await page.waitForTimeout(900);
    await expect(page.getByRole("article", { name: "Arrivals", exact: true })).not.toContainText("99");
});

test("a failed new range clears old values and offers retry", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    await expect(page.getByRole("article", { name: "Visitors inside now" })).toContainText("4");
    state.status = 503;
    await page.getByRole("combobox", { name: "Dashboard period", exact: true }).selectOption("LAST_7_DAYS");
    const dashboard = page.getByRole("region", { name: "Administration dashboard" });
    await expect(dashboard.getByRole("alert")).toBeVisible();
    await expect(dashboard.locator('article[data-metric-id="VIS05"]')).toContainText("Unavailable");
    await expect(dashboard.locator('article[data-metric-id="VIS05"] strong')).not.toHaveText("4");
    await expect(dashboard.getByText("Arrived today", { exact: true }).locator("..")).toContainText("12");
    state.status = 200; await dashboard.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(dashboard.getByRole("article", { name: "Visitors inside now" })).toContainText("4");
});

test("source freshness expires while the dashboard stays open", async ({ page }) => {
    const state = await fixture(page); state.expires = true; await page.goto("/");
    const occupancy = page.getByRole("article", { name: "Visitors inside now" });
    await expect(occupancy.locator('[data-freshness="fresh"]')).toBeVisible();
    await expect(occupancy.locator('[data-freshness="stale"]')).toBeVisible();
    await expect(occupancy.locator("strong")).toHaveText("4");
});

test("custom office dates show linked validation and apply an inclusive source range", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    await expect(page.getByRole("article", { name: "Arrivals", exact: true }).locator("strong")).toHaveText("0");
    await page.getByRole("combobox", { name: "Dashboard period", exact: true }).selectOption("CUSTOM");
    const from = page.getByLabel("Dashboard from", { exact: true });
    const to = page.getByLabel("Dashboard to", { exact: true });
    await from.fill("2024-01-01"); await to.fill("2025-01-01");
    const requestCount = state.cardRequests.length;
    await page.getByRole("button", { name: "Apply dashboard range" }).click();
    await expect(page.getByRole("alert")).toContainText("366");
    await expect(from).toHaveAttribute("aria-describedby", "dashboard-date-help");
    expect(state.cardRequests.length).toBe(requestCount);
    await from.fill("2026-10-01"); await to.fill("2026-10-02");
    await page.getByRole("button", { name: "Apply dashboard range" }).click();
    await expect.poll(() => state.cardRequests.some(search => search.includes("from=2026-10-01") && search.includes("to=2026-10-02"))).toBe(true);
    await expect(page.getByRole("article", { name: "Arrivals", exact: true })).toContainText("2026-10-01 to 2026-10-02");
});

test("denied record refresh clears previously visible source rows", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    await page.getByRole("article", { name: "Arrivals", exact: true }).getByRole("button").click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("First arrival", { exact: true })).toBeVisible();
    state.deniedRecords = true;
    await dialog.getByRole("button", { name: "Next", exact: true }).click();
    await expect(dialog.getByRole("alert")).toBeVisible();
    await expect(dialog.getByText("First arrival", { exact: true })).toHaveCount(0);
    await expect(dialog.getByText("Last arrival", { exact: true })).toHaveCount(0);
});

test("new dashboard and record dialog fit phone, tablet and desktop widths", async ({ page }, testInfo) => {
    await fixture(page); await page.goto("/");
    for (const width of [360, 768, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        await expect(page.getByRole("article", { name: "Arrivals", exact: true })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.screenshot({ path: testInfo.outputPath(`dashboard-${width}.png`), fullPage: true });
        await page.getByRole("article", { name: "Arrivals", exact: true }).getByRole("button").first().click();
        await expect(page.getByRole("dialog").getByText("First arrival", { exact: true })).toBeVisible();
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.keyboard.press("Escape");
    }
});
