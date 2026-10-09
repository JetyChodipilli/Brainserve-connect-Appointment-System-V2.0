import { test, expect, type Page, type Locator } from '@playwright/test';
import { createRequire } from 'node:module';
import type { ReleaseSnapshot } from '../features/release/types';

const require = createRequire(import.meta.url);
const initial: ReleaseSnapshot = { version: 0, profile: { status: 'UNCONFIGURED', reference: '', startsOn: null, renewsOn: null, supportOwner: '', supportEmail: '', supportHours: '' }, officeZone: 'Asia/Kolkata', officeDate: '2026-10-09', renewalDue: false };
async function fixture(page: Page, role = 'SYSTEM_ADMIN') {
    const state = { record: structuredClone(initial), reads: 0, writes: [] as { expectedVersion: number; profile: ReleaseSnapshot['profile'] }[], delay: 0, readDelay: 0, readFailure: false, conflict: false, unknown: false, invalid: false, badVersion: false, unknownFeature: false };
    const profile = { userId: '11111111-1111-4111-8111-111111111111', employeeId: null, email: 'admin@sprint16.invalid', fullName: 'Synthetic admin', roles: [`ROLE_${role}`], permissions: [], forcePasswordChange: false, departmentId: null, photoUrl: null };
    const empty = { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0, last: true };
    await page.addInitScript(() => { sessionStorage.setItem('brainserve.connect.access-token', 'sprint16-access'); sessionStorage.setItem('brainserve.connect.refresh-token', 'sprint16-refresh'); });
    await page.route('http://backend.invalid/api/v1/**', async route => {
        const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
        if (path === '/release-profile') {
            if (route.request().method() === 'GET') {
                state.reads++; if (state.readDelay) await new Promise(resolve => setTimeout(resolve, state.readDelay));
                if (state.readFailure) return route.fulfill({ status: 503, json: { detail: 'Unavailable' } });
                return route.fulfill({ json: { ...state.record, version: state.badVersion ? '0' : state.record.version } });
            }
            const body = route.request().postDataJSON(); state.writes.push(body);
            if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
            if (state.conflict) return route.fulfill({ status: 409, json: { errorCode: 'RELEASE_PROFILE_CHANGED' } });
            if (state.invalid) return route.fulfill({ status: 400, json: { errorCode: 'RELEASE_PROFILE_INVALID' } });
            state.record = { ...state.record, profile: body.profile, version: state.record.version + 1 };
            if (state.unknown) return route.fulfill({ status: 503, json: { detail: 'Response unavailable after save' } });
            return route.fulfill({ json: state.record });
        }
        if (path === '/integrations/google-calendar/config') return route.fulfill({ json: { configured: true } });
        if (path === '/integrations/slack/config') return route.fulfill({ json: { configured: state.unknownFeature ? 'yes' : false } });
        if (path === '/admin/kiosks/config') return state.unknownFeature ? route.fulfill({ status: 503, json: {} }) : route.fulfill({ json: { enabled: false } });
        if (['/auth/me', '/profile/me'].includes(path)) return route.fulfill({ json: profile });
        if (path === '/auth/security') return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: true, mfaVerified: true, stepUpRequired: false } });
        if (path === '/dashboard/summary') return route.fulfill({ json: { awaitingApproval: 0, activeVisits: 0, totalEmployees: 0, activeEmployees: 0, scope: 'COMPANY', departmentId: null } });
        if (path === '/dashboard/cards') return route.fulfill({ status: 503, json: { detail: 'Unavailable' } });
        if (['/appointments', '/employees', '/admin/staff-accounts'].includes(path)) return route.fulfill({ json: empty });
        if (path.includes('unread')) return route.fulfill({ json: { unreadCount: 0 } });
        if (path === '/realtime/stream') return route.fulfill({ status: 204 });
        return route.fulfill({ json: [] });
    });
    await page.goto('/'); await expect(page.getByRole('navigation', { name: 'Role workspace', includeHidden: true })).toBeAttached();
    return state;
}
async function navigate(page: Page) {
    const menu = page.getByRole('button', { name: 'Open navigation', exact: true }); if (await menu.isVisible()) await menu.click();
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Release and support', exact: true }).click();
    const panel = page.getByRole('region', { name: 'Release and support', exact: true });
    await expect(panel.getByRole('heading', { name: 'Release and support', exact: true })).toBeVisible(); return panel;
}
async function complete(panel: Locator) {
    await expect(panel.getByLabel('Agreement status')).toBeEnabled();
    await panel.getByLabel('Agreement status').selectOption('ACTIVE');
    await panel.getByLabel('Agreement reference').fill('Synthetic customer agreement');
    await panel.getByLabel('Term starts on').fill('2026-10-09'); await panel.getByLabel('Renewal date').fill('2027-10-09');
    await panel.getByLabel('Support owner', { exact: true }).fill('Synthetic support owner');
    await panel.getByLabel('Support contact email').fill('support@sprint16.invalid');
    await panel.getByLabel('Agreed support hours').fill('Mon–Fri 09:00–17:00 UTC');
}

