import assert from 'node:assert/strict';
import { embeddedTestPdf } from './fixtures/work-evidence-pdf.mjs';
import { readFileSync } from 'node:fs';
import { randomUUID, createHash, createHmac } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { request as httpsRequest } from 'node:https';
import { join } from 'node:path';

// Synthetic, disposable-stack verification only. Never seed a persistent host.
assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1', 'Explicit disposable-stack opt-in required');
assert.equal(process.env.STAGING_DOMAIN ?? 'localhost', 'localhost', 'Evidence drill is restricted to localhost');
const compose = ['compose', '--env-file', 'backend/.env', '-f', 'docker-compose.yml', '-f', 'ops/staging/compose.yml', '--profile', 'full-stack'];
function docker(args, input) {
  const result = spawnSync('docker', [...compose, ...args], { input, encoding: 'utf8', timeout: 180_000, maxBuffer: 1024 * 1024 });
  assert.equal(result.status, 0, `Disposable ${args[0]} failed; inspect job logs`);
  return result.stdout.trim();
}
function sql(statement) {
  return docker(['exec', '-T', 'postgres', 'sh', '-c', 'psql -U "$POSTGRES_USER" -d brainserve -v ON_ERROR_STOP=1 -At'], statement);
}
assert.equal(sql('select count(*) from iam_user_account;'), '0', 'Refuse to seed a stack with existing accounts');
const config = Object.fromEntries(readFileSync('backend/.env', 'utf8').split('\n').filter(line => /^[A-Z_]+=/.test(line)).map(line => {
  const at = line.indexOf('='); return [line.slice(0, at), line.slice(at + 1)];
}));
assert.ok(config.JWT_SECRET?.length >= 32);
const roleSource = readFileSync('backend/src/main/java/com/brainserve/appointment/iam/domain/SystemRole.java', 'utf8');
const id = () => randomUUID();
const department = id(), leadEmployee = id(), workerEmployee = id(), strangerEmployee = id();
const lead = id(), worker = id(), stranger = id();
const principals = [
  { user: lead, employee: leadEmployee, role: 'ROLE_TEAM_LEAD', name: 'Lead' },
  { user: worker, employee: workerEmployee, role: 'ROLE_EMPLOYEE', name: 'Worker' },
  { user: stranger, employee: strangerEmployee, role: 'ROLE_EMPLOYEE', name: 'Stranger' }
];
const auditColumns = 'version,created_at,created_by,updated_at,updated_by';
const auditValues = "0,now(),'sprint6-staging',now(),'sprint6-staging'";
sql(`insert into org_department(id,code,name,active,${auditColumns}) values('${department}','S6_STAGE','Synthetic evidence department',true,${auditValues});`);
for (const person of principals) {
  person.family = id();
  sql(`insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,${auditColumns}) values('${person.employee}','S6-${person.name}','${person.name}','Synthetic','${person.name} Synthetic','${person.name.toLowerCase()}@sprint6.invalid','${department}','Synthetic tester','2026-01-01','ACTIVE',${auditValues});
insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,${auditColumns}) values('${person.user}','${person.name.toLowerCase()}@sprint6.invalid','${person.name} Synthetic','${person.employee}','not-a-login-password',true,false,'ACTIVE',false,${auditValues});
insert into iam_user_role(user_id,role_name) values('${person.user}','${person.role}');
insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,session_started_at,mfa_verified_at,${auditColumns}) values('${id()}','${person.user}','${createHash('sha256').update(id()).digest('hex')}','${person.family}',now()+interval '1 hour',now(),now(),${auditValues});`);
  if (person.role !== 'ROLE_EMPLOYEE') sql(`insert into iam_mfa_credential(user_id,secret_ciphertext,enrolled_at,last_accepted_step) values('${person.user}','synthetic-not-an-enrollment-secret',now(),0);`);
  const permissions = roleSource.match(new RegExp(`${person.role}\\(EnumSet\\.of\\(([\\s\\S]*?)\\)\\)`))?.[1].match(/[A-Z][A-Z_]+/g);
  assert.ok(permissions?.length, 'Cannot determine actual role authorities');
  const now = Math.floor(Date.now() / 1000);
  const mfaVerifiedAt = Number(sql(`select floor(extract(epoch from mfa_verified_at)) from iam_refresh_token_session where user_id='${person.user}' and family_id='${person.family}';`));
  assert.ok(Number.isSafeInteger(mfaVerifiedAt) && mfaVerifiedAt > 0, 'Synthetic token must use its persisted MFA proof');
  const encode = data => Buffer.from(JSON.stringify(data)).toString('base64url');
  const parts = [encode({ alg: 'HS256', typ: 'JWT' }), encode({ iss: 'brainserve-appointment-service', sub: person.user, iat: now, exp: now + 3600, sid: person.family, employeeId: person.employee, mfaVerifiedAt, authorities: [person.role, ...permissions] })];
  person.token = `${parts.join('.')}.${createHmac('sha256', config.JWT_SECRET).update(parts.join('.')).digest('base64url')}`;
}
sql(`insert into department_team_lead(id,department_id,team_lead_user_id,team_lead_employee_id,active,assigned_by_user_id,assigned_at,${auditColumns}) values('${id()}','${department}','${lead}','${leadEmployee}',true,'${lead}',now(),${auditValues});`);
const ca = readFileSync(join(process.env.STAGING_EVIDENCE_DIR ?? '/tmp/brainserve-staging-evidence', 'staging-ca.crt'));
async function call(person, path, method = 'GET', value, expected = 200) {
  let body; const headers = { Authorization: `Bearer ${person.token}`, Accept: 'application/json' };
  if (value instanceof FormData) {
    const encoded = new Request('https://localhost', { method: 'POST', body: value });
    body = Buffer.from(await encoded.arrayBuffer()); headers['Content-Type'] = encoded.headers.get('content-type');
  } else if (value !== undefined) { body = Buffer.from(JSON.stringify(value)); headers['Content-Type'] = 'application/json'; }
  if (body) headers['Content-Length'] = String(body.length);
  const response = await new Promise((resolve, reject) => {
    const req = httpsRequest(`https://localhost:8443/api/v1${path}`, { method, headers, ca, timeout: 30_000 }, res => {
      const chunks = []; let length = 0;
      res.on('data', chunk => { length += chunk.length; if (length > 12 * 1024 * 1024) { req.destroy(new Error('Evidence response exceeded bound')); return; } chunks.push(chunk); });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, bytes: Buffer.concat(chunks) }));
      res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('Evidence request timed out'))); req.on('error', reject); req.end(body);
  });
  assert.ok(Array.isArray(expected) ? expected.includes(response.status) : response.status === expected, `${method} evidence operation: HTTP ${response.status}, expected ${expected}`);
  if (response.headers['content-type']?.includes('json')) response.json = JSON.parse(response.bytes.toString('utf8'));
  return response;
}
const [leadPerson, employeePerson, otherPerson] = principals;
const tomorrow = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10);
const created = (await call(leadPerson, '/work-tasks', 'POST', { employeeId: workerEmployee, title: 'Synthetic scanner and storage verification', description: 'Disposable evidence only; no customer data', dueDate: tomorrow }, 200)).json;
const taskPath = `/work-tasks/${created.id}`;
let planning = (await call(employeePerson, `${taskPath}/planning`)).json;
const checklistId = id();
planning = (await call(leadPerson, `${taskPath}/planning`, 'PUT', { expectedVersion: planning.taskVersion, priority: 'HIGH', estimateMinutes: 30, evidenceRequired: true, dueDate: tomorrow, reason: 'Require synthetic evidence', checklist: [{ id: checklistId, title: 'Attach verified file', required: true }] })).json;
await call(employeePerson, `${taskPath}/complete`, 'POST', { expectedVersion: planning.taskVersion, note: 'Missing evidence must fail' }, 422);
planning = (await call(employeePerson, `${taskPath}/checklist`, 'PUT', { expectedVersion: planning.taskVersion, completedIds: [checklistId] })).json;
function upload(bytes, filename, type) { const form = new FormData(); form.set('expectedVersion', String(planning.taskVersion)); form.set('file', new Blob([bytes], { type }), filename); return form; }
await call(employeePerson, `${taskPath}/evidence`, 'POST', upload(Buffer.from('not a PDF'), 'mismatch.pdf', 'application/pdf'), 415);
const safe = Buffer.from('%PDF-1.4\nSynthetic clean evidence for disposable staging.\n%%EOF\n');
planning = (await call(employeePerson, `${taskPath}/evidence`, 'POST', upload(safe, 'safe-evidence.pdf', 'application/pdf'))).json;
const evidence = planning.evidence[0];
assert.equal(evidence.sha256, createHash('sha256').update(safe).digest('hex'));
let downloaded = await call(employeePerson, `${taskPath}/evidence/${evidence.id}/download`);
assert.deepEqual(downloaded.bytes, safe); assert.equal(downloaded.headers['cache-control'], 'no-store');
assert.equal(downloaded.headers['x-content-type-options'], 'nosniff'); assert.match(downloaded.headers['content-disposition'], /^attachment;/);
await call(otherPerson, `${taskPath}/evidence/${evidence.id}/download`, 'GET', undefined, 404);
// Embed the canonical antivirus test file as an actual, uncompressed PDF attachment.
const eicar = embeddedTestPdf(Buffer.from('X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*'));
await call(employeePerson, `${taskPath}/evidence`, 'POST', upload(eicar, 'antivirus-test.pdf', 'application/pdf'), 422);
assert.equal((await call(employeePerson, `${taskPath}/planning`)).json.evidence.length, 1);
docker(['stop', 'clamav']);
try { await call(employeePerson, `${taskPath}/evidence`, 'POST', upload(safe, 'scanner-unavailable.pdf', 'application/pdf'), 503); }
finally { docker(['up', '-d', '--no-build', '--wait', '--wait-timeout', '180', 'clamav']); }
planning = (await call(employeePerson, `${taskPath}/planning`)).json;
assert.equal(planning.evidence.length, 1);
await call(employeePerson, `${taskPath}/complete`, 'POST', { expectedVersion: planning.taskVersion, note: 'Verified immutable version' });
planning = (await call(leadPerson, `${taskPath}/planning`)).json;
assert.equal(planning.submissions.length, 1); assert.deepEqual(planning.submissions[0].evidence[0], evidence);
await call(leadPerson, `${taskPath}/approve`, 'POST', { expectedVersion: planning.taskVersion, note: 'Accept verified evidence' });
planning = (await call(leadPerson, `${taskPath}/planning`)).json;
assert.ok(planning.submissions[0].acceptedAt);
downloaded = await call(leadPerson, `${taskPath}/evidence/${evidence.id}/download`); assert.deepEqual(downloaded.bytes, safe);
assert.ok(Number(sql("select count(*) from audit_event where event_type='DOCUMENT_READ' and target_type='WORK_TASK';")) >= 2, 'Successful private reads must be audited');
assert.ok(Number(sql("select count(*) from audit_event where event_type='WORK_TASK_EVIDENCE_READ';")) >= 2, 'Successful task evidence reads must be audited');
// The old authenticated token becomes unusable immediately after a permission change.
sql(`insert into iam_user_permission_deny(user_id,permission_name) values('${worker}','WORK_TASK_READ');`);
await call(employeePerson, `${taskPath}/evidence/${evidence.id}/download`, 'GET', undefined, [401, 403, 404]);
console.log('WORK_EVIDENCE_SCANNER_STORAGE_VERIFIED');
