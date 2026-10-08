import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
export async function verifyKioskStaging({ call, sql, adminPerson, employeePerson, hrPerson, department }) {
  assert.equal(process.env.BRAINSERVE_DISPOSABLE_STACK, '1');
  await call(employeePerson, '/admin/kiosks', 'POST', { label: 'Denied device' }, [401, 403]);
  const result = await call(adminPerson, '/admin/kiosks', 'POST', { label: 'Disposable reception kiosk' });
  assert.equal(result.headers['cache-control'], 'no-store');
  assert.match(result.json.token, /^[A-Za-z0-9_-]{43}$/);
  const device = result.json;
  const unavailable = await call(adminPerson, '/kiosk/session', 'POST', {}, 503, 'application/json', { 'X-Kiosk-Token': device.token });
  assert.equal(unavailable.json.errorCode, 'KIOSK_DISABLED');
  const list = await call(adminPerson, '/admin/kiosks');
  assert.equal(list.json.length, 1); assert.ok(!JSON.stringify(list.json).includes(device.token));
  await call(adminPerson, `/admin/kiosks/${device.id}/revoke`, 'POST', { version: device.version });
  assert.ok((await call(adminPerson, '/admin/kiosks')).json[0].revokedAt);
  const group = randomUUID(), visits = [randomUUID(), randomUUID()];
  sql(`insert into appointment_visit_group(id,owner_id,request_id,payload_hash,label,host_employee_id,type,slot_start,slot_end,member_count)
    values('${group}','${adminPerson.user}','${randomUUID()}','${'1'.repeat(64)}','Disposable retained group','${hrPerson.employee}','HR_VISIT','2026-01-05T04:00:00Z','2026-01-05T04:30:00Z',2);`);
  for (const [index, visit] of visits.entries()) sql(`insert into appointment(id,reference_number,idempotency_key,type,status,visitor_name,visitor_email,visitor_phone,host_employee_id,routing_department_id,slot_start,slot_end,purpose,visit_group_id,version,created_at,created_by,updated_at,updated_by)
    values('${visit}','BSA-S14${index}-ABCD','s14:${visit}','HR_VISIT','CANCELLED','Synthetic visitor','synthetic${index}@s14.invalid','0000000000','${hrPerson.employee}','${department}','2026-01-05T04:00:00Z','2026-01-05T04:30:00Z','Synthetic restore fixture','${group}',0,now(),'s14',now(),'s14');`);
  sql(`insert into kiosk_arrival_intake(id,device_id,appointment_id,resolved_at,resolved_by,version)
    values('${randomUUID()}','${device.id}','${visits[0]}',now(),'${adminPerson.user}',1);`);
  for (const table of ['appointment_visit_group', 'kiosk_device', 'kiosk_arrival_intake']) assert.ok(Number(sql(`select count(*) from ${table};`)) > 0);
  console.log('SPRINT14_KIOSK_BOUNDARIES_RESTORE_FIXTURES_VERIFIED');
}
