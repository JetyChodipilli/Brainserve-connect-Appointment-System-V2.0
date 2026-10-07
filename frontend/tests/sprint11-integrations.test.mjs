import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

function loadApi(file, dependency) {
    const source = readFileSync(new URL(file, import.meta.url), 'utf8');
    const js = stripTypeScriptTypes(source.replace(/^import .*;$/gm, ''), { mode: 'transform' }).replace('export const', 'const');
    return new Function('apiRequest', 'requestSpringPage', `${js}; return { ${file.includes('integrations-api') ? 'integrationsApi' : 'supportApi'} };`)(dependency.apiRequest, dependency.requestSpringPage);
}
const calls = [], request = (...args) => { calls.push(args); return Promise.resolve({}); };
const { integrationsApi } = loadApi('../features/integrations/api/integrations-api.ts', { apiRequest: request, requestSpringPage: request });
const { supportApi } = loadApi('../features/support/api/support-api.ts', { apiRequest: request });

test('integration and diagnostics reads propagate cancellation and never use a stored response', async () => {
    calls.length = 0; const controller = new AbortController();
    await integrationsApi.connections(controller.signal); await integrationsApi.deliveries('connection/a', 2, controller.signal); await integrationsApi.attempts('delivery/a', controller.signal);
    await supportApi.preview(6, controller.signal); await supportApi.packages(controller.signal); await supportApi.download('package/a', controller.signal);
    assert.deepEqual(calls.map(item => item[0]), ['/integrations/connections', '/integrations/connections/connection%2Fa/deliveries?page=2&size=20', '/integrations/deliveries/delivery%2Fa/attempts', '/support/diagnostics/preview?hours=6', '/support/diagnostics', '/support/diagnostics/package%2Fa/download']);
    for (const call of calls) { assert.equal(call[1].signal, controller.signal); assert.equal(call[1].cache, 'no-store'); }
});

test('all writes prevent automatic replay, carry observed versions and use fresh request IDs where required', async () => {
    calls.length = 0; const controller = new AbortController();
    const creation = { requestId: crypto.randomUUID(), provider: 'SIMULATOR_CALENDAR', label: 'Calendar test', credential: 'transient-test-secret', credentialExpiresAt: '2026-11-01T00:00:00Z' };
    await integrationsApi.create(creation, controller.signal); await integrationsApi.reconnect('connection/a', 4, 'replacement-test-secret', creation.credentialExpiresAt, controller.signal);
    await integrationsApi.revoke('connection/a', 5, controller.signal); await integrationsApi.test('connection/a', 6, 'OUTAGE', controller.signal);
    await integrationsApi.retry('delivery/a', 7, controller.signal); await supportApi.generate(12, controller.signal);
    for (const call of calls) { assert.equal(call[2], false); assert.equal(call[1].method, 'POST'); assert.equal(call[1].signal, controller.signal); }
    assert.deepEqual(JSON.parse(calls[0][1].body), creation);
    assert.deepEqual(JSON.parse(calls[1][1].body), { expectedVersion: 4, credential: 'replacement-test-secret', credentialExpiresAt: creation.credentialExpiresAt });
    assert.deepEqual(JSON.parse(calls[2][1].body), { expectedVersion: 5 });
    assert.equal(JSON.parse(calls[3][1].body).scenario, 'OUTAGE'); assert.equal(JSON.parse(calls[4][1].body).expectedVersion, 7);
    const ids = [3, 4, 5].map(index => JSON.parse(calls[index][1].body).requestId);
    assert.equal(new Set(ids).size, 3); ids.forEach(id => assert.match(id, /^[a-f0-9-]{36}$/));
    assert.deepEqual(Object.keys(JSON.parse(calls[5][1].body)).sort(), ['hours', 'requestId']);
});
