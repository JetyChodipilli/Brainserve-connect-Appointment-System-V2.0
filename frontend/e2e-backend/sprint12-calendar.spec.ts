import { test, expect, type Page } from '@playwright/test';
import type { CalendarReconciliation, Connection, GoogleConnectionMetadata, GoogleConsent } from '../features/integrations/types';

const adminId = '11111111-1111-4111-8111-111111111111', connectionId = '22222222-2222-4222-8222-222222222222', consentId = '33333333-3333-4333-8333-333333333333', jobId = '44444444-4444-4444-8444-444444444444';
const now = new Date().toISOString(), future = new Date(Date.now() + 7 * 86400000).toISOString();
const scope = 'https://www.googleapis.com/auth/calendar.app.created';
const ticket = 'private-browser-ticket-abcdefghijklmnopqrstuvwxyz';
const consentUrl = `http://backend.invalid/api/v1/integrations/google-calendar/authorize?ticket=${ticket}`;
const connection: Connection = { id: connectionId, provider: 'GOOGLE_CALENDAR', kind: 'CALENDAR', label: 'Office Google calendar', ownerId: adminId, minimumScopes: [scope], status: 'ACTIVE', credentialVersion: 1, credentialExpiresAt: future, version: 4, lastCheckedAt: now, lastResultCode: 'SUCCESS', createdAt: now, updatedAt: now };
const consent = (status: string): GoogleConsent => ({ id: consentId, connectionId, status, expiresAt: future, lastResultCode: status === 'DENIED' ? 'CONSENT_DENIED' : null });

// Transport stubs exercise the production connected UI. They do not represent live Google UAT.
async function fixture(page: Page) {
    const state = { configured: true, connections: [{ ...connection }], consents: [] as GoogleConsent[], metadata: { provisioningStatus: 'READY', revocationStatus: 'NONE', lastResultCode: null } as GoogleConnectionMetadata, reconciliation: null as CalendarReconciliation | null, writes: [] as { path: string; body: Record<string, unknown> }[], reads: [] as string[], delay: 0, downloadDelay: 0, unknown: false, conflict: false, configFailure: false, unsafeUrl: '', exportLimit: false };
    const profile = { userId: adminId, employeeId: null, email: 'sprint12@example.invalid', fullName: 'System Administrator', roles: ['ROLE_SYSTEM_ADMIN'], permissions: [], forcePasswordChange: false, departmentId: null, photoUrl: null };
    const emptyPage = { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0, last: true };
    await page.addInitScript(() => { sessionStorage.setItem('brainserve.connect.access-token', 'sprint12-access'); sessionStorage.setItem('brainserve.connect.refresh-token', 'sprint12-refresh'); });
    await page.context().route('http://backend.invalid/api/v1/**', async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname.replace('/api/v1', ''), method = request.method();
        if (path.endsWith('/authorize')) return route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Consent callback</title><p>Return to the original BrainServe tab.</p>' });
        if (path.startsWith('/integrations')) {
            expect(request.headers().authorization).toBe('Bearer sprint12-access');
            if (method === 'GET') {
                state.reads.push(path);
                if (path.endsWith('/config')) return state.configFailure ? route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } }) : route.fulfill({ json: { configured: state.configured, scope, usesDedicatedCalendar: true } });
                if (path === '/integrations/connections') return route.fulfill({ json: state.connections });
                if (path.endsWith('/consents')) return route.fulfill({ json: state.consents });
                if (path.endsWith('/deliveries')) return route.fulfill({ json: emptyPage });
                if (path.endsWith('/reconciliation')) return route.fulfill({ json: state.reconciliation });
                if (path.endsWith('/calendar.ics')) {
                    if (state.downloadDelay) await new Promise(resolve => setTimeout(resolve, state.downloadDelay));
                    if (state.exportLimit) return route.fulfill({ status: 409, json: { errorCode: 'CALENDAR_EXPORT_LIMIT' } });
                    return route.fulfill({ contentType: 'text/calendar', body: 'BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n' });
                }
                if (path.includes('/google-calendar/connections/')) return route.fulfill({ json: state.metadata });
            }
            const body = request.postDataJSON(); state.writes.push({ path, body });
            if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
            if (state.conflict) return route.fulfill({ status: 409, json: { errorCode: 'INTEGRATION_VERSION_CONFLICT' } });
            if (path === '/integrations/google-calendar/consents') {
                state.consents = [consent('INITIATED')];
                if (state.unknown) return route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } });
                return route.fulfill({ json: { ...state.consents[0], authorizationUrl: state.unsafeUrl || consentUrl } });
            }
            if (path.endsWith('/complete')) { state.consents = [consent('COMPLETED')]; state.connections[0] = { ...state.connections[0], status: 'ACTIVE', version: state.connections[0].version + 1 }; }
            if (path.endsWith('/recover')) { state.metadata = { ...state.metadata, provisioningStatus: 'READY', lastResultCode: 'SUCCESS' }; state.connections[0] = { ...state.connections[0], status: 'ACTIVE', version: state.connections[0].version + 1 }; }
            if (path.endsWith('/reconcile')) state.reconciliation = { id: jobId, status: 'QUEUED', processed: 0, createdAt: now, completedAt: null };
            if (path.endsWith('/revocation/retry')) state.metadata = { ...state.metadata, revocationStatus: 'PENDING' };
            if (path.endsWith('/revoke')) { state.connections[0] = { ...state.connections[0], status: 'REVOKED', version: state.connections[0].version + 1 }; state.metadata.revocationStatus = 'PENDING'; }
            if (state.unknown) return route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } });
            return route.fulfill({ json: path.endsWith('/reconcile') ? state.reconciliation : path.endsWith('/revocation/retry') ? state.metadata : state.connections[0] });
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
    await page.goto('/'); await expect(page.getByRole('navigation', { name: 'Role workspace', includeHidden: true })).toBeAttached();
    return state;
}

