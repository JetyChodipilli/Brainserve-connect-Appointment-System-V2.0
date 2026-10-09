import { test, expect, type Page, type Route } from '@playwright/test';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const person = 'a1500000-0000-4000-8000-000000000001';
async function fixture(page: Page, role = 'ROLE_SYSTEM_ADMIN', reply?: (route: Route, path: string) => Promise<boolean>) {
    await page.addInitScript(() => sessionStorage.setItem('brainserve.connect.access-token', 'synthetic-s15-token'));
    await page.route('http://backend.invalid/**', async route => {
        const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
        if (reply && await reply(route, path)) return;
        if (['/auth/me', '/profile/me'].includes(path)) return route.fulfill({ json: { userId: person, employeeId: person, fullName: 'Synthetic operator', email: 's15@example.invalid', roles: [role], permissions: [], forcePasswordChange: false, photoUrl: null } });
        if (path === '/auth/security') return route.fulfill({ json: { mfaRequired: true, mfaEnrolled: true, mfaVerified: true, stepUpRequired: false } });
        if (path === '/dashboard/summary') return route.fulfill({ json: { awaitingApproval: 0, activeVisits: 0, totalEmployees: 0, activeEmployees: 0, scope: 'COMPANY', departmentId: null } });
        if (path === '/dashboard/cards') return route.fulfill({ status: 503, json: { detail: 'Synthetic unavailable measurement' } });
        if (path.includes('unread')) return route.fulfill({ json: { unreadCount: 0 } });
        if (path === '/realtime/stream') return route.fulfill({ status: 204 });
        if (['/employees', '/appointments', '/admin/staff-accounts'].includes(path)) return route.fulfill({ json: { content: [], number: 0, size: 20, totalElements: 0, totalPages: 0, last: true } });
        if (['/departments', '/departments/visible', '/public/departments'].includes(path)) return route.fulfill({ json: [{ id: person, code: 'S15', name: 'Synthetic department', active: true, version: 0 }] });
        return route.fulfill({ json: [] });
    });
    await page.goto('/');
    await expect(page.getByRole('navigation', { name: 'Role workspace', includeHidden: true })).toBeAttached();
}
async function accessibility(page: Page) {
    await page.addScriptTag({ path: require.resolve('axe-core/axe.min.js') });
    const violations = await page.evaluate(async () => {
        const axe = (window as unknown as { axe: { run: (root: Element, options: object) => Promise<{ violations: { id: string; impact: string; nodes: { target: string[] }[] }[] }> } }).axe;
        const result = await axe.run(document.body, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } });
        return result.violations.map(item => ({ id: item.id, impact: item.impact, targets: item.nodes.map(node => node.target) }));
    });
    expect(violations).toEqual([]);
}
for (const role of ['ROLE_SYSTEM_ADMIN', 'ROLE_CEO', 'ROLE_HR_ADMIN', 'ROLE_MANAGER', 'ROLE_TEAM_LEAD', 'ROLE_EMPLOYEE', 'ROLE_RECEPTIONIST', 'ROLE_SECURITY']) {
    test(`scoped ${role} overview meets automated accessibility rules`, async ({ page }) => { await fixture(page, role); await accessibility(page); });
}
test('mobile navigation isolates the workspace, traps focus, closes on Escape and restores the opener', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 }); await fixture(page);
    const opener = page.getByRole('button', { name: 'Open navigation', exact: true });
    await expect(page.locator('#workspace-navigation')).toBeHidden();
    await opener.focus(); await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Workspace navigation' });
    await expect(dialog).toBeVisible(); await expect(opener).toHaveAttribute('aria-expanded', 'true');
    await expect(page.locator('#workspace-main')).toHaveAttribute('inert', '');
    await dialog.getByRole('button', { name: 'Close navigation', exact: true }).focus(); await page.keyboard.press('Shift+Tab');
    expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    await accessibility(page); await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0); await expect(opener).toBeFocused();
    await expect(page.locator('#workspace-main')).not.toHaveAttribute('inert', '');
    await opener.click(); await page.locator('.sidebar-scrim').click({ position: { x: 350, y: 200 } });
    await expect(page.locator('#workspace-navigation')).toBeHidden(); await expect(opener).toBeFocused();
});
test('profile menu supports arrows, Home, End, Escape and normal Tab exit', async ({ page }) => {
    await fixture(page);
    const opener = page.locator('[aria-haspopup="menu"]'); await opener.focus(); await page.keyboard.press('Enter');
    await expect(page.getByRole('menuitem', { name: /My profile/ })).toBeFocused();
    await page.keyboard.press('ArrowDown'); await expect(page.getByRole('menuitemcheckbox')).toBeFocused();
    await page.keyboard.press('End'); await expect(page.getByRole('menuitem', { name: /Logout/ })).toBeFocused();
    await page.keyboard.press('Home'); await expect(page.getByRole('menuitem', { name: /My profile/ })).toBeFocused();
    await page.keyboard.press('Escape'); await expect(opener).toBeFocused();
    await page.keyboard.press('Enter'); await page.keyboard.press('Tab'); await expect(page.getByRole('menu')).toHaveCount(0);
});
test('legacy employee form edits preserve focus and dialog closure releases background controls', async ({ page }) => {
    await fixture(page, 'ROLE_HR_ADMIN');
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Employees', exact: true }).click();
    const opener = page.getByRole('button', { name: 'Add employee', exact: true }); await opener.click();
    const dialog = page.getByRole('dialog', { name: 'Add a new employee' });
    await dialog.getByLabel('Full name').focus(); await page.keyboard.type('Synthetic operator');
    await expect(dialog.getByLabel('Full name')).toBeFocused();
    const department = dialog.getByRole('combobox', { name: 'Department', exact: true });
    await department.focus(); await department.selectOption(person);
    await expect(department).toBeFocused();
    await page.keyboard.press('Tab'); expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    await accessibility(page); await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0); await expect(opener).toBeFocused();
    expect(await page.locator('.sidebar').evaluate(node => (node as HTMLElement).inert)).toBe(false);
});
test('privacy dialog closes by keyboard and narrow reduced-motion layout keeps controls visible', async ({ page }, info) => {
    await page.setViewportSize({ width: 768, height: 900 }); await page.emulateMedia({ reducedMotion: 'reduce' }); await fixture(page);
    await page.getByRole('button', { name: 'Open navigation', exact: true }).click();
    await page.getByRole('button', { name: /Privacy centre/ }).click();
    const dialog = page.getByRole('dialog', { name: 'How BrainServe Connect protects visitor data' });
    await expect(dialog).toBeVisible(); await accessibility(page);
    await page.screenshot({ path: info.outputPath('privacy-tablet.png'), fullPage: true });
    await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Open navigation', exact: true })).toBeFocused();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('mobile profile menu handles keys before its containing navigation dialog', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 }); await fixture(page);
    const opener = page.getByRole('button', { name: 'Open navigation', exact: true }); await opener.click();
    const dialog = page.getByRole('dialog', { name: 'Workspace navigation' });
    const profile = dialog.locator('[aria-haspopup="menu"]'); await profile.click();
    await expect(page.getByRole('menuitem', { name: /My profile/ })).toBeFocused();
    await page.keyboard.press('Escape'); await expect(page.getByRole('menu')).toHaveCount(0);
    await expect(dialog).toBeVisible(); await expect(profile).toBeFocused();
    await page.keyboard.press('Enter'); await expect(page.getByRole('menuitem', { name: /My profile/ })).toBeFocused(); await page.keyboard.press('Tab');
    await expect(page.getByRole('menu')).toHaveCount(0);
    expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    await profile.focus(); await page.keyboard.press('Enter'); await expect(page.getByRole('menuitem', { name: /My profile/ })).toBeFocused(); await page.keyboard.press('Shift+Tab');
    await expect(page.getByRole('menu')).toHaveCount(0);
    expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    await page.keyboard.press('Escape'); await expect(opener).toBeFocused();
});

