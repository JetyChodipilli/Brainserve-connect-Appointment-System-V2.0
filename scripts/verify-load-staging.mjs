import assert from 'node:assert/strict';
import { createHash, createHmac, randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { runWorkload } from './lib/load-workload.mjs';

export async function verifyLoadStaging({ call, sql, leadPerson, employeePerson, department, roleSource, jwtSecret, evidenceDir, resources }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  const seconds = Number(process.env.STAGING_LOAD_SECONDS ?? 30);
  assert.ok(Number.isInteger(seconds) && seconds >= 30 && seconds <= 120, 'Smoke duration must fit the existing privileged MFA proof; customer soak needs real renewed sessions');
  const before = Number(sql('select count(*) from iam_user_account;'));
  assert.ok(before > 0 && before < 500, 'Refuse an unknown or already seeded dataset');
  const permissions = roleSource.match(/ROLE_EMPLOYEE\(EnumSet\.of\(([\s\S]*?)\)\)/)?.[1].match(/[A-Z][A-Z_]+/g);
  assert.ok(permissions?.length);
  const added = Array.from({ length: 500 - before }, (_, i) => ({ user: randomUUID(), employee: randomUUID(), family: randomUUID(), role: 'ROLE_EMPLOYEE', index: i }));
  const columns = 'version,created_at,created_by,updated_at,updated_by', audit = "0,now(),'s15-load',now(),'s15-load'";
  sql('begin;\n' + added.map(p => `insert into employee(id,employee_number,first_name,last_name,display_name,official_email,department_id,designation,joining_date,status,${columns}) values('${p.employee}','S15-${p.index}','Load','Synthetic','Load Synthetic','load${p.index}@s15.invalid','${department}','Synthetic tester','2026-01-01','ACTIVE',${audit});
insert into iam_user_account(id,email,full_name,employee_id,password_hash,enabled,force_password_change,account_status,archived,${columns}) values('${p.user}','load${p.index}@s15.invalid','Load Synthetic','${p.employee}','not-a-login-password',true,false,'ACTIVE',false,${audit});
insert into iam_user_role(user_id,role_name) values('${p.user}','ROLE_EMPLOYEE');
insert into iam_refresh_token_session(id,user_id,token_hash,family_id,expires_at,session_started_at,${columns}) values('${randomUUID()}','${p.user}','${createHash('sha256').update(randomUUID()).digest('hex')}','${p.family}',now()+interval '2 hours',now(),${audit});`).join('\n') + '\ncommit;');
  const now = Math.floor(Date.now() / 1000), encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  for (const p of added.slice(0, 48)) {
    const parts = [encode({ alg: 'HS256', typ: 'JWT' }), encode({ iss: 'brainserve-appointment-service', sub: p.user, iat: now, exp: now + 7200, sid: p.family, employeeId: p.employee, authorities: [p.role, ...permissions] })];
    p.token = `${parts.join('.')}.${createHmac('sha256', jwtSecret).update(parts.join('.')).digest('base64url')}`;
  }
  leadPerson.targetEmployee = employeePerson.employee;
  const actors = [leadPerson, employeePerson, ...added.slice(0, 48)]; assert.equal(actors.length, 50);
  assert.equal(Number(sql('select count(*) from iam_user_account;')), 500);
  const samples = [{ phase: 'before', values: await resources() }];
  let sampling = null, sampleFailures = 0;
  const timer = setInterval(() => {
    if (sampling) return;
    sampling = Promise.resolve().then(resources).then(values => samples.push({ phase: 'during', values }))
      .catch(() => { sampleFailures++; }).finally(() => { sampling = null; });
  }, 10000);
  let report;
  try { report = await runWorkload({ actors, durationSeconds: seconds, call: (person, path, method, body) => call(person, path, method, body, null) }); }
  finally { clearInterval(timer); await sampling; }
  samples.push({ phase: 'after', values: await resources() });
  Object.assign(report, { datasetAccounts: 500, releaseId: process.env.RELEASE_ID, environment: 'disposable-single-runner', resources: samples, sampleFailures,
    exclusions: ['provider/printer latency', 'three-tab browser behavior', 'multi-host availability', 'customer dataset distribution', 'login/refresh/MFA journey'] });
  writeFileSync(join(evidenceDir, 'load-report.json'), JSON.stringify(report, null, 2) + '\n', { mode: 0o600 });
  assert.ok(report.complete); assert.equal(report.errors, 0, 'Failed mixed workload requests; inspect sanitized report');
  assert.equal(sampleFailures, 0, 'Resource measurement failed; inspect runner logs');
  assert.ok(report.endpoints['work-create']?.requests > 0, 'A core mutation must execute');
  console.log('SPRINT15_MIXED_LOAD_MEASURED');
}
