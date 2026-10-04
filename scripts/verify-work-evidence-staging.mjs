import assert from 'node:assert/strict';
import { embeddedTestPdf } from './fixtures/work-evidence-pdf.mjs';
import { verifyWorkPlanningStaging } from './verify-work-planning-staging.mjs';
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
async function call(person, path, method = 'GET', value, expected = 200, accept = 'application/json') {
  let body; const headers = { Authorization: `Bearer ${person.token}`, Accept: accept };
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
// Sprint 7 runs against the same real private evidence and disposable business scope.
const search = (await call(employeePerson, '/search?q=Synthetic&size=5')).json;
const sheets = search.groups.find(group => group.type === 'worksheets');
assert.ok(sheets.available && sheets.items.some(item => item.id === created.id));
assert.equal((await call(employeePerson, `/search/worksheets/${created.id}/open`)).json.id, created.id);
await call(otherPerson, `/search/worksheets/${created.id}/open`, 'GET', undefined, 404);
const discussion = (await call(employeePerson, `${taskPath}/comments`)).json;
assert.ok(discussion.participants.some(person => person.id === lead));
const commentRequest = { clientRequestId: id(), body: 'Synthetic Sprint 7 discussion <b>plain text</b>', mentionUserIds: [lead], evidenceIds: [evidence.id] };
const comment = (await call(employeePerson, `${taskPath}/comments`, 'POST', commentRequest)).json;
assert.equal((await call(employeePerson, `${taskPath}/comments`, 'POST', commentRequest)).json.id, comment.id);
assert.deepEqual((await call(leadPerson, `${taskPath}/comments/${comment.id}/evidence/${evidence.id}/download`)).bytes, safe);
await call(otherPerson, `${taskPath}/comments`, 'GET', undefined, 404);
const edited = (await call(employeePerson, `${taskPath}/comments/${comment.id}`, 'PUT', { expectedVersion: comment.version, body: 'Synthetic Sprint 7 reviewed discussion', mentionUserIds: [], evidenceIds: [evidence.id] })).json;
assert.equal(edited.version, comment.version + 1);
await call(employeePerson, `${taskPath}/comments/${comment.id}`, 'PUT', { expectedVersion: comment.version, body: 'A stale update cannot overwrite', mentionUserIds: [], evidenceIds: [] }, 409);
const activity = (await call(leadPerson, `${taskPath}/activity`)).json;
assert.ok(activity.events.some(event => event.eventType === 'WORK_TASK_COMMENT_CREATED'));
const draftPath = '/drafts/TASK_CREATE/new';
const draft = (await call(leadPerson, draftPath, 'PUT', { schemaVersion: 1, expectedRevision: 0, fields: { employeeId: workerEmployee, title: 'Synthetic Sprint 7 receipt verification', description: 'Safe restore fixture', dueDate: tomorrow } })).json;
await call(leadPerson, draftPath, 'PUT', { schemaVersion: 1, expectedRevision: 0, fields: { title: 'Stale tab cannot overwrite' } }, 409);
const submit = { expectedRevision: draft.revision, submissionKey: draft.submissionKey };
const receipt = (await call(leadPerson, `${draftPath}/submit`, 'POST', submit)).json;
assert.deepEqual((await call(leadPerson, `${draftPath}/submit`, 'POST', submit)).json, receipt);
assert.equal(sql("select count(*) from department_work_task where title='Synthetic Sprint 7 receipt verification';"), '1');
await call(leadPerson, `${draftPath}?expectedRevision=${draft.revision}`, 'DELETE', undefined, 204);
await call(leadPerson, draftPath, 'PUT', { schemaVersion: 1, expectedRevision: 0, fields: { employeeId: workerEmployee, title: 'Synthetic Sprint 7 retained draft', description: 'Encrypted recovery fixture', dueDate: tomorrow } });
assert.ok(Number(sql('select count(*) from task_comment_revision;')) >= 2);
console.log('SPRINT7_SCOPED_SEARCH_COMMENTS_DRAFT_RECEIPT_VERIFIED');
// Exercise the real recurrence scheduler and durable notification transaction.
const routines = '/work-routines';
const routineContextResponse = await call(leadPerson, `${routines}/context`);
assert.equal(routineContextResponse.headers['cache-control'], 'no-store');
const routineContext = routineContextResponse.json;
const templateBody = { requestId: id(), title: 'Synthetic daily reconciliation', instructions: 'Complete the retained required checklist for the scheduled occurrence.', checklist: [{ title: 'Reconcile synthetic totals', required: true }], assigneeRule: 'EMPLOYEE', dueOffsetDays: 2 };
const routineTemplate = (await call(leadPerson, `${routines}/templates`, 'POST', templateBody)).json;
assert.equal((await call(leadPerson, `${routines}/templates`, 'POST', templateBody)).json.id, routineTemplate.id);
await call(leadPerson, `${routines}/templates`, 'POST', { ...templateBody, title: 'Changed request must conflict' }, 409);
assert.equal((await call(leadPerson, `${routines}/templates/${routineTemplate.id}`)).json.instructions, templateBody.instructions);
await call(employeePerson, `${routines}/templates`, 'GET', undefined, [401, 403]);
const scheduleBody = { requestId: id(), templateId: routineTemplate.id, employeeId: workerEmployee, frequency: 'DAILY', interval: 1, startDate: routineContext.officeDate, endDate: routineContext.officeDate, localTime: '00:00', weekdays: [], monthDay: null, weekendPolicy: 'INCLUDE', holidayPolicy: 'INCLUDE', holidays: [] };
const preview = (await call(leadPerson, `${routines}/preview`, 'POST', scheduleBody)).json;
assert.equal(preview.officeZone, routineContext.officeZone);
assert.equal(preview.occurrences.length, 1);
assert.equal(preview.occurrences[0].occurrenceDate, routineContext.officeDate);
const routineSchedule = (await call(leadPerson, `${routines}/schedules`, 'POST', scheduleBody)).json;
assert.equal((await call(leadPerson, `${routines}/schedules`, 'POST', scheduleBody)).json.id, routineSchedule.id);
let occurrence;
for (let attempt = 0; attempt < 100; attempt++) {
  const history = (await call(leadPerson, `${routines}/schedules/${routineSchedule.id}/occurrences`)).json;
  occurrence = history.items.find(item => item.occurrenceDate === routineContext.officeDate);
  if (occurrence) break;
  await new Promise(resolve => setTimeout(resolve, 500));
}
assert.ok(occurrence, 'Actual scheduler must materialize the due occurrence');
assert.equal(occurrence.status, 'CREATED');
assert.ok(occurrence.taskId);
const retryPath = `${routines}/schedules/${routineSchedule.id}/occurrences/${occurrence.occurrenceDate}/retry`;
for (const replay of await Promise.all([call(leadPerson, retryPath, 'POST', { expectedVersion: occurrence.version }), call(leadPerson, retryPath, 'POST', { expectedVersion: occurrence.version })])) {
  assert.equal(replay.json.taskId, occurrence.taskId);
}
assert.equal(sql(`select count(*) from work_routine_occurrence where schedule_id='${routineSchedule.id}';`), '1');
assert.equal(sql(`select count(*) from work_routine_notice_receipt r join internal_call_notification n on n.id=r.notification_id where r.event_key='routine:${routineSchedule.id}:${occurrence.occurrenceDate}';`), '1');
const scheduledTaskPath = `/work-tasks/${occurrence.taskId}`;
let scheduledPlanning = (await call(employeePerson, `${scheduledTaskPath}/planning`)).json;
assert.equal(scheduledPlanning.checklist.length, 1);
assert.equal(scheduledPlanning.checklist[0].title, templateBody.checklist[0].title);
assert.equal(scheduledPlanning.checklist[0].required, true);
const scheduledUpload = new FormData();
scheduledUpload.set('expectedVersion', String(scheduledPlanning.taskVersion));
scheduledUpload.set('file', new Blob([safe], { type: 'application/pdf' }), 'retained-routine.pdf');
scheduledPlanning = (await call(employeePerson, `${scheduledTaskPath}/evidence`, 'POST', scheduledUpload)).json;
const scheduledEvidence = scheduledPlanning.evidence[0];
await call(leadPerson, `${routines}/templates/${routineTemplate.id}`, 'PUT', { ...templateBody, expectedVersion: routineTemplate.version, instructions: 'Changed only for future scheduled work.', checklist: [{ title: 'Future requirement', required: false }] });
const currentSchedule = (await call(leadPerson, `${routines}/schedules`)).json.items.find(item => item.id === routineSchedule.id);
await call(leadPerson, `${routines}/schedules/${routineSchedule.id}/state`, 'POST', { paused: true }, 422);
const pausedSchedule = (await call(leadPerson, `${routines}/schedules/${routineSchedule.id}/state`, 'POST', { expectedVersion: currentSchedule.version, paused: true })).json;
assert.equal(pausedSchedule.paused, true);
await call(leadPerson, `${routines}/schedules/${routineSchedule.id}/state`, 'POST', { expectedVersion: currentSchedule.version, paused: false }, 409);
assert.equal((await call(employeePerson, `${scheduledTaskPath}/planning`)).json.checklist[0].title, templateBody.checklist[0].title);
assert.deepEqual((await call(employeePerson, `${scheduledTaskPath}/evidence/${scheduledEvidence.id}/download`)).bytes, safe);
await call(otherPerson, `${scheduledTaskPath}/planning`, 'GET', undefined, [403, 404]);
console.log('SPRINT8_RECURRENCE_SNAPSHOT_NOTIFICATION_EVIDENCE_VERIFIED');
await verifyWorkPlanningStaging({ call, sql, leadPerson, employeePerson, otherPerson,
  taskId: created.id, evidence, safe, workerEmployee, strangerEmployee, tomorrow });
// The old authenticated token becomes unusable immediately after a permission change.
sql(`insert into iam_user_permission_deny(user_id,permission_name) values('${worker}','WORK_TASK_READ');`);
await call(employeePerson, `${taskPath}/evidence/${evidence.id}/download`, 'GET', undefined, [401, 403, 404]);
await call(employeePerson, `${taskPath}/comments`, 'GET', undefined, [401, 403, 404]);
await call(employeePerson, `/search/worksheets/${created.id}/open`, 'GET', undefined, [401, 403, 404]);
console.log('WORK_EVIDENCE_SCANNER_STORAGE_VERIFIED');
