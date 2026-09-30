CREATE INDEX IF NOT EXISTS idx_employee_department_id
    ON employee (department_id, id);
