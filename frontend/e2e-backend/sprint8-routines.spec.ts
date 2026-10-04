import { expect, test, type Page } from '@playwright/test';
import type { RoutineOccurrence, RoutineSchedule, RoutineTemplate } from '../features/work-routines/types';

const departmentId = '11111111-1111-4111-8111-111111111111';
const employeeId = '22222222-2222-4222-8222-222222222222';
const leadId = '33333333-3333-4333-8333-333333333333';
const userId = '44444444-4444-4444-8444-444444444444';
const now = '2026-10-04T04:00:00Z';
const templateId = (index: number) => `55555555-5555-4555-8555-${String(index + 1).padStart(12, '0')}`;
const scheduleId = (index: number) => `66666666-6666-4666-8666-${String(index + 1).padStart(12, '0')}`;
const template: RoutineTemplate = { id: templateId(0), departmentId, version: 1, title: 'Weekly office review', instructions: 'Review the department report and record an outcome.', checklist: [{ title: 'Record the outcome', required: true }], assigneeRule: 'EMPLOYEE', dueOffsetDays: 2, updatedAt: now };
const schedule: RoutineSchedule = { id: scheduleId(0), departmentId, templateId: template.id, templateTitle: template.title, templateVersion: 1, employeeId, assigneeName: 'Scoped Worker', frequency: 'WEEKLY', interval: 1, startDate: '2026-10-05', endDate: null, localTime: '09:00', weekdays: [1], monthDay: null, weekendPolicy: 'SKIP', holidayPolicy: 'SKIP', holidays: ['2026-12-25'], officeZone: 'Europe/London', paused: false, version: 1, nextOccurrenceAt: '2026-10-05T08:00:00Z', exceptionsCount: 1, createdAt: now };
const blocked: RoutineOccurrence = { occurrenceDate: '2026-10-05', scheduledAt: '2026-10-05T08:00:00Z', templateVersion: 1, taskId: null, status: 'BLOCKED', exceptionCode: 'ASSIGNEE_NOT_ELIGIBLE', message: 'Assignee no longer has an active employee login.', attempts: 1, version: 1 };