async function navigate(page: Page) {
    const menu = page.getByRole('button', { name: 'Open navigation', exact: true }); if (await menu.isVisible()) await menu.click();
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Integrations', exact: true }).click();
    const panel = page.getByRole('region', { name: 'Integrations', exact: true });
    await expect(panel).toContainText('Current connections and delivery versions loaded.'); return panel;
}

async function prepareConsent(panel: ReturnType<Page['getByRole']>) {
    await panel.getByLabel('Google Calendar connection label', { exact: true }).fill('Dedicated calendar');
    await panel.getByLabel('I approve granting access to a dedicated BrainServe calendar.').check();
}

for (const width of [360, 768, 1440]) test(`Google administration consent, recovery and export fit ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 }); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
    const state = await fixture(page); state.consents = [consent('CALLBACK_RECEIVED')]; const panel = await navigate(page);
    await expect(panel.getByRole('button', { name: 'Finish connection', exact: true })).toBeEnabled();
    await expect(panel.getByRole('button', { name: 'Run simulator test' })).toHaveCount(0); await expect(panel.getByLabel('Replacement credential')).toHaveCount(0);
    await panel.locator('summary').filter({ hasText: 'Reconnect Google Calendar' }).click();
    await panel.locator('summary').filter({ hasText: 'Reconcile Google calendar' }).click();
    await panel.locator('summary').filter({ hasText: 'Download calendar file' }).click();
    const download = page.waitForEvent('download'); await panel.getByRole('button', { name: 'Download calendar (.ics)', exact: true }).click(); expect((await download).suggestedFilename()).toBe('brainserve-calendar.ics');
    const controls = await panel.locator('button:visible, input:not([type=checkbox]):visible, a:visible, summary:visible').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().height)); expect(controls.every(height => height >= 44)).toBe(true);
    const checkTargets = await panel.locator('label:visible').evaluateAll(elements => elements.filter(element => element.querySelector('input[type=checkbox]')).map(element => element.getBoundingClientRect().height)); expect(checkTargets.every(height => height >= 44)).toBe(true);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`sprint12-calendar-${width}.png`) }); expect(errors).toEqual([]);
});

test('consent is deliberate, opens a guarded isolated tab, and requires original-tab finish after metadata reload', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); const start = panel.getByRole('button', { name: 'Start Google consent', exact: true });
    await expect(start).toBeDisabled(); await prepareConsent(panel); await start.click();
    const link = panel.getByRole('link', { name: 'Continue to Google (opens new tab)', exact: true });
    await expect(link).toHaveAttribute('target', '_blank'); await expect(link).toHaveAttribute('rel', 'noopener noreferrer'); await expect(link).toHaveAttribute('referrerpolicy', 'no-referrer');
    await expect(panel.getByRole('button', { name: 'Finish connection' })).toHaveCount(0);
    const popup = page.context().waitForEvent('page'); await link.click(); const consentTab = await popup; await consentTab.waitForLoadState();
    expect(await consentTab.evaluate(() => window.opener === null)).toBe(true); await consentTab.close(); await expect(link).toHaveCount(0);
    state.consents = [consent('CALLBACK_RECEIVED')]; await panel.getByRole('button', { name: 'Reload connections', exact: true }).click();
    await panel.getByRole('button', { name: 'Finish connection', exact: true }).click(); await expect(panel).toContainText('Connection completion accepted.');
    expect(state.writes.map(write => write.path)).toEqual(['/integrations/google-calendar/consents', `/integrations/google-calendar/consents/${consentId}/complete`]);
    const stored = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } })); expect(stored).not.toContain(ticket); expect(stored).not.toContain('authorizationUrl'); await expect(panel).not.toContainText(ticket);
});

test('rapid consent submits lock synchronously and same-account session change discards pending link', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await prepareConsent(panel); state.delay = 450;
    await panel.getByRole('button', { name: 'Start Google consent', exact: true }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
    await expect.poll(() => state.writes.length).toBe(1);
    await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed')));
    await expect(panel).toContainText('The account changed.'); await page.waitForTimeout(600);
    await expect(panel.getByRole('link', { name: 'Continue to Google (opens new tab)' })).toHaveCount(0); await expect(panel).not.toContainText('Consent request created.'); expect(state.writes).toHaveLength(1);
});

test('uncertain consent reloads metadata without replaying start or exposing the lost authorization link', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); state.unknown = true; await prepareConsent(panel);
    await panel.getByRole('button', { name: 'Start Google consent', exact: true }).click(); await expect(panel.getByRole('alert')).toContainText('Consent was not confirmed.'); await expect(panel.getByRole('alert')).toBeFocused();
    await expect(panel.getByRole('button', { name: 'Start Google consent', exact: true })).toBeDisabled(); await expect(panel.getByLabel('Google Calendar connection label')).toHaveValue('Dedicated calendar');
    state.unknown = false; state.consents = [consent('CALLBACK_RECEIVED')]; await panel.getByRole('button', { name: 'Reload connections', exact: true }).click();
    await expect(panel.getByRole('button', { name: 'Finish connection', exact: true })).toBeEnabled(); await expect(panel.getByRole('link')).toHaveCount(0); expect(state.writes).toHaveLength(1);
});

test('missing configuration, unavailable configuration and denied consent never suggest a connected calendar', async ({ page }) => {
    const state = await fixture(page); state.configured = false; state.connections = []; const panel = await navigate(page);
    await expect(panel).toContainText('Google Calendar is not configured on this service.'); await expect(panel.getByRole('button', { name: 'Start Google consent' })).toHaveCount(0);
    state.configured = true; state.configFailure = true; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Google Calendar availability could not be verified.');
    state.configFailure = false; state.consents = [consent('DENIED')]; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Permission was not granted.');
    await expect(panel.getByRole('button', { name: 'Finish connection' })).toHaveCount(0); expect(state.writes).toHaveLength(0);
});

test('unsafe backend consent addresses never become navigable links', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); state.unsafeUrl = 'https://evil.example.invalid/authorize?ticket=private-browser-ticket-abcdefghijklmnopqrstuvwxyz'; await prepareConsent(panel);
    await panel.getByRole('button', { name: 'Start Google consent' }).click(); await expect(panel.getByRole('alert')).toContainText('Consent was not confirmed.'); await expect(panel.getByRole('link')).toHaveCount(0); expect(state.writes).toHaveLength(1);
});

test('reconsent and provisioning recovery use fresh observed versions after uncertain writes', async ({ page }) => {
    const state = await fixture(page); state.connections[0].status = 'NEEDS_RECONNECT'; state.metadata.provisioningStatus = 'PROVISIONING_UNKNOWN'; state.metadata.lastResultCode = 'PROVISIONING_UNKNOWN'; const panel = await navigate(page);
    await panel.getByLabel('Google Calendar connection label').fill('A separate calendar'); await panel.locator('summary').filter({ hasText: 'Reconnect Google Calendar' }).click(); await panel.getByLabel('I approve renewing access to this BrainServe calendar.').check(); await expect(panel.getByRole('button', { name: 'Start Google consent', exact: true })).toBeDisabled(); await panel.getByRole('button', { name: 'Start Google reconsent' }).click();
    expect(state.writes[0].body).toMatchObject({ connectionId, expectedVersion: 4, label: connection.label });
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.getByLabel('Existing Google calendar ID').fill('https://calendar.google.com/calendar'); await expect(panel.getByRole('button', { name: 'Verify and recover calendar' })).toBeDisabled();
    await panel.getByLabel('Existing Google calendar ID').fill('existing@group.calendar.google.com'); state.unknown = true; await panel.getByRole('button', { name: 'Verify and recover calendar' }).click(); await expect(panel.getByRole('alert')).toContainText('not confirmed');
    expect(state.writes[1].body).toEqual({ expectedVersion: 4, calendarId: 'existing@group.calendar.google.com' }); await expect(panel.getByRole('button', { name: 'Verify and recover calendar' })).toBeDisabled();
    state.unknown = false; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('ready'); await expect(panel.getByLabel('Existing Google calendar ID')).toHaveCount(0);
    await panel.getByLabel('I approve renewing access to this BrainServe calendar.').check(); await panel.getByRole('button', { name: 'Start Google reconsent' }).click(); expect(state.writes[2].body.expectedVersion).toBe(5); expect(state.writes).toHaveLength(3);
});

test('finish completion is never replayed after an unknown exchange response', async ({ page }) => {
    const state = await fixture(page); state.consents = [consent('CALLBACK_RECEIVED')]; state.unknown = true; const panel = await navigate(page);
    await panel.getByRole('button', { name: 'Finish connection' }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); }); await expect(panel.getByRole('alert')).toContainText('not confirmed');
    state.unknown = false; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Finish connection' })).toHaveCount(0); expect(state.writes).toHaveLength(1);
});

test('reconciliation reload shows bounded progress, fences duplicate writes and reads fresh versions after conflict', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await panel.locator('summary').filter({ hasText: 'Reconcile Google calendar' }).click();
    state.delay = 250; await panel.getByRole('button', { name: 'Start calendar reconciliation' }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); }); await expect(panel).toContainText('Reconciliation accepted.'); expect(state.writes).toHaveLength(1); expect(state.writes[0].body.expectedVersion).toBe(4);
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Reconciliation queued'); await expect(panel.getByRole('button', { name: 'Start calendar reconciliation' })).toBeDisabled();
    state.reconciliation = { id: jobId, status: 'RUNNING', processed: 25, createdAt: now, completedAt: null }; await panel.getByRole('button', { name: 'Check reconciliation progress' }).click(); await expect(panel).toContainText('25 resources processed');
    state.reconciliation = { ...state.reconciliation, status: 'COMPLETED', processed: 35, completedAt: now }; state.connections[0].version = 8; state.conflict = true;
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.getByRole('button', { name: 'Start calendar reconciliation' }).click(); await expect(panel.getByRole('alert')).toContainText('another session'); expect(state.writes[1].body.expectedVersion).toBe(8);
    state.conflict = false; state.connections[0].version = 9; await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.getByRole('button', { name: 'Start calendar reconciliation' }).click(); await expect(panel).toContainText('Reconciliation accepted.'); expect(state.writes[2].body.expectedVersion).toBe(9); expect(new Set(state.writes.map(write => write.body.requestId)).size).toBe(3);
});

test('ICS fallback revokes object URLs, rejects overflow and cancels downloads on session expiry', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await panel.locator('summary').filter({ hasText: 'Download calendar file' }).click();
    await page.evaluate(() => {
        const calls = { created: 0, revoked: 0 }, create = URL.createObjectURL.bind(URL), revoke = URL.revokeObjectURL.bind(URL);
        (window as unknown as { blobCalls: typeof calls }).blobCalls = calls;
        URL.createObjectURL = blob => { calls.created++; return create(blob); }; URL.revokeObjectURL = url => { calls.revoked++; revoke(url); };
    });
    const download = page.waitForEvent('download'); await panel.getByRole('button', { name: 'Download calendar (.ics)' }).click(); await download;
    expect(await page.evaluate(() => (window as unknown as { blobCalls: unknown }).blobCalls)).toEqual({ created: 1, revoked: 1 });
    state.exportLimit = true; await panel.getByRole('button', { name: 'Download calendar (.ics)' }).click(); await expect(panel.getByRole('alert')).toContainText('No partial file was downloaded.');
    state.exportLimit = false; state.downloadDelay = 450; await panel.getByRole('button', { name: 'Download calendar (.ics)' }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-expired'))); await page.waitForTimeout(600);
    expect(await page.evaluate(() => (window as unknown as { blobCalls: unknown }).blobCalls)).toEqual({ created: 1, revoked: 1 }); await expect(panel).toHaveCount(0);
});

test('remote revocation recovery is explicit and versioned', async ({ page }) => {
    const state = await fixture(page); state.connections[0].status = 'REVOKED'; state.metadata.revocationStatus = 'FAILED'; const panel = await navigate(page);
    await panel.locator('summary').filter({ hasText: 'Retry remote revocation' }).click(); await panel.getByRole('button', { name: 'Retry Google revocation' }).click(); await expect(panel).toContainText('Remote revocation retry accepted.');
    expect(state.writes[0]).toEqual({ path: `/integrations/google-calendar/connections/${connectionId}/revocation/retry`, body: { expectedVersion: 4 } });
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('pending'); await expect(panel.getByRole('button', { name: 'Retry Google revocation' })).toHaveCount(0);
});
