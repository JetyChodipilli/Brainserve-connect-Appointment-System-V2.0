CREATE TABLE work_task_audit_record (
    id uuid PRIMARY KEY,
    work_task_id uuid NOT NULL UNIQUE REFERENCES department_work_task(id),
    week_start date NOT NULL,
    department_id uuid NOT NULL,
    department_name varchar(170) NOT NULL,
    employee_id uuid NOT NULL,
    employee_number varchar(40) NOT NULL,
    employee_name varchar(170) NOT NULL,
    team_lead_user_id uuid NOT NULL,
    team_lead_name varchar(170) NOT NULL,
    task_title varchar(160) NOT NULL,
    task_status varchar(30) NOT NULL,
    audit_status varchar(30) NOT NULL CHECK (audit_status IN ('PENDING_CEO_APPROVAL', 'CEO_APPROVED', 'CEO_REJECTED')),
    hr_audited_by_user_id uuid NOT NULL,
    hr_audited_at timestamptz NOT NULL,
    ceo_decided_by_user_id uuid,
    ceo_decided_at timestamptz,
    ceo_remarks varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE INDEX ix_work_insight_week_status ON work_task_audit_record(week_start DESC, audit_status, hr_audited_at DESC);
CREATE INDEX ix_work_insight_employee_week ON work_task_audit_record(employee_id, week_start DESC);
CREATE INDEX ix_work_insight_department_week ON work_task_audit_record(department_id, week_start DESC);
