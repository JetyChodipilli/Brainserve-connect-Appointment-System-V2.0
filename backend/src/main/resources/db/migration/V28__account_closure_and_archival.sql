ALTER TABLE iam_user_account
    ADD COLUMN archived boolean NOT NULL DEFAULT false,
    ADD COLUMN archived_at timestamptz,
    ADD COLUMN archive_reason varchar(1000);

ALTER TABLE iam_user_account ADD CONSTRAINT ck_user_account_archive_state CHECK (
    (archived = false AND archived_at IS NULL)
    OR (archived = true AND archived_at IS NOT NULL AND enabled = false)
);

CREATE TABLE account_closure_request (
    id uuid PRIMARY KEY,
    target_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    target_role varchar(60) NOT NULL,
    department_id uuid REFERENCES org_department(id),
    requester_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    request_origin varchar(40) NOT NULL,
    reason varchar(1000) NOT NULL,
    requested_effective_date date NOT NULL,
    replacement_user_id uuid REFERENCES iam_user_account(id),
    status varchar(40) NOT NULL,
    requested_at timestamptz NOT NULL,
    business_approver_user_id uuid REFERENCES iam_user_account(id),
    business_approved_at timestamptz,
    system_admin_approver_user_id uuid REFERENCES iam_user_account(id),
    system_admin_approved_at timestamptz,
    decision_note varchar(1000),
    scheduled_at timestamptz,
    archived_at timestamptz,
    cancelled_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_account_closure_status CHECK (status IN (
        'REQUESTED','BUSINESS_APPROVED','PENDING_SYSTEM_ADMIN','SCHEDULED','ARCHIVED','REJECTED','CANCELLED'
    )),
    CONSTRAINT ck_account_closure_origin CHECK (request_origin IN (
        'SELF_SERVICE','SYSTEM_ADMIN_EMERGENCY','EMPLOYEE_TERMINATION'
    )),
    CONSTRAINT ck_account_closure_not_self_replacement CHECK (
        replacement_user_id IS NULL OR replacement_user_id <> target_user_id
    )
);

CREATE UNIQUE INDEX uq_open_account_closure_request
    ON account_closure_request(target_user_id)
    WHERE status IN ('REQUESTED','BUSINESS_APPROVED','PENDING_SYSTEM_ADMIN','SCHEDULED');
CREATE INDEX ix_account_closure_system_admin_queue
    ON account_closure_request(status, requested_at);
CREATE INDEX ix_account_closure_requester_history
    ON account_closure_request(requester_user_id, requested_at DESC);
CREATE INDEX ix_account_closure_scheduled
    ON account_closure_request(status, requested_effective_date)
    WHERE status = 'SCHEDULED';

CREATE TABLE archived_account (
    id uuid PRIMARY KEY,
    original_user_id uuid NOT NULL UNIQUE REFERENCES iam_user_account(id),
    full_name_snapshot varchar(170) NOT NULL,
    email_snapshot varchar(180) NOT NULL,
    role_snapshot varchar(60) NOT NULL,
    department_id_snapshot uuid,
    department_name_snapshot varchar(120),
    employee_id_snapshot uuid,
    employee_number_snapshot varchar(30),
    previous_account_status varchar(40) NOT NULL,
    closure_reason varchar(1000) NOT NULL,
    closure_request_id uuid NOT NULL REFERENCES account_closure_request(id),
    archived_by_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    archived_at timestamptz NOT NULL,
    retention_until date NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_archived_account_date ON archived_account(archived_at DESC);
CREATE INDEX ix_archived_account_role ON archived_account(role_snapshot, archived_at DESC);

CREATE TABLE account_lifecycle_record (
    id uuid PRIMARY KEY,
    closure_request_id uuid NOT NULL REFERENCES account_closure_request(id),
    target_user_id uuid NOT NULL REFERENCES iam_user_account(id),
    event_type varchar(100) NOT NULL,
    from_status varchar(40),
    to_status varchar(40) NOT NULL,
    actor_user_id uuid REFERENCES iam_user_account(id),
    detail varchar(1200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_account_lifecycle_request
    ON account_lifecycle_record(closure_request_id, occurred_at ASC);
CREATE INDEX ix_account_lifecycle_target
    ON account_lifecycle_record(target_user_id, occurred_at DESC);
