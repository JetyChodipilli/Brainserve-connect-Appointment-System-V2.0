import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// This disposable-stack drill proves API boundaries and restore coverage.
// It deliberately leaves Google disabled; it cannot satisfy live provider UAT.
export async function verifyCalendarStaging({ call, sql, adminPerson, employeePerson }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  const config = await call(adminPerson, '/integrations/google-calendar/config');
  assert.equal(config.headers['cache-control'], 'no-store');
  assert.deepEqual(config.json, { configured: false, scope: 'https://www.googleapis.com/auth/calendar.app.created', usesDedicatedCalendar: true });
  await call(employeePerson, '/integrations/google-calendar/config', 'GET', undefined, [401, 403]);
  await call(employeePerson, '/integrations/google-calendar/consents', 'GET', undefined, [401, 403]);
  await call(employeePerson, '/integrations/google-calendar/calendar.ics', 'GET', undefined, [401, 403]);
  const file = await call(adminPerson, '/integrations/google-calendar/calendar.ics', 'GET', undefined, 200, 'text/calendar');
  assert.equal(file.headers['cache-control'], 'no-store');
  assert.equal(file.headers['x-content-type-options'], 'nosniff');
  assert.match(file.headers['content-disposition'], /^attachment;/);
  assert.match(file.headers['content-type'], /^text\/calendar/);
  const text = file.bytes.toString('utf8');
  assert.ok(text.startsWith('BEGIN:VCALENDAR\r\n') && text.endsWith('END:VCALENDAR\r\n'));
  assert.ok(!text.includes('@sprint6.invalid') && !text.includes('ATTENDEE:'));

  // Terminal fixtures cannot execute provider work. Their nonempty records must
  // survive logical backup/restore unchanged, including retained encrypted fields.
  const connection = randomUUID(), consent = randomUUID(), revoke = randomUUID(), repair = randomUUID();
  sql(`insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_version,credential_expires_at,last_result_code)
    values('${connection}','${randomUUID()}','GOOGLE_CALENDAR','CALENDAR','Disposable Google restore fixture','${adminPerson.user}',
      '["https://www.googleapis.com/auth/calendar.app.created"]','REVOKED',1,now()+interval '1 day','REVOKED');
    insert into integration_google_calendar(connection_id,revocation_status,last_result_code) values('${connection}','FAILED','REVOKED');
    insert into integration_google_consent(id,request_id,connection_id,owner_id,session_id,observed_version,label,status,expires_at,last_result_code)
      values('${consent}','${randomUUID()}','${connection}','${adminPerson.user}','${adminPerson.family}',0,'Disposable consent restore fixture','DENIED',now()-interval '1 day','CONSENT_DENIED');
    insert into integration_google_revocation(id,connection_id,credential_version,token_ciphertext,status,attempts,recovery_count,last_result_code)
      values('${revoke}','${connection}',1,'synthetic-non-provider-ciphertext','FAILED',10,3,'REVOKE_FAILED');
    insert into integration_calendar_reconciliation(id,request_id,connection_id,actor_id,expected_version,credential_version,status,completed_at)
      values('${repair}','${randomUUID()}','${connection}','${adminPerson.user}',0,1,'CANCELLED',now());`);
  const metadata = await call(adminPerson, `/integrations/google-calendar/connections/${connection}`);
  assert.equal(metadata.headers['cache-control'], 'no-store');
  assert.equal(metadata.json.provisioningStatus, 'UNPROVISIONED');
  const reconciliation = await call(adminPerson, `/integrations/connections/${connection}/reconciliation`);
  assert.equal(reconciliation.json.status, 'CANCELLED');
  const serialized = JSON.stringify((await call(adminPerson, '/integrations/google-calendar/consents')).json);
  assert.ok(!serialized.includes('ciphertext') && !serialized.includes('authorizationUrl') && !serialized.includes('sessionId'));
  console.log('SPRINT12_CALENDAR_BOUNDARIES_RESTORE_FIXTURES_VERIFIED');
}
