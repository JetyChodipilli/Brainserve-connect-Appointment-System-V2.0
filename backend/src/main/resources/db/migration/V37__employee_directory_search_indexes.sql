CREATE INDEX IF NOT EXISTS idx_employee_number_search
    ON employee ((lower(employee_number)) varchar_pattern_ops, id);

CREATE INDEX IF NOT EXISTS idx_employee_email_search
    ON employee ((lower(official_email)) varchar_pattern_ops, id);

CREATE INDEX IF NOT EXISTS idx_employee_department_number_search
    ON employee (department_id, (lower(employee_number)) varchar_pattern_ops, id);

CREATE INDEX IF NOT EXISTS idx_employee_department_email_search
    ON employee (department_id, (lower(official_email)) varchar_pattern_ops, id);
