import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url), ts = require('typescript');
function runtime(path, dependencies = {}) {
    const source = readFileSync(new URL(`../features/work-routines/${path}`, import.meta.url), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
    const instance = { exports: {} };
    new Function('require', 'module', 'exports', js)(name => { if (!(name in dependencies)) throw new Error(`Unexpected dependency: ${name}`); return dependencies[name]; }, instance, instance.exports);
    return instance.exports;
}
const model = runtime('routine-model.ts');
const { RoutineSession } = runtime('routine-session.ts', { './routine-model': model });
const context = { departmentId: 'department-a', departmentName: 'Engineering', officeZone: 'Europe/London', officeDate: '2026-10-04', eligibleAssignees: [{ employeeId: 'employee-a', displayName: 'Scoped Worker', role: 'EMPLOYEE' }, { employeeId: 'lead-a', displayName: 'Scoped Lead', role: 'TEAM_LEAD' }] };
const template = { id: 'template-a', departmentId: 'department-a', version: 1, title: 'Office review', instructions: 'Review the retained report.', checklist: [{ title: 'Record outcome', required: true }], assigneeRule: 'EMPLOYEE', dueOffsetDays: 2, updatedAt: '2026-10-04T09:00:00Z' };
const definition = { ...model.emptySchedule(context.officeDate, template.id), employeeId: 'employee-a' };
const schedule = { ...definition, id: 'schedule-a', departmentId: context.departmentId, templateTitle: template.title, templateVersion: 1, assigneeName: 'Scoped Worker', officeZone: context.officeZone, paused: false, version: 1, nextOccurrenceAt: '2026-10-05T08:00:00Z', exceptionsCount: 1, createdAt: '2026-10-04T09:00:00Z' };
const blocked = { occurrenceDate: '2026-10-05', scheduledAt: '2026-10-05T08:00:00Z', templateVersion: 1, taskId: null, status: 'BLOCKED', exceptionCode: 'ASSIGNEE_NOT_ELIGIBLE', message: 'Assignee no longer has an active employee login.', attempts: 1, version: 1 };
const preview = { officeZone: context.officeZone, occurrences: [{ occurrenceDate: '2026-10-05', scheduledAt: blocked.scheduledAt, dueDate: '2026-10-07' }], policyText: 'Weekends and listed holidays are skipped.' };
const page = (items, number = 0, totalPages = 1) => ({ items, totalElements: items.length * totalPages, page: number, size: 20, totalPages });
const problem = (status, message = 'Routine request failed') => Object.assign(new Error(message), { status });
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { resolve, reject, promise }; }
function harness(overrides = {}) {
    const calls = [];
    const api = {
        context: async () => context,
        templates: async number => page([template], number),
        template: async () => template,
        schedules: async number => page([schedule], number),
        createTemplate: async fields => ({ ...template, ...fields }),
        updateTemplate: async (_id, fields) => ({ ...template, ...fields, version: fields.expectedVersion + 1 }),
        preview: async () => preview,
        createSchedule: async fields => ({ ...schedule, ...fields }),
        setState: async (_id, version, paused) => ({ ...schedule, paused, version: version + 1 }),
        occurrences: async (_id, number) => page([blocked], number),
        retry: async () => ({ ...blocked, status: 'CREATED', taskId: 'task-once', version: 2 }),
        ...overrides,
    };
    const transport = Object.fromEntries(Object.entries(api).map(([name, fn]) => [name, (...args) => { calls.push({ name, args }); return fn(...args); }]));
    return { session: new RoutineSession(transport, 'account-a:0', 'HR Admin'), calls, api };
}
async function prepared(overrides = {}) { const h = harness(overrides); await h.session.open(); h.session.openSchedule(template); h.session.changeSchedule(definition); return h; }

