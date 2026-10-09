import { expect, test, type Page } from "@playwright/test";

const profile = { userId: "22222222-2222-4222-8222-222222222222", email: "review@brainserve.in",
    fullName: "Review User", employeeId: null, roles: ["ROLE_CEO"], permissions: [], forcePasswordChange: false };
const paged = { content: [], number: 0, size: 50, totalElements: 0, totalPages: 0, last: true };
async function fixture(page: Page, enrolled = true, restore = true) {
    const state = { verified: false, requests: [] as string[], codes: [] as string[] };
    if (restore) await page.addInitScript(() => {
        sessionStorage.setItem("brainserve.connect.access-token", "pending-access");
        sessionStorage.setItem("brainserve.connect.refresh-token", "pending-refresh");
    });
    await page.route("http://backend.invalid/api/v1/**", async (route) => {
        const path = new URL(route.request().url()).pathname; state.requests.push(path);
        if (path.endsWith("/auth/login")) return route.fulfill({ json: { accessToken: "pending-access", refreshToken: "pending-refresh", forcePasswordChange: false, mfaRequired: true, mfaEnrolled: enrolled } });
        if (path.endsWith("/auth/me") || path.endsWith("/profile/me")) return route.fulfill({ json: profile });
        if (path.endsWith("/auth/security")) return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: enrolled, mfaVerified: state.verified, stepUpRequired: !state.verified, recoveryCodesRemaining: 10 } });
        if (path.endsWith("/auth/mfa/enrollment")) return route.fulfill({ json: { secret: "JBSWY3DPEHPK3PXP", otpauthUri: "otpauth://totp/BrainServe:test?secret=JBSWY3DPEHPK3PXP&issuer=BrainServe" } });
        if (path.endsWith("/auth/mfa/verify") || path.endsWith("/auth/mfa/enrollment/confirm")) {
            const code = route.request().postDataJSON().code as string; state.codes.push(code);
            if (code !== "654321" && code !== "01234567-89ABCDEF-01234567-89ABCDEF") return route.fulfill({ status: 400, json: { detail: "The security code is invalid or already used." } });
            state.verified = true;
            return route.fulfill({ json: { tokens: { accessToken: "verified-access", refreshToken: "verified-refresh", forcePasswordChange: false }, recoveryCodes: enrolled ? [] : ["01234567-89ABCDEF-01234567-89ABCDEF", "11111111-22222222-33333333-44444444"] } });
        }
        if (path.endsWith("/auth/logout") || path.endsWith("/realtime/stream")) return route.fulfill({ status: 204 });
        if (path.endsWith("/public/company-profile")) return route.fulfill({ json: { name: "BrainServe", emailDomain: "brainserve.in" } });
        if (path.endsWith("/dashboard/summary")) return route.fulfill({ json: { scope: "COMPANY", awaitingApproval: 0, activeVisits: 0, visitorsInside: 0, arrivedVisits: 0, totalEmployees: 0, activeEmployees: 0 } });
        if (path.endsWith("/employees") || path.endsWith("/appointments")) return route.fulfill({ json: paged });
        if (path.includes("unread")) return route.fulfill({ json: { unreadCount: 0, count: 0 } });
        return route.fulfill({ json: [] });
    });
    return state;
}
const navigation = (page: Page) => page.getByRole("navigation", { name: "Role workspace" });

test("restored privileged session waits for MFA and retries an invalid code", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    await expect(page.getByRole("heading", { name: "Verify your identity" })).toBeVisible();
    await expect(navigation(page)).toHaveCount(0);
    expect(state.requests.some((path) => path.endsWith("/dashboard/summary"))).toBe(false);
    await page.getByLabel("Authenticator code", { exact: true }).fill("000000");
    await page.getByRole("button", { name: "Verify and continue" }).click();
    await expect(page.getByRole("alert")).toContainText("invalid");
    await page.getByLabel("Authenticator code", { exact: true }).fill("654321");
    await page.getByRole("button", { name: "Verify and continue" }).click();
    await expect(navigation(page)).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.access-token"))).toBe("verified-access");
});

test("mobile enrollment shows recovery codes once and requires acknowledgement", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    const state = await fixture(page, false); await page.goto("/");
    await page.getByRole("button", { name: "Set up authenticator", exact: true }).click();
    await expect(page.getByLabel("Authenticator setup key")).toHaveValue("JBSWY3DPEHPK3PXP");
    await page.getByLabel("Authenticator code", { exact: true }).fill("654321");
    await page.getByRole("button", { name: "Verify and continue" }).click();
    await expect(page.getByRole("heading", { name: "Save your recovery codes" })).toBeVisible();
    await expect(navigation(page)).toHaveCount(0);
    expect(state.requests.some((path) => path.endsWith("/dashboard/summary"))).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await page.evaluate(() => Object.keys(sessionStorage).some((key) => /secret|recovery/i.test(key)))).toBe(false);
    await page.getByRole("button", { name: "I have saved my codes" }).click();
    await page.getByRole("button", { name: "Open navigation", exact: true }).click();
    await expect(navigation(page)).toBeVisible();
    await expect(page.getByText("01234567-89ABCDEF-01234567-89ABCDEF", { exact: true })).toHaveCount(0);
});

test("fresh login supports pasted recovery codes before workspace data loads", async ({ page }) => {
    const state = await fixture(page, true, false); await page.goto("/");
    await page.getByRole("button", { name: "Staff login" }).click();
    await page.getByLabel("Login email").fill(profile.email);
    await page.getByLabel("Password", { exact: true }).fill("test-password");
    await page.getByRole("button", { name: "Sign in securely" }).click();
    await expect(page.getByRole("heading", { name: "Verify your identity" })).toBeVisible();
    expect(state.requests.some((path) => path.endsWith("/dashboard/summary"))).toBe(false);
    await page.getByRole("button", { name: "Use a recovery code" }).click();
    await page.getByLabel("Recovery code", { exact: true }).fill("01234567-89ABCDEF-01234567-89ABCDEF");
    await page.getByRole("button", { name: "Verify and continue" }).click();
    await expect(navigation(page)).toBeVisible();
    expect(state.codes).toEqual(["01234567-89ABCDEF-01234567-89ABCDEF"]);
});

test("cancelling verification clears credentials without loading the dashboard", async ({ page }) => {
    const state = await fixture(page); await page.goto("/");
    await page.getByRole("button", { name: "Cancel verification" }).click();
    await expect(page.getByRole("button", { name: "Sign in securely" })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem("brainserve.connect.refresh-token"))).toBeNull();
    expect(state.requests.some((path) => path.endsWith("/dashboard/summary"))).toBe(false);
});
