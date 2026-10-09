import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHmac } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { request } from 'node:https';
import { join } from 'node:path';

// Read retained synthetic business data through each restored application image.
assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
assert.equal(process.env.STAGING_DOMAIN ?? 'localhost', 'localhost');
const [database, phase] = process.argv.slice(2);
assert.ok(['brainserve', 'brainserve_restore_sprint1'].includes(database));
assert.ok(['restored', 'rollback', 'reapply'].includes(phase));
const compose = ['compose', '--env-file', 'backend/.env', '-f', 'docker-compose.yml', '-f', 'ops/staging/compose.yml', '--profile', 'full-stack'];
const sql = statement => {
  const result = spawnSync('docker', [...compose, 'exec', '-T', 'postgres', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At', 'sh', database], { input: statement, encoding: 'utf8', timeout: 20000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, 'Retained-read fixture lookup failed'); return result.stdout.trim();
};
const task = sql("select id from department_work_task where title='Synthetic load worksheet' order by created_at desc limit 1;");
assert.match(task, /^[a-f0-9-]{36}$/);
const secret = readFileSync('backend/.env', 'utf8').split('\n').find(line => line.startsWith('JWT_SECRET='))?.slice(11);
assert.ok(secret?.length >= 32);
const roleSource = readFileSync('backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java', 'utf8');
function tokenFor(email, role) {
  // Renew only the disposable synthetic proof; recovery startup may exceed the
  // production step-up window. This does not exercise a real MFA challenge.
  if (role === 'ROLE_SYSTEM_ADMIN') sql("update iam_refresh_token_session set mfa_verified_at=date_trunc('second',now()) where user_id=(select id from iam_user_account where email='admin@sprint6.invalid') and revoked_at is null;");
  const [user, employeeId, family, proof] = sql(`select u.id,u.employee_id,s.family_id,floor(extract(epoch from s.mfa_verified_at)) from iam_user_account u join iam_refresh_token_session s on s.user_id=u.id where u.email='${email}' and s.revoked_at is null order by s.created_at desc limit 1;`).split('|');
  for (const value of [user, employeeId, family]) assert.match(value ?? '', /^[a-f0-9-]{36}$/);
  assert.ok(Number.isSafeInteger(Number(proof)) && Number(proof) > 0);
  const permissions = roleSource.match(new RegExp(`${role}\\(EnumSet\\.of\\(([\\s\\S]*?)\\)\\)`))?.[1].match(/[A-Z][A-Z_]+/g);
  assert.ok(permissions?.length);
  const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const parts = [encode({ alg: 'HS256', typ: 'JWT' }), encode({ iss: 'brainserve-appointment-service', sub: user, iat: now, exp: now + 300, sid: family, employeeId, mfaVerifiedAt: Number(proof), authorities: [role, ...permissions] })];
  return `${parts.join('.')}.${createHmac('sha256', secret).update(parts.join('.')).digest('base64url')}`;
}
const ca = readFileSync(join(process.env.STAGING_EVIDENCE_DIR ?? '/tmp/brainserve-staging-evidence', 'staging-ca.crt'));
const read = (path, token) => new Promise((resolve, reject) => {
  const req = request(`https://localhost:8443/api/v1${path}`, { ca, timeout: 20000, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } }, res => {
    let bytes = 0; const chunks = [];
    res.on('data', chunk => { bytes += chunk.length; if (bytes > 1024 * 1024) { req.destroy(new Error('Retained-read response exceeded bound')); return; } chunks.push(chunk); });
    res.on('error', reject); res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks).toString('utf8') }));
  });
  req.on('error', reject); req.on('timeout', () => req.destroy(new Error('Retained read timed out'))); req.end();
});
const response = await read(`/search/worksheets/${task}/open`, tokenFor('lead@sprint6.invalid', 'ROLE_TEAM_LEAD'));
assert.equal(response.status, 200, 'Restored application must authorize and serve retained work');
let value; try { value = JSON.parse(response.body); } catch { throw new Error('Retained-read response was invalid JSON'); }
assert.equal(value.id, task); assert.equal(value.title, 'Synthetic load worksheet');
assert.equal(value.detail?.Description, 'Disposable Sprint 15 load fixture');
console.log(`SPRINT15_RETAINED_APPLICATION_READ_VERIFIED phase=${phase}`);

const adminToken = tokenFor('admin@sprint6.invalid', 'ROLE_SYSTEM_ADMIN');
const retained = JSON.parse(sql("select profile_json from release_profile where id='00000000-0000-0000-0000-000000000271';"));
assert.equal(retained.status, 'CANCELLED');
const settings = await read('/system-settings', adminToken);
assert.equal(settings.status, 200);
for (const privateValue of [retained.reference, retained.supportEmail]) assert.ok(!settings.body.includes(privateValue), 'Prior release must keep commercial records private');
if (phase !== 'rollback') {
  const release = await read('/release-profile', adminToken);
  assert.equal(release.status, 200);
  assert.equal(release.headers['cache-control'], 'no-store');
  const current = JSON.parse(release.body);
  assert.equal(current.version, 2);
  assert.deepEqual(current.profile, retained);
}
console.log(`SPRINT16_PRIVATE_RELEASE_RESTORE_VERIFIED phase=${phase}`);
