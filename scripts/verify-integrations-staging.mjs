import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Called only by the disposable TLS drill. Fixture time/scenarios may be changed here.
export async function verifyIntegrationsStaging({ call, sql, adminPerson, employeePerson }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  await call(employeePerson, '/integrations/connections', 'GET', undefined, [401, 403]);
  await call(employeePerson, '/support/diagnostics/preview', 'GET', undefined, [401, 403]);
  const credential = `s11-staging-${randomUUID()}`;
  const create = { requestId: randomUUID(), provider: 'SIMULATOR_CALENDAR', label: 'Disposable calendar simulator',
    credential, credentialExpiresAt: new Date(Date.now() + 3600000).toISOString() };
  const created = await call(adminPerson, '/integrations/connections', 'POST', create);
  assert.equal(created.headers['cache-control'], 'no-store');
  const connection = created.json;
  assert.equal(connection.ownerId, adminPerson.user);
  assert.equal(connection.status, 'ACTIVE');
  assert.ok(connection.minimumScopes.length > 0);
  assert.ok(!JSON.stringify(connection).includes(credential));
  const ciphertext = sql(`select credential_ciphertext from integration_connection where id='${connection.id}';`);
  assert.ok(ciphertext && ciphertext !== credential);
  const current = async () => (await call(adminPerson, '/integrations/connections')).json.find(c => c.id === connection.id);
  async function test(scenario) {
    const saved = await current();
    return (await call(adminPerson, `/integrations/connections/${connection.id}/test`, 'POST', {
      requestId: randomUUID(), expectedVersion: saved.version, scenario
    })).json;
  }
  async function settled(id, status) {
    for (let n = 0; n < 80; n++) {
      if (sql(`select status from integration_delivery where id='${id}';`) === status) return;
      await new Promise(resolve => setTimeout(resolve, 500));
    }
    assert.equal(sql(`select status from integration_delivery where id='${id}';`), status);
  }
  const success = await test('SUCCESS');
  await settled(success.id, 'DELIVERED');
  const attempts = (await call(adminPerson, `/integrations/deliveries/${success.id}/attempts`)).json;
  assert.equal(attempts.length, 1);
  assert.equal(Number(sql(`select count(*) from integration_simulator_receipt where business_event_id='${success.businessEventId}';`)), 1);
  const failure = await test('PERMANENT_FAILURE');
  await settled(failure.id, 'FAILED');
  // Recovery is deterministic without contacting a real provider.
  sql(`update integration_delivery set scenario='SUCCESS' where id='${failure.id}';`);
  const page = (await call(adminPerson, `/integrations/connections/${connection.id}/deliveries?page=0`)).json;
  const failed = page.content.find(d => d.id === failure.id);
  assert.equal(failed.status, 'FAILED');
  await call(adminPerson, `/integrations/deliveries/${failure.id}/retry`, 'POST', { requestId: randomUUID(), expectedVersion: failed.version });
  await settled(failure.id, 'DELIVERED');
  const saved = await current();
  const revoked = (await call(adminPerson, `/integrations/connections/${connection.id}/revoke`, 'POST', { expectedVersion: saved.version })).json;
  assert.equal(revoked.status, 'REVOKED');
  assert.equal(sql(`select credential_ciphertext is null from integration_connection where id='${connection.id}';`), 't');

  const preview = await call(adminPerson, '/support/diagnostics/preview?hours=1');
  assert.equal(preview.headers['cache-control'], 'no-store');
  assert.equal(preview.json.environment, 'STAGING');
  assert.equal(preview.json.schemaVersion, 1);
  const packageResult = await call(adminPerson, '/support/diagnostics', 'POST', { requestId: randomUUID(), hours: 1 });
  assert.ok(packageResult.json.sizeBytes <= 65536);
  const download = await call(adminPerson, `/support/diagnostics/${packageResult.json.id}/download`);
  assert.equal(download.headers['cache-control'], 'no-store');
  assert.equal(download.headers['x-content-type-options'], 'nosniff');
  assert.match(download.headers['content-disposition'], /^attachment;/);
  const text = download.bytes.toString('utf8');
  assert.ok(!text.includes(credential) && !text.includes('@sprint6.invalid'));
  await call(employeePerson, `/support/diagnostics/${packageResult.json.id}/download`, 'GET', undefined, [401, 403]);
  sql(`update support_diagnostic_package set created_at=now()-interval '2 days',expires_at=now()-interval '1 day' where id='${packageResult.json.id}';`);
  await call(adminPerson, `/support/diagnostics/${packageResult.json.id}/download`, 'GET', undefined, 410);
  console.log('SPRINT11_CONNECTIONS_DIAGNOSTICS_VERIFIED');
}
