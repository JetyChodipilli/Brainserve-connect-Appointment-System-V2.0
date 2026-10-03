import { expect, test, type Page } from "@playwright/test";
import type { WorkspaceSetting } from "../types/api";

const jobId = "11111111-1111-4111-8111-111111111111";
const paged = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: content.length ? 1 : 0, last: true });
const steps = ["company", "departments", "roles", "policy", "notifications", "privacy", "review"];
const titles = ["Company profile", "Departments", "Roles & responsibilities", "Appointment policy", "Notifications", "Privacy & retention", "Review readiness"];
const makeSetup = (ready = false) => ({ policyVersion: "setup.v1", revision: 0, status: "IN_PROGRESS", currentStep: "company", completedAt: null as string | null,
    officeZone: "Asia/Kolkata", steps: steps.map((id, index) => ({ id, title: titles[index], complete: ready,
        issues: ready ? [] : [id === "departments" ? "A current department Manager and HR Admin are required." : `Review current ${id} configuration.`], settingKeys: id === "company" ? ["COMPANY.NAME"] : [] })) });
type Row = { rowNumber: number; status: string; values: Record<string, string>; errors: string[]; recordId: string | null };
type Job = { id: string; kind: string; duplicatePolicy: string; status: string; checksum: string; createdAt: string; expiresAt: string;
    totalRows: number; applied: number; skipped: number; failed: number; rows: Row[]; rollbackNotice: string };
const makeJob = (kind = "DEPARTMENTS", count = 2): Job => ({ id: jobId, kind, duplicatePolicy: "SKIP", status: "PREVIEW", checksum: "exact-preview-sha256",
    createdAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 86_400_000).toISOString(), totalRows: count, applied: 0, skipped: 0, failed: 1,
    rows: Array.from({ length: count }, (_, index): Row => ({ rowNumber: index + 2, status: index === count - 1 ? "FAILED" : "VALID",
        values: kind === "EMPLOYEES" ? { firstName: `Employee ${index + 1}`, officialEmail: `employee${index + 1}@example.invalid`, departmentCode: "ENG" }
            : kind === "VISITORS" ? { visitorName: `Visitor ${index + 1}`, type: "OTHER", departmentCode: "ENG" }
                : { code: `D${index + 1}`, name: `Department ${index + 1}` }, errors: index === count - 1 ? ["Department code is required."] : [], recordId: null })),
    rollbackNotice: "Created records and notifications cannot be automatically reversed. Deleting this job does not delete created records." });

