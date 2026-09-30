ALTER TABLE department_work_task ADD COLUMN department_branch varchar(170);

UPDATE department_work_task task
   SET department_branch = department.name
  FROM org_department department
 WHERE department.id = task.department_id;

ALTER TABLE department_work_task ALTER COLUMN department_branch SET NOT NULL;

DROP INDEX IF EXISTS ix_work_task_department;
CREATE INDEX ix_work_task_department
    ON department_work_task(department_id, department_branch, status, due_date);

ALTER TABLE department_work_task DROP COLUMN category;
