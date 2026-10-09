import { test } from 'node:test';
import assert from 'node:assert/strict';
import { percentile, runWorkload } from '../lib/load-workload.mjs';

test('nearest-rank quantiles retain zero and do not fabricate missing measurements', () => {
  assert.equal(percentile([], .95), null); assert.equal(percentile([0], .95), 0);
  assert.equal(percentile(Array.from({ length: 100 }, (_, i) => i), .95), 94);
});
test('mixed run measures mutations, accounts for transport and HTTP failures, and redacts credentials', async () => {
  let calls = 0; const methods = [], secret = 'private-session';
  const result = await runWorkload({ actors: [{ user: secret, token: secret, role: 'ROLE_TEAM_LEAD', targetEmployee: secret }], durationSeconds: 1, thinkMs: 100,
    call: async (_actor, _path, method) => { calls++; methods.push(method); if (calls === 1) throw new Error(secret); return { status: calls === 2 ? 429 : 200 }; } });
  assert.ok(methods.includes('POST')); assert.equal(result.errors, 2); assert.equal(result.measuredTargetsPass, false);
  assert.equal(result.customerCapacityAccepted, false); assert.ok(!JSON.stringify(result).includes(secret));
});
test('sample reservations cannot overrun the cap under concurrency or certify incomplete coverage', async () => {
  const result = await runWorkload({ actors: [{ user: '1' }, { user: '2' }], durationSeconds: 1, thinkMs: 100, maxSamples: 1, call: async () => ({ status: 200 }) });
  assert.equal(result.requests, 1); assert.equal(result.complete, false); assert.equal(result.measuredTargetsPass, false);
  await assert.rejects(() => runWorkload({ actors: [{ user: '1' }, { user: '1' }] }), /distinct/);
});
test('HTTP and invalid-response errors preserve numeric status instead of becoming transport failures', async () => {
  let calls = 0;
  const result = await runWorkload({ actors: [{ user: 'synthetic' }], durationSeconds: 1, thinkMs: 100, maxSamples: 2,
    call: async () => { throw Object.assign(new Error('Invalid response'), { status: ++calls === 1 ? 418 : 200 }); } });
  assert.equal(result.errors, 2);
  assert.equal(result.endpoints['dashboard-read'].statuses[418], 1);
  assert.equal(result.endpoints['workboard-read'].statuses[200], 1);
  assert.equal(result.endpoints['dashboard-read'].statuses[0], undefined);
});