test('connection recovery releases a removed mobile navigation focus lock and reinstalls it after retry', async ({ page }) => {
    let release!: () => void, healthy = false;
    const pending = new Promise<void>(resolve => { release = resolve; });
    await page.setViewportSize({ width: 360, height: 900 });
    await fixture(page, 'ROLE_CEO', async (route, path) => {
        if (!healthy && ['/appointments', '/dashboard/summary'].includes(path)) {
            await pending; await route.fulfill({ status: 503, json: { detail: 'Synthetic connection failure' } }); return true;
        }
        return false;
    });
    await page.getByRole('button', { name: 'Open navigation', exact: true }).click();
    await expect(page.getByRole('dialog', { name: 'Workspace navigation' })).toBeVisible();
    release(); await expect(page.getByRole('heading', { name: 'Reconnecting to your workspace' })).toBeVisible();
    expect(await page.evaluate(() => document.body.style.overflow)).not.toBe('hidden');
    healthy = true; await page.getByRole('button', { name: 'Retry connection' }).click();
    const dialog = page.getByRole('dialog', { name: 'Workspace navigation' }); await expect(dialog).toBeVisible();
    await page.keyboard.press('Tab'); expect(await dialog.evaluate(node => node.contains(document.activeElement))).toBe(true);
    await page.keyboard.press('Escape'); await expect(page.locator('#workspace-main')).not.toHaveAttribute('inert', '');
});

