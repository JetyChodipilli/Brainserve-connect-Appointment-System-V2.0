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
const [user, employeeId, family, proof] = sql("select u.id,u.employee_id,s.family_id,floor(extract(epoch from s.mfa_verified_at)) from iam_user_account u join iam_refresh_token_session s on s.user_id=u.id where u.email='lead@sprint6.invalid' and s.revoked_at is null order by s.created_at desc limit 1;").split('|');
for (const value of [user, employeeId, family]) assert.match(value ?? '', /^[a-f0-9-]{36}$/);
assert.ok(Number.isSafeInteger(Number(proof)) && Number(proof) > 0);
const task = sql("select id from department_work_task where title='Synthetic load worksheet' order by created_at desc limit 1;");
assert.match(task, /^[a-f0-9-]{36}$/);
const secret = readFileSync('backend/.env', 'utf8').split('\n').find(line => line.startsWith('JWT_SECRET='))?.slice(11);
assert.ok(secret?.length >= 32);
const roleSource = readFileSync('backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java', 'utf8');
const permissions = roleSource.match(/ROLE_TEAM_LEAD\(EnumSet\.of\(([\s\S]*?)\)\)/)?.[1].match(/[A-Z][A-Z_]+/g); assert.ok(permissions?.length);
const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const parts = [encode({ alg: 'HS256', typ: 'JWT' }), encode({ iss: 'brainserve-appointment-service', sub: user, iat: now, exp: now + 300, sid: family, employeeId, mfaVerifiedAt: Number(proof), authorities: ['ROLE_TEAM_LEAD', ...permissions] })];
const token = `${parts.join('.')}.${createHmac('sha256', secret).update(parts.join('.')).digest('base64url')}`;
const ca = readFileSync(join(process.env.STAGING_EVIDENCE_DIR ?? '/tmp/brainserve-staging-evidence', 'staging-ca.crt'));
const response = await new Promise((resolve, reject) => {
  const req = request(`https://localhost:8443/api/v1/search/worksheets/${task}/open`, { ca, timeout: 20000, headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } }, res => {
    let bytes = 0; const chunks = [];
    res.on('data', chunk => { bytes += chunk.length; if (bytes > 1024 * 1024) { req.destroy(new Error('Retained-read response exceeded bound')); return; } chunks.push(chunk); });
    res.on('error', reject); res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString('utf8') }));
  });
  req.on('error', reject); req.on('timeout', () => req.destroy(new Error('Retained read timed out'))); req.end();
});
assert.equal(response.status, 200, 'Restored application must authorize and serve retained work');
let value; try { value = JSON.parse(response.body); } catch { throw new Error('Retained-read response was invalid JSON'); }
assert.equal(value.id, task); assert.equal(value.title, 'Synthetic load worksheet');
assert.equal(value.detail?.Description, 'Disposable Sprint 15 load fixture');
console.log(`SPRINT15_RETAINED_APPLICATION_READ_VERIFIED phase=${phase}`);
