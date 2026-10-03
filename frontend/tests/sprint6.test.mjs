import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';
const compile = path => ts.transpileModule(readFileSync(new URL('../' + path, import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
function api() { const calls = [], exports = {}; vm.runInNewContext(compile('features/workboard/api/workboard-api.ts'), { exports, URLSearchParams, FormData, require: () => ({ apiRequest: (...args) => { calls.push(args); return Promise.resolve({}); }, apiDownload: (...args) => { calls.push(args); return Promise.resolve(new Blob()); } }) }); return { calls, api: exports.workboardApi }; }
test('evidence uploads use multipart version, avoid automatic replay and never expose object URLs', async () => {
    const { api: client, calls } = api(); await client.uploadEvidence('private/task', 9, new File(['%PDF'], 'synthetic.pdf', { type: 'application/pdf' }));
    assert.equal(calls[0][0], '/work-tasks/private%2Ftask/evidence'); assert.equal(calls[0][1].body.get('expectedVersion'), '9'); assert.equal(calls[0][1].body.get('file').name, 'synthetic.pdf'); assert.equal(calls[0][2], false);
    await client.downloadEvidence('private/task', 'retained/id'); assert.equal(calls[1][0], '/work-tasks/private%2Ftask/evidence/retained%2Fid/download');
});
test('every planning write binds explicit current version and opts out of automatic replay', async () => {
    const { api: client, calls } = api(); await client.saveChecklist('task', 7, ['known']); await client.raiseBlocker('task', 7, 'Need approval', null); await client.resolveBlocker('task', 'blocker', 7, 'Approved'); await client.contactBlocker('task', 'blocker', 7, 'Follow up', 'lead'); await client.savePlanning('task', { expectedVersion: 7, priority: 'HIGH' }); await client.removeEvidence('task', 'file', 7);
    for (const [path, init, retry] of calls) { assert.equal(retry, false); if (init.body) assert.equal(JSON.parse(init.body).expectedVersion, 7); else assert.match(path, /expectedVersion=7/); }
});
test('BLOCKED quick filter and deterministic real priority preserve scoped totals', () => {
    const exports = {}; vm.runInNewContext(compile('features/workboard/utils/workboard-model.ts'), { exports, require: () => ({ officeDateFromInstant: value => value.slice(0, 10) }), Date, Object, Number, Set });
    const items = ['LOW', 'URGENT', 'HIGH', 'NORMAL'].map((priority, index) => ({ id: String(index), priority, blocked: index % 2 === 1, title: 'Task', description: '', departmentBranch: 'ENG', createdAt: '2026-10-03', dueDate: '2026-10-03', lane: 'DELIVERY', allowedActions: [], status: 'ASSIGNED' }));
    const page = exports.previewWorkboardPage(items, { ...exports.defaultCriteria, sort: 'PRIORITY', quickFilter: 'BLOCKED' }, 0, 20, '2026-10-03', true);
    assert.equal(page.totalElements, 2); assert.equal(page.counts.quickFilters.BLOCKED, 2); assert.deepEqual(Array.from(page.items, value => value.priority), ['URGENT', 'NORMAL']); assert.equal(page.counts.scopes.TODAY, 4);
});
test('private byte download rejects a response from a previous account session', async () => {
    const exports = {}, store = new Map(); let release;
    const window = { sessionStorage: { getItem: key => store.get(key) ?? null, setItem: (key, value) => store.set(key, value), removeItem: key => store.delete(key) }, dispatchEvent() {} };
    vm.runInNewContext(compile('lib/api-client.ts'), { exports, process: { env: { NEXT_PUBLIC_API_BASE_URL: 'http://synthetic.invalid/api/v1' } }, window, CustomEvent: class {}, Headers, FormData, AbortController, Map, Set, Error, setTimeout, clearTimeout, fetch: () => new Promise(resolve => { release = resolve; }), require: () => ({}) });
    exports.setAccessToken('first-synthetic-session'); const pending = exports.apiDownload('/work-tasks/task/evidence/id/download'); exports.setAccessToken('second-synthetic-session'); release(new Response('%PDF synthetic', { headers: { 'Content-Type': 'application/pdf' } })); await assert.rejects(pending, /active session changed/);
});
