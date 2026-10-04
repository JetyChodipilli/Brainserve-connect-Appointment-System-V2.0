import assert from 'node:assert/strict';

// Called only by the existing explicitly disposable TLS/scanner/storage drill.
export async function verifyWorkPlanningStaging({ call, sql, leadPerson, employeePerson,
  otherPerson, taskId, evidence, safe, workerEmployee, strangerEmployee, tomorrow }) {
  const taskPath = `/work-tasks/${taskId}`;
  const contextResponse = await call(leadPerson, '/work-analytics/context');
  assert.equal(contextResponse.headers['cache-control'], 'no-store');
  const context = contextResponse.json;
  assert.equal(context.canReadWorkload, true);
  assert.ok(context.metricVersion && context.officeZone && context.officeDate);
  const filters = `from=${tomorrow}&to=${tomorrow}`;
  const summary = async () => (await call(leadPerson, `/work-analytics/summary?${filters}`)).json;
  const card = (data, id) => data.cards.find(item => item.id === id);
  const before = await summary();
  assert.equal(before.metricVersion, context.metricVersion);
  assert.equal(before.cards.length, 13);
  const accepted = card(before, 'WORK07');
  assert.ok(accepted.denominator >= 1 && accepted.numerator >= 1,
    'Actual Team Lead evidence acceptance must count before any CEO decision');
  const workload = (await call(leadPerson, '/work-analytics/workload')).json;
  const worker = workload.members.find(member => member.employeeId === workerEmployee);
  assert.ok(worker.activeTasks >= 1);
  assert.equal(worker.capacityMinutes, null);
  assert.ok(worker.estimatedTasks >= 1 && worker.unestimatedTasks >= 1);
  assert.equal(workload.metricVersion, before.metricVersion);
  const originalCommitment = sql(`select row_to_json(c)::text from work_original_commitment c where work_task_id='${taskId}';`);
  const noticeCount = Number(sql('select count(*) from work_handover_notice_receipt;'));
  const preview = (await call(leadPerson, `${taskPath}/handover`)).json;
  assert.equal(preview.originalEmployeeId, workerEmployee);
  assert.ok(preview.canHandover && preview.eligibleAssignees.some(member => member.employeeId === strangerEmployee));
  const moved = (await call(leadPerson, `${taskPath}/handover`, 'POST', {
    expectedVersion: preview.taskVersion, targetEmployeeId: strangerEmployee,
    reason: 'Transfer synthetic delivery with retained verified evidence', effectiveAt: null
  })).json;
  assert.equal(moved.currentEmployeeId, strangerEmployee);
  assert.equal(moved.originalEmployeeId, workerEmployee);
  assert.equal(moved.history.length, 1);
  assert.equal(moved.history[0].fromEmployeeId, workerEmployee);
  assert.equal(moved.history[0].toEmployeeId, strangerEmployee);
  await call(leadPerson, `${taskPath}/handover`, 'POST', {
    expectedVersion: preview.taskVersion, targetEmployeeId: workerEmployee,
    reason: 'A stale competing transfer cannot overwrite', effectiveAt: null
  }, 409);
  assert.equal(Number(sql('select count(*) from work_handover_notice_receipt;')), noticeCount + 2);
  assert.equal(sql(`select row_to_json(c)::text from work_original_commitment c where work_task_id='${taskId}';`), originalCommitment);
  assert.equal(sql(`select original_employee_id::text from department_work_task where id='${taskId}';`), workerEmployee);
  for (const path of [`${taskPath}/planning`, `${taskPath}/comments`,
    `${taskPath}/evidence/${evidence.id}/download`, `/search/worksheets/${taskId}/open`]) {
    await call(employeePerson, path, 'GET', undefined, [403, 404]);
  }
  assert.deepEqual((await call(otherPerson, `${taskPath}/evidence/${evidence.id}/download`)).bytes, safe);
  let planning = (await call(otherPerson, `${taskPath}/planning`)).json;
  assert.equal(planning.evidence.length, 0);
  assert.equal(planning.submissions[0].authorEmployeeId, workerEmployee);
  assert.ok(planning.submissions[0].acceptedAt);
  const after = await summary();
  assert.equal(card(after, 'WORK07').denominator, accepted.denominator);
  assert.equal(card(after, 'WORK07').numerator, accepted.numerator - 1);
  // Changing the operational deadline cannot change the original-date cohort.
  const deadline = new Date(`${tomorrow}T00:00:00Z`);
  deadline.setUTCDate(deadline.getUTCDate() + 4);
  planning = (await call(leadPerson, `${taskPath}/planning`, 'PUT', {
    expectedVersion: planning.taskVersion, priority: planning.priority,
    estimateMinutes: planning.estimateMinutes, evidenceRequired: true,
    dueDate: deadline.toISOString().slice(0, 10), reason: 'Change current date without changing commitment',
    checklist: planning.checklist.map(item => ({ id: item.id, title: item.title, required: item.required }))
  })).json;
  assert.equal(card(await summary(), 'WORK07').denominator, accepted.denominator);
  const upload = new FormData();
  upload.set('expectedVersion', String(planning.taskVersion));
  upload.set('file', new Blob([safe], { type: 'application/pdf' }), 'new-assignee-delivery.pdf');
  planning = (await call(otherPerson, `${taskPath}/evidence`, 'POST', upload)).json;
  planning = (await call(otherPerson, `${taskPath}/checklist`, 'PUT', {
    expectedVersion: planning.taskVersion, completedIds: planning.checklist.map(item => item.id)
  })).json;
  await call(otherPerson, `${taskPath}/complete`, 'POST', {
    expectedVersion: planning.taskVersion, note: 'Fresh delivery by the new synthetic assignee'
  });
  planning = (await call(leadPerson, `${taskPath}/planning`)).json;
  assert.equal(planning.submissions.at(-1).authorEmployeeId, strangerEmployee);
  assert.ok(planning.submissions.at(-1).version > planning.submissions[0].version);
  await call(leadPerson, `${taskPath}/approve`, 'POST', {
    expectedVersion: planning.taskVersion, note: 'Accept the new authored version'
  });
  const restoredAcceptance = await summary();
  assert.equal(card(restoredAcceptance, 'WORK07').numerator, accepted.numerator);
  const records = (await call(leadPerson, `/work-analytics/WORK07/records?${filters}&page=0&size=50&metricVersion=${context.metricVersion}`)).json;
  assert.equal(records.metricVersion, context.metricVersion);
  assert.equal(records.totalElements, accepted.denominator);
  assert.ok(records.items.some(item => item.id === taskId));
  const exported = await call(leadPerson, `/work-analytics/WORK07/export.csv?${filters}&metricVersion=${context.metricVersion}`);
  assert.equal(exported.headers['cache-control'], 'no-store');
  assert.ok(exported.bytes.toString('utf8').includes(taskId));
  assert.ok(exported.bytes.toString('utf8').includes(context.metricVersion));
  await call(leadPerson, `/work-analytics/summary?${filters}&departmentId=00000000-0000-0000-0000-000000000009`, 'GET', undefined, [403, 404]);
  console.log('SPRINT9_HANDOVER_AUTHORSHIP_WORKLOAD_ANALYTICS_VERIFIED');
}