async function fixture(page: Page, role = "ROLE_SYSTEM_ADMIN") {
    const state = { setup: makeSetup(), job: makeJob(), options: role === "ROLE_RECEPTIONIST" ? ["VISITORS"] : role === "ROLE_HR_ADMIN" ? ["EMPLOYEES"] : ["DEPARTMENTS"],
        status: 200, conflict: false, delayPreview: false, lostExecution: false, partialExecution: false,
        previewCalls: 0, completeCalls: 0, jobReads: 0, jobReadDelay: 0, executions: [] as { checksum: string; idempotencyKey: string }[], requests: [] as string[],
        policySettings: [
            { key: "COMPANY.OFFICE_ZONE", value: "UTC", type: "STRING", description: "Configured company office ZoneId", version: 1 },
            { key: "APPROVAL.INTERVIEW.REQUIRES_HR", value: "true", type: "BOOLEAN", description: "Require HR approval for interviews", version: 1 },
        ] as WorkspaceSetting[], savedSettings: [] as { key: string; value: string }[] };
    const profile = { userId: "22222222-2222-4222-8222-222222222222", employeeId: null, email: "sprint4@example.invalid", fullName: "Sprint 4 Reviewer",
        roles: [role], permissions: ["SYSTEM_CONFIGURE", "EMPLOYEE_CREATE", "VISITOR_REGISTER", "DEPARTMENT_MANAGE", "REPORT_VIEW"], forcePasswordChange: false };
    await page.addInitScript(() => { sessionStorage.setItem("brainserve.connect.access-token", "sprint4-access"); sessionStorage.setItem("brainserve.connect.refresh-token", "sprint4-refresh"); });
    await page.route("http://backend.invalid/api/v1/**", async route => {
        const path = new URL(route.request().url()).pathname, method = route.request().method(); state.requests.push(path);
        if (path.endsWith("/auth/me") || path.endsWith("/profile/me")) return route.fulfill({ json: profile });
        if (path.endsWith("/auth/security")) return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: true, mfaVerified: true, stepUpRequired: false } });
        if (path.includes("/system-settings") || path.includes("/workspace-settings")) {
            if (method === "PUT") {
                const key = decodeURIComponent(path.split("/").at(-1)!);
                const body = route.request().postDataJSON(); expect(Object.keys(body)).toEqual(["value"]);
                const current = state.policySettings.find(setting => setting.key === key)!;
                const updated = { ...current, value: body.value, version: current.version + 1 };
                state.policySettings = state.policySettings.map(setting => setting.key === key ? updated : setting);
                state.savedSettings.push({ key, value: body.value });
                return route.fulfill({ json: updated });
            }
            return route.fulfill({ json: state.policySettings });
        }
        if (path.includes("/company-setup")) {
            if (method === "PUT") {
                if (state.conflict) { state.conflict = false; return route.fulfill({ status: 409, json: { detail: "Setup revision changed" } }); }
                const body = route.request().postDataJSON(); expect(body.expectedRevision).toBe(state.setup.revision);
                state.setup = { ...state.setup, revision: state.setup.revision + 1, currentStep: body.stepId };
            } else if (method === "POST") { state.completeCalls++; expect(route.request().postDataJSON().expectedRevision).toBe(state.setup.revision); state.setup = { ...state.setup, revision: state.setup.revision + 1, status: "COMPLETE", completedAt: new Date().toISOString() }; }
            return route.fulfill({ json: state.setup });
        }
        if (path.includes("/bulk-imports")) {
            if (state.status !== 200) return route.fulfill({ status: state.status, json: { detail: "Import permission was removed. Refresh access." } });
            if (path.endsWith("/options")) return route.fulfill({ json: { allowedKinds: state.options, maxRows: 1_000, maxBytes: 2_097_152, duplicatePolicies: ["SKIP", "FAIL"] } });
            if (path.includes("/templates/")) return route.fulfill({ json: { kind: path.split("/").at(-1), filename: "departments-template.csv", csv: "code,name\nENG,Engineering\n", columns: ["code", "name"] } });
            if (path.endsWith("/preview")) {
                state.previewCalls++; const body = route.request().postDataJSON(); state.job = { ...state.job, kind: body.kind, duplicatePolicy: body.duplicatePolicy };
                const response = JSON.parse(JSON.stringify(state.job));
                if (state.delayPreview) await new Promise(resolve => setTimeout(resolve, 700));
                return route.fulfill({ json: response });
            }
            if (path.endsWith("/execute")) {
                state.executions.push(route.request().postDataJSON());
                if (state.lostExecution) {
                    state.lostExecution = false; state.job = { ...state.job, status: "RUNNING" };
                    return route.abort("failed");
                }
                const rows = state.job.rows.map((row, index) => row.status === "VALID" && (!state.partialExecution || index === 0) ? { ...row, status: "APPLIED", recordId: `record-${row.rowNumber}` } : row);
                state.job = { ...state.job, status: state.partialExecution ? "RUNNING" : "COMPLETED", rows, applied: rows.filter(row => row.status === "APPLIED").length };
                return route.fulfill({ json: state.job });
            }
            if (path.endsWith("/errors")) return route.fulfill({ json: { filename: "import-errors.csv", csv: "rowNumber,name,error\n3,'=HYPERLINK(unsafe),Department code is required\n" } });
            state.jobReads++; const response = JSON.parse(JSON.stringify(state.job));
            if (state.jobReadDelay) await new Promise(resolve => setTimeout(resolve, state.jobReadDelay));
            return route.fulfill({ json: response });
        }
        if (path.endsWith("/public/company-profile")) return route.fulfill({ json: { name: "BrainServe", emailDomain: "example.invalid" } });
        if (path.endsWith("/admin/integrations")) return route.fulfill({ json: { status: "DEGRADED", checkedAt: new Date().toISOString(), services: [] } });
        if (path.endsWith("/employees") || path.endsWith("/appointments") || path.endsWith("/admin/staff-accounts")) return route.fulfill({ json: paged([]) });
        if (path.endsWith("/realtime/stream") || path.endsWith("/auth/logout")) return route.fulfill({ status: 204 });
        if (path.includes("unread")) return route.fulfill({ json: { unreadCount: 0, count: 0 } });
        return route.fulfill({ json: [] });
    });
    return state;
}
async function settings(page: Page) { await page.goto("/"); await page.getByRole("button", { name: "Settings", exact: true }).click(); }
async function imports(page: Page) { await settings(page); await page.getByRole("button", { name: "Safe CSV imports", exact: true }).click(); await expect(page.getByLabel("Import type")).toBeVisible(); }
async function upload(page: Page, csv = "code,name\nENG,Engineering\nBAD,Missing department") {
    await page.getByLabel("CSV UTF-8 file").setInputFiles({ name: "departments.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
    await page.getByRole("button", { name: "Validate & preview CSV", exact: true }).click();
    await expect(page.getByRole("region", { name: "Import job results" })).toBeVisible();
}

test("setup resumes its persisted step, exposes blockers, handles revision conflicts and completes current readiness", async ({ page }) => {
    const state = await fixture(page); await settings(page); await page.getByRole("button", { name: "Company setup", exact: true }).click();
    const setup = page.getByRole("region", { name: "Company setup" });
    await expect(setup).toContainText("Runtime office timezone: Asia/Kolkata");
    await setup.getByRole("button", { name: /^2\. Departments/ }).click(); await expect(setup).toContainText("A current department Manager and HR Admin are required.");
    await setup.getByRole("button", { name: "Open department imports", exact: true }).click(); await expect(page.getByLabel("Import type")).toBeVisible();
    await page.getByRole("button", { name: "Company setup", exact: true }).click(); await expect(setup.getByRole("button", { name: /^2\. Departments/ })).toHaveAttribute("aria-current", "step");
    state.conflict = true; await setup.getByRole("button", { name: /^7\. Review readiness/ }).click();
    await expect(setup.getByRole("alert")).toContainText("changed in another session"); await expect(setup.getByRole("alert")).toBeFocused();
    await setup.getByRole("button", { name: "Reload current setup" }).click(); await setup.getByRole("button", { name: /^7\. Review readiness/ }).click();
    await expect(setup.getByRole("button", { name: "Complete company setup" })).toBeDisabled();
    state.setup = { ...makeSetup(true), revision: state.setup.revision + 1, currentStep: "review" }; await setup.getByRole("button", { name: "Refresh checklist" }).click();
    await setup.getByRole("button", { name: "Complete company setup" }).click(); await expect(setup).toContainText("Currently ready"); expect(state.completeCalls).toBe(1);
});

test("setup links to editable office time zone and interview approval without changing the runtime clock", async ({ page }) => {
    const state = await fixture(page); await settings(page); await page.getByRole("button", { name: "Company setup", exact: true }).click();
    const setup = page.getByRole("region", { name: "Company setup" });
    await setup.getByRole("button", { name: /^4\. Appointment policy/ }).click();
    await setup.getByRole("button", { name: "Open Appointment policy", exact: true }).click();
    await expect(page.getByText("The office time zone preference must match the running service", { exact: false })).toBeVisible();
    const zone = page.getByRole("textbox", { name: "Office time zone", exact: true });
    await expect(zone).toHaveValue("UTC"); await zone.fill("Asia/Kolkata");
    await zone.locator("..").getByRole("button", { name: "Save", exact: true }).click();
    await expect.poll(() => state.savedSettings).toEqual([{ key: "COMPANY.OFFICE_ZONE", value: "Asia/Kolkata" }]);
    await expect(zone.locator("..").getByRole("button", { name: "Save", exact: true })).toBeDisabled();
    await page.getByRole("checkbox", { name: "Require HR approval for interviews", exact: true }).uncheck();
    await expect.poll(() => state.savedSettings).toHaveLength(2);
    expect(state.savedSettings[1]).toEqual({ key: "APPROVAL.INTERVIEW.REQUIRES_HR", value: "false" });
    expect(state.requests).toContain("/api/v1/system-settings/COMPANY.OFFICE_ZONE");
    await page.getByRole("button", { name: "Company setup", exact: true }).click();
    await expect(setup).toContainText("Runtime office timezone: Asia/Kolkata");
    expect(state.setup.officeZone).toBe("Asia/Kolkata");
});

test("templates, review pagination, exact confirmation and error CSV complete a safe import", async ({ page }) => {
    const errors: string[] = []; page.on("pageerror", error => errors.push(error.message));
    const state = await fixture(page); state.job = makeJob("DEPARTMENTS", 21); await imports(page);
    const download = page.waitForEvent("download"); await page.getByRole("button", { name: "Download departments template" }).click(); expect((await download).suggestedFilename()).toBe("departments-template.csv");
    await upload(page, "code,name\n" + Array.from({ length: 21 }, (_, index) => `D${index},Department ${index}`).join("\n"));
    const results = page.getByRole("region", { name: "Import job results" });
    await expect(results.getByText("Department 21", { exact: true })).toHaveCount(0); await expect(results.getByRole("button", { name: "Confirm & create valid rows" })).toBeDisabled();
    await results.getByRole("button", { name: "Next rows" }).click(); await expect(results).toContainText("Department 21"); await expect(results).toContainText("Department code is required.");
    await results.getByRole("checkbox").check(); await results.getByRole("button", { name: "Confirm & create valid rows" }).click();
    await expect(results).toContainText("COMPLETED"); expect(state.executions).toEqual([{ checksum: "exact-preview-sha256", idempotencyKey: `import-${jobId}` }]);
    await expect(results).toContainText("Deleting this job does not delete created records.");
    const errorDownload = page.waitForEvent("download"); await results.getByRole("button", { name: "Download error CSV" }).click(); expect((await errorDownload).suggestedFilename()).toBe("import-errors.csv");
    expect(errors).toEqual([]);
});

test("lost execution response reloads durable state and resumes with the same key after a page reload", async ({ page }) => {
    const state = await fixture(page); state.lostExecution = true; await imports(page); await upload(page);
    await page.getByRole("checkbox").check(); await page.getByRole("button", { name: "Confirm & create valid rows" }).click();
    await expect(page.getByRole("region", { name: "Safe CSV imports" })).toContainText("may have saved rows"); expect(state.executions).toHaveLength(1);
    await page.reload(); await page.getByRole("button", { name: "Settings", exact: true }).click(); await page.getByRole("button", { name: "Safe CSV imports", exact: true }).click();
    await expect(page.getByLabel("Recovery job ID")).toHaveValue(jobId); await page.getByRole("button", { name: "Load existing job" }).click();
    await page.getByRole("button", { name: "Safely resume this job" }).click(); await expect(page.getByRole("region", { name: "Import job results" })).toContainText("COMPLETED");
    expect(state.executions).toHaveLength(2); expect(state.executions[1]).toEqual(state.executions[0]);
});

test("file/policy changes clear an old preview and prevent late validation from replacing the current selection", async ({ page }) => {
    const state = await fixture(page); await imports(page); await upload(page);
    await page.getByLabel("Existing record policy").selectOption("FAIL"); await expect(page.getByRole("region", { name: "Import job results" })).toHaveCount(0);
    state.delayPreview = true; await page.getByRole("button", { name: "Validate & preview CSV" }).click();
    await page.getByLabel("CSV UTF-8 file").setInputFiles({ name: "new.csv", mimeType: "text/csv", buffer: Buffer.from("code,name\nNEW,New department") });
    await expect(page.getByText(/new.csv · 1 data rows ready/)).toBeVisible();
    await page.waitForTimeout(800); await expect(page.getByRole("region", { name: "Import job results" })).toHaveCount(0); expect(state.executions).toHaveLength(0);
});

test("an in-flight old RUNNING poll cannot replace COMPLETED execution results", async ({ page }) => {
    const state = await fixture(page); state.job = makeJob("DEPARTMENTS", 3); state.partialExecution = true; await imports(page); await upload(page);
    await page.getByRole("checkbox").check(); await page.getByRole("button", { name: "Confirm & create valid rows" }).click();
    await expect(page.getByRole("region", { name: "Import job results" })).toContainText("RUNNING");
    state.jobReadDelay = 1_300;
    await expect.poll(() => state.jobReads).toBeGreaterThan(0);
    state.partialExecution = false; await page.getByRole("button", { name: "Safely resume this job" }).click();
    await expect(page.getByRole("region", { name: "Import job results" })).toContainText("COMPLETED");
    await page.waitForTimeout(1_500);
    await expect(page.getByRole("region", { name: "Import job results" })).toContainText("COMPLETED");
    await expect(page.getByRole("button", { name: "Safely resume this job" })).toHaveCount(0);
});

test("invalid CSV focuses linked errors; server permission loss removes loaded sensitive records", async ({ page }) => {
    const state = await fixture(page); await imports(page);
    await page.getByLabel("CSV UTF-8 file").setInputFiles({ name: "malformed.csv", mimeType: "text/csv", buffer: Buffer.from('code,name\nENG,"open') });
    const summary = page.getByRole("region", { name: "Safe CSV imports" }).getByRole("alert"); await expect(summary).toContainText("not closed"); await expect(summary).toBeFocused();
    await summary.getByRole("link").click(); await expect(page.getByLabel("CSV UTF-8 file")).toBeFocused(); expect(state.previewCalls).toBe(0);
    await upload(page); await expect(page.getByText("Department 1", { exact: true })).toBeVisible();
    state.status = 403; await page.getByRole("button", { name: "Reload job status" }).click();
    await expect(summary).toContainText("permission was removed"); await expect(page.getByRole("region", { name: "Import job results" })).toHaveCount(0); await expect(page.getByLabel("CSV UTF-8 file")).toHaveCount(0);
    state.status = 200; state.options = []; await page.getByRole("button", { name: "Refresh import access" }).click(); await expect(page.getByRole("region", { name: "Safe CSV imports" })).toContainText("No CSV import types");
});

test("auth-change clears job contents and relevant employee/visitor entries honor allowed server kinds", async ({ page }) => {
    await fixture(page, "ROLE_HR_ADMIN"); await page.goto("/"); await page.getByRole("button", { name: "Employees", exact: true }).click();
    await page.getByRole("button", { name: "Import employee profiles" }).click(); await expect(page.getByLabel("Import type")).toHaveValue("EMPLOYEES");
    await upload(page, "firstName,lastName,officialEmail,phoneNumber,departmentCode,designation,joiningDate\nOne,Staff,one@example.invalid,1234567890,ENG,Engineer,2026-10-03\nTwo,Staff,two@example.invalid,1234567891,ENG,Engineer,2026-10-03");
    await page.evaluate(() => window.dispatchEvent(new CustomEvent("brainserve:auth-session-changed")));
    await expect(page.getByRole("region", { name: "Import job results" })).toHaveCount(0);
});

test("Reception pending-visit imports and paged results fit 360, 768 and 1440 widths with keyboard access", async ({ page }, testInfo) => {
    const state = await fixture(page, "ROLE_RECEPTIONIST"); state.job = makeJob("VISITORS"); await page.goto("/");
    await page.getByRole("button", { name: "Visitors", exact: true }).click(); await page.getByRole("button", { name: "Import pending visits" }).click();
    await expect(page.getByLabel("Import type")).toHaveValue("VISITORS"); await expect(page.getByRole("region", { name: "Safe CSV imports" })).toContainText("Normal routing, approvals and check-in are still required");
    await upload(page, "visitorName,type\nOne,OTHER\nTwo,OTHER");
    for (const width of [360, 768, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        await page.getByRole("checkbox").focus(); await expect(page.getByRole("checkbox")).toBeFocused(); await page.keyboard.press("Space");
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.screenshot({ path: testInfo.outputPath(`sprint4-import-${width}.png`), fullPage: true });
    }
});

test("company setup fits phone and desktop widths and remains keyboard navigable", async ({ page }, testInfo) => {
    await fixture(page); await settings(page); await page.getByRole("button", { name: "Company setup", exact: true }).click();
    const setup = page.getByRole("region", { name: "Company setup" }); await expect(setup).toContainText("Runtime office timezone");
    for (const width of [360, 768, 1440]) {
        await page.setViewportSize({ width, height: 900 });
        expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
        const first = setup.getByRole("button", { name: /^1\. Company profile/ }); await first.focus(); await expect(first).toBeFocused();
        await page.keyboard.press("Tab"); await expect(setup.getByRole("button", { name: /^2\. Departments/ })).toBeFocused();
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.screenshot({ path: testInfo.outputPath(`sprint4-setup-${width}.png`), fullPage: true });
    }
});
