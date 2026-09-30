CREATE TABLE department_hr_assignment (
    id uuid PRIMARY KEY,
    department_id uuid NOT NULL REFERENCES org_department(id),
    hr_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    hr_employee_id uuid NOT NULL REFERENCES employee(id),
    active boolean NOT NULL DEFAULT true,
    assigned_by_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    assigned_at timestamptz NOT NULL,
    ended_by_user_id uuid REFERENCES iam_user_account(id),
    ended_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_department_hr_end_state CHECK ((active AND ended_at IS NULL AND ended_by_user_id IS NULL)
        OR (NOT active AND ended_at IS NOT NULL AND ended_by_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_active_hr_per_department ON department_hr_assignment(department_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_hr ON department_hr_assignment(hr_user_id) WHERE active;
CREATE UNIQUE INDEX uq_active_department_per_hr_employee ON department_hr_assignment(hr_employee_id) WHERE active;
CREATE INDEX ix_department_hr_history ON department_hr_assignment(department_id, assigned_at DESC);

ALTER TABLE appointment ADD COLUMN routing_department_id uuid REFERENCES org_department(id);
ALTER TABLE appointment ADD COLUMN requested_employee_id uuid REFERENCES employee(id);
UPDATE appointment a
   SET routing_department_id = e.department_id
  FROM employee e
 WHERE e.id = a.host_employee_id;
UPDATE appointment
   SET requested_employee_id = host_employee_id
 WHERE type = 'EMPLOYEE_VISIT';
ALTER TABLE appointment ALTER COLUMN routing_department_id SET NOT NULL;
CREATE INDEX ix_appointment_department_queue ON appointment(routing_department_id, status, slot_start);
