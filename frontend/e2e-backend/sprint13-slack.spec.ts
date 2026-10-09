import { test, expect, type Page } from '@playwright/test';
import type { Connection, Delivery, SlackConnectionMetadata } from '../features/integrations/types';

const adminId = '11111111-1111-4111-8111-111111111111', connectionId = '22222222-2222-4222-8222-222222222222', deliveryId = '33333333-3333-4333-8333-333333333333';
const now = new Date().toISOString(), future = new Date(Date.now() + 7 * 86400000).toISOString();
const token = 'xoxb-private-browser-test-credential', replacement = 'xoxb-private-browser-replacement-credential';
const connection: Connection = { id: connectionId, provider: 'SLACK_MESSAGING', kind: 'MESSAGING', label: 'Reception Slack channel', ownerId: adminId, minimumScopes: ['chat:write'], status: 'ACTIVE', credentialVersion: 1, credentialExpiresAt: future, version: 4, lastCheckedAt: now, lastResultCode: 'SUCCESS', createdAt: now, updatedAt: now };
const delivery: Delivery = { id: deliveryId, connectionId, businessEventId: '44444444-4444-4444-8444-444444444444', eventType: 'VISITOR_ARRIVED', resourceId: '55555555-5555-4555-8555-555555555555', businessRevision: 2, status: 'UNKNOWN', attempts: 1, totalAttempts: 1, manualRetries: 0, nextAttemptAt: null, lastResultCode: 'SLACK_ACK_UNKNOWN', version: 7, createdAt: now, deliveredAt: null };
const expiryInput = () => { const value = new Date(future); return new Date(value.getTime() - value.getTimezoneOffset() * 60000).toISOString().slice(0, 16); };

// Provider transport stubs exercise the production admin UI; live Slack UAT is separate.
async function fixture(page: Page) {
    const state = { configured: true, malformedConfig: false, configFailure: false, metadataFailure: false, connections: [{ ...connection }], deliveries: [{ ...delivery }], metadata: { workspaceId: 'T12345678', channelId: 'C12345678', botId: 'B12345678', revocationStatus: 'NONE', lastResultCode: null } as SlackConnectionMetadata, writes: [] as { path: string; body: Record<string, unknown> }[], reads: [] as string[], delay: 0, unknown: false, conflict: false, mfaRequired: false, expired: false, rejection: '' };
    const profile = { userId: adminId, employeeId: null, email: 'sprint13@example.invalid', fullName: 'System Administrator', roles: ['ROLE_SYSTEM_ADMIN'], permissions: [], forcePasswordChange: false, departmentId: null, photoUrl: null };
    const emptyPage = { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0, last: true };
    await page.addInitScript(() => { sessionStorage.setItem('brainserve.connect.access-token', 'sprint13-access'); sessionStorage.setItem('brainserve.connect.refresh-token', 'sprint13-refresh'); });
    await page.context().route('http://backend.invalid/api/v1/**', async route => {
        const request = route.request(), path = new URL(request.url()).pathname.replace('/api/v1', '');
        if (path.startsWith('/integrations')) {
            expect(request.headers().authorization).toBe('Bearer sprint13-access');
            if (request.method() === 'GET') {
                state.reads.push(path);
                if (path === '/integrations/slack/config') return state.configFailure ? route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } }) : route.fulfill({ json: { configured: state.configured, scope: state.malformedConfig ? 'chat:write.public' : 'chat:write', usesDedicatedBot: true } });
                if (path === '/integrations/google-calendar/config') return route.fulfill({ json: { configured: false, scope: 'https://www.googleapis.com/auth/calendar.app.created', usesDedicatedCalendar: true } });
                if (path === '/integrations/connections') return route.fulfill({ json: state.connections });
                if (path.endsWith('/deliveries')) return route.fulfill({ json: { ...emptyPage, content: state.deliveries, totalElements: state.deliveries.length } });
                if (path.endsWith('/attempts')) return route.fulfill({ json: [{ id: '66666666-6666-4666-8666-666666666666', deliveryId, attemptNumber: 1, credentialVersion: 1, outcome: 'SLACK_ACK_UNKNOWN', startedAt: now, completedAt: now }] });
                if (path.startsWith('/integrations/slack/connections/')) return state.metadataFailure ? route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } }) : route.fulfill({ json: state.metadata });
            }
            const body = request.postDataJSON(); state.writes.push({ path, body });
            if (state.delay) await new Promise(resolve => setTimeout(resolve, state.delay));
            if (state.conflict) return route.fulfill({ status: 409, json: { errorCode: 'INTEGRATION_VERSION_CONFLICT' } });
            if (state.mfaRequired) return route.fulfill({ status: 403, json: { errorCode: 'MFA_STEP_UP_REQUIRED' } });
            if (state.expired) return route.fulfill({ status: 409, json: { errorCode: 'SLACK_DELIVERY_EXPIRED' } });
            if (state.rejection) return route.fulfill({ status: 409, json: { errorCode: state.rejection, detail: 'Provider detail is excluded from the UI.' } });
            if (path === '/integrations/slack/connections') state.connections = [{ ...connection, label: String(body.label), credentialExpiresAt: String(body.credentialExpiresAt), version: 0 }];
            if (path.endsWith('/renew')) { state.connections[0] = { ...state.connections[0], status: 'ACTIVE', credentialExpiresAt: String(body.credentialExpiresAt), version: state.connections[0].version + 1, credentialVersion: state.connections[0].credentialVersion + 1 }; state.metadata.revocationStatus = 'NONE'; }
            if (path.endsWith('/revoke')) { state.connections[0] = { ...state.connections[0], status: 'REVOKED', version: state.connections[0].version + 1 }; state.metadata.revocationStatus = 'PENDING'; }
            if (path.endsWith('/revocation/retry')) state.metadata.revocationStatus = 'PENDING';
            else if (path.endsWith('/retry')) state.deliveries[0] = { ...state.deliveries[0], status: 'PENDING', version: state.deliveries[0].version + 1, manualRetries: state.deliveries[0].manualRetries + 1 };
            if (state.unknown) return route.fulfill({ status: 503, json: { errorCode: 'UNAVAILABLE' } });
            return route.fulfill({ json: path.endsWith('/revocation/retry') ? state.metadata : state.connections[0] });
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

