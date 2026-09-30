CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SEQUENCE employee_number_seq START WITH 1 INCREMENT BY 1;
CREATE SEQUENCE visitor_badge_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE iam_user_account (
    id uuid PRIMARY KEY,
    email varchar(180) NOT NULL UNIQUE,
    employee_id uuid UNIQUE,
    password_hash varchar(100) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    force_password_change boolean NOT NULL DEFAULT true,
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE TABLE iam_user_role (
    user_id uuid NOT NULL REFERENCES iam_user_account(id) ON DELETE CASCADE,
    role_name varchar(60) NOT NULL,
    PRIMARY KEY (user_id, role_name)
);

CREATE TABLE iam_refresh_token_session (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES iam_user_account(id),
    token_hash varchar(64) NOT NULL UNIQUE,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by_hash varchar(64),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_refresh_user ON iam_refresh_token_session(user_id);
CREATE INDEX ix_refresh_family ON iam_refresh_token_session(family_id);

CREATE TABLE org_department (
    id uuid PRIMARY KEY,
    code varchar(20) NOT NULL UNIQUE,
    name varchar(120) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

CREATE TABLE employee (
    id uuid PRIMARY KEY,
    employee_number varchar(30) NOT NULL UNIQUE,
    first_name varchar(80) NOT NULL,
    last_name varchar(80) NOT NULL,
    display_name varchar(170) NOT NULL,
    official_email varchar(180) NOT NULL UNIQUE,
    phone_number varchar(30),
    department_id uuid NOT NULL REFERENCES org_department(id),
    designation varchar(120) NOT NULL,
    reporting_manager_id uuid REFERENCES employee(id),
    joining_date date NOT NULL,
    relieving_date date,
    status varchar(30) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_employee_dates CHECK (relieving_date IS NULL OR relieving_date >= joining_date),
    CONSTRAINT ck_employee_not_self_manager CHECK (reporting_manager_id IS NULL OR reporting_manager_id <> id)
);
CREATE INDEX ix_employee_department_status ON employee(department_id, status);
CREATE INDEX ix_employee_manager ON employee(reporting_manager_id);

ALTER TABLE iam_user_account ADD CONSTRAINT fk_user_employee FOREIGN KEY (employee_id) REFERENCES employee(id);

CREATE TABLE appointment (
    id uuid PRIMARY KEY,
    reference_number varchar(40) NOT NULL UNIQUE,
    idempotency_key varchar(100) NOT NULL UNIQUE,
    type varchar(40) NOT NULL,
    status varchar(40) NOT NULL,
    visitor_name varchar(170) NOT NULL,
    visitor_email varchar(180) NOT NULL,
    visitor_phone varchar(30) NOT NULL,
    visitor_company varchar(160),
    host_employee_id uuid NOT NULL REFERENCES employee(id),
    slot_start timestamptz NOT NULL,
    slot_end timestamptz NOT NULL,
    purpose varchar(1000) NOT NULL,
    approval_actor_id uuid,
    decision_at timestamptz,
    decision_remarks varchar(500),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_appointment_slot CHECK (slot_end > slot_start)
);
ALTER TABLE appointment ADD CONSTRAINT ex_appointment_host_slot
    EXCLUDE USING gist (host_employee_id WITH =, tstzrange(slot_start, slot_end, '[)') WITH &&)
    WHERE (status IN ('PENDING_VERIFICATION','PENDING_APPROVAL','APPROVED','RESCHEDULED','CHECKED_IN','IN_MEETING'));
CREATE INDEX ix_appointment_host_start ON appointment(host_employee_id, slot_start);
CREATE INDEX ix_appointment_status_start ON appointment(status, slot_start);
CREATE INDEX ix_appointment_visitor_email ON appointment(lower(visitor_email));

CREATE TABLE compensation_package (
    id uuid PRIMARY KEY,
    employee_id uuid NOT NULL REFERENCES employee(id),
    basic_salary numeric(19,2) NOT NULL CHECK (basic_salary >= 0),
    hra numeric(19,2) NOT NULL CHECK (hra >= 0),
    transport_allowance numeric(19,2) NOT NULL CHECK (transport_allowance >= 0),
    medical_allowance numeric(19,2) NOT NULL CHECK (medical_allowance >= 0),
    special_allowance numeric(19,2) NOT NULL CHECK (special_allowance >= 0),
    other_allowance numeric(19,2) NOT NULL CHECK (other_allowance >= 0),
    pf_deduction numeric(19,2) NOT NULL CHECK (pf_deduction >= 0),
    professional_tax numeric(19,2) NOT NULL CHECK (professional_tax >= 0),
    income_tax_estimate numeric(19,2) NOT NULL CHECK (income_tax_estimate >= 0),
    other_deductions numeric(19,2) NOT NULL CHECK (other_deductions >= 0),
    gross_salary numeric(19,2) NOT NULL,
    total_deductions numeric(19,2) NOT NULL,
    net_salary numeric(19,2) NOT NULL,
    annual_ctc numeric(19,2) NOT NULL,
    currency varchar(3) NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_compensation_period CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
ALTER TABLE compensation_package ADD CONSTRAINT ex_compensation_period
    EXCLUDE USING gist (employee_id WITH =, daterange(effective_from, COALESCE(effective_to, 'infinity'::date), '[]') WITH &&);
CREATE INDEX ix_compensation_employee ON compensation_package(employee_id, effective_from DESC);

CREATE TABLE visitor (
    id uuid PRIMARY KEY,
    name varchar(170) NOT NULL,
    email varchar(180) NOT NULL,
    phone varchar(30) NOT NULL,
    company varchar(160),
    government_id_encrypted varchar(1000),
    government_id_last4 varchar(4),
    identity_verified boolean NOT NULL DEFAULT false,
    consent_version varchar(40) NOT NULL,
    consented_at timestamptz NOT NULL,
    restricted boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_visitor_email_phone ON visitor(lower(email), phone);

CREATE TABLE visit_access_record (
    id uuid PRIMARY KEY,
    appointment_id uuid NOT NULL UNIQUE REFERENCES appointment(id),
    visitor_name varchar(170) NOT NULL,
    badge_number varchar(30) NOT NULL,
    checked_in_at timestamptz NOT NULL,
    checked_out_at timestamptz,
    processed_by varchar(120) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL,
    CONSTRAINT ck_access_times CHECK (checked_out_at IS NULL OR checked_out_at >= checked_in_at)
);
CREATE UNIQUE INDEX uk_active_badge ON visit_access_record(badge_number) WHERE checked_out_at IS NULL;
CREATE INDEX ix_access_inside ON visit_access_record(checked_out_at, checked_in_at);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor_id varchar(120) NOT NULL,
    event_type varchar(100) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id varchar(120) NOT NULL,
    outcome varchar(20) NOT NULL,
    correlation_id varchar(100),
    details_json jsonb
);
CREATE INDEX ix_audit_occurred ON audit_event(occurred_at DESC);
CREATE INDEX ix_audit_target ON audit_event(target_type, target_id);
CREATE INDEX ix_audit_event_type ON audit_event(event_type);

CREATE TABLE notification_outbox (
    id uuid PRIMARY KEY,
    event_key varchar(160) NOT NULL UNIQUE,
    channel varchar(20) NOT NULL,
    destination varchar(180) NOT NULL,
    template varchar(100) NOT NULL,
    payload_json jsonb NOT NULL,
    status varchar(20) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    sent_at timestamptz,
    last_error_code varchar(80),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);
CREATE INDEX ix_outbox_ready ON notification_outbox(status, next_attempt_at);

CREATE TABLE system_setting (
    id uuid PRIMARY KEY,
    setting_key varchar(120) NOT NULL UNIQUE,
    setting_value varchar(2000) NOT NULL,
    value_type varchar(20) NOT NULL,
    description varchar(500) NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    created_by varchar(120) NOT NULL,
    updated_at timestamptz NOT NULL,
    updated_by varchar(120) NOT NULL
);

INSERT INTO system_setting (id, setting_key, setting_value, value_type, description, version, created_at, created_by, updated_at, updated_by)
VALUES
    ('00000000-0000-0000-0000-000000000101', 'APPOINTMENT.SLOT_MINUTES', '30', 'INTEGER', 'Default appointment duration in minutes', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000102', 'COMPENSATION.EMPLOYEE_SELF_VIEW', 'false', 'BOOLEAN', 'Allow employees to view their own compensation', 0, now(), 'flyway', now(), 'flyway'),
    ('00000000-0000-0000-0000-000000000103', 'VISITOR.RETENTION_DAYS', '365', 'INTEGER', 'Visitor profile retention period in days', 0, now(), 'flyway', now(), 'flyway');
