import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url), ts = require('typescript');
function runtime(path, dependencies = {}) {
    const source = readFileSync(new URL(`../features/work-planning/${path}`, import.meta.url), 'utf8');
    const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText, instance = { exports: {} };
    new Function('require', 'module', 'exports', js)(name => { if (!(name in dependencies)) throw new Error(`Unexpected dependency: ${name}`); return dependencies[name]; }, instance, instance.exports);
    return instance.exports;
}
const model = runtime('planning-model.ts'), { WorkAnalyticsSession, HandoverSession } = runtime('planning-session.ts', { './planning-model': model });
const context = { metricVersion: 'sprint9.v1', officeZone: 'Asia/Kolkata', officeDate: '2026-10-04', scope: 'DEPARTMENT', departmentOptions: [{ id: 'dep-a', name: 'Engineering' }], canReadWorkload: true, canHandover: true };
const filters = model.defaultPeriod(context.officeDate);
const metric = { id: 'WORK07', title: 'Original commitment acceptance', kind: 'RATE', unit: 'PERCENT', value: 50, numerator: 1, denominator: 2, sampleCount: 2, coverageKnown: 2, coverageTotal: 2, excluded: 0, smallSample: true, definition: 'Current valid delivery acceptance by original due date.', reason: null };
const summary = { ...context, generatedAt: '2026-10-04T05:00:00Z', from: filters.from, to: filters.to, departmentId: 'dep-a', cards: [metric], stages: [], trend: [], departmentTrends: [] };
const workload = { ...context, generatedAt: summary.generatedAt, departmentId: 'dep-a', departmentName: 'Engineering', members: [], totals: { activeTasks: 2, dueToday: 1, upcoming: 0, overdueDelivery: 1, pendingReview: 0, blocked: 0, estimatedMinutes: null, estimatedTasks: 0, unestimatedTasks: 2, capacityMinutes: null } };
const recordPage = (number = 0, totalPages = 1) => ({ metricVersion: context.metricVersion, generatedAt: summary.generatedAt, number, size: 20, totalElements: 2, totalPages, items: [] });
const view = { taskId: 'task-a', taskVersion: 3, currentEmployeeId: 'employee-a', originalEmployeeId: 'employee-a', currentAssigneeName: 'Original Worker', originalAssigneeName: 'Original Worker', canHandover: true, unavailableReason: null, eligibleAssignees: [{ employeeId: 'employee-b', displayName: 'New Worker', role: 'EMPLOYEE' }], history: [], historyTruncated: false };
const problem = (status, message = 'Server request failed') => Object.assign(new Error(message), { status });
function deferred() { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
function harness(overrides = {}, role = 'HR Admin') {
    const calls = [], raw = {
        context: async () => context, workload: async () => workload,
        summary: async criteria => ({ ...summary, from: criteria.from, to: criteria.to, departmentId: criteria.departmentId || 'dep-a' }),
        records: async (_metric, _criteria, _version, number) => recordPage(number), export: async () => new Blob(['csv']),
        handover: async () => view,
        changeHandover: async (_task, body) => ({ ...view, taskVersion: body.expectedVersion + 1, currentEmployeeId: body.targetEmployeeId, currentAssigneeName: 'New Worker' }),
        ...overrides,
    };
    const api = Object.fromEntries(Object.entries(raw).map(([name, fn]) => [name, (...args) => { calls.push({ name, args }); return fn(...args); }]));
    return { calls, api, analytics: new WorkAnalyticsSession(api, 'account-a:0', role), handover: new HandoverSession(api, 'task-a', 'account-a:0') };
}
test('inclusive office-date validation covers leap dates, reversed periods and 366-day bounds', () => {
    assert.equal(model.validOfficeDate('2028-02-29'), true); assert.equal(model.validOfficeDate('2026-02-29'), false);
    assert.equal(model.validatePeriod({ from: '2026-01-01', to: '2027-01-01', departmentId: '' }), null);
    assert.match(model.validatePeriod({ from: '2026-01-01', to: '2027-01-02', departmentId: '' }), /366/);
    assert.match(model.validatePeriod({ from: '2026-10-05', to: '2026-10-04', departmentId: '' }), /on or after/);
    assert.deepEqual(model.defaultPeriod('2026-10-04'), { from: '2026-09-05', to: '2026-10-04', departmentId: '' });
});
test('metric display keeps unknown and zero distinct; rates and elapsed distributions carry units', () => {
    assert.equal(model.metricValue({ ...metric, value: null }), 'Unknown'); assert.equal(model.metricValue({ ...metric, value: 0 }), '0%');
    assert.equal(model.metricValue({ ...metric, value: 7200, unit: 'SECONDS' }), '2.0 hr'); assert.equal(model.durationLabel(null), 'Unknown');
    assert.equal(model.durationLabel(0), '0 sec'); assert.equal(model.metricValue({ ...metric, value: 60, unit: 'MINUTES' }), '60 min');
});
test('closed analytics and handover sessions make no requests; unsupported roles stay closed', async () => {
    const h = harness(); assert.equal(h.calls.length, 0); const unsupported = harness({}, 'System Admin'); await unsupported.analytics.open(); assert.equal(unsupported.calls.length, 0);
    await h.analytics.open(); assert.deepEqual(h.calls.map(call => call.name), ['context', 'summary', 'workload']); assert.equal(h.analytics.state.summary.metricVersion, 'sprint9.v1');
});
test('workload availability is decided by current context rather than role-side assumptions', async () => {
    const h = harness({ context: async () => ({ ...context, canReadWorkload: false }) }, 'Employee'); await h.analytics.open(); assert.equal(h.calls.filter(call => call.name === 'workload').length, 0); assert.ok(h.analytics.state.summary);
});
test('invalid date or foreign department never sends an analytics query', async () => {
    const h = harness(); await h.analytics.open(); h.analytics.changeFilters({ ...filters, departmentId: 'foreign' }); await h.analytics.load(); assert.match(h.analytics.state.error, /authorized/); assert.equal(h.calls.filter(call => call.name === 'summary').length, 1);
    h.analytics.changeFilters({ ...filters, to: '2026-02-30' }); await h.analytics.load(); assert.match(h.analytics.state.error, /valid/); assert.equal(h.analytics.state.summary, null);
});
test('filter edits cancel old queries, clear prior cohorts and discard late generations', async () => {
    const pending = deferred(); const h = harness({ summary: () => pending.promise }); const loading = h.analytics.open(); await Promise.resolve();
    h.analytics.changeFilters({ ...filters, from: '2026-10-01' }); const signal = h.calls.find(call => call.name === 'summary').args[1]; assert.equal(signal.aborted, true);
    pending.resolve(summary); await loading; assert.equal(h.analytics.state.summary, null); assert.equal(h.analytics.state.applied, null); assert.equal(h.analytics.state.filters.from, '2026-10-01');
});
test('responses with a changed cohort, scope or definition version cannot populate the displayed summary', async () => {
    for (const changed of [{ from: '2026-01-01' }, { scope: 'COMPANY' }, { metricVersion: 'future.v2' }, { departmentId: 'foreign' }]) { const h = harness({ summary: async () => ({ ...summary, ...changed }) }); await h.analytics.open(); assert.equal(h.analytics.state.summary, null); assert.match(h.analytics.state.error, /period or scope/); }
});
test('record pagination publishes only the newest page and snaps back after cohort shrink', async () => {
    const pending = deferred(); const h = harness({ records: async (_m, _f, _v, number) => number === 1 ? pending.promise : recordPage(number, 3) }); await h.analytics.open(); h.analytics.openRecords(metric); await Promise.resolve();
    const old = h.analytics.loadRecords(1), fresh = h.analytics.loadRecords(2); await fresh; pending.resolve(recordPage(1, 3)); await old; assert.equal(h.analytics.state.records.page, 2); assert.equal(h.analytics.state.records.data.number, 2);
    const shrunk = harness({ records: async (_m, _f, _v, number) => recordPage(number, 1) }); await shrunk.analytics.open(); shrunk.analytics.openRecords(metric); await Promise.resolve(); await shrunk.analytics.loadRecords(4); assert.equal(shrunk.analytics.state.records.page, 0);
});
test('drill-down and export use the exact applied filters and displayed metric version', async () => {
    const h = harness(); await h.analytics.open(); h.analytics.changeFilters({ from: '2026-10-01', to: '2026-10-04', departmentId: 'dep-a' }); await h.analytics.load(); h.analytics.openRecords(metric); await Promise.resolve(); await Promise.resolve();
    const result = await h.analytics.exportRecords(); const record = h.calls.find(call => call.name === 'records'), exported = h.calls.find(call => call.name === 'export');
    assert.deepEqual(record.args.slice(0, 3), ['WORK07', { from: '2026-10-01', to: '2026-10-04', departmentId: 'dep-a' }, 'sprint9.v1']); assert.deepEqual(exported.args.slice(0, 3), record.args.slice(0, 3)); assert.match(result.filename, /WORK07-2026-10-01-2026-10-04-sprint9.v1/);
});
test('failed or oversized exports produce an honest error and no downloadable artifact', async () => {
    const h = harness({ export: async () => { throw problem(422, 'Export exceeds 5,000 observations. Choose a smaller period.'); } }); await h.analytics.open(); h.analytics.openRecords(metric); await Promise.resolve(); await Promise.resolve(); assert.equal(await h.analytics.exportRecords(), null); assert.match(h.analytics.state.recordsError, /5,000/);
});
test('account change and access loss clear retained workload, records and in-flight exports', async () => {
    const pending = deferred(), h = harness({ export: () => pending.promise }); await h.analytics.open(); h.analytics.openRecords(metric); await Promise.resolve(); await Promise.resolve(); const exporting = h.analytics.exportRecords();
    h.analytics.invalidate(); pending.resolve(new Blob(['private'])); assert.equal(await exporting, null); for (const key of ['context', 'workload', 'summary', 'records', 'applied']) assert.equal(h.analytics.state[key], null); assert.equal(h.analytics.state.open, false);
    const denied = harness({ records: async () => { throw problem(403); } }); await denied.analytics.open(); denied.analytics.openRecords(metric); await Promise.resolve(); await Promise.resolve(); assert.equal(denied.analytics.state.context, null);
});
test('handover validates current eligibility and trimmed reason before any write', async () => {
    const h = harness(); await h.handover.load(); h.handover.openDraft(); h.handover.changeDraft({ targetEmployeeId: 'foreign', reason: 'Review the transfer.' }); assert.equal(await h.handover.save(), false); assert.match(h.handover.state.error, /eligible/);
    h.handover.changeDraft({ targetEmployeeId: 'employee-b', reason: '    ' }); await h.handover.save(); assert.equal(h.calls.filter(call => call.name === 'changeHandover').length, 0);
    assert.match(model.validateHandover({ ...view, canHandover: false, unavailableReason: 'Closed by CEO' }, 'employee-b', 'valid reason'), /Closed by CEO/);
});
test('CAS handover conflicts retain fields, block replay, require reload plus explicit current-state review', async () => {
    let conflicts = true; const h = harness({ handover: async () => ({ ...view, taskVersion: conflicts ? 3 : 8 }), changeHandover: async (_id, body) => { if (conflicts) throw problem(409, 'Worksheet changed'); return { ...view, taskVersion: body.expectedVersion + 1, currentEmployeeId: body.targetEmployeeId, currentAssigneeName: 'New Worker' }; } });
    await h.handover.load(); h.handover.openDraft(); const fields = { targetEmployeeId: 'employee-b', reason: 'Retained handover reason.' }; h.handover.changeDraft(fields); await h.handover.save(); assert.equal(h.handover.state.conflict, true); assert.deepEqual(h.handover.state.draft, fields);
    await h.handover.save(); assert.equal(h.calls.filter(call => call.name === 'changeHandover').length, 1); conflicts = false; await h.handover.load(); assert.equal(h.handover.state.view.taskVersion, 8); assert.equal(h.handover.state.reviewRequired, true); await h.handover.save(); assert.equal(h.calls.filter(call => call.name === 'changeHandover').length, 1);
    h.handover.acknowledgeCurrent(); assert.equal(await h.handover.save(), true); assert.equal(h.calls.filter(call => call.name === 'changeHandover')[1].args[1].expectedVersion, 8); assert.match(h.handover.state.notice, /confirmed/);
});
test('unconfirmed handover locks fields and closing until explicit reload, never claims success', async () => {
    const h = harness({ changeHandover: async () => { throw problem(503, 'Handover response unavailable'); } }); await h.handover.load(); h.handover.openDraft(); const fields = { targetEmployeeId: 'employee-b', reason: 'Retained original reason.' }; h.handover.changeDraft(fields); await h.handover.save(); assert.equal(h.handover.state.uncertain, true); assert.equal(h.handover.state.notice, '');
    h.handover.changeDraft({ ...fields, reason: 'Altered' }); h.handover.closeDraft(); assert.deepEqual(h.handover.state.draft, fields); await h.handover.save(); assert.equal(h.calls.filter(call => call.name === 'changeHandover').length, 1);
    await h.handover.load(); assert.equal(h.handover.state.uncertain, false); assert.equal(h.handover.state.reviewRequired, true); assert.doesNotMatch(h.handover.state.notice, /confirmed/);
});
test('a mismatched handover mutation receipt is treated as uncertain and not as success', async () => {
    const h = harness({ changeHandover: async () => view }); await h.handover.load(); h.handover.openDraft(); h.handover.changeDraft({ targetEmployeeId: 'employee-b', reason: 'Valid transfer reason.' }); assert.equal(await h.handover.save(), false); assert.equal(h.handover.state.uncertain, true); assert.equal(h.handover.state.notice, '');
});
test('session invalidation during handover removes authors and ignores a late successful response', async () => {
    const pending = deferred(), h = harness({ changeHandover: () => pending.promise }); await h.handover.load(); h.handover.openDraft(); h.handover.changeDraft({ targetEmployeeId: 'employee-b', reason: 'Valid handover reason.' }); const saving = h.handover.save(); const signal = h.calls.find(call => call.name === 'changeHandover').args[2]; h.handover.invalidate(); assert.equal(signal.aborted, true);
    pending.resolve({ ...view, taskVersion: 4, currentEmployeeId: 'employee-b' }); assert.equal(await saving, false); assert.equal(h.handover.state.view, null); assert.equal(h.handover.state.draft, null); assert.equal(h.handover.state.notice, '');
});
test('handover scope denial clears prior assignment history and draft fields', async () => {
    const h = harness({ changeHandover: async () => { throw problem(404); } }); await h.handover.load(); h.handover.openDraft(); h.handover.changeDraft({ targetEmployeeId: 'employee-b', reason: 'Valid handover reason.' }); await h.handover.save(); assert.equal(h.handover.state.view, null); assert.equal(h.handover.state.draft, null);
});
test('API uses escaped task paths, bounded record pages, exact filters/version and no mutation replay', async () => {
    const calls = [], apiRequest = async (...args) => { calls.push(args); return {}; }, apiDownload = async (...args) => { calls.push(args); return new Blob(); };
    const api = runtime('api/planning-api.ts', { '../../../lib/api-client': { apiRequest, apiDownload }, '../planning-model': model }).planningApi, signal = new AbortController().signal;
    await api.records('WORK07', { ...filters, departmentId: 'dep/a' }, 'sprint9.v1', 2, signal); await api.export('WORK07', filters, 'sprint9.v1', signal); await api.changeHandover('task/a', { expectedVersion: 3, targetEmployeeId: 'employee-b', reason: 'Valid reason' }, signal);
    assert.match(calls[0][0], /departmentId=dep%2Fa/); assert.match(calls[0][0], /metricVersion=sprint9.v1&page=2&size=20/); assert.equal(calls[0][1].cache, 'no-store'); assert.match(calls[1][0], /WORK07\/export.csv/); assert.equal(calls[2][0], '/work-tasks/task%2Fa/handover'); assert.equal(calls[2][2], false); assert.equal(calls[2][1].signal, signal);
});