async function navigate(page: Page) {
    const menu = page.getByRole('button', { name: 'Open navigation', exact: true }); if (await menu.isVisible()) await menu.click();
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Integrations', exact: true }).click();
    const panel = page.getByRole('region', { name: 'Integrations', exact: true }); await expect(panel).toContainText('Current connections and delivery versions loaded.'); return panel;
}

async function prepareCreation(panel: ReturnType<Page['getByRole']>) {
    await panel.locator('summary').filter({ hasText: 'Add a Slack connection' }).click();
    await panel.getByLabel('Slack connection label', { exact: true }).fill('Reception notices'); await panel.getByLabel('Slack channel ID', { exact: true }).fill('C12345678');
    await panel.getByLabel('Slack bot token', { exact: true }).fill(token); await panel.getByLabel('Slack credential expiry', { exact: true }).fill(expiryInput());
    await panel.getByLabel('I confirm this dedicated bot is invited to the selected channel.').check();
}

async function prepareRenewal(panel: ReturnType<Page['getByRole']>) {
    await panel.locator('summary').filter({ hasText: 'Renew Slack credential' }).click();
    await panel.getByLabel('Replacement Slack bot token', { exact: true }).fill(replacement); await panel.getByLabel('Replacement Slack expiry', { exact: true }).fill(expiryInput());
    await panel.getByLabel('I rotated or revoked the old token in Slack before this renewal.').check();
}

