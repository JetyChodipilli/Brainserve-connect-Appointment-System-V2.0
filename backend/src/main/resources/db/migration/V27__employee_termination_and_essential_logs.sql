CREATE TABLE employee_termination_request (
    id uuid PRIMARY KEY,
    employee_id uuid NOT NULL REFERENCES employee(id),
    department_id uuid NOT NULL REFERENCES org_department(id),
    requested_by_hr_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    reason varchar(1000) NOT NULL,
    effective_date date NOT NULL,
    status varchar(30) NOT NULL DEFAULT 'PENDING_CEO_APPROVAL',
    requested_at timestamptz NOT NULL,
    decided_by_ceo_user_id uuid REFERENCES iam_user_account(id),
    decided_at timestamptz,
    decision_note varchar(1000),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_employee_termination_status CHECK (status IN ('PENDING_CEO_APPROVAL', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_employee_termination_decision CHECK (
        (status = 'PENDING_CEO_APPROVAL' AND decided_by_ceo_user_id IS NULL AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_by_ceo_user_id IS NOT NULL AND decided_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_pending_employee_termination
    ON employee_termination_request(employee_id) WHERE status = 'PENDING_CEO_APPROVAL';
CREATE INDEX ix_employee_termination_ceo_queue
    ON employee_termination_request(status, requested_at);
CREATE INDEX ix_employee_termination_hr_history
    ON employee_termination_request(requested_by_hr_user_id, requested_at DESC);

CREATE TABLE essential_log_record (
    id uuid PRIMARY KEY,
    category varchar(60) NOT NULL,
    event_type varchar(100) NOT NULL,
    subject_type varchar(60) NOT NULL,
    subject_id varchar(120) NOT NULL,
    reference_id varchar(120),
    actor_user_id uuid REFERENCES iam_user_account(id),
    approver_user_id uuid REFERENCES iam_user_account(id),
    status varchar(30) NOT NULL,
    title varchar(180) NOT NULL,
    detail varchar(1200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE INDEX ix_essential_log_occurred_at ON essential_log_record(occurred_at DESC);
CREATE INDEX ix_essential_log_subject ON essential_log_record(subject_type, subject_id, occurred_at DESC);
CREATE INDEX ix_essential_log_category ON essential_log_record(category, event_type, occurred_at DESC);
