import { test, expect, type Page } from '@playwright/test';
import type { Connection, Delivery } from '../features/integrations/types';
import type { DiagnosticPackage, DiagnosticPreview } from '../features/support/types';

const adminId = '11111111-1111-4111-8111-111111111111', connectionId = '22222222-2222-4222-8222-222222222222', deliveryId = '33333333-3333-4333-8333-333333333333';
const packageId = '44444444-4444-4444-8444-444444444444', eventId = '55555555-5555-4555-8555-555555555555';
const now = new Date().toISOString(), future = new Date(Date.now() + 7 * 86400000).toISOString();
const windowStart = new Date(Date.now() - 24 * 3600000).toISOString(), packageExpiry = new Date(Date.now() + 24 * 3600000).toISOString();
const snapshot: DiagnosticPreview = { schemaVersion: 1, supportReference: eventId, generatedAt: now, windowStart, windowEnd: now, environment: 'TEST', releaseVersion: '2.0.0', buildRevision: 'UNKNOWN', databaseStatus: 'AVAILABLE', migrationVersion: 66, connections: { active: 1, needsReconnect: 0, revoked: 0 }, deliveries: { pending: 0, running: 0, delivered: 0, failed: 1, needsReconnect: 0, cancelled: 0, superseded: 0 } };
const connection: Connection = { id: connectionId, provider: 'SIMULATOR_CALENDAR', kind: 'CALENDAR', label: 'Office calendar', ownerId: adminId, minimumScopes: ['calendar.events.write'], status: 'ACTIVE', credentialVersion: 1, credentialExpiresAt: future, version: 4, lastCheckedAt: now, lastResultCode: 'SUCCESS', createdAt: now, updatedAt: now };
const delivery: Delivery = { id: deliveryId, connectionId, businessEventId: eventId, eventType: 'APPOINTMENT_UPDATED', resourceId: eventId, businessRevision: 3, status: 'FAILED', attempts: 3, totalAttempts: 3, manualRetries: 0, nextAttemptAt: null, lastResultCode: 'OUTAGE', version: 7, createdAt: now, deliveredAt: null };
const metadata: DiagnosticPackage = { id: packageId, createdAt: now, expiresAt: packageExpiry, sizeBytes: 512, downloadCount: 0, windowStart, windowEnd: now };

