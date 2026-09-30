-- BrainServe has one active Team Lead per department rather than a separate Manager role.
-- Employee leadership is resolved from department_team_lead, so this duplicate hierarchy is removed.
DROP INDEX IF EXISTS ix_employee_manager;
ALTER TABLE employee DROP CONSTRAINT IF EXISTS ck_employee_not_self_manager;
ALTER TABLE employee DROP COLUMN IF EXISTS reporting_manager_id;
