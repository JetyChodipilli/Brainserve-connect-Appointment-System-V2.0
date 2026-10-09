import assert from 'node:assert/strict';

// Only synthetic data, before the existing real-session load and recovery drill.
export async function verifyReleaseProfileStaging({ call, sql, adminPerson, employeePerson, hrPerson }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  const first = await call(adminPerson, '/release-profile');
  assert.equal(first.headers['cache-control'], 'no-store');
  assert.equal(first.json.version, 0);
  assert.equal(first.json.profile.status, 'UNCONFIGURED');
  assert.equal(first.json.profile.startsOn, null);
  assert.equal(first.json.profile.renewsOn, null);
  const profile = { status: 'ACTIVE', reference: 'Synthetic Sprint 16 agreement', startsOn: first.json.officeDate,
    renewsOn: '2099-12-31', supportOwner: 'Synthetic support owner', supportEmail: 'support@sprint16.invalid', supportHours: 'Synthetic weekdays UTC' };
  for (const person of [employeePerson, hrPerson]) {
    await call(person, '/release-profile', 'GET', undefined, 403);
    await call(person, '/release-profile', 'PUT', { expectedVersion: 0, profile }, 403);
    await call(person, '/admin/kiosks/config', 'GET', undefined, 403);
  }
  const kiosk = await call(adminPerson, '/admin/kiosks/config');
  assert.equal(kiosk.headers['cache-control'], 'no-store');
  assert.equal(typeof kiosk.json.enabled, 'boolean');
  await call(adminPerson, '/release-profile', 'PUT', { expectedVersion: 0, profile: { ...profile, supportOwner: '' } }, 400);
  assert.equal((await call(adminPerson, '/release-profile')).json.version, 0);
  const saved = (await call(adminPerson, '/release-profile', 'PUT', { expectedVersion: 0, profile })).json;
  assert.equal(saved.version, 1); assert.deepEqual(saved.profile, profile);
  await call(adminPerson, '/release-profile', 'PUT', { expectedVersion: 0, profile: { ...profile, reference: 'Stale overwrite' } }, 409);
  const settings = (await call(adminPerson, '/system-settings')).bytes.toString('utf8');
  assert.ok(!settings.includes(profile.reference) && !settings.includes(profile.supportEmail));
  await call(adminPerson, '/system-settings/RELEASE.MANUAL_PROFILE', 'PUT', { value: JSON.stringify(profile) }, 404);
  const cancelled = (await call(adminPerson, '/release-profile', 'PUT', { expectedVersion: saved.version, profile: { ...profile, status: 'CANCELLED' } })).json;
  assert.equal(cancelled.version, 2); assert.equal(cancelled.profile.status, 'CANCELLED');
  assert.equal((await call(adminPerson, '/admin/kiosks/config')).json.enabled, kiosk.json.enabled);
  await call(employeePerson, '/auth/me');
  assert.equal(sql("select count(*) from audit_event where event_type='RELEASE_PROFILE_UPDATED';"), '2');
  console.log('SPRINT16_RELEASE_BOUNDARIES_VERIFIED');
}
