import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(new URL('../features/release/api/release-api.ts', import.meta.url), 'utf8');
const javascript = stripTypeScriptTypes(source.replace(/^import .*;$/gm, ''), { mode: 'transform' }).replaceAll('export ', '');
const calls = [];
const api = new Function('apiRequest', `${javascript}; return releaseApi;`)((...args) => { calls.push(args); return Promise.resolve({}); });
test('private release and kiosk configuration reads keep cancellation and no-store', async () => {
    calls.length = 0; const { signal } = new AbortController();
    await api.read(signal); await api.kioskConfig(signal);
    assert.deepEqual(calls.map(call => call[0]), ['/release-profile', '/admin/kiosks/config']);
    for (const call of calls) { assert.equal(call[1].signal, signal); assert.equal(call[1].cache, 'no-store'); }
});
test('manual agreement saves send the observed revision and never automatically replay', async () => {
    calls.length = 0; const { signal } = new AbortController();
    const profile = { status: 'CANCELLED', reference: 'Synthetic contract', startsOn: null, renewsOn: null, supportOwner: '', supportEmail: '', supportHours: '' };
    await api.save(7, profile, signal);
    assert.equal(calls[0][0], '/release-profile'); assert.equal(calls[0][1].method, 'PUT');
    assert.equal(calls[0][1].signal, signal); assert.equal(calls[0][2], false);
    assert.deepEqual(JSON.parse(calls[0][1].body), { expectedVersion: 7, profile });
});