for (const width of [375, 768, 1440]) test(`release record fits ${width}px with labelled keyboard inputs and automated accessibility checks`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 }); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
    await fixture(page); const panel = await navigate(page); await complete(panel);
    const reference = panel.getByLabel('Agreement reference'); await reference.focus(); await page.keyboard.press('End'); await page.keyboard.type(' revised'); await expect(reference).toBeFocused();
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    const violations = await panel.evaluate(async root => {
        const axe = (window as unknown as { axe: { run: (root: Element, options: object) => Promise<{ violations: { id: string }[] }> } }).axe;
        return (await axe.run(root, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } })).violations.map(item => item.id);
    });
    expect(violations).toEqual([]); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: testInfo.outputPath(`sprint16-release-${width}.png`), fullPage: true }); expect(errors).toEqual([]);
});
test('versioned agreement saves cannot duplicate pending requests or change configured features', async ({ page }) => {
    const state = await fixture(page); state.delay = 350; const panel = await navigate(page); await complete(panel);
    await panel.getByRole('button', { name: 'Save agreement and support' }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
    await expect(panel.getByRole('button', { name: 'Save agreement and support' })).toBeDisabled();
    await expect(panel).toContainText('Agreement and support record saved'); expect(state.writes).toHaveLength(1);
    expect(state.writes[0].expectedVersion).toBe(0); expect(Object.keys(state.writes[0]).sort()).toEqual(['expectedVersion', 'profile']);
    await expect(panel).toContainText('Configured on this service'); await expect(panel).toContainText('Recorded status: Active');
    expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain('support@sprint16.invalid');
});
test('native required fields and inverted dates prevent a write', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await expect(panel.getByLabel('Agreement status')).toBeEnabled();
    await panel.getByLabel('Agreement status').selectOption('ACTIVE'); await panel.getByRole('button', { name: 'Save agreement and support' }).click();
    await expect(panel.getByLabel('Agreement reference')).toBeFocused(); expect(state.writes).toHaveLength(0);
    await complete(panel); await panel.getByLabel('Renewal date').fill('2025-01-01'); await panel.getByRole('button', { name: 'Save agreement and support' }).click();
    await expect(panel.getByLabel('Renewal date')).toBeFocused(); expect(state.writes).toHaveLength(0);
});
for (const failure of ['conflict', 'unknown'] as const) test(`${failure} saves require reload and never replay automatically`, async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await complete(panel); state[failure] = true;
    await panel.getByRole('button', { name: 'Save agreement and support' }).click(); await expect(panel.getByRole('alert')).toBeFocused();
    await expect(panel.getByRole('button', { name: 'Save agreement and support' })).toBeDisabled(); expect(state.writes).toHaveLength(1);
    state[failure] = false;
    if (failure === 'conflict') state.record = { ...state.record, version: 7, profile: { ...state.writes[0].profile, reference: 'Other administrator record' } };
    await panel.getByRole('button', { name: 'Reload saved record' }).click(); await expect(panel.getByLabel('Agreement status')).toBeEnabled();
    await expect(panel.getByLabel('Agreement reference')).toHaveValue(failure === 'conflict' ? 'Other administrator record' : 'Synthetic customer agreement');
    expect(state.writes).toHaveLength(1);
});
test('a confirmed validation failure retains the draft for a deliberate correction', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await complete(panel); state.invalid = true;
    await panel.getByRole('button', { name: 'Save agreement and support' }).click(); await expect(panel.getByRole('alert')).toContainText('corrected record');
    await expect(panel.getByLabel('Agreement reference')).toHaveValue('Synthetic customer agreement'); await expect(panel.getByLabel('Agreement reference')).toBeEnabled();
    state.invalid = false; await panel.getByLabel('Agreement reference').fill('Corrected reference'); await panel.getByRole('button', { name: 'Save agreement and support' }).click();
    await expect(panel).toContainText('Agreement and support record saved'); expect(state.writes).toHaveLength(2); expect(state.writes[1].expectedVersion).toBe(0);
});
for (const failure of ['readFailure', 'badVersion'] as const) test(`${failure} never presents an editable invented record`, async ({ page }) => {
    const state = await fixture(page); state[failure] = true; const panel = await navigate(page);
    await expect(panel.getByRole('alert')).toContainText('could not be verified'); await expect(panel.getByLabel('Agreement reference')).toHaveCount(0); expect(state.writes).toHaveLength(0);
    state[failure] = false; await panel.getByRole('button', { name: 'Reload saved record' }).click(); await expect(panel.getByLabel('Agreement reference')).toBeEnabled();
});
test('unavailable and malformed feature configuration is reported as unverified', async ({ page }) => {
    const state = await fixture(page); state.unknownFeature = true; const panel = await navigate(page);
    await expect(panel.locator('dl')).toContainText('Google Calendar'); await expect(panel.locator('dd').filter({ hasText: 'Could not verify' })).toHaveCount(2);
});
for (const operation of ['read', 'save'] as const) test(`account changes clear private records and fence late ${operation} responses`, async ({ page }) => {
    const state = await fixture(page); if (operation === 'read') { state.readDelay = 400; state.record.profile.reference = 'Private prior account record'; }
    const panel = await navigate(page);
    if (operation === 'save') { await complete(panel); state.delay = 400; await panel.getByRole('button', { name: 'Save agreement and support' }).click(); }
    await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed')));
    await expect(panel).toContainText('account changed'); await expect(panel.getByLabel('Agreement reference')).toHaveCount(0);
    await page.waitForTimeout(500); await expect(panel).not.toContainText('record saved'); await expect(panel).not.toContainText('Private prior account record');
});
test('session expiry clears the release workspace while a save is pending', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await complete(panel); state.delay = 400;
    await panel.getByRole('button', { name: 'Save agreement and support' }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-expired')));
    await expect(panel).toHaveCount(0); await page.waitForTimeout(500); await expect(page.getByText('Synthetic support owner')).toHaveCount(0);
});
for (const role of ['CEO', 'HR_ADMIN', 'MANAGER', 'TEAM_LEAD', 'EMPLOYEE', 'SECURITY', 'RECEPTIONIST']) test(`${role} has no release navigation or private profile requests`, async ({ page }) => {
    const state = await fixture(page, role);
    await expect(page.getByRole('navigation', { name: 'Role workspace', includeHidden: true }).getByRole('button', { name: 'Release and support', exact: true })).toHaveCount(0);
    expect(state.reads).toBe(0);
});