test('Insights note edits preserve focus and Escape returns to the retained decision opener', async ({ page }) => {
    await fixture(page, 'ROLE_MANAGER', async (route, path) => {
        if (path !== '/work-insights') return false;
        await route.fulfill({ json: [{ auditRecordId: person, workTaskId: person, employeeId: person, departmentId: person,
            employeeName: 'Synthetic operator', employeeNumber: 'S15-1', departmentName: 'Synthetic department',
            teamLeadName: 'Synthetic lead', taskTitle: 'Synthetic worksheet', taskDescription: 'Synthetic retained audit',
            weekStart: new URL(route.request().url()).searchParams.get('weekStart'), auditStatus: 'PENDING_MANAGER_APPROVAL',
            taskStatus: 'APPROVED', assignedByRole: 'TEAM_LEAD', assigneeRole: 'EMPLOYEE', reworkCycle: 0,
            hrAuditedAt: null, managerDecidedAt: null, managerRemarks: null, ceoDecidedAt: null, ceoRemarks: null }] }); return true;
    });
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Work oversight', exact: true }).click();
    const opener = page.getByRole('button', { name: 'Verify', exact: true }); await opener.focus(); await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Verify department work audit' });
    const note = dialog.getByRole('textbox', { name: 'Manager decision note' }); await note.focus(); await page.keyboard.type('Synthetic review note');
    await expect(note).toBeFocused(); await page.keyboard.press('Escape'); await expect(dialog).toHaveCount(0);
    await expect(opener).toBeFocused(); expect(await page.locator('.sidebar').evaluate(node => (node as HTMLElement).inert)).toBe(false);
});

test('HR termination dialog supports keyboard editing and returns to its employee action', async ({ page }) => {
    await fixture(page, 'ROLE_HR_ADMIN', async (route, path) => {
        if (path !== '/employees') return false;
        await route.fulfill({ json: { content: [{ id: person, employeeNumber: 'S15-1', displayName: 'Synthetic operator',
            officialEmail: 's15@example.invalid', departmentId: person, designation: 'Synthetic tester', status: 'ACTIVE',
            lifecycleProtected: false }], number: 0, size: 25, totalElements: 1, totalPages: 1, last: true } }); return true;
    });
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Employees', exact: true }).click();
    const opener = page.getByRole('combobox', { name: 'Change Synthetic operator status' }); await opener.focus(); await opener.selectOption('Terminated');
    const dialog = page.getByRole('dialog', { name: 'Send to CEO for approval' }); await expect(dialog).toBeVisible();
    const reason = dialog.getByRole('textbox', { name: 'Reason for termination' }); await reason.focus(); await page.keyboard.type('Synthetic review request');
    await expect(reason).toBeFocused(); await accessibility(page); await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0); await expect(opener).toBeFocused();
});