test('calendar form validation covers limits, assignee role, weekdays, ISO dates and leap-year five-year horizon', () => {
    assert.deepEqual(model.validateTemplate(model.templateFields(template), 'Team Lead'), []);
    assert.ok(model.validateTemplate({ ...model.templateFields(template), assigneeRule: 'TEAM_LEAD' }, 'Team Lead').length);
    assert.ok(model.validateTemplate({ ...model.templateFields(template), dueOffsetDays: NaN }, 'HR Admin').length);
    assert.ok(model.validateTemplate({ ...model.templateFields(template), checklist: [{ title: '', required: true }] }, 'HR Admin').length);
    assert.deepEqual(model.validateSchedule(definition, context, template), []);
    for (const fields of [{ ...definition, employeeId: 'lead-a' }, { ...definition, frequency: 'WEEKLY', weekdays: [] }, { ...definition, frequency: 'MONTHLY', monthDay: 32 }, { ...definition, interval: 1.5 }, { ...definition, localTime: '24:00' }, { ...definition, startDate: '2026-10-03' }, { ...definition, endDate: '2026-02-30' }, { ...definition, holidays: ['2026-02-30'] }]) assert.ok(model.validateSchedule(fields, context, template).length);
    const leapContext = { ...context, officeDate: '2028-02-29' };
    assert.deepEqual(model.validateSchedule({ ...definition, startDate: '2028-02-29', endDate: '2033-02-28' }, leapContext, template), []);
    assert.ok(model.validateSchedule({ ...definition, startDate: '2028-02-29', endDate: '2033-03-01' }, leapContext, template).length);
    assert.equal(model.validIsoDate('2028-02-29'), true); assert.equal(model.validIsoDate('2026-02-29'), false);
});
test('canonical payload removes irrelevant recurrence fields and deduplicates explicit holidays', () => {
    const value = model.canonicalSchedule({ ...definition, weekdays: [7, 1, 1], monthDay: 31, holidays: ['2026-12-25', '2026-10-04', '2026-12-25'] });
    assert.deepEqual(value.weekdays, []); assert.equal(value.monthDay, null); assert.deepEqual(value.holidays, ['2026-10-04', '2026-12-25']);
    assert.deepEqual(model.canonicalSchedule({ ...value, frequency: 'WEEKLY', weekdays: [7, 1, 1] }).weekdays, [1, 7]);
    assert.notEqual(model.previewKey(value, 1), model.previewKey(value, 2));
    const fields = model.templateFields(template); fields.checklist[0].title = 'Local title'; assert.equal(template.checklist[0].title, 'Record outcome');
});
test('operation guard aborts superseded channels and every request on identity invalidation', () => {
    const guard = model.createRoutineOperationGuard(), first = guard.begin('preview'), list = guard.begin('templates'), next = guard.begin('preview');
    assert.equal(first.signal.aborted, true); assert.equal(first.current(), false); assert.equal(list.current(), true); first.finish(); assert.equal(next.current(), true);
    guard.invalidate(); assert.equal(list.signal.aborted, true); assert.equal(next.current(), false);
});
test('workspace makes no transport requests before opening and does not enumerate unsupported roles', async () => {
    const h = harness(); assert.equal(h.calls.length, 0); await h.session.open(); assert.deepEqual(h.calls.map(item => item.name), ['context', 'templates', 'schedules']);
    const denied = new RoutineSession(h.api, 'account-b', 'Employee'); await denied.open(); assert.equal(denied.state.open, false);
});
test('paginated lists publish only the newest page and correct a page removed by concurrent updates', async () => {
    const old = deferred(), current = deferred();
    const h = harness({ templates: number => number === 1 ? old.promise : number === 2 ? current.promise : Promise.resolve(page([template])) }); await h.session.open();
    const first = h.session.loadTemplates(1), second = h.session.loadTemplates(2); old.resolve(page([{ ...template, title: 'Stale department title' }], 1, 3)); await first;
    assert.equal(h.session.state.templates, null); current.resolve(page([{ ...template, title: 'Current page' }], 2, 3)); await second; assert.equal(h.session.state.templates.items[0].title, 'Current page');
    h.api.templates = async number => page([template], number, 1);
    const shrinking = harness({ templates: async number => page([template], number, 1) }); await shrinking.session.open(); await shrinking.session.loadTemplates(2); assert.equal(shrinking.session.state.templatePage, 0);
});
test('changing any schedule field cancels a pending preview and requires a new accepted preview', async () => {
    const pending = deferred(); const h = await prepared({ preview: () => pending.promise });
    const loading = h.session.previewSchedule(); const request = h.calls.find(item => item.name === 'preview');
    h.session.changeSchedule({ ...definition, localTime: '10:00' }); assert.equal(request.args[1].aborted, true);
    pending.resolve(preview); await loading; assert.equal(h.session.state.preview, null); assert.equal(h.session.state.busy.includes('preview'), false);
    await h.session.saveSchedule(); assert.equal(h.calls.filter(item => item.name === 'createSchedule').length, 0); assert.match(h.session.state.errors.schedule, /Preview/);
});
test('updated template version invalidates preview while keeping schedule fields', async () => {
    const h = await prepared({ templates: async () => page([{ ...template, version: 2, dueOffsetDays: 4 }]) });
    await h.session.previewSchedule(); assert.ok(h.session.state.preview); await h.session.loadTemplates();
    assert.equal(h.session.state.preview, null); assert.equal(h.session.state.scheduleDraft.template.version, 2); assert.equal(h.session.state.scheduleDraft.fields.employeeId, 'employee-a');
});
test('version conflict retains full local fields, loads one scoped current template and never automatically replays', async () => {
    let currentVersion = 1;
    const saved = { ...template, version: 8, instructions: 'Updated saved instructions.', assigneeRule: 'TEAM_LEAD', dueOffsetDays: 5, checklist: [{ title: 'New current requirement', required: false }] };
    const h = harness({ template: async () => saved, updateTemplate: async (_id, fields) => { if (currentVersion === 1) throw problem(409); return { ...template, ...fields, version: 9 }; } });
    await h.session.open(); h.session.openTemplate(template); const local = { ...model.templateFields(template), title: 'Retained local title', instructions: 'Local instructions are retained.' }; h.session.changeTemplate(local);
    await h.session.saveTemplate(); assert.equal(h.session.state.templateDraft.conflict, true); assert.deepEqual(h.session.state.templateDraft.fields, local);
    await h.session.saveTemplate(); assert.equal(h.calls.filter(item => item.name === 'updateTemplate').length, 1);
    await h.session.reloadTemplateConflict(); assert.equal(h.calls.filter(item => item.name === 'template').length, 1); assert.deepEqual(h.session.state.templateDraft.current, saved); assert.deepEqual(h.session.state.templateDraft.fields, local); assert.equal(h.session.state.templateDraft.expectedVersion, 8);
    assert.equal(h.calls.filter(item => item.name === 'updateTemplate').length, 1); currentVersion = 8; await h.session.saveTemplate(); assert.equal(h.calls.filter(item => item.name === 'updateTemplate')[1].args[1].expectedVersion, 8);
});
test('an unconfirmed create locks fields and reuses the same request id and payload until a receipt is confirmed', async () => {
    let attempt = 0;
    const h = await prepared({ createSchedule: async fields => { if (++attempt === 1) throw problem(503, 'Response unavailable'); return { ...schedule, ...fields }; } }); await h.session.previewSchedule();
    await h.session.saveSchedule(); const requestId = h.session.state.scheduleDraft.requestId;
    assert.equal(h.session.state.scheduleDraft.unknown, true); h.session.changeSchedule({ ...definition, localTime: '11:00' }); h.session.closeSchedule(); assert.equal(h.session.state.scheduleDraft.fields.localTime, '09:00');
    await h.session.saveSchedule(); const creates = h.calls.filter(item => item.name === 'createSchedule'); assert.equal(creates[0].args[0].requestId, requestId); assert.deepEqual(creates[1].args[0], creates[0].args[0]); assert.equal(h.session.state.scheduleDraft, null);
});
test('template create retry also preserves its idempotency key after unknown service response', async () => {
    let attempt = 0; const h = harness({ createTemplate: async fields => { if (++attempt === 1) throw new Error('Disconnected'); return { ...template, ...fields }; } }); await h.session.open(); h.session.openTemplate(); h.session.changeTemplate(model.templateFields(template)); await h.session.saveTemplate();
    assert.equal(h.session.state.templateDraft.unknown, true); const requestId = h.session.state.templateDraft.requestId; h.session.changeTemplate(model.emptyTemplate()); h.session.closeTemplate(); await h.session.saveTemplate();
    assert.equal(h.calls.filter(item => item.name === 'createTemplate')[1].args[0].requestId, requestId); assert.equal(h.session.state.templateDraft, null);
});
test('account invalidation clears forms, previews, names, receipts and late mutation completions', async () => {
    const pending = deferred(); const h = await prepared({ createSchedule: () => pending.promise }); await h.session.previewSchedule(); const saving = h.session.saveSchedule();
    const request = h.calls.find(item => item.name === 'createSchedule'); h.session.invalidate(); assert.equal(request.args[1].aborted, true);
    for (const key of ['context', 'templates', 'schedules', 'templateDraft', 'scheduleDraft', 'preview', 'history']) assert.equal(h.session.state[key], null);
    pending.resolve(schedule); await saving; assert.equal(h.session.state.notice, ''); assert.equal(h.session.state.open, false); assert.equal(h.session.state.busy.length, 0);
    assert.equal(h.calls.filter(item => item.name === 'schedules').length, 1);
});
test('scope denial on a history read or retry removes all previously visible department data', async () => {
    const h = harness({ occurrences: async () => { throw problem(404); } }); await h.session.open(); await h.session.openHistory(schedule);
    assert.equal(h.session.state.denied, true); assert.equal(h.session.state.history, null); assert.equal(h.session.state.context, null);
    const retry = harness({ retry: async () => { throw problem(403); } }); await retry.session.open(); await retry.session.openHistory(schedule); await retry.session.retryOccurrence(blocked); assert.equal(retry.session.state.schedules, null);
});
test('paused schedules prevent retry; resume is versioned and retains occurrence history', async () => {
    const h = harness(); await h.session.open(); await h.session.openHistory({ ...schedule, paused: true }); await h.session.retryOccurrence(blocked); assert.equal(h.calls.filter(item => item.name === 'retry').length, 0);
    await h.session.toggleSchedule(h.session.state.history.schedule); assert.deepEqual(h.calls.find(item => item.name === 'setState').args.slice(0, 3), ['schedule-a', 1, false]); assert.equal(h.session.state.history.schedule.paused, false); assert.match(h.session.state.notice, /Elapsed paused dates/);
    await h.session.retryOccurrence(blocked); assert.equal(h.session.state.history.data.items[0].taskId, 'task-once'); assert.match(h.session.state.notice, /recorded once/);
    await h.session.retryOccurrence(h.session.state.history.data.items[0]); assert.equal(h.calls.filter(item => item.name === 'retry').length, 1);
});
test('retry and schedule conflicts require an explicit reload with fresh versions', async () => {
    const h = harness({ retry: async () => { throw problem(409); }, setState: async () => { throw problem(409); } }); await h.session.open(); await h.session.openHistory(schedule);
    await h.session.retryOccurrence(blocked); assert.equal(h.session.state.history.conflict, true); await h.session.retryOccurrence(blocked); assert.equal(h.calls.filter(item => item.name === 'retry').length, 1);
    await h.session.loadHistory(); assert.equal(h.session.state.history.conflict, false);
    await h.session.toggleSchedule(schedule); assert.equal(h.session.state.stateConflict, schedule.id); await h.session.toggleSchedule(schedule); assert.equal(h.calls.filter(item => item.name === 'setState').length, 1); await h.session.loadSchedules(); assert.equal(h.session.state.stateConflict, null);
});
test('empty lists, empty history and transport failures remain distinguishable and recoverable', async () => {
    const h = harness({ templates: async () => page([]), schedules: async () => page([]), occurrences: async () => page([]) }); await h.session.open(); assert.equal(h.session.state.templates.items.length, 0); assert.equal(h.session.state.schedules.items.length, 0); await h.session.openHistory(schedule); assert.equal(h.session.state.history.data.items.length, 0);
    const failed = harness({ context: async () => { throw problem(503, 'Context unavailable'); } }); await failed.session.open(); assert.equal(failed.session.state.open, true); assert.equal(failed.session.state.context, null); assert.match(failed.session.state.errors.workspace, /Context unavailable/); assert.equal(failed.session.state.busy.length, 0);
});
test('API transport uses bounded pages, scoped escaped paths, caller cancellation and no automatic mutation replay', async () => {
    const calls = [], apiRequest = async (...args) => { calls.push(args); return {}; };
    const api = runtime('api/routines-api.ts', { '../../../lib/api-client': { apiRequest } }).routinesApi, signal = new AbortController().signal;
    await api.templates(3, 20, signal); await api.template('id/a', signal); await api.createTemplate({ ...model.templateFields(template), requestId: 'request-a' }, signal); await api.retry('id/a', '2026-10-05', 8, signal);
    assert.equal(calls[0][0], '/work-routines/templates?page=3&size=20'); assert.equal(calls[1][0], '/work-routines/templates/id%2Fa'); assert.equal(calls[2][2], false); assert.equal(calls[3][2], false); assert.equal(calls[3][1].signal, signal); assert.deepEqual(JSON.parse(calls[3][1].body), { expectedVersion: 8 });
});
