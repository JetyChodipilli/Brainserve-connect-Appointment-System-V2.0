-- Team Lead worksheets historically stayed COMPLETED after the CEO's final
-- approval. They therefore continued to appear as open carry-forward work.
-- Repair both the live worksheet and retained audit snapshot so existing
-- installations receive the same terminal state as new approvals.
UPDATE department_work_task task
SET status = 'APPROVED',
    approved_at = COALESCE(task.approved_at, audit.ceo_decided_at, CURRENT_TIMESTAMP),
    version = task.version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 'flyway-v48'
FROM work_task_audit_record audit
WHERE audit.work_task_id = task.id
  AND audit.audit_status = 'CEO_APPROVED'
  AND task.assignee_role = 'TEAM_LEAD'
  AND task.status = 'COMPLETED';

UPDATE work_task_audit_record
SET task_status = 'APPROVED',
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP,
    updated_by = 'flyway-v48'
WHERE audit_status = 'CEO_APPROVED'
  AND assignee_role = 'TEAM_LEAD'
  AND task_status = 'COMPLETED';
