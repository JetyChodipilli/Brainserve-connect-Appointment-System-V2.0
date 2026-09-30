ALTER TABLE department_work_task
    ADD COLUMN assigned_by_user_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN assigned_by_role varchar(30),
    ADD COLUMN assignee_role varchar(30);

UPDATE department_work_task
SET assigned_by_user_id = team_lead_user_id,
    assigned_by_role = 'TEAM_LEAD',
    assignee_role = 'EMPLOYEE'
WHERE assigned_by_user_id IS NULL;

ALTER TABLE department_work_task
    ALTER COLUMN assigned_by_user_id SET NOT NULL,
    ALTER COLUMN assigned_by_role SET NOT NULL,
    ALTER COLUMN assignee_role SET NOT NULL,
    ADD CONSTRAINT department_work_task_assigned_by_role_check
        CHECK (assigned_by_role IN ('HR_ADMIN', 'TEAM_LEAD')),
    ADD CONSTRAINT department_work_task_assignee_role_check
        CHECK (assignee_role IN ('EMPLOYEE', 'TEAM_LEAD'));

CREATE INDEX ix_work_task_assigned_by
    ON department_work_task(assigned_by_user_id, created_at DESC);

ALTER TABLE work_task_audit_record
    ADD COLUMN assigned_by_role varchar(30),
    ADD COLUMN assignee_role varchar(30),
    ADD COLUMN manager_decided_by_user_id uuid REFERENCES iam_user_account(id),
    ADD COLUMN manager_decided_at timestamptz,
    ADD COLUMN manager_remarks varchar(1000);

UPDATE work_task_audit_record audit
SET assigned_by_role = task.assigned_by_role,
    assignee_role = task.assignee_role
FROM department_work_task task
WHERE task.id = audit.work_task_id
  AND audit.assigned_by_role IS NULL;

ALTER TABLE work_task_audit_record
    ALTER COLUMN assigned_by_role SET NOT NULL,
    ALTER COLUMN assignee_role SET NOT NULL;

-- Pending records created before this release have not received a Manager
-- decision. Put them into the new department-governance queue instead of
-- allowing them to bypass the Manager and remain directly visible to CEO.
UPDATE work_task_audit_record
SET audit_status = 'PENDING_MANAGER_APPROVAL'
WHERE audit_status = 'PENDING_CEO_APPROVAL'
  AND ceo_decided_at IS NULL;

ALTER TABLE work_task_audit_record
    DROP CONSTRAINT IF EXISTS work_task_audit_record_audit_status_check;

ALTER TABLE work_task_audit_record
    ADD CONSTRAINT work_task_audit_record_audit_status_check
        CHECK (audit_status IN ('HR_REWORK_REQUESTED', 'PENDING_MANAGER_APPROVAL',
                                'MANAGER_REWORK_REQUESTED', 'PENDING_CEO_APPROVAL',
                                'CEO_APPROVED', 'CEO_REWORK_REQUESTED', 'REWORK_ASSIGNED'));

CREATE INDEX ix_work_insight_manager_queue
    ON work_task_audit_record(department_id, audit_status, hr_audited_at DESC)
    WHERE audit_status = 'PENDING_MANAGER_APPROVAL';