async function fixture(page: Page, role = 'SYSTEM_ADMIN') {
    const state = { connections: [{ ...connection }], deliveries: [{ ...delivery }], packages: [] as DiagnosticPackage[], writes: [] as { path: string; body: Record<string, unknown> }[], reads: [] as string[], conflict: false, unknown: false, delay: 0, readFailure: false, previewDelay: 0, downloadDelay: 0, moreDeliveries: false, previewSnapshot: { ...snapshot } };
    const profile = { userId: adminId, employeeId: null, email: 'sprint11@example.invalid', fullName: 'System Administrator', roles: [`ROLE_${role}`], permissions: [], forcePasswordChange: false, departmentId: null, photoUrl: null };
    const emptyPage = { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0, last: true };
    await page.addInitScript(() => { sessionStorage.setItem('brainserve.connect.access-token', 'sprint11-access'); sessionStorage.setItem('brainserve.connect.refresh-token', 'sprint11-refresh'); });
    await page.route('http://backend.invalid/api/v1/**', async route => {
        const url = new URL(route.request().url()), path = url.pathname.replace('/api/v1', ''), method = route.request().method();
        if (path.startsWith('/integrations') || path.startsWith('/support/diagnostics')) {
            if (method === 'GET') {
                state.reads.push(path + url.search);
                if (state.readFailure) return route.fulfill({ status: 503, json: { detail: 'Source unavailable' } });
                if (path === '/integrations/connections') return route.fulfill({ json: state.connections });
                if (path === '/integrations/google-calendar/config') return route.fulfill({ json: { configured: false, scope: 'https://www.googleapis.com/auth/calendar.app.created', usesDedicatedCalendar: true } });
                if (path === '/integrations/slack/config') return route.fulfill({ json: { configured: false, scope: 'chat:write', usesDedicatedBot: true } });
                if (path.endsWith('/deliveries')) return route.fulfill({ json: { content: state.deliveries, number: Number(url.searchParams.get('page')), size: 20, totalElements: state.moreDeliveries ? 21 : state.deliveries.length, totalPages: state.moreDeliveries ? 2 : 1 } });
                if (path.endsWith('/attempts')) return route.fulfill({ json: [{ id: eventId, deliveryId, attemptNumber: 1, credentialVersion: 1, outcome: 'OUTAGE', startedAt: now, completedAt: now }] });
                if (path.endsWith('/preview')) { if (state.previewDelay) await new Promise(resolve => setTimeout(resolve, state.previewDelay)); return route.fulfill({ json: state.previewSnapshot }); }
                if (path.endsWith('/download')) { if (state.downloadDelay) await new Promise(resolve => setTimeout(resolve, state.downloadDelay)); return route.fulfill({ json: state.previewSnapshot }); }
                if (path === '/support/diagnostics') return route.fulfill({ json: state.packages });
            }
            const body = route.request().postDataJSON(); state.writes.push({ path, body });
            if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
            if (state.conflict) return route.fulfill({ status: 409, json: { detail: 'Observed version changed' } });
            if (path === '/integrations/connections') state.connections.push({ ...connection, id: eventId, label: body.label, provider: body.provider, credentialExpiresAt: body.credentialExpiresAt, version: 0 });
            if (path.endsWith('/reconnect')) state.connections[0] = { ...state.connections[0], status: 'ACTIVE', credentialVersion: 2, version: 5, credentialExpiresAt: body.credentialExpiresAt };
            if (path.endsWith('/revoke')) { state.connections[0] = { ...state.connections[0], status: 'REVOKED', version: 5 }; state.deliveries = [{ ...state.deliveries[0], status: 'CANCELLED' }]; }
            if (path.endsWith('/retry')) state.deliveries = [{ ...state.deliveries[0], status: 'PENDING', version: 8, manualRetries: 1 }];
            if (path === '/support/diagnostics') state.packages = [{ ...metadata }];
            if (state.unknown) return route.fulfill({ status: 503, json: { detail: 'Response unavailable' } });
            return route.fulfill({ json: path === '/support/diagnostics' ? metadata : state.connections[0] });
        }
        if (['/auth/me', '/profile/me'].includes(path)) return route.fulfill({ json: profile });
        if (path === '/auth/security') return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: true, mfaVerified: true, stepUpRequired: false } });
        if (path === '/dashboard/summary') return route.fulfill({ json: { awaitingApproval: 0, activeVisits: 0, totalEmployees: 0, activeEmployees: 0, scope: 'COMPANY', departmentId: null } });
        if (path === '/dashboard/cards') return route.fulfill({ status: 503, json: { detail: 'Dashboard unavailable' } });
        if (['/appointments', '/employees', '/admin/staff-accounts'].includes(path)) return route.fulfill({ json: emptyPage });
        if (path.includes('unread')) return route.fulfill({ json: { unreadCount: 0 } });
        if (path === '/realtime/stream') return route.fulfill({ status: 204 });
        return route.fulfill({ json: [] });
    });
    await page.goto('/'); await expect(page.getByRole('navigation', { name: 'Role workspace', includeHidden: true })).toBeAttached(); return state;
}
async function navigate(page: Page, name: 'Integrations' | 'Support diagnostics') {
    const menu = page.getByRole('button', { name: 'Open navigation', exact: true }); if (await menu.isVisible()) await menu.click();
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name, exact: true }).click();
    const panel = page.getByRole('region', { name, exact: true }); await expect(panel.getByRole('heading', { name, exact: true })).toBeVisible(); return panel;
}
const localFuture = () => { const value = new Date(Date.now() + 3 * 86400000); return new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };

