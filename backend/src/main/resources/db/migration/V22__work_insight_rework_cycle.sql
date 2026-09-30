ALTER TABLE department_work_task DROP CONSTRAINT IF EXISTS department_work_task_status_check;
ALTER TABLE department_work_task ADD CONSTRAINT department_work_task_status_check
    CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CHANGES_REQUESTED',
                      'INSIGHT_REWORK_REQUESTED', 'APPROVED', 'ACKNOWLEDGED'));

ALTER TABLE department_work_task ADD COLUMN insight_review_source varchar(30);
ALTER TABLE department_work_task ADD COLUMN insight_review_reason varchar(1000);
ALTER TABLE department_work_task ADD COLUMN insight_review_requested_at timestamptz;
ALTER TABLE department_work_task ADD COLUMN rework_cycle integer NOT NULL DEFAULT 0;

ALTER TABLE work_task_audit_record DROP CONSTRAINT IF EXISTS work_task_audit_record_audit_status_check;
ALTER TABLE work_task_audit_record ADD COLUMN rework_requested_by_role varchar(30);
ALTER TABLE work_task_audit_record ADD COLUMN rework_reason varchar(1000);
ALTER TABLE work_task_audit_record ADD COLUMN rework_requested_by_user_id uuid;
ALTER TABLE work_task_audit_record ADD COLUMN rework_requested_at timestamptz;
ALTER TABLE work_task_audit_record ADD COLUMN team_lead_rework_guidance varchar(1000);
ALTER TABLE work_task_audit_record ADD COLUMN team_lead_responded_at timestamptz;
ALTER TABLE work_task_audit_record ADD COLUMN rework_cycle integer NOT NULL DEFAULT 0;

UPDATE work_task_audit_record
SET audit_status = 'CEO_REWORK_REQUESTED',
    rework_requested_by_role = 'CEO',
    rework_reason = COALESCE(ceo_remarks, 'Legacy CEO rejection requires Team Lead review'),
    rework_requested_by_user_id = ceo_decided_by_user_id,
    rework_requested_at = ceo_decided_at,
    rework_cycle = 1
WHERE audit_status = 'CEO_REJECTED';

UPDATE department_work_task task
SET status = 'INSIGHT_REWORK_REQUESTED',
    insight_review_source = 'CEO',
    insight_review_reason = audit.rework_reason,
    insight_review_requested_at = audit.rework_requested_at,
    rework_cycle = 1,
    approved_at = NULL,
    acknowledged_at = NULL
FROM work_task_audit_record audit
WHERE audit.work_task_id = task.id
  AND audit.audit_status = 'CEO_REWORK_REQUESTED'
  AND task.status IN ('APPROVED', 'ACKNOWLEDGED');

ALTER TABLE work_task_audit_record ADD CONSTRAINT work_task_audit_record_audit_status_check
    CHECK (audit_status IN ('HR_REWORK_REQUESTED', 'PENDING_CEO_APPROVAL', 'CEO_APPROVED',
                            'CEO_REWORK_REQUESTED', 'REWORK_ASSIGNED'));

CREATE INDEX ix_work_insight_rework_queue
    ON work_task_audit_record(team_lead_user_id, audit_status, rework_requested_at DESC);
