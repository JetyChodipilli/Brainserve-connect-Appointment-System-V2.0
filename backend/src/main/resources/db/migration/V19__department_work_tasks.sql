CREATE TABLE department_work_task (
    id uuid PRIMARY KEY,
    department_id uuid NOT NULL REFERENCES org_department(id),
    employee_id uuid NOT NULL REFERENCES employee(id),
    team_lead_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    title varchar(160) NOT NULL,
    description varchar(1000) NOT NULL,
    category varchar(100) NOT NULL,
    due_date date NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('ASSIGNED', 'IN_PROGRESS', 'COMPLETED', 'CHANGES_REQUESTED', 'APPROVED', 'ACKNOWLEDGED')),
    employee_update varchar(1000),
    team_lead_review varchar(1000),
    started_at timestamptz,
    completed_at timestamptz,
    approved_at timestamptz,
    acknowledged_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE INDEX ix_work_task_employee ON department_work_task(employee_id, status, due_date, created_at DESC);
CREATE INDEX ix_work_task_team_lead ON department_work_task(team_lead_user_id, status, updated_at DESC);
CREATE INDEX ix_work_task_department ON department_work_task(department_id, category, status, due_date);