for (const width of [360, 768, 1440]) test(`integrations and preview-first diagnostics fit ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 }); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message)); const state = await fixture(page);
    const integrations = await navigate(page, 'Integrations'); await expect(integrations).toContainText('Office calendar');
    await integrations.getByRole('button', { name: 'Load delivery attempts', exact: true }).click(); await expect(integrations).toContainText('Attempt 1: OUTAGE');
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({ path: testInfo.outputPath(`sprint11-integrations-${width}.png`) });
    const support = await navigate(page, 'Support diagnostics'); await expect(support.getByRole('button', { name: 'Generate diagnostic package' })).toBeDisabled();
    await support.getByRole('button', { name: 'Preview included fields' }).click(); await expect(support.getByRole('region', { name: 'Diagnostic fields' })).toContainText('deliveries.failed');
    await support.getByLabel('I reviewed the included fields for this time window.').check(); await support.getByRole('button', { name: 'Generate diagnostic package' }).click(); await expect(support).toContainText('Diagnostic package generated');
    const download = page.waitForEvent('download'); await support.getByRole('button', { name: 'Download diagnostic package' }).click(); expect((await download).suggestedFilename()).toBe(`brainserve-diagnostics-${packageId}.json`);
    expect(state.writes.filter(item => item.path === '/support/diagnostics')).toHaveLength(1); await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({ path: testInfo.outputPath(`sprint11-support-${width}.png`) }); expect(errors).toEqual([]);
});

test('credential submits clear inputs immediately, never persist secrets, and cannot duplicate pending writes', async ({ page }) => {
    const state = await fixture(page); state.delay = 450; const panel = await navigate(page, 'Integrations');
    await panel.locator('summary').filter({ hasText: 'Add a simulator connection' }).click(); await panel.getByLabel('Connection label', { exact: true }).fill('Calendar connection'); await panel.getByLabel('Credential expiry', { exact: true }).fill(localFuture());
    await panel.getByLabel('Connection credential', { exact: true }).fill('unique-private-credential'); const submit = panel.getByRole('button', { name: 'Create connection', exact: true });
    await submit.click(); await expect(panel.getByLabel('Connection credential', { exact: true })).toHaveValue(''); await expect(submit).toBeDisabled(); expect(state.writes).toHaveLength(1);
    expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain('unique-private-credential'); await expect(panel).not.toContainText('unique-private-credential'); await expect(panel).toContainText('Connection creation accepted'); expect(state.writes).toHaveLength(1);
});

test('unconfirmed writes require reload, preserve nonsecret context, and never replay creation', async ({ page }) => {
    const state = await fixture(page); state.unknown = true; const panel = await navigate(page, 'Integrations');
    await panel.locator('summary').filter({ hasText: 'Add a simulator connection' }).click(); await panel.getByLabel('Connection label', { exact: true }).fill('Retained calendar choice'); await panel.getByLabel('Credential expiry', { exact: true }).fill(localFuture()); await panel.getByLabel('Connection credential', { exact: true }).fill('transient-private-credential');
    await panel.getByRole('button', { name: 'Create connection', exact: true }).click(); await expect(panel.getByRole('alert')).toContainText('not confirmed'); await expect(panel.getByRole('alert')).toBeFocused(); await expect(panel.getByLabel('Connection label', { exact: true })).toHaveValue('Retained calendar choice'); await expect(panel.getByRole('button', { name: 'Create connection' })).toBeDisabled();
    state.unknown = false; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Create connection' })).toBeEnabled(); await expect(panel).toContainText('Retained calendar choice'); expect(state.writes).toHaveLength(1);
});

test('retry, reconnect and revoke use explicit observed-version actions', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page, 'Integrations');
    await panel.getByRole('button', { name: 'Retry failed delivery' }).click(); await expect(panel).toContainText('Delivery retry accepted'); expect(state.writes[0].body.expectedVersion).toBe(7); await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Retry failed delivery' })).toBeDisabled();
    await panel.locator('summary').filter({ hasText: 'Replace credential' }).click(); await panel.getByLabel('Replacement credential', { exact: true }).fill('replacement-private-credential'); await panel.getByLabel('Replacement expiry', { exact: true }).fill(localFuture()); await panel.getByRole('button', { name: 'Reconnect connection' }).click(); await expect(panel.getByLabel('Replacement credential', { exact: true })).toHaveValue(''); await expect(panel).toContainText('Replacement credential accepted'); expect(state.writes[1].body.expectedVersion).toBe(4); await panel.getByRole('button', { name: 'Reload connections' }).click();
    await panel.locator('summary').filter({ hasText: 'Revoke connection' }).click(); const revoke = panel.getByRole('button', { name: 'Confirm connection revocation' }); await expect(revoke).toBeDisabled(); await panel.getByLabel('I confirm revoking this connection.').check(); await revoke.click(); await expect(panel).toContainText('Revocation accepted'); expect(state.writes[2].body.expectedVersion).toBe(5); await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('cancelled'); expect(state.writes).toHaveLength(3);
});

test('connection conflicts and expired eligibility block tests until verified reload', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page, 'Integrations'); state.conflict = true;
    await panel.locator('summary').filter({ hasText: 'Test delivery behaviour' }).click(); await panel.getByRole('combobox', { name: 'Simulator scenario' }).selectOption('RATE_LIMITED'); await panel.getByRole('button', { name: 'Run simulator test' }).click(); await expect(panel.getByRole('alert')).toContainText('another session'); await expect(panel.getByRole('button', { name: 'Run simulator test' })).toBeDisabled(); expect(state.writes[0].body).toMatchObject({ expectedVersion: 4, scenario: 'RATE_LIMITED' });
    state.conflict = false; state.connections[0].credentialExpiresAt = '2020-01-01T00:00:00Z'; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('credential expired'); await expect(panel.getByRole('button', { name: 'Run simulator test' })).toBeDisabled(); expect(state.writes).toHaveLength(1);
});

test('all five simulator scenarios are deliberate and versioned', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page, 'Integrations'); await panel.locator('summary').filter({ hasText: 'Test delivery behaviour' }).click();
    for (const scenario of ['SUCCESS', 'OUTAGE', 'RATE_LIMITED', 'REAUTH_REQUIRED', 'PERMANENT_FAILURE']) { await panel.getByRole('combobox', { name: 'Simulator scenario' }).selectOption(scenario); await panel.getByRole('button', { name: 'Run simulator test' }).click(); await expect(panel).toContainText('Simulator test accepted'); await panel.getByRole('button', { name: 'Reload connections' }).click(); }
    expect(state.writes.map(item => item.body.scenario)).toEqual(['SUCCESS', 'OUTAGE', 'RATE_LIMITED', 'REAUTH_REQUIRED', 'PERMANENT_FAILURE']); expect(new Set(state.writes.map(item => item.body.requestId)).size).toBe(5);
});

test('rapid simulator test clicks submit only one pending mutation', async ({ page }) => {
    const state = await fixture(page); state.delay = 450; const panel = await navigate(page, 'Integrations'); await panel.locator('summary').filter({ hasText: 'Test delivery behaviour' }).click();
    await panel.getByRole('button', { name: 'Run simulator test' }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
    await expect(panel.getByRole('button', { name: 'Run simulator test' })).toBeDisabled(); await expect(panel).toContainText('Simulator test accepted'); expect(state.writes).toHaveLength(1);
});

test('session changes clear credential inputs and block late mutation completions', async ({ page }) => {
    const state = await fixture(page); state.delay = 500; const panel = await navigate(page, 'Integrations'); await panel.locator('summary').filter({ hasText: 'Replace credential' }).click(); await panel.getByLabel('Replacement credential', { exact: true }).fill('pending-private-credential'); await panel.getByLabel('Replacement expiry', { exact: true }).fill(localFuture()); await panel.getByRole('button', { name: 'Reconnect connection' }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed')));
    await expect(panel).toContainText('account changed'); await expect(panel.getByLabel('Replacement credential', { exact: true })).toHaveCount(0); await expect(panel).not.toContainText('Office calendar'); await page.waitForTimeout(600); await expect(panel).not.toContainText('Replacement credential accepted');
});

test('uncertain package generation and preview changes require deliberate fresh review', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page, 'Support diagnostics'); await panel.getByRole('button', { name: 'Preview included fields' }).click(); await panel.getByLabel('I reviewed the included fields for this time window.').check(); await panel.getByRole('combobox', { name: 'Diagnostic time window' }).selectOption('6'); await expect(panel.getByRole('button', { name: 'Generate diagnostic package' })).toBeDisabled();
    await panel.getByRole('button', { name: 'Preview included fields' }).click(); await panel.getByLabel('I reviewed the included fields for this time window.').check(); state.unknown = true; await panel.getByRole('button', { name: 'Generate diagnostic package' }).click(); await expect(panel.getByRole('alert')).toContainText('not confirmed'); await expect(panel.getByRole('button', { name: 'Generate diagnostic package' })).toBeDisabled(); state.unknown = false; await panel.getByRole('button', { name: 'Reload packages' }).click(); await expect(panel.getByRole('button', { name: 'Download diagnostic package' })).toBeVisible(); await expect(panel.getByRole('button', { name: 'Generate diagnostic package' })).toBeDisabled(); expect(state.writes).toHaveLength(1); expect(state.writes[0].body.hours).toBe(6);
});

test('session expiry cancels diagnostic previews and authenticated downloads', async ({ page }) => {
    const state = await fixture(page); state.packages = [{ ...metadata }]; const panel = await navigate(page, 'Support diagnostics'); state.downloadDelay = 500; const downloads: string[] = []; page.on('download', item => downloads.push(item.suggestedFilename()));
    await panel.getByRole('button', { name: 'Download diagnostic package' }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-expired'))); await expect(panel).toHaveCount(0); await page.waitForTimeout(600); expect(downloads).toEqual([]); await expect(page.getByText(packageId, { exact: true })).toHaveCount(0);
});

test('late diagnostics previews cannot display after an account switch', async ({ page }) => {
    const state = await fixture(page); state.previewDelay = 450; const panel = await navigate(page, 'Support diagnostics'); await panel.getByRole('button', { name: 'Preview included fields' }).click();
    await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed'))); await expect(panel).toContainText('account changed'); await page.waitForTimeout(550); await expect(panel.getByRole('region', { name: 'Diagnostic fields' })).toHaveCount(0); await expect(panel).not.toContainText(eventId);
});

test('delivery pagination and unavailable sources remain distinct from empty sources', async ({ page }) => {
    const state = await fixture(page); state.moreDeliveries = true; const panel = await navigate(page, 'Integrations'); await panel.getByRole('button', { name: 'Next deliveries page' }).click(); await expect(panel).toContainText('Page 2 of 2'); expect(state.reads.at(-1)).toContain('page=1&size=20'); state.readFailure = true; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('alert')).toContainText('could not be verified'); await expect(panel).not.toContainText('No connections available'); state.readFailure = false; state.connections = []; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('No connections available');
});

test('non-admin navigation excludes integration and support routes', async ({ page }) => {
    const state = await fixture(page, 'CEO'); const nav = page.getByRole('navigation', { name: 'Role workspace' }); await expect(nav.getByRole('button', { name: 'Integrations', exact: true })).toHaveCount(0); await expect(nav.getByRole('button', { name: 'Support diagnostics', exact: true })).toHaveCount(0); expect(state.reads).toEqual([]);
});
