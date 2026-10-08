import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Disposable, terminal fixtures prove release/recovery boundaries without a Slack credential.
export async function verifySlackStaging({ call, sql, adminPerson, employeePerson }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  const config = await call(adminPerson, '/integrations/slack/config');
  assert.equal(config.headers['cache-control'], 'no-store');
  assert.deepEqual(config.json, { configured: false, scope: 'chat:write', usesDedicatedBot: true });
  await call(employeePerson, '/integrations/slack/config', 'GET', undefined, [401, 403]);
  const command = { requestId: randomUUID(), label: 'Disposable Slack', channelId: 'C12345678', credential: 'xoxb-synthetic-never-provider-token', credentialExpiresAt: new Date(Date.now() + 86400000).toISOString() };
  await call(employeePerson, '/integrations/slack/connections', 'POST', command, [401, 403]);
  let rejected = await call(adminPerson, '/integrations/slack/connections', 'POST', command, [409, 429]);
  if (rejected.status === 429) {
    // Earlier sprint checks share this administrator's real write budget. Respect its fence.
    const delay = Number(rejected.headers['retry-after']);
    assert.ok(Number.isInteger(delay) && delay > 0 && delay <= 60, 'Bounded application rate window required');
    await new Promise(resolve => setTimeout(resolve, delay * 1000));
    rejected = await call(adminPerson, '/integrations/slack/connections', 'POST', command, 409);
  }
  assert.equal(rejected.json.errorCode, 'SLACK_NOT_CONFIGURED');

  const connection = randomUUID(), delivery = randomUUID(), revoke = randomUUID();
  sql(`insert into integration_connection(id,request_id,provider,kind,label,owner_id,minimum_scopes,status,credential_version,credential_expires_at,last_result_code)
    values('${connection}','${randomUUID()}','SLACK_MESSAGING','MESSAGING','Disposable Slack restore fixture','${adminPerson.user}','["chat:write"]','REVOKED',2,now()+interval '1 day','CONNECTION_REVOKED');
    insert into integration_slack_destination(connection_id,workspace_id,channel_id,bot_id,credential_fingerprint,revocation_status,last_result_code)
    values('${connection}','T12345678','C12345678','B12345678','${'1'.repeat(64)}','FAILED','REVOCATION_FAILED');
    insert into integration_slack_rate_limit(workspace_id,channel_id,next_attempt_at) values('T12345678','',now()+interval '1 hour'),('T12345678','C12345678',now()+interval '1 second');
    insert into integration_slack_revocation(id,connection_id,credential_version,token_ciphertext,status,attempts,manual_retries,last_result_code)
    values('${revoke}','${connection}',1,'synthetic-non-provider-ciphertext','FAILED',10,3,'REVOCATION_FAILED');
    insert into integration_delivery(id,connection_id,business_event_id,event_type,resource_id,business_revision,occurred_at,payload_json,status,attempts,total_attempts,last_result_code)
    values('${delivery}','${connection}','${randomUUID()}','VISITOR_ARRIVED','${randomUUID()}',1,now(),'${JSON.stringify({arrived:true})}','UNKNOWN',1,1,'DELIVERY_UNKNOWN');`);
  const metadata = await call(adminPerson, `/integrations/slack/connections/${connection}`);
  assert.equal(metadata.headers['cache-control'], 'no-store');
  assert.equal(metadata.json.revocationStatus, 'FAILED');
  assert.ok(!JSON.stringify(metadata.json).includes('fingerprint') && !JSON.stringify(metadata.json).includes('ciphertext'));
  await call(employeePerson, `/integrations/slack/connections/${connection}`, 'GET', undefined, [401, 403]);
  const backlog = await call(adminPerson, `/integrations/connections/${connection}/deliveries`);
  assert.equal(backlog.json.content[0].status, 'UNKNOWN');
  console.log('SPRINT13_SLACK_BOUNDARIES_RESTORE_FIXTURES_VERIFIED');
}
