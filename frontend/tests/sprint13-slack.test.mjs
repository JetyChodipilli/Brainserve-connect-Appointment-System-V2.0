import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';

const source = readFileSync(new URL('../features/integrations/api/integrations-api.ts', import.meta.url), 'utf8');
const javascript = stripTypeScriptTypes(source.replace(/^import .*;$/gm, ''), { mode: 'transform' }).replaceAll('export ', '');
const calls = [];
const request = (...arguments_) => { calls.push(arguments_); return Promise.resolve({}); };
const api = new Function('apiRequest', 'requestSpringPage', 'apiDownload', `${javascript}; return integrationsApi;`)(request, request, request);

test('Slack availability and metadata reads preserve cancellation, no-store and encoded connection boundaries', async () => {
    calls.length = 0; const { signal } = new AbortController();
    await api.slackConfig(signal); await api.slackConnection('connection/a', signal);
    assert.deepEqual(calls.map(call => call[0]), ['/integrations/slack/config', '/integrations/slack/connections/connection%2Fa']);
    for (const call of calls) { assert.equal(call[1].signal, signal); assert.equal(call[1].cache, 'no-store'); }
});

test('Slack creation, manual credential renewal and revocation retry use explicit versioned writes without automatic replay', async () => {
    calls.length = 0; const { signal } = new AbortController();
    const creation = { requestId: crypto.randomUUID(), label: 'Reception notices', channelId: 'C12345678', credential: 'xoxb-transient-test-credential', credentialExpiresAt: '2026-11-01T00:00:00Z' };
    await api.createSlack(creation, signal); await api.renewSlack('connection/a', 7, 'xoxb-transient-replacement', creation.credentialExpiresAt, signal); await api.retrySlackRevocation('connection/a', 8, signal);
    assert.deepEqual(calls.map(call => call[0]), ['/integrations/slack/connections', '/integrations/slack/connections/connection%2Fa/renew', '/integrations/slack/connections/connection%2Fa/revocation/retry']);
    for (const call of calls) { assert.equal(call[1].method, 'POST'); assert.equal(call[1].signal, signal); assert.equal(call[2], false); }
    assert.deepEqual(JSON.parse(calls[0][1].body), creation);
    assert.deepEqual(JSON.parse(calls[1][1].body), { expectedVersion: 7, credential: 'xoxb-transient-replacement', credentialExpiresAt: creation.credentialExpiresAt });
    assert.deepEqual(JSON.parse(calls[2][1].body), { expectedVersion: 8 });
});

test('uncertain-delivery risk acknowledgement is never implicit in the existing retry contract', async () => {
    calls.length = 0; const { signal } = new AbortController();
    await api.retry('delivery/a', 7, signal); await api.retry('delivery/a', 8, signal, false); await api.retry('delivery/a', 9, signal, true);
    const bodies = calls.map(call => JSON.parse(call[1].body));
    assert.deepEqual(bodies.map(body => body.expectedVersion), [7, 8, 9]);
    assert.equal(Object.hasOwn(bodies[0], 'acceptDuplicateRisk'), false); assert.equal(Object.hasOwn(bodies[1], 'acceptDuplicateRisk'), false); assert.equal(bodies[2].acceptDuplicateRisk, true);
    assert.equal(new Set(bodies.map(body => body.requestId)).size, 3);
    for (const call of calls) { assert.equal(call[0], '/integrations/deliveries/delivery%2Fa/retry'); assert.equal(call[1].signal, signal); assert.equal(call[2], false); }
});

test('Slack test notice uses the established test command with a fresh request ID and a fixed success scenario', async () => {
    calls.length = 0; const { signal } = new AbortController();
    await api.test('connection/a', 9, 'SUCCESS', signal); await api.test('connection/a', 9, 'SUCCESS', signal);
    const bodies = calls.map(call => JSON.parse(call[1].body));
    for (const call of calls) { assert.equal(call[0], '/integrations/connections/connection%2Fa/test'); assert.equal(call[1].signal, signal); assert.equal(call[2], false); }
    for (const body of bodies) { assert.equal(body.scenario, 'SUCCESS'); assert.equal(body.expectedVersion, 9); assert.deepEqual(Object.keys(body).sort(), ['expectedVersion', 'requestId', 'scenario']); }
    assert.notEqual(bodies[0].requestId, bodies[1].requestId);
});