for (const width of [360, 768, 1440]) test(`Slack connection, renewal and uncertain-delivery recovery fit ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 1000 }); const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
    await fixture(page); const panel = await navigate(page);
    await expect(panel).toContainText('C12345678'); await expect(panel.getByRole('button', { name: 'Run simulator test' })).toHaveCount(0);
    await panel.locator('summary').filter({ hasText: 'Add a Slack connection' }).click(); await panel.locator('summary').filter({ hasText: 'Renew Slack credential' }).click(); await panel.locator('summary').filter({ hasText: 'Test Slack delivery' }).click(); await panel.locator('summary').filter({ hasText: 'Revoke connection' }).click();
    await expect(panel.getByRole('button', { name: 'Create Slack connection', exact: true })).toBeDisabled(); await expect(panel.getByRole('button', { name: 'Retry uncertain delivery', exact: true })).toBeDisabled();
    const controls = await panel.locator('button:visible, input:not([type=checkbox]):visible, summary:visible').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().height)); expect(controls.every(height => height >= 44)).toBe(true);
    const checkTargets = await panel.locator('label:visible').evaluateAll(elements => elements.filter(element => element.querySelector('input[type=checkbox]')).map(element => element.getBoundingClientRect().height)); expect(checkTargets.every(height => height >= 44)).toBe(true);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({ path: testInfo.outputPath(`sprint13-slack-${width}.png`), fullPage: true }); expect(errors).toEqual([]);
});

test('disabled, unavailable and malformed Slack configuration never enable provider writes', async ({ page }) => {
    const state = await fixture(page); state.configured = false; const panel = await navigate(page);
    await expect(panel).toContainText('Slack is not configured on this service.'); await expect(panel.getByRole('button', { name: 'Create Slack connection' })).toHaveCount(0);
    await panel.locator('summary').filter({ hasText: 'Test Slack delivery' }).click(); await expect(panel.getByRole('button', { name: 'Send Slack test notice' })).toBeDisabled();
    state.configured = true; state.configFailure = true; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Slack availability could not be verified.');
    state.configFailure = false; state.malformedConfig = true; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Slack availability could not be verified.'); expect(state.writes).toHaveLength(0);
});

test('creation validates a channel ID, clears the token before the response and prevents rapid duplicate submits', async ({ page }) => {
    const state = await fixture(page); state.connections = []; state.deliveries = []; const panel = await navigate(page); await prepareCreation(panel);
    await panel.getByLabel('Slack channel ID', { exact: true }).fill('https://slack.com/example'); await expect(panel.getByRole('button', { name: 'Create Slack connection' })).toBeDisabled(); await expect(panel.getByLabel('Slack channel ID', { exact: true })).toHaveAttribute('aria-invalid', 'true');
    await panel.getByLabel('Slack channel ID', { exact: true }).fill('C12345678');
    await panel.getByLabel('Slack bot token', { exact: true }).fill('xoxe-private-browser-rotating-token'); await panel.getByRole('button', { name: 'Create Slack connection' }).click();
    expect(await panel.getByLabel('Slack bot token', { exact: true }).evaluate((input: HTMLInputElement) => input.validity.patternMismatch)).toBe(true); expect(state.writes).toHaveLength(0);
    await panel.getByLabel('Slack bot token', { exact: true }).fill(token); state.delay = 450;
    await panel.getByRole('button', { name: 'Create Slack connection' }).evaluate((button: HTMLButtonElement) => { button.click(); button.click(); });
    await expect.poll(() => state.writes.length).toBe(1); await expect(panel.getByLabel('Slack bot token', { exact: true })).toHaveValue(''); await expect(panel.getByRole('button', { name: 'Create Slack connection' })).toBeDisabled();
    await expect(panel).toContainText('Slack connection creation accepted.'); expect(state.writes[0]).toMatchObject({ path: '/integrations/slack/connections', body: { channelId: 'C12345678', credential: token, label: 'Reception notices' } });
    expect(await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }))).not.toContain(token); await expect(panel).not.toContainText(token);
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Reception notices'); expect(state.writes).toHaveLength(1);
});

test('uncertain creation retains nonsecret fields, focuses the error and requires a metadata reload without replay', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await prepareCreation(panel); state.unknown = true;
    await panel.getByRole('button', { name: 'Create Slack connection' }).click(); await expect(panel.getByRole('alert')).toContainText('not confirmed'); await expect(panel.getByRole('alert')).toBeFocused();
    await expect(panel.getByLabel('Slack bot token', { exact: true })).toHaveValue(''); await expect(panel.getByLabel('Slack connection label', { exact: true })).toHaveValue('Reception notices'); await expect(panel.getByLabel('Slack channel ID', { exact: true })).toHaveValue('C12345678'); await expect(panel.getByRole('button', { name: 'Create Slack connection' })).toBeDisabled();
    state.unknown = false; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('Reception notices'); expect(state.writes).toHaveLength(1);
});

test('renewal requires prior Slack token rotation and uses the fresh observed version after a conflict', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await prepareRenewal(panel);
    await panel.getByLabel('I rotated or revoked the old token in Slack before this renewal.').uncheck(); await expect(panel.getByRole('button', { name: 'Renew Slack credential', exact: true })).toBeDisabled();
    await panel.getByLabel('I rotated or revoked the old token in Slack before this renewal.').check(); state.conflict = true; await panel.getByRole('button', { name: 'Renew Slack credential', exact: true }).click();
    await expect(panel.getByRole('alert')).toContainText('another session'); await expect(panel.getByLabel('Replacement Slack bot token')).toHaveValue(''); expect(state.writes[0]).toMatchObject({ path: `/integrations/slack/connections/${connectionId}/renew`, body: { expectedVersion: 4, credential: replacement } });
    state.conflict = false; state.connections[0].version = 8; await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.getByLabel('Replacement Slack bot token').fill(replacement); await panel.getByLabel('I rotated or revoked the old token in Slack before this renewal.').check();
    await panel.getByRole('button', { name: 'Renew Slack credential', exact: true }).click(); await expect(panel).toContainText('Slack credential renewal accepted.'); expect(state.writes[1].body.expectedVersion).toBe(8); expect(state.writes).toHaveLength(2);
});

test('scope rejection gives a safe recovery instruction and clears the submitted token', async ({ page }) => {
    const state = await fixture(page); state.rejection = 'SLACK_AUTH_REJECTED'; const panel = await navigate(page); await prepareCreation(panel);
    await panel.getByRole('button', { name: 'Create Slack connection' }).click(); await expect(panel.getByRole('alert')).toContainText('Check its installation and scope in Slack.'); await expect(panel.getByRole('alert')).not.toContainText('another session'); await expect(panel.getByRole('alert')).not.toContainText('Provider detail'); await expect(panel.getByLabel('Slack bot token', { exact: true })).toHaveValue(''); await expect(panel.getByRole('button', { name: 'Create Slack connection' })).toBeDisabled();
    expect(state.writes).toHaveLength(1);
});

test('same-account session change clears creation and renewal secrets and discards late provider results', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await prepareCreation(panel); await prepareRenewal(panel); state.delay = 450;
    await panel.getByRole('button', { name: 'Renew Slack credential', exact: true }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed')));
    await expect(panel).toContainText('The account changed.'); await page.waitForTimeout(600); await expect(panel.getByLabel('Slack bot token')).toHaveCount(0); await expect(panel).not.toContainText('Slack credential renewal accepted.'); await expect(panel).not.toContainText(token); await expect(panel).not.toContainText(replacement); expect(state.writes).toHaveLength(1);
});

test('a remotely revoked connection can be renewed only after complete revocation with the same bot and channel', async ({ page }) => {
    const state = await fixture(page); state.connections[0].status = 'REVOKED'; state.metadata.revocationStatus = 'PENDING'; const panel = await navigate(page);
    await expect(panel.getByRole('button', { name: 'Renew Slack credential', exact: true })).toHaveCount(0);
    state.metadata.revocationStatus = 'COMPLETE'; await panel.getByRole('button', { name: 'Reload connections' }).click(); await prepareRenewal(panel);
    await expect(panel).toContainText('A new token from Slack and renewed channel membership reactivate this connection for future notices.'); await panel.getByRole('button', { name: 'Renew Slack credential', exact: true }).click(); await expect(panel).toContainText('Slack credential renewal accepted.');
    expect(state.writes[0]).toMatchObject({ path: `/integrations/slack/connections/${connectionId}/renew`, body: { expectedVersion: 4, credential: replacement } });
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.locator('summary').filter({ hasText: 'Test Slack delivery' }).click(); await expect(panel.getByRole('button', { name: 'Send Slack test notice' })).toBeEnabled(); expect(state.writes).toHaveLength(1);
});

test('a Slack test emits only the fixed SUCCESS command and requires reload before another mutation', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await panel.locator('summary').filter({ hasText: 'Test Slack delivery' }).click();
    await panel.getByRole('button', { name: 'Send Slack test notice' }).click(); await expect(panel).toContainText('Slack test notice accepted.'); await expect(panel.getByRole('button', { name: 'Send Slack test notice' })).toBeDisabled(); await expect(panel.getByLabel('Simulator scenario')).toHaveCount(0);
    expect(state.writes[0]).toMatchObject({ path: `/integrations/connections/${connectionId}/test`, body: { expectedVersion: 4, scenario: 'SUCCESS' } }); expect(Object.keys(state.writes[0].body).sort()).toEqual(['expectedVersion', 'requestId', 'scenario']);
});

test('an uncertain acknowledgement needs a visible duplicate-risk acknowledgement for each explicit retry', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); const retry = panel.getByRole('button', { name: 'Retry uncertain delivery' });
    await expect(panel).toContainText('Slack may already have accepted'); await expect(retry).toBeDisabled(); await panel.getByLabel(`I accept the duplicate-notice risk for delivery ${deliveryId}.`).check(); await retry.click();
    await expect(panel).toContainText('Delivery retry accepted.'); expect(state.writes[0]).toMatchObject({ path: `/integrations/deliveries/${deliveryId}/retry`, body: { expectedVersion: 7, acceptDuplicateRisk: true } }); await expect(panel.getByLabel(`I accept the duplicate-notice risk for delivery ${deliveryId}.`)).not.toBeChecked();
    state.deliveries[0].status = 'UNKNOWN'; state.deliveries[0].version = 8; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(retry).toBeDisabled();
    await panel.getByRole('button', { name: 'Load delivery attempts' }).click(); await expect(panel).toContainText('Attempt 1: SLACK_ACK_UNKNOWN'); expect(state.writes).toHaveLength(1);
});

test('failed deliveries retain ordinary bounded retry while exhausted attempts cannot be resubmitted', async ({ page }) => {
    const state = await fixture(page); state.deliveries[0].status = 'FAILED'; const panel = await navigate(page);
    await expect(panel.getByLabel(`I accept the duplicate-notice risk for delivery ${deliveryId}.`)).toHaveCount(0); await panel.getByRole('button', { name: 'Retry failed delivery' }).click(); await expect(panel).toContainText('Delivery retry accepted.'); expect(state.writes[0].body).not.toHaveProperty('acceptDuplicateRisk');
    state.deliveries[0].status = 'FAILED'; state.deliveries[0].manualRetries = 3; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Retry failed delivery' })).toBeDisabled();
    state.deliveries[0].manualRetries = 0; state.deliveries[0].totalAttempts = 20; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Retry failed delivery' })).toBeDisabled(); expect(state.writes).toHaveLength(1);
});

test('an expired event explains its recovery horizon and is never replayed', async ({ page }) => {
    const state = await fixture(page); state.expired = true; state.deliveries[0].status = 'FAILED'; const panel = await navigate(page);
    await expect(panel).toContainText('Arrival notices expire 24 hours after the event.');
    await panel.getByRole('button', { name: 'Retry failed delivery' }).click(); await expect(panel.getByRole('alert')).toContainText('expired 24 hours after its event and cannot be sent again'); await expect(panel.getByRole('alert')).toBeFocused(); await expect(panel.getByRole('button', { name: 'Retry failed delivery' })).toBeDisabled();
    await panel.getByRole('button', { name: 'Reload connections' }).click(); expect(state.writes).toHaveLength(1);
});

test('revocation is deliberate, reports pending remote work and offers recovery only after a remote failure', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await panel.locator('summary').filter({ hasText: 'Revoke connection' }).click();
    await expect(panel).toContainText('removes the bot from its channels'); await expect(panel.getByRole('button', { name: 'Confirm connection revocation' })).toBeDisabled(); await panel.getByLabel('I confirm revoking this connection.').check(); await panel.getByRole('button', { name: 'Confirm connection revocation' }).click(); await expect(panel).toContainText('Revocation accepted.');
    expect(state.writes[0]).toEqual({ path: `/integrations/connections/${connectionId}/revoke`, body: { expectedVersion: 4 } }); await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel).toContainText('pending'); await expect(panel.getByRole('button', { name: 'Renew Slack credential' })).toHaveCount(0);
    state.metadata.revocationStatus = 'FAILED'; await panel.getByRole('button', { name: 'Reload connections' }).click(); await panel.locator('summary').filter({ hasText: 'Retry Slack remote revocation' }).click(); await panel.getByRole('button', { name: 'Retry Slack revocation' }).click(); await expect(panel).toContainText('Slack remote revocation retry accepted.'); expect(state.writes[1]).toEqual({ path: `/integrations/slack/connections/${connectionId}/revocation/retry`, body: { expectedVersion: 5 } });
    await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Retry Slack revocation' })).toHaveCount(0); expect(state.writes).toHaveLength(2);
});

test('MFA refusal and failed detail reload keep mutations blocked until verified metadata returns', async ({ page }) => {
    const state = await fixture(page); const panel = await navigate(page); await panel.locator('summary').filter({ hasText: 'Test Slack delivery' }).click(); state.mfaRequired = true;
    await panel.getByRole('button', { name: 'Send Slack test notice' }).click(); await expect(panel.getByRole('alert')).toContainText('Verify your identity'); await expect(panel.getByRole('alert')).toBeFocused();
    state.mfaRequired = false; state.metadataFailure = true; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('alert')).toContainText('Connections could not be verified.'); await expect(panel.getByRole('button', { name: 'Send Slack test notice' })).toBeDisabled();
    state.metadataFailure = false; await panel.getByRole('button', { name: 'Reload connections' }).click(); await expect(panel.getByRole('button', { name: 'Send Slack test notice' })).toBeEnabled(); expect(state.writes).toHaveLength(1);
});
