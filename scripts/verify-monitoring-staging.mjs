import assert from 'node:assert/strict';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

export async function verifyMonitoringStaging({ adminPerson, employeePerson, evidenceDir, control }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  const get = async (path, person) => {
    const response = await fetch(`http://127.0.0.1:8080/actuator/${path}`, { redirect: 'error', signal: AbortSignal.timeout(20000), headers: person ? { Authorization: `Bearer ${person.token}` } : {} });
    const text = await response.text(); assert.ok(text.length <= 1024 * 1024);
    return { status: response.status, text };
  };
  assert.equal((await get('prometheus')).status, 401);
  assert.equal((await get('prometheus', employeePerson)).status, 403);
  const metrics = await get('prometheus', adminPerson); assert.equal(metrics.status, 200);
  for (const name of ['http_server_requests_seconds_bucket', 'jvm_memory_used_bytes', 'hikaricp_connections_active']) assert.ok(metrics.text.includes(name), `Required metric missing: ${name}`);
  assert.ok(!metrics.text.includes(adminPerson.token) && !metrics.text.includes('@sprint6.invalid'));
  const ready = await get('health/readiness'); assert.equal(ready.status, 200); assert.equal(JSON.parse(ready.text).status, 'UP');
  assert.equal(JSON.parse(ready.text).components, undefined, 'Anonymous health must hide dependencies');
  const started = performance.now(); let down = false;
  try {
    control(['stop', 'redis']);
    for (let i = 0; i < 10; i++) {
      const result = await get('health/readiness');
      if (result.status === 503 && JSON.parse(result.text).status === 'DOWN') { down = true; break; }
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.ok(down, 'Readiness must reject traffic without Redis');
    assert.equal((await get('health/liveness')).status, 200, 'Redis loss must not trigger a restart loop');
  } finally { control(['up', '-d', '--wait', '--wait-timeout', '90', 'redis']); }
  let recovered = false;
  for (let i = 0; i < 40; i++) {
    const result = await get('health/readiness');
    if (result.status === 200 && JSON.parse(result.text).status === 'UP') { recovered = true; break; }
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert.ok(recovered, 'Readiness must recover when Redis returns');
  writeFileSync(join(evidenceDir, 'monitoring-report.json'), JSON.stringify({ schemaVersion: 1, releaseId: process.env.RELEASE_ID, environment: 'disposable-single-runner', metricsAuthorization: 'verified', routeHistogram: 'verified', anonymousHealthRedaction: 'verified', redisOutageReadiness: 'verified', livenessDuringOutage: 'verified', recovered, elapsedSeconds: Math.round((performance.now() - started) / 10) / 100 }, null, 2) + '\n', { mode: 0o600 });
  console.log('SPRINT15_MONITORING_REDIS_RECOVERY_VERIFIED');
}