async function fixture(page: Page, role: 'TEAM_LEAD' | 'HR_ADMIN' = 'TEAM_LEAD') {
    const state = {
        templates: Array.from({ length: 21 }, (_, i) => ({ ...template, id: templateId(i), title: i ? `Office routine ${i + 1}` : template.title })),
        schedules: Array.from({ length: 21 }, (_, i) => ({ ...schedule, id: scheduleId(i), templateTitle: i ? `Office routine ${i + 1}` : template.title })),
        occurrences: Array.from({ length: 21 }, (_, i): RoutineOccurrence => i ? { ...blocked, occurrenceDate: `2026-10-${String(i + 5).padStart(2, '0')}`, status: 'CREATED', taskId: `task-${i}`, exceptionCode: null, message: null } : { ...blocked }),
        requests: [] as string[], writes: [] as { path: string; body: Record<string, unknown> }[],
        conflictTemplate: false, conflictState: false, conflictRetry: false, contextError: false, emptyPreview: false, previewDelay: false, createDelay: false, createUnknown: false, historyDenied: false,
    };
    const profile = { userId, employeeId: role === 'TEAM_LEAD' ? leadId : null, email: 'sprint8@example.invalid', fullName: 'Scoped Routine Owner', roles: [`ROLE_${role}`], permissions: ['WORK_TASK_CREATE'], forcePasswordChange: false, departmentId, photoUrl: null };
    const department = { id: departmentId, code: 'ENG', name: 'Engineering', active: true, version: 1 };
    const routinePage = (items: unknown[], url: URL) => { const pageNumber = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20); return { items: items.slice(pageNumber * size, (pageNumber + 1) * size), page: pageNumber, size, totalElements: items.length, totalPages: Math.ceil(items.length / size) }; };
    const springPage = (content: unknown[]) => ({ content, number: 0, size: 50, totalElements: content.length, totalPages: 1, last: true });
    await page.addInitScript(() => { sessionStorage.setItem('brainserve.connect.access-token', 'sprint8-access'); sessionStorage.setItem('brainserve.connect.refresh-token', 'sprint8-refresh'); });
    await page.route('http://backend.invalid/api/v1/**', async route => {
        const url = new URL(route.request().url()), path = url.pathname.replace('/api/v1', ''), method = route.request().method();
        if (path.startsWith('/work-routines')) {
            state.requests.push(`${method} ${path}${url.search}`);
            if (method !== 'GET') state.writes.push({ path, body: route.request().postDataJSON() });
            if (path === '/work-routines/context') return state.contextError ? route.fulfill({ status: 503, json: { detail: 'Routine context unavailable' } }) : route.fulfill({ json: { departmentId, departmentName: 'Engineering', officeZone: 'Europe/London', officeDate: '2026-10-04', eligibleAssignees: [{ employeeId, displayName: 'Scoped Worker', role: 'EMPLOYEE' }, ...(role === 'HR_ADMIN' ? [{ employeeId: leadId, displayName: 'Scoped Lead', role: 'TEAM_LEAD' }] : [])] } });
            if (path === '/work-routines/templates' && method === 'GET') return route.fulfill({ json: routinePage(state.templates, url) });
            if (path === '/work-routines/templates' && method === 'POST') {
                const body = route.request().postDataJSON(); const created = { ...template, ...body, id: crypto.randomUUID() }; state.templates.unshift(created); return route.fulfill({ json: created });
            }
            if (path.startsWith('/work-routines/templates/')) {
                const id = path.split('/').at(-1), index = state.templates.findIndex(item => item.id === id);
                if (index < 0) return route.fulfill({ status: 404, json: { detail: 'Routine unavailable', errorCode: 'ROUTINE_NOT_FOUND' } });
                if (method === 'GET') return route.fulfill({ json: state.templates[index] });
                if (state.conflictTemplate) { state.conflictTemplate = false; state.templates[index] = { ...state.templates[index], version: 7, instructions: 'Current saved instructions changed in another tab.', checklist: [{ title: 'Current saved checklist', required: false }], dueOffsetDays: 5 }; return route.fulfill({ status: 409, json: { detail: 'Routine template changed', errorCode: 'ROUTINE_VERSION_CONFLICT' } }); }
                const body = route.request().postDataJSON(); expect(body.expectedVersion).toBe(state.templates[index].version); state.templates[index] = { ...state.templates[index], ...body, version: body.expectedVersion + 1 }; return route.fulfill({ json: state.templates[index] });
            }
            if (path === '/work-routines/schedules' && method === 'GET') return route.fulfill({ json: routinePage(state.schedules, url) });
            if (path === '/work-routines/preview') {
                const body = route.request().postDataJSON(); if (state.previewDelay) await new Promise(resolve => setTimeout(resolve, 700));
                return route.fulfill({ json: { officeZone: 'Europe/London', occurrences: state.emptyPreview ? [] : [{ occurrenceDate: body.startDate, scheduledAt: `${body.startDate}T${body.localTime}:00Z`, dueDate: body.startDate }], policyText: 'Preview uses explicit holidays, weekend policy and calendar-day due offsets.' } });
            }
            if (path === '/work-routines/schedules' && method === 'POST') {
                if (state.createDelay) await new Promise(resolve => setTimeout(resolve, 700));
                if (state.createUnknown) { state.createUnknown = false; return route.fulfill({ status: 503, json: { detail: 'Creation response unavailable' } }); }
                const body = route.request().postDataJSON(); const selected = state.templates.find(item => item.id === body.templateId)!; const created = { ...schedule, ...body, id: crypto.randomUUID(), templateTitle: selected.title, templateVersion: selected.version, assigneeName: body.employeeId === leadId ? 'Scoped Lead' : 'Scoped Worker' }; state.schedules.unshift(created); return route.fulfill({ json: created });
            }
            if (path.endsWith('/state')) {
                const index = state.schedules.findIndex(item => item.id === path.split('/')[3]);
                if (state.conflictState) { state.conflictState = false; state.schedules[index] = { ...state.schedules[index], version: 6 }; return route.fulfill({ status: 409, json: { detail: 'Schedule changed', errorCode: 'ROUTINE_VERSION_CONFLICT' } }); }
                const body = route.request().postDataJSON(); expect(body.expectedVersion).toBe(state.schedules[index].version); state.schedules[index] = { ...state.schedules[index], paused: body.paused, version: body.expectedVersion + 1 }; return route.fulfill({ json: state.schedules[index] });
            }
            if (path.endsWith('/occurrences')) return state.historyDenied ? route.fulfill({ status: 404, json: { detail: 'Routine unavailable' } }) : route.fulfill({ json: routinePage(state.occurrences, url) });
            if (path.endsWith('/retry')) {
                if (state.conflictRetry) { state.conflictRetry = false; state.occurrences[0] = { ...state.occurrences[0], version: 4, attempts: 2 }; return route.fulfill({ status: 409, json: { detail: 'Occurrence changed', errorCode: 'ROUTINE_VERSION_CONFLICT' } }); }
                const body = route.request().postDataJSON(); expect(body.expectedVersion).toBe(state.occurrences[0].version); state.occurrences[0] = { ...state.occurrences[0], status: 'CREATED', taskId: 'retained-task-once', exceptionCode: null, message: null, attempts: state.occurrences[0].attempts + 1, version: body.expectedVersion + 1 }; state.schedules[0].exceptionsCount = 0; return route.fulfill({ json: state.occurrences[0] });
            }
            return route.fulfill({ status: 404, json: { detail: 'Unmocked routine path' } });
        }
        if (['/auth/me', '/profile/me'].includes(path)) return route.fulfill({ json: profile });
        if (path === '/employees') return route.fulfill({ json: springPage([{ id: employeeId, employeeNumber: 'EMP-8', departmentId, displayName: 'Scoped Worker', officialEmail: 'worker@example.invalid', designation: 'Engineer', status: 'ACTIVE' }, { id: leadId, employeeNumber: 'LEAD-8', departmentId, displayName: 'Scoped Routine Owner', officialEmail: profile.email, designation: 'Lead', status: 'ACTIVE' }]) });
        if (['/departments', '/departments/visible'].includes(path)) return route.fulfill({ json: [department] });
        if (['/appointments', '/admin/staff-accounts'].includes(path)) return route.fulfill({ json: springPage([]) });
        if (path === '/work-tasks') return route.fulfill({ json: [] });
        if (path === '/work-tasks/workspace') return route.fulfill({ json: { departmentId, departmentName: 'Engineering', departmentCode: 'ENG', eligibleAssignees: [{ employeeId, displayName: 'Scoped Worker', designation: 'Engineer', role: 'EMPLOYEE' }] } });
        if (path === '/workboard/preferences') return route.fulfill({ json: { revision: 0, layout: 'LIST', density: 'COMPACT', savedFilters: [] } });
        if (path === '/workboard') return route.fulfill({ json: { policyVersion: 'workboard.v1', generatedAt: now, officeZone: 'Europe/London', officeDate: '2026-10-04', scope: 'DEPARTMENT', departmentId, number: 0, size: 20, totalElements: 0, totalPages: 0, counts: { scopes: { TODAY: 0, CARRY_FORWARD: 0, HISTORY: 0, ALL: 0 }, quickFilters: { ALL: 0, MY_ACTIONS: 0, DUE_TODAY: 0, OVERDUE_DELIVERY: 0, AWAITING_MY_REVIEW: 0, RETURNED_FOR_REWORK: 0 } }, laneCounts: { DELIVERY: 0, REVIEW: 0, REWORK: 0, CLOSED: 0 }, items: [] } });
        if (path === '/realtime/stream') return route.fulfill({ status: 204 });
        if (path.includes('unread')) return route.fulfill({ json: { unreadCount: 0 } });
        return route.fulfill({ json: [] });
    });
    await page.goto('/');
    const menu = page.getByRole('button', { name: 'Open navigation' }); if (await menu.isVisible()) await menu.click();
    await page.getByRole('navigation', { name: 'Role workspace' }).getByRole('button', { name: 'Work board', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Work routines', exact: true })).toBeVisible();
    expect(state.requests).toHaveLength(0);
    return state;
}
async function open(page: Page) { await page.getByRole('button', { name: 'Work routines', exact: true }).click(); await expect(page.getByRole('region', { name: 'Routine templates', exact: true })).toBeVisible(); }
async function scheduleForm(page: Page) {
    await page.getByRole('button', { name: 'Schedule template Weekly office review', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'Create routine schedule', exact: true }); await expect(dialog).toBeVisible();
    await dialog.getByLabel('Routine assignee', { exact: true }).selectOption(employeeId); return dialog;
}
const screenshots = process.env.SPRINT8_SCREENSHOT_DIR;

for (const width of [360, 768, 1440]) test(`routine forms preserve labels, keyboard, preview invalidation and layout at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 }); const state = await fixture(page);
    if (screenshots) await page.screenshot({ path: `${screenshots}/sprint8-routines-closed-${width}.png`, fullPage: true });
    const entry = page.getByRole('button', { name: 'Work routines', exact: true }); await entry.focus(); await entry.press('Enter');
    await expect(page.getByRole('region', { name: 'Routine templates', exact: true })).toContainText(template.title);
    await page.getByRole('button', { name: 'Next templates page', exact: true }).click(); await expect(page.getByRole('region', { name: 'Routine templates', exact: true })).toContainText('Office routine 21');
    await page.getByRole('button', { name: 'Previous templates page', exact: true }).click();
    const trigger = page.getByRole('button', { name: 'Schedule template Weekly office review', exact: true }); await trigger.focus(); await trigger.press('Enter');
    const dialog = page.getByRole('dialog', { name: 'Create routine schedule', exact: true }); await expect(dialog.getByLabel('Routine template', { exact: true })).toBeFocused();
    await dialog.getByLabel('Routine assignee', { exact: true }).selectOption(employeeId);
    await dialog.getByLabel('Repeat frequency', { exact: true }).selectOption('WEEKLY'); await dialog.getByLabel('Tuesday', { exact: true }).check();
    await dialog.getByLabel('Repeat every', { exact: true }).fill('2'); await dialog.getByLabel('Holiday date', { exact: true }).fill('2026-12-25'); await dialog.getByRole('button', { name: 'Add holiday date', exact: true }).click();
    await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('region', { name: 'Routine occurrence preview' })).toContainText('Due 2026-10-04');
    await expect(dialog.getByRole('button', { name: 'Create routine schedule', exact: true })).toBeEnabled();
    await dialog.getByLabel('Office local time', { exact: true }).fill('10:30'); await expect(dialog.getByRole('button', { name: 'Create routine schedule', exact: true })).toBeDisabled();
    await dialog.getByLabel('Repeat frequency', { exact: true }).selectOption('MONTHLY'); await dialog.getByLabel('Day of month', { exact: true }).fill('31');
    await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('region', { name: 'Routine occurrence preview' })).toContainText('Office zone: Europe/London');
    if (screenshots) await page.screenshot({ path: `${screenshots}/sprint8-routines-${width}.png`, fullPage: true });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth)).toBe(true);
    await page.keyboard.press('Escape'); await expect(page.getByRole('dialog', { name: 'Keep unsaved routine changes?' })).toBeVisible();
    await page.getByRole('button', { name: 'Keep editing routine', exact: true }).click();
    await dialog.getByRole('button', { name: 'Create routine schedule', exact: true }).click(); await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused(); expect(state.writes.at(-1)?.body.frequency).toBe('MONTHLY'); expect(state.writes.at(-1)?.body.monthDay).toBe(31); expect(state.writes.at(-1)?.body.weekdays).toEqual([]);
});

test('template creation, checklist and full current-version comparison retain fields after conflict', async ({ page }) => {
    const state = await fixture(page, 'HR_ADMIN'); await open(page);
    await page.getByRole('button', { name: 'Create routine template', exact: true }).click();
    let dialog = page.getByRole('dialog', { name: 'Create routine template', exact: true });
    await dialog.getByLabel('Template title', { exact: true }).fill('Monthly compliance'); await dialog.getByLabel('Routine instructions', { exact: true }).fill('Review compliance and record delivery requirements.');
    await dialog.getByLabel('Assignee role', { exact: true }).selectOption('TEAM_LEAD'); await dialog.getByLabel('Due offset (calendar days)').fill('5'); await dialog.getByRole('button', { name: 'Add routine requirement' }).click(); await dialog.getByLabel('Requirement 1', { exact: true }).fill('Provide the compliance summary'); await dialog.getByLabel('Required 1', { exact: true }).uncheck();
    await dialog.getByRole('button', { name: 'Save routine template', exact: true }).click(); await expect(dialog).toHaveCount(0);
    expect(state.writes.at(-1)?.body.checklist).toEqual([{ title: 'Provide the compliance summary', required: false }]); expect(state.writes.at(-1)?.body.assigneeRule).toBe('TEAM_LEAD');
    state.conflictTemplate = true; await page.getByRole('button', { name: 'Edit template Weekly office review', exact: true }).click(); dialog = page.getByRole('dialog', { name: 'Edit routine template', exact: true });
    await dialog.getByLabel('Template title', { exact: true }).fill('Retained local title'); await dialog.getByLabel('Routine instructions', { exact: true }).fill('Retained local instructions.'); await dialog.getByRole('button', { name: 'Save routine template', exact: true }).click();
    await expect(dialog.getByRole('alert')).toContainText('Routine template changed'); await expect(dialog.getByLabel('Template title', { exact: true })).toHaveValue('Retained local title');
    await dialog.getByRole('button', { name: 'Reload template version and retain fields' }).click();
    const comparison = dialog.getByRole('region', { name: 'Current saved template comparison' }); await expect(comparison).toContainText('Current saved instructions changed in another tab.'); await expect(comparison).toContainText('Current saved checklist'); await expect(comparison).toContainText('5 calendar days');
    await expect(dialog.getByLabel('Routine instructions', { exact: true })).toHaveValue('Retained local instructions.');
    await dialog.getByRole('button', { name: 'Save routine template', exact: true }).click(); await expect(dialog).toHaveCount(0); expect(state.writes.at(-1)?.body.expectedVersion).toBe(7);
});

test('pause/resume and paginated exception history use current versions and confirm one retry receipt', async ({ page }) => {
    const state = await fixture(page); await open(page); state.conflictState = true;
    await page.getByRole('button', { name: 'Pause schedule Weekly office review', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('Schedule changed'); await expect(page.getByRole('button', { name: 'Pause schedule Weekly office review', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: 'Reload routine schedules', exact: true }).click(); await page.getByRole('button', { name: 'Pause schedule Weekly office review', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Resume schedule Weekly office review', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'View history & exceptions for Weekly office review', exact: true }).click(); const dialog = page.getByRole('dialog', { name: 'Routine history: Weekly office review' });
    await expect(dialog.getByRole('button', { name: 'Retry occurrence 2026-10-05' })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Resume schedule', exact: true }).click(); await expect(dialog).toContainText('Elapsed paused dates are skipped');
    await dialog.getByRole('button', { name: 'Next occurrences page', exact: true }).click(); await expect(dialog.getByRole('region', { name: 'Routine occurrences' })).toContainText('2026-10-25'); await dialog.getByRole('button', { name: 'Previous occurrences page', exact: true }).click();
    state.conflictRetry = true; await dialog.getByRole('button', { name: 'Retry occurrence 2026-10-05' }).click(); await expect(dialog.getByRole('alert')).toContainText('Occurrence changed');
    await dialog.getByRole('button', { name: 'Reload routine history' }).click(); await dialog.getByRole('button', { name: 'Retry occurrence 2026-10-05' }).click();
    await expect(dialog.getByRole('article', { name: 'Occurrence 2026-10-05' })).toContainText('retained-task-once'); await expect(dialog.getByRole('button', { name: 'Retry occurrence 2026-10-05' })).toHaveCount(0);
    expect(state.writes.filter(item => item.path.endsWith('/retry')).at(-1)?.body.expectedVersion).toBe(4);
});

test('loading, empty, preview validation and service-error recovery are visible', async ({ page }) => {
    const state = await fixture(page); state.contextError = true; await page.getByRole('button', { name: 'Work routines', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('Routine context unavailable');
    state.contextError = false; state.templates = []; state.schedules = []; await page.getByRole('button', { name: 'Retry routine workspace' }).click(); await expect(page.getByRole('region', { name: 'Routine templates', exact: true })).toContainText('No routine templates yet'); await expect(page.getByRole('region', { name: 'Routine schedules', exact: true })).toContainText('No routine schedules yet');
    state.templates = [{ ...template }]; await page.getByRole('button', { name: 'Reload routine templates', exact: true }).click(); const dialog = await scheduleForm(page);
    await dialog.getByLabel('Repeat frequency', { exact: true }).selectOption('WEEKLY'); await dialog.getByLabel('Monday', { exact: true }).uncheck(); await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('alert')).toContainText('Choose at least one weekday'); await expect(dialog.getByRole('alert')).toBeFocused();
    await dialog.getByLabel('Monday', { exact: true }).check(); state.emptyPreview = true; state.previewDelay = true; await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('status')).toContainText('Calculating accepted dates'); await expect(dialog).toContainText('No accepted occurrences'); await expect(dialog.getByRole('button', { name: 'Create routine schedule', exact: true })).toBeDisabled();
});

test('an unconfirmed creation retries the same request and account changes cancel late preview/mutation publication', async ({ page }) => {
    const state = await fixture(page); await open(page); let dialog = await scheduleForm(page); await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('button', { name: 'Create routine schedule', exact: true })).toBeEnabled();
    state.createUnknown = true; await dialog.getByRole('button', { name: 'Create routine schedule', exact: true }).click(); await expect(dialog.getByRole('button', { name: 'Retry same schedule request' })).toBeVisible(); await expect(dialog.getByLabel('Office local time', { exact: true })).toBeDisabled();
    await dialog.getByRole('button', { name: 'Retry same schedule request' }).click(); await expect(dialog).toHaveCount(0); const bodies = state.writes.filter(item => item.path === '/work-routines/schedules').map(item => item.body); expect(bodies[1]).toEqual(bodies[0]);
    dialog = await scheduleForm(page); state.previewDelay = true; await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed'))); await expect(dialog).toHaveCount(0); await expect(page.getByRole('region', { name: 'Routine templates', exact: true })).toHaveCount(0);
    await open(page); dialog = await scheduleForm(page); state.previewDelay = false; await dialog.getByRole('button', { name: 'Preview routine dates', exact: true }).click(); await expect(dialog.getByRole('button', { name: 'Create routine schedule', exact: true })).toBeEnabled();
    state.createDelay = true; await dialog.getByRole('button', { name: 'Create routine schedule', exact: true }).click(); await page.evaluate(() => window.dispatchEvent(new Event('brainserve:auth-session-changed'))); await expect(dialog).toHaveCount(0); await expect(page.getByRole('region', { name: 'Department work routines' })).not.toContainText('Scoped Worker');
    await expect(page.getByRole('button', { name: 'Work routines', exact: true })).toBeVisible();
});

test('history access loss removes retained names and exception data from the workspace', async ({ page }) => {
    const state = await fixture(page); await open(page); state.historyDenied = true; await page.getByRole('button', { name: 'View history & exceptions for Weekly office review', exact: true }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0); const workspace = page.getByRole('region', { name: 'Department work routines' }); await expect(workspace).not.toContainText('Scoped Worker'); await expect(workspace).toContainText('unavailable in your current account or department');
});
