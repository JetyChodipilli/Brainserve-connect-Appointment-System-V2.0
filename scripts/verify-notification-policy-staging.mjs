import assert from 'node:assert/strict';

// Runs inside the authorized disposable TLS drill. Time is advanced only in synthetic database fixtures.
export async function verifyNotificationPolicyStaging({call,sql,leadPerson,employeePerson,alternatePerson,adminPerson,workerEmployee,tomorrow}) {
  const read=await call(employeePerson,'/notification-preferences');
  assert.equal(read.headers['cache-control'],'no-store');
  const saved=(await call(employeePerson,'/notification-preferences','PUT',{...read.json,soundEnabled:false,emailEnabled:true,cadence:'HOURLY',zoneId:'UTC'})).json;
  assert.equal(saved.version,read.json.version+1);
  await call(employeePerson,'/notification-preferences','PUT',read.json,409);
  assert.equal((await call(employeePerson,'/notification-preferences')).json.version,saved.version);
  await call(employeePerson,'/approval-policies','GET',undefined,[401,403]);
  const current=(await call(adminPerson,'/approval-policies')).json.find(p=>p.kind==='WORK'&&p.stage==='TEAM_LEAD');
  assert.equal(current.enabled,false);
  await call(adminPerson,'/approval-policies','POST',{...current,enabled:true,deadlineMinutes:5,reminderMinutes:5,escalationRole:'MANAGER'});
  const task=(await call(leadPerson,'/work-tasks','POST',{employeeId:workerEmployee,title:'Synthetic Sprint 10 policy verification',description:'Retained current review authority',dueDate:tomorrow})).json;
  const planning=(await call(employeePerson,`/work-tasks/${task.id}/planning`)).json;
  await call(employeePerson,`/work-tasks/${task.id}/complete`,'POST',{expectedVersion:planning.taskVersion,note:'Original worker authored delivery'});
  const queue=await call(leadPerson,'/approval-policies/queue?overdue=false&page=0');
  assert.equal(queue.headers['cache-control'],'no-store');
  const stage=queue.json.items.find(s=>s.resourceId===task.id);
  assert.ok(stage?.entryKnown&&stage.deadlineAt);assert.equal(stage.policyVersion,current.version+1);
  const candidates=(await call(leadPerson,`/approval-policies/stages/${stage.id}/candidates`)).json;
  assert.ok(candidates.some(p=>p.userId===alternatePerson.user));
  const grant=(await call(leadPerson,`/approval-policies/stages/${stage.id}/delegations`,'POST',{delegateId:alternatePerson.user,expiresAt:new Date(Date.now()+3600000).toISOString(),reason:'Cover a synthetic reviewer absence'})).json;
  assert.equal((await call(alternatePerson,`/work-tasks/${task.id}/planning`)).json.submissions[0].authorUserId,employeePerson.user);
  await call(leadPerson,`/approval-policies/delegations/${grant.id}`,'DELETE',undefined,204);
  await call(alternatePerson,`/work-insights/review-queue/${stage.id}/decision`,'POST',{expectedVersion:stage.resourceVersion,approved:true,remarks:'Revoked grant must fail'},409);
  // The actual scheduled worker must send two body-free mandatory notices with durable receipts.
  sql(`update approval_stage set deadline_at=now()-interval '1 minute',next_reminder_at=now()-interval '1 minute' where id='${stage.id}';`);
  for(let n=0;n<80;n++){if(Number(sql(`select count(*) from approval_reminder_receipt where stage_id='${stage.id}';`))===2)break;await new Promise(resolve=>setTimeout(resolve,500));}
  assert.equal(Number(sql(`select count(*) from approval_reminder_receipt where stage_id='${stage.id}';`)),2);
  assert.equal(Number(sql(`select count(*) from approval_reminder_receipt r join internal_call_notification n on n.id=r.notification_id where r.stage_id='${stage.id}' and n.category='ESCALATION' and n.mandatory;`)),2);
  assert.equal(sql(`select status from department_work_task where id='${task.id}';`),'COMPLETED');
  const currentStage=(await call(leadPerson,'/approval-policies/queue?overdue=true&page=0')).json.items.find(s=>s.id===stage.id);
  await call(leadPerson,`/work-insights/review-queue/${stage.id}/decision`,'POST',{expectedVersion:currentStage.resourceVersion,approved:true,remarks:'Existing reviewer approves current stage'});
  assert.equal(Number(sql(`select count(*) from approval_stage where id='${stage.id}' and closed_at is not null;`)),1);
  console.log('SPRINT10_PREFERENCES_DEADLINE_REMINDERS_REVOCATION_VERIFIED');
}
