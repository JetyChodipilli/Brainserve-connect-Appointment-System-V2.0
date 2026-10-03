ALTER TABLE department_work_task
 ADD COLUMN priority varchar(10) NOT NULL DEFAULT 'NORMAL',
 ADD COLUMN original_due_date date,
 ADD COLUMN original_due_date_known boolean NOT NULL DEFAULT false,
 ADD COLUMN estimate_minutes integer,
 ADD COLUMN evidence_required boolean NOT NULL DEFAULT false,
 ADD COLUMN blocked boolean NOT NULL DEFAULT false,
 ADD COLUMN planning_state jsonb NOT NULL DEFAULT '{"checklist":[],"evidence":[],"blockers":[],"submissions":[]}'::jsonb;
UPDATE department_work_task SET original_due_date = due_date;
ALTER TABLE department_work_task ADD CONSTRAINT work_task_priority_valid CHECK (priority IN ('LOW','NORMAL','HIGH','URGENT'));
ALTER TABLE department_work_task ADD CONSTRAINT work_task_estimate_valid CHECK (estimate_minutes IS NULL OR estimate_minutes BETWEEN 1 AND 525600);
CREATE INDEX ix_work_task_department_blocked ON department_work_task(department_id, blocked);
